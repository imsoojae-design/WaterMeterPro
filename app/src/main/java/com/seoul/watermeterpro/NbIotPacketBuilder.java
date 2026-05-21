package com.seoul.watermeterpro;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;

/**
 * 수도계량기 원격검침 서버 수신용 데이터포맷 V1.5
 * NB-IoT 주기보고 패킷 생성 (Python nbiot_udp_packet.py 동일 포맷)
 *
 * 패킷 구조:
 * [0xA3][길이][0x70][IMEI 8B][IMSI 8B][무선품질 10B][단말기정보 8B]
 * [계량기정보 7B][검침/보고주기 2B][검침시간 6B][검침데이터 9~55B][체크섬 1B]
 */
public class NbIotPacketBuilder {

    // 설정값 (UI에서 변경 가능)
    public String imei          = "123456789012345";
    public String imsi          = "991231234567890";
    public long   serialNo      = 2508002425L;
    public int    fwMajor       = 3;
    public int    fwMinor       = 2;
    public float  batteryV      = 3.6f;
    public boolean battAlarm    = false;

    // 계량기 정보
    public int    meterId       = 26123456;
    public int    diameterMm    = 15;
    public int    decimalPos    = 3;
    public boolean statusOverload  = false;
    public boolean statusLeak      = false;
    public boolean statusReverse   = false;
    public boolean statusBattery   = false;

    // 검침/보고 주기
    public int readPeriod   = 1;
    public int reportPeriod = 6;

    // 무선 품질
    public int rssi = 75;   // 절댓값 (음수 처리)
    public int ber  = 0;
    public int cid  = 4288;
    public int rsrp = 80;   // 절댓값
    public int rsrq = 10;   // 절댓값
    public int snr  = -27;

    // ── BCD 인코딩 (Little-Endian, reversed) ─────────────────
    private byte[] bcdEncode(long number, int digits) {
        String s = String.format("%0" + digits + "d", number);
        int pairCount = (s.length() + 1) / 2;
        byte[] pairs = new byte[pairCount];
        for (int i = 0; i < s.length(); i += 2) {
            int high = s.charAt(i) - '0';
            int low  = (i + 1 < s.length()) ? (s.charAt(i+1) - '0') : 0xF;
            pairs[i/2] = (byte)((high << 4) | low);
        }
        // Little-Endian: reverse
        byte[] result = new byte[pairs.length];
        for (int i = 0; i < pairs.length; i++)
            result[i] = pairs[pairs.length - 1 - i];
        return result;
    }

    // ── 체크섬 ────────────────────────────────────────────────
    private int calcChecksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        return sum & 0xFF;
    }

    // ── 5.1 검침 시간 (6B) ────────────────────────────────────
    private byte[] buildMeterTime() {
        Calendar c = Calendar.getInstance();
        return new byte[]{
            (byte)(c.get(Calendar.YEAR) - 2000),
            (byte)(c.get(Calendar.MONTH) + 1),
            (byte) c.get(Calendar.DAY_OF_MONTH),
            (byte) c.get(Calendar.HOUR_OF_DAY),
            (byte) c.get(Calendar.MINUTE),
            (byte) c.get(Calendar.SECOND)
        };
    }

    // ── 5.3 무선 품질 NB-IoT (10B) ───────────────────────────
    private byte[] buildWirelessQuality() {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte)(rssi & 0xFF));       // RSSI 1B
        buf.put((byte)(ber  & 0xFF));       // BER  1B
        buf.putShort((short)(cid & 0xFFFF)); // CID  2B
        buf.putShort((short)(rsrp & 0xFFFF)); // RSRP 2B
        buf.putShort((short)(rsrq & 0xFFFF)); // RSRQ 2B
        buf.putShort((short)(snr  & 0xFFFF)); // SNR  2B
        return buf.array();
    }

    // ── 5.4 계량기 정보 (7B) ─────────────────────────────────
    private byte[] buildMeterInfo() {
        int[] diamMap = {0,15,20,25,32,40,50,80,100,150,200,250,300};
        int diamCode = 0;
        for (int i = 1; i < diamMap.length; i++)
            if (diamMap[i] == diameterMm) { diamCode = i; break; }

        byte[] idBcd = bcdEncode(meterId, 8);
        int diamDec  = (diamCode << 4) | (decimalPos & 0x0F);
        int status   = 0;
        if (statusOverload) status |= 0x80;
        if (statusReverse)  status |= 0x40;
        if (statusLeak)     status |= 0x20;
        if (statusBattery)  status |= 0x04;

        byte[] result = new byte[7];
        System.arraycopy(idBcd, 0, result, 0, 4);
        result[4] = 0x01;                   // 계량기 형식 (항상 0x01)
        result[5] = (byte) diamDec;
        result[6] = (byte) status;
        return result;
    }

    // ── 5.5 이동통신 ID (16B) ────────────────────────────────
    private byte[] buildImeiImsi() {
        // IMEI 15자리 BCD + F padding → 8B
        String imei15 = imei.length() >= 15 ? imei.substring(0,15) : imei;
        String imsi15 = imsi.length() >= 15 ? imsi.substring(0,15) : imsi;
        byte[] imeiB = bcdEncodeImei(imei15);
        byte[] imsiB = bcdEncodeImei(imsi15);
        byte[] result = new byte[16];
        System.arraycopy(imeiB, 0, result, 0, 8);
        System.arraycopy(imsiB, 0, result, 8, 8);
        return result;
    }

    private byte[] bcdEncodeImei(String num15) {
        // 15자리 + F → 8바이트 BCD (Big-Endian, MSB first)
        String padded = num15 + "F";
        byte[] result = new byte[8];
        for (int i = 0; i < 8; i++) {
            int high = Character.digit(padded.charAt(i*2),   16);
            int low  = Character.digit(padded.charAt(i*2+1), 16);
            result[i] = (byte)((high << 4) | low);
        }
        return result;
    }

    // ── 5.6 단말기 정보 (8B) ─────────────────────────────────
    private byte[] buildTerminalInfo() {
        // 일련번호 하위 10자리 BCD 5B
        byte[] serial = bcdEncodeSerial(serialNo);
        // F/W 버전 2B
        byte fw1 = (byte)(fwMajor & 0xFF);
        byte fw2 = (byte)(fwMinor & 0xFF);
        // 배터리 전압/경보 1B
        int battRaw  = Math.round(batteryV * 10) & 0x3F;
        int battByte = battRaw | (battAlarm ? 0x80 : 0x00);

        byte[] result = new byte[8];
        System.arraycopy(serial, 0, result, 0, 5);
        result[5] = fw1;
        result[6] = fw2;
        result[7] = (byte) battByte;
        return result;
    }

    private byte[] bcdEncodeSerial(long num) {
        // 하위 10자리 BCD → 5바이트 Little-Endian
        String s = String.format("%010d", num % 10000000000L);
        byte[] pairs = new byte[5];
        for (int i = 0; i < 5; i++) {
            int high = s.charAt(i*2)   - '0';
            int low  = s.charAt(i*2+1) - '0';
            pairs[i] = (byte)((high << 4) | low);
        }
        // Little-Endian reverse
        byte[] result = new byte[5];
        for (int i = 0; i < 5; i++) result[i] = pairs[4-i];
        return result;
    }

    // ── 5.7 검침/보고 주기 (2B) ──────────────────────────────
    private byte[] buildPeriod() {
        return new byte[]{(byte) readPeriod, (byte) reportPeriod};
    }

    // ── 5.8 검침데이터 (검침값 1개, 9B) ──────────────────────
    private byte[] buildMeterData(double readingValue) {
        // 검침값 → 정수 변환 (소수점 위치 반영)
        long readingInt = Math.round(readingValue * Math.pow(10, decimalPos));

        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) readPeriod);   // 검침 주기
        buf.put((byte) 1);            // 데이터 개수 = 1
        buf.put((byte) 0);            // 기준 검침값 위치 = 0
        buf.putInt((int)(readingInt & 0xFFFFFFFFL)); // 기준 검침값 4B LE
        buf.putShort((short) 0);      // 차이값_0 = 0 (기준값)
        return buf.array();
    }

    // ── 전체 패킷 조립 ────────────────────────────────────────
    public byte[] build(double readingValue) {
        byte[] imeiImsi  = buildImeiImsi();
        byte[] wq        = buildWirelessQuality();
        byte[] termInfo  = buildTerminalInfo();
        byte[] meterInfo = buildMeterInfo();
        byte[] period    = buildPeriod();
        byte[] mTime     = buildMeterTime();
        byte[] mData     = buildMeterData(readingValue);

        // 체크섬 대상: MSG_COMMAND + 이후 모든 필드
        int chkLen = 1 + imeiImsi.length + wq.length + termInfo.length
                   + meterInfo.length + period.length + mTime.length + mData.length;
        byte[] chkPayload = new byte[chkLen];
        int pos = 0;
        chkPayload[pos++] = 0x70; // MSG_COMMAND
        pos = copy(chkPayload, pos, imeiImsi);
        pos = copy(chkPayload, pos, wq);
        pos = copy(chkPayload, pos, termInfo);
        pos = copy(chkPayload, pos, meterInfo);
        pos = copy(chkPayload, pos, period);
        pos = copy(chkPayload, pos, mTime);
        pos = copy(chkPayload, pos, mData);

        int msgLen  = chkLen;
        int checksum = calcChecksum(chkPayload);

        byte[] packet = new byte[2 + chkLen + 1];
        packet[0] = (byte) 0xA3;       // MSG_HEADER
        packet[1] = (byte) msgLen;      // MSG_LENGTH
        System.arraycopy(chkPayload, 0, packet, 2, chkLen);
        packet[2 + chkLen] = (byte) checksum;

        return packet;
    }

    private int copy(byte[] dest, int pos, byte[] src) {
        System.arraycopy(src, 0, dest, pos, src.length);
        return pos + src.length;
    }

    // 패킷 HEX 문자열 반환 (디버그용)
    public static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
