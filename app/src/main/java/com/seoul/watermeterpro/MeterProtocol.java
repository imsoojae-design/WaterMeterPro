package com.seoul.watermeterpro;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MeterProtocol {
    public static final int LONG_START  = 0x68;
    public static final int SHORT_START = 0x10;
    public static final int STOP_BYTE   = 0x16;
    public static final int C_REQ_UD2   = 0x5B;
    public static final int BAUD_RATE   = 1200;
    private static final int[] DIAMETER = {0,15,20,25,32,40,50,80,100,150,200,250,300};

    public static byte[] buildRequest(int addr) {
        int c=C_REQ_UD2, a=addr&0xFF;
        return new byte[]{(byte)SHORT_START,(byte)c,(byte)a,(byte)((c+a)&0xFF),(byte)STOP_BYTE};
    }

    public static ParseResult parseLongFrame(byte[] data) {
        ParseResult r = new ParseResult();
        r.rawHex = toHex(data);
        r.timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        if (data.length<14){r.error="길이 부족";return r;}
        if ((data[0]&0xFF)!=LONG_START||(data[3]&0xFF)!=LONG_START){r.error="Start 오류";return r;}
        if ((data[data.length-1]&0xFF)!=STOP_BYTE){r.error="Stop 오류";return r;}
        r.addr=data[5]&0xFF;
        int udLen=data.length-9;
        if (udLen<11){r.error="UserData 짧음";return r;}
        byte[] ud=new byte[udLen];
        System.arraycopy(data,7,ud,0,udLen);
        int calcChk=0;
        for(int i=4;i<data.length-2;i++) calcChk+=(data[i]&0xFF);
        r.checksumOk=((calcChk&0xFF)==(data[data.length-2]&0xFF));
        r.meterNo=parseMeterNo(new byte[]{ud[1],ud[2],ud[3],ud[4]});
        int status=ud[5]&0xFF;
        r.q3Exceed=(status&0x80)!=0; r.reverse=(status&0x40)!=0;
        r.leak=(status&0x20)!=0; r.battLow=(status&0x04)!=0;
        int dif=ud[6]&0xFF, vif=ud[7]&0xFF;
        r.decimals=vif&0x0F;
        int di=(dif>>4)&0xF;
        r.diameter=(di<DIAMETER.length)?DIAMETER[di]:0;
        r.reading=parseBcd(new byte[]{ud[8],ud[9],ud[10],ud[11]},r.decimals);
        r.ok=true; return r;
    }

    private static double parseBcd(byte[] data, int decimals) {
        StringBuilder sb=new StringBuilder();
        for(int i=data.length-1;i>=0;i--) sb.append(String.format("%02X",data[i]&0xFF));
        try{return Long.parseLong(sb.toString())/Math.pow(10,decimals);}
        catch(NumberFormatException e){return 0.0;}
    }

    private static String parseMeterNo(byte[] data) {
        StringBuilder sb=new StringBuilder();
        for(int i=data.length-1;i>=0;i--) sb.append(String.format("%02X",data[i]&0xFF));
        String s=sb.toString(); return s.substring(0,2)+"-"+s.substring(2);
    }

    public static String toHex(byte[] data) {
        StringBuilder sb=new StringBuilder();
        for(byte b:data){if(sb.length()>0)sb.append(' ');sb.append(String.format("%02X",b&0xFF));}
        return sb.toString();
    }

    public static int findLongFrameEnd(byte[] buf, int len) {
        for(int i=0;i<len-5;i++){
            if((buf[i]&0xFF)==LONG_START&&i+3<len&&(buf[i+3]&0xFF)==LONG_START){
                int lf=buf[i+1]&0xFF, exp=lf+6;
                if(i+exp<=len&&(buf[i+exp-1]&0xFF)==STOP_BYTE) return i+exp;
            }
        }
        return -1;
    }

    public static class ParseResult {
        public boolean ok=false; public String error="",timestamp="",rawHex="";
        public int addr=0,diameter=0,decimals=3;
        public double reading=0; public String meterNo="—";
        public boolean q3Exceed,reverse,leak,battLow,checksumOk;
        public String statusString(){
            if(!q3Exceed&&!reverse&&!leak&&!battLow) return "정상";
            StringBuilder sb=new StringBuilder();
            if(q3Exceed)sb.append("Q3초과 ");if(reverse)sb.append("역류 ");
            if(leak)sb.append("누수 ");if(battLow)sb.append("배터리낮음");
            return sb.toString().trim();
        }
        public boolean hasWarning(){return q3Exceed||reverse||leak||battLow;}
        public String readingFmt(){return String.format(Locale.getDefault(),"%,."+decimals+"f",reading);}

        // UDP 전송용 JSON 패킷 생성
        public String toUdpJson() {
            return "{"
                + "\"meterNo\":\"" + meterNo + "\","
                + "\"reading\":" + readingFmt() + ","
                + "\"unit\":\"m3\","
                + "\"diameter\":" + diameter + ","
                + "\"status\":\"" + statusString() + "\","
                + "\"timestamp\":\"" + timestamp + "\","
                + "\"checksumOk\":" + checksumOk + ","
                + "\"rawHex\":\"" + rawHex + "\""
                + "}";
        }
    }
}
