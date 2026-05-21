package com.seoul.watermeterpro;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * UDP 전송 클래스
 * V1.5 NB-IoT 바이너리 포맷으로 전송 (Python nbiot_udp_packet.py 동일)
 */
public class UdpSender {

    public interface SendCallback {
        void onSuccess(String ip, int port, int bytes, String hexPreview);
        void onError(String message);
    }

    private String serverIp   = "192.168.1.100";
    private int    serverPort = 5000;

    public UdpSender() {}
    public void setTarget(String ip, int port) { this.serverIp=ip; this.serverPort=port; }
    public String getIp()   { return serverIp;   }
    public int    getPort() { return serverPort;  }

    /**
     * V1.5 NB-IoT 바이너리 패킷 전송
     */
    public void sendNbIot(NbIotPacketBuilder builder, double readingValue, SendCallback cb) {
        new Thread(() -> {
            try {
                byte[] payload = builder.build(readingValue);
                String hex = NbIotPacketBuilder.toHex(payload);

                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(3000);
                InetAddress addr = InetAddress.getByName(serverIp);
                DatagramPacket pkt = new DatagramPacket(payload, payload.length, addr, serverPort);
                socket.send(pkt);
                socket.close();

                if (cb != null)
                    cb.onSuccess(serverIp, serverPort, payload.length,
                        hex.substring(0, Math.min(hex.length(), 60)) + "...");
            } catch (Exception e) {
                if (cb != null)
                    cb.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }

    /**
     * Raw 바이너리 전송 (테스트용)
     */
    public void sendRaw(byte[] payload, SendCallback cb) {
        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(3000);
                InetAddress addr = InetAddress.getByName(serverIp);
                DatagramPacket pkt = new DatagramPacket(payload, payload.length, addr, serverPort);
                socket.send(pkt);
                socket.close();
                if (cb != null)
                    cb.onSuccess(serverIp, serverPort, payload.length,
                        NbIotPacketBuilder.toHex(payload));
            } catch (Exception e) {
                if (cb != null)
                    cb.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }
}
