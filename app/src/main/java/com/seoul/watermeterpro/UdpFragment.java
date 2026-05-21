package com.seoul.watermeterpro;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class UdpFragment extends Fragment {

    private EditText     etUdpIp, etUdpPort, etImei, etImsi, etSerial, etBattery;
    private SwitchCompat switchAutoSend;
    private Button       btnSaveSettings, btnSendNow;
    private TextView     tvUdpStatus, tvLastPacket, tvSendOk, tvSendFail, tvLastSend;

    private int    sendOkCount   = 0;
    private int    sendFailCount = 0;
    private double lastReading   = 0.0;
    private boolean hasData      = false;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_udp, vg, false);

        etUdpIp       = v.findViewById(R.id.etUdpIp);
        etUdpPort     = v.findViewById(R.id.etUdpPort);
        etImei        = v.findViewById(R.id.etImei);
        etImsi        = v.findViewById(R.id.etImsi);
        etSerial      = v.findViewById(R.id.etSerial);
        etBattery     = v.findViewById(R.id.etBattery);
        switchAutoSend = v.findViewById(R.id.switchAutoSend);
        btnSaveSettings = v.findViewById(R.id.btnSaveSettings);
        btnSendNow    = v.findViewById(R.id.btnSendNow);
        tvUdpStatus   = v.findViewById(R.id.tvUdpStatus);
        tvLastPacket  = v.findViewById(R.id.tvLastPacket);
        tvSendOk      = v.findViewById(R.id.tvSendOk);
        tvSendFail    = v.findViewById(R.id.tvSendFail);
        tvLastSend    = v.findViewById(R.id.tvLastSend);

        // 설정 로드
        loadPrefs();

        btnSaveSettings.setOnClickListener(x -> {
            savePrefs();
            Toast.makeText(requireContext(), "설정 저장됨", Toast.LENGTH_SHORT).show();
        });

        btnSendNow.setOnClickListener(x -> {
            if (!hasData) {
                Toast.makeText(requireContext(), "검침 데이터가 없습니다", Toast.LENGTH_SHORT).show();
                return;
            }
            savePrefs();
            sendUdp(lastReading);
        });

        return v;
    }

    private void loadPrefs() {
        if (MainActivity.instance == null) return;
        SharedPreferences p = MainActivity.instance
            .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        etUdpIp.setText(p.getString("udp_ip", "192.168.1.100"));
        etUdpPort.setText(String.valueOf(p.getInt("udp_port", 5000)));
        etImei.setText(p.getString("imei", "123456789012345"));
        etImsi.setText(p.getString("imsi", "991231234567890"));
        etSerial.setText(String.valueOf(p.getLong("serial", 2508002425L)));
        etBattery.setText(String.valueOf(p.getFloat("battery", 3.6f)));
        switchAutoSend.setChecked(p.getBoolean("auto_send", false));
    }

    private void savePrefs() {
        if (MainActivity.instance == null) return;
        String ip   = etUdpIp.getText().toString().trim();
        int    port = 5000;
        try { port = Integer.parseInt(etUdpPort.getText().toString().trim()); } catch (Exception ignored) {}

        SharedPreferences.Editor ed = MainActivity.instance
            .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit();
        ed.putString("udp_ip", ip);
        ed.putInt("udp_port", port);
        ed.putString("imei", etImei.getText().toString().trim());
        ed.putString("imsi", etImsi.getText().toString().trim());
        try { ed.putLong("serial", Long.parseLong(etSerial.getText().toString().trim())); } catch (Exception ignored) {}
        try { ed.putFloat("battery", Float.parseFloat(etBattery.getText().toString().trim())); } catch (Exception ignored) {}
        ed.putBoolean("auto_send", switchAutoSend.isChecked());
        ed.apply();

        if (MainActivity.instance != null)
            MainActivity.instance.udpSender.setTarget(ip, port);
    }

    // 새 검침값 수신 시 호출
    public void onNewReading(MeterProtocol.ParseResult r) {
        lastReading = r.reading;
        hasData = true;
        if (btnSendNow != null) btnSendNow.setEnabled(true);
        if (switchAutoSend != null && switchAutoSend.isChecked()) {
            savePrefs();
            sendUdp(r.reading);
        }
    }

    private void sendUdp(double reading) {
        if (MainActivity.instance == null) return;

        // NbIotPacketBuilder 설정
        NbIotPacketBuilder builder = new NbIotPacketBuilder();
        builder.imei       = etImei.getText().toString().trim();
        builder.imsi       = etImsi.getText().toString().trim();
        try { builder.serialNo = Long.parseLong(etSerial.getText().toString().trim()); } catch (Exception ignored) {}
        try { builder.batteryV = Float.parseFloat(etBattery.getText().toString().trim()); } catch (Exception ignored) {}

        // 검침값 적용 (USB 검침 결과 반영)
        if (!MainActivity.instance.history.isEmpty()) {
            MeterProtocol.ParseResult r = MainActivity.instance.history.get(0);
            builder.meterId        = parseMeterId(r.meterNo);
            builder.diameterMm     = r.diameter > 0 ? r.diameter : 15;
            builder.decimalPos     = r.decimals;
            builder.statusOverload = r.q3Exceed;
            builder.statusLeak     = r.leak;
            builder.statusReverse  = r.reverse;
            builder.statusBattery  = r.battLow;
        }

        if (tvUdpStatus != null)
            tvUdpStatus.setText("전송 중... → " + MainActivity.instance.udpSender.getIp()
                + ":" + MainActivity.instance.udpSender.getPort());

        MainActivity.instance.udpSender.sendNbIot(builder, reading, new UdpSender.SendCallback() {
            @Override
            public void onSuccess(String ip, int port, int bytes, String hexPreview) {
                sendOkCount++;
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                requireActivity().runOnUiThread(() -> {
                    tvSendOk.setText(String.valueOf(sendOkCount));
                    tvLastSend.setText(ts);
                    tvUdpStatus.setText("✓ 전송 성공 → " + ip + ":" + port + " (" + bytes + "B)");
                    tvLastPacket.setText(hexPreview);
                });
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog(
                        "UDP V1.5 전송 성공 → " + ip + ":" + port + " " + bytes + "B", "OK");
            }

            @Override
            public void onError(String message) {
                sendFailCount++;
                requireActivity().runOnUiThread(() -> {
                    tvSendFail.setText(String.valueOf(sendFailCount));
                    tvUdpStatus.setText("✗ 전송 실패: " + message);
                });
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("UDP 전송 실패: " + message, "ERR");
            }
        });
    }

    private int parseMeterId(String meterNo) {
        try {
            return Integer.parseInt(meterNo.replace("-", ""));
        } catch (Exception e) { return 0; }
    }
}
