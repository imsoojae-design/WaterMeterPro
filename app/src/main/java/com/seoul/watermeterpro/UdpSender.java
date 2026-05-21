package com.seoul.watermeterpro;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * UDP 패킷 전송 클래스
 * 검침값 + HEX 원본 데이터를 JSON 형태로 UDP 전송
 */
public class UdpSender {

    public interface SendCallback {
        void onSuccess(String ip, int port, int bytes);
        void onError(String message);
    }

    private String serverIp   = "192.168.1.100";
    private int    serverPort = 5000;

    public UdpSender() {}

    public UdpSender(String ip, int port) {
        this.serverIp   = ip;
        this.serverPort = port;
    }

    public void setTarget(String ip, int port) {
        this.serverIp   = ip;
        this.serverPort = port;
    }

    public String getIp()   { return serverIp;   }
    public int    getPort() { return serverPort;  }

    /**
     * 검침 결과를 UDP로 전송 (백그라운드 스레드에서 호출)
     */
    public void send(MeterProtocol.ParseResult result, SendCallback callback) {
        new Thread(() -> {
            try {
                String json    = result.toUdpJson();
                byte[] payload = json.getBytes("UTF-8");

                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(3000);
                InetAddress addr   = InetAddress.getByName(serverIp);
                DatagramPacket pkt = new DatagramPacket(payload, payload.length, addr, serverPort);
                socket.send(pkt);
                socket.close();

                if (callback != null)
                    callback.onSuccess(serverIp, serverPort, payload.length);

            } catch (Exception e) {
                if (callback != null)
                    callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }

    /**
     * 원시 문자열 전송 (테스트용)
     */
    public void sendRaw(String data, SendCallback callback) {
        new Thread(() -> {
            try {
                byte[] payload = data.getBytes("UTF-8");
                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(3000);
                InetAddress addr   = InetAddress.getByName(serverIp);
                DatagramPacket pkt = new DatagramPacket(payload, payload.length, addr, serverPort);
                socket.send(pkt);
                socket.close();
                if (callback != null)
                    callback.onSuccess(serverIp, serverPort, payload.length);
            } catch (Exception e) {
                if (callback != null)
                    callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }).start();
    }
}
