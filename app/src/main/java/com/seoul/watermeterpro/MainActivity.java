package com.seoul.watermeterpro;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.seoul.watermeterpro.USB_PERMISSION";
    private static final String[] TAB_TITLES = {"검침", "UDP 전송", "로그"};
    public static final String PREFS_NAME = "udp_prefs";

    public static MainActivity instance;
    public final List<MeterProtocol.ParseResult> history = new ArrayList<>();
    public UdpSender udpSender = new UdpSender();

    private UsbManager       usbManager;
    private UsbSerialPort    serialPort;
    private TextView         tvConnStatus;

    private final ExecutorService readExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
    private final Handler    mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isConnected = false;
    private volatile boolean isReading   = false;

    private ReadFragment readFragment;
    private UdpFragment  udpFragment;
    private LogFragment  logFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        instance     = this;
        usbManager   = (UsbManager) getSystemService(Context.USB_SERVICE);
        tvConnStatus = findViewById(R.id.tvConnStatus);

        // SharedPreferences에서 UDP 설정 로드
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String ip   = prefs.getString("udp_ip", "192.168.1.100");
        int    port = prefs.getInt("udp_port", 5000);
        udpSender.setTarget(ip, port);

        setupViewPager();

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        setConnStatus("● 연결 안됨", R.color.muted, R.drawable.bg_pill_gray);
    }

    private void setConnStatus(String text, int colorRes, int bgRes) {
        mainHandler.post(() -> {
            if (tvConnStatus == null) return;
            tvConnStatus.setText(text);
            tvConnStatus.setTextColor(getColor(colorRes));
            tvConnStatus.setBackgroundResource(bgRes);
        });
    }

    private void setupViewPager() {
        ViewPager2 vp = findViewById(R.id.viewPager);
        TabLayout  tl = findViewById(R.id.tabLayout);
        vp.setAdapter(new FragmentStateAdapter(this) {
            public int getItemCount() { return 3; }
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0: readFragment = new ReadFragment(); return readFragment;
                    case 1: udpFragment  = new UdpFragment();  return udpFragment;
                    default: logFragment = new LogFragment();  return logFragment;
                }
            }
        });
        new TabLayoutMediator(tl, vp, (tab, pos) -> tab.setText(TAB_TITLES[pos])).attach();
    }

    public void connectUsb() {
        addLog("=== USB 연결 시도 ===", "INFO");
        List<UsbSerialDriver> drivers =
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            addLog("USB 장치 없음", "ERR"); toast("USB 장치를 찾을 수 없습니다"); return;
        }
        UsbDevice dev = drivers.get(0).getDevice();
        addLog("USB: " + dev.getProductName() + " VID=" + dev.getVendorId() + " PID=" + dev.getProductId(), "INFO");
        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(dev, pi);
            addLog("권한 요청 중...", "WARN"); return;
        }
        openPort(drivers.get(0));
    }

    private void openPort(UsbSerialDriver driver) {
        readExecutor.execute(() -> {
            try {
                UsbDeviceConnection conn = usbManager.openDevice(driver.getDevice());
                if (conn == null) { addLog("openDevice 실패", "ERR"); return; }
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(conn);
                port.setParameters(MeterProtocol.BAUD_RATE,
                    UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                port.setDTR(true); port.setRTS(true);
                Thread.sleep(50);
                serialPort = port; isConnected = true;
                setConnStatus("● 연결됨", R.color.yellow, R.drawable.bg_pill_gray);
                mainHandler.post(() -> { if (readFragment != null) readFragment.onConnected(true); });
                addLog("연결됨: " + driver.getDevice().getProductName() + " (1200bps 8N1)", "OK");
                startReadLoop();
            } catch (IOException | InterruptedException e) {
                addLog("연결 실패: " + e.getMessage(), "ERR");
            }
        });
    }

    public void disconnectUsb() {
        isReading = false; isConnected = false;
        UsbSerialPort p = serialPort; serialPort = null;
        if (p != null) { try { p.close(); } catch (IOException ignored) {} }
        setConnStatus("● 연결 안됨", R.color.muted, R.drawable.bg_pill_gray);
        mainHandler.post(() -> { if (readFragment != null) readFragment.onConnected(false); });
        addLog("=== 연결 해제 ===", "WARN");
    }

    private void startReadLoop() {
        isReading = true;
        byte[] buf = new byte[256], acc = new byte[512];
        int[]  accLen = {0};
        while (isReading && serialPort != null) {
            try {
                int n = serialPort.read(buf, 200);
                if (n > 0) {
                    System.arraycopy(buf, 0, acc, accLen[0], n); accLen[0] += n;
                    int end = MeterProtocol.findLongFrameEnd(acc, accLen[0]);
                    if (end > 0) {
                        byte[] frame = new byte[end];
                        System.arraycopy(acc, 0, frame, 0, end);
                        accLen[0] -= end; System.arraycopy(acc, end, acc, 0, accLen[0]);
                        String hexStr = MeterProtocol.toHex(frame);
                        addLog("← " + end + "B: " + hexStr, "HEX");
                        MeterProtocol.ParseResult r = MeterProtocol.parseLongFrame(frame);
                        mainHandler.post(() -> handleResult(r));
                    }
                    if (accLen[0] > 400) accLen[0] = 0;
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("closed"))) {
                    mainHandler.post(this::disconnectUsb); break;
                }
            }
        }
    }

    public void sendRequest(int addr) {
        addLog("=== 검침 요청 (주소:" + addr + ") ===", "INFO");
        if (!isConnected || serialPort == null) { toast("먼저 연결하세요"); return; }
        byte[] frame = MeterProtocol.buildRequest(addr);
        writeExecutor.execute(() -> {
            try {
                serialPort.setRTS(true); serialPort.setDTR(true);
                Thread.sleep(35);
                byte[] flush = new byte[64];
                try { serialPort.read(flush, 30); } catch (IOException ignored) {}
                serialPort.write(frame, 3000);
                addLog("→ REQ_UD2: " + MeterProtocol.toHex(frame), "OK");
                Thread.sleep(100);
            } catch (IOException | InterruptedException e) {
                addLog("전송 실패: " + e.getMessage(), "ERR");
            }
        });
    }

    public void handleResult(MeterProtocol.ParseResult r) {
        if (!r.ok) { addLog("파싱 오류: " + r.error, "ERR"); return; }
        history.add(0, r);
        setConnStatus("● 검침 완료", R.color.green, R.drawable.bg_pill_green);
        if (readFragment != null) readFragment.updateReading(r);
        addLog("✓ " + r.meterNo + " = " + r.readingFmt() + " ㎥ | " + r.statusString()
            + (r.checksumOk ? "" : " | 체크섬오류"), r.hasWarning() ? "WARN" : "OK");

        // UDP 자동 전송
        if (udpFragment != null) udpFragment.onNewReading(r);
    }

    public void addLog(String msg, String level) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (logFragment != null) logFragment.addLog(msg, level);
        } else {
            mainHandler.post(() -> { if (logFragment != null) logFragment.addLog(msg, level); });
        }
    }

    public boolean isConnected() { return isConnected; }

    private void toast(String m) {
        mainHandler.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                addLog("USB 권한: " + (granted ? "허용" : "거부"), granted ? "OK" : "ERR");
                if (granted) {
                    List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
                    if (!drivers.isEmpty()) openPort(drivers.get(0));
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                addLog("USB 장치 연결됨", "INFO");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (isConnected) disconnectUsb();
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy(); disconnectUsb();
        readExecutor.shutdown(); writeExecutor.shutdown();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        instance = null;
    }
}
