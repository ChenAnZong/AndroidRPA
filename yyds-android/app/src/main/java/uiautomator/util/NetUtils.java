package uiautomator.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URL;

public class NetUtils {
    public static boolean isNetOnline() {
        int counts = 0;
        boolean isNetsOnline = false;
        while (counts++ < 3) {
            try {
                URL url = new URL("https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp");
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                int state = con.getResponseCode();
                if (state >= 200) {
                    isNetsOnline = true;
                    break;
                }
                SystemClock.sleep(100);
            } catch (Exception ex) {
                SystemClock.sleep(500);
            }
        }
        return isNetsOnline;
    }
    public static boolean checkUrlOk(String checkUrl) {
        int counts = 0;
        boolean isNetsOnline = false;
        HttpURLConnection con = null;
        while (counts++ < 3) {
            try {
                URL url = new URL(checkUrl);
                con = (HttpURLConnection) url.openConnection();
                int state = con.getResponseCode();
                if (state >= 200) {
                    con.disconnect();
                    isNetsOnline = true;
                    break;
                }
                con.disconnect();
                SystemClock.sleep(100);
            } catch (Exception ex) {
                try {
                    if (con != null) {
                        con.disconnect();
                    }
                } catch (Exception ignore) {}
                SystemClock.sleep(300);
            }
        }
        return isNetsOnline;
    }
    public static String getLanIp(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            int ip = wifiInfo.getIpAddress();
            @SuppressLint("DefaultLocale") String ipString = String.format("%d.%d.%d.%d", (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
            return ipString;
        } catch (Exception e) {
            return e.toString();
        }
    }
}
