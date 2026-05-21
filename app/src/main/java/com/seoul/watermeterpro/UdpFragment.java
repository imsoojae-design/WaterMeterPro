package com.seoul.watermeterpro;

import android.content.SharedPreferences;
import android.content.Context;
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

    private EditText     etUdpIp, etUdpPort;
    private SwitchCompat switchAutoSend;
    private Button       btnSaveSettings, btnSendNow;
    private TextView     tvUdpStatus, tvLastPacket, tvSendOk, tvSendFail, tvLastSend;

    private int sendOkCount   = 0;
    private int sendFailCount = 0;
    private MeterProtocol.ParseResult lastResult = null;

    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup vg, Bundle b) {
        View v = inf.inflate(R.layout.fragment_udp, vg, false);

        etUdpIp       = v.findViewById(R.id.etUdpIp);
        etUdpPort     = v.findViewById(R.id.etUdpPort);
        switchAutoSend = v.findViewById(R.id.switchAutoSend);
        btnSaveSettings = v.findViewById(R.id.btnSaveSettings);
        btnSendNow    = v.findViewById(R.id.btnSendNow);
        tvUdpStatus   = v.findViewById(R.id.tvUdpStatus);
        tvLastPacket  = v.findViewById(R.id.tvLastPacket);
        tvSendOk      = v.findViewById(R.id.tvSendOk);
        tvSendFail    = v.findViewById(R.id.tvSendFail);
        tvLastSend    = v.findViewById(R.id.tvLastSend);

        // SharedPreferences에서 설정 로드
        if (MainActivity.instance != null) {
            SharedPreferences prefs = MainActivity.instance
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            etUdpIp.setText(prefs.getString("udp_ip", "192.168.1.100"));
            etUdpPort.setText(String.valueOf(prefs.getInt("udp_port", 5000)));
            switchAutoSend.setChecked(prefs.getBoolean("auto_send", false));
        }

        // 설정 저장
        btnSaveSettings.setOnClickListener(x -> saveSettings());

        // 수동 전송
        btnSendNow.setOnClickListener(x -> {
            if (lastResult == null) {
                Toast.makeText(requireContext(), "검침 데이터가 없습니다", Toast.LENGTH_SHORT).show();
                return;
            }
            saveSettings();
            sendUdp(lastResult);
        });

        return v;
    }

    private void saveSettings() {
        if (etUdpIp == null) return;
        String ip   = etUdpIp.getText().toString().trim();
        String portStr = etUdpPort.getText().toString().trim();
        if (ip.isEmpty()) { Toast.makeText(requireContext(),"IP를 입력하세요",Toast.LENGTH_SHORT).show(); return; }

        int port = 5000;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {}

        if (MainActivity.instance != null) {
            MainActivity.instance.udpSender.setTarget(ip, port);
            SharedPreferences.Editor ed = MainActivity.instance
                .getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit();
            ed.putString("udp_ip", ip);
            ed.putInt("udp_port", port);
            ed.putBoolean("auto_send", switchAutoSend.isChecked());
            ed.apply();
        }
        Toast.makeText(requireContext(), "설정 저장됨: " + ip + ":" + port, Toast.LENGTH_SHORT).show();
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("UDP 설정: " + ip + ":" + port, "INFO");
    }

    // 새 검침값 수신 시 호출
    public void onNewReading(MeterProtocol.ParseResult r) {
        lastResult = r;
        if (btnSendNow != null) btnSendNow.setEnabled(true);
        // 자동 전송
        if (switchAutoSend != null && switchAutoSend.isChecked()) {
            sendUdp(r);
        }
    }

    private void sendUdp(MeterProtocol.ParseResult r) {
        if (MainActivity.instance == null) return;
        String json = r.toUdpJson();

        if (tvUdpStatus != null) tvUdpStatus.setText("전송 중... → " +
            MainActivity.instance.udpSender.getIp() + ":" +
            MainActivity.instance.udpSender.getPort());

        MainActivity.instance.udpSender.send(r, new UdpSender.SendCallback() {
            @Override
            public void onSuccess(String ip, int port, int bytes) {
                sendOkCount++;
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                requireActivity().runOnUiThread(() -> {
                    tvSendOk.setText(String.valueOf(sendOkCount));
                    tvLastSend.setText(ts);
                    tvUdpStatus.setText("✓ 전송 성공 → " + ip + ":" + port + " (" + bytes + "B)");
                    tvLastPacket.setText(json);
                });
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("UDP 전송 성공 → " + ip + ":" + port + " " + bytes + "B", "OK");
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
}
