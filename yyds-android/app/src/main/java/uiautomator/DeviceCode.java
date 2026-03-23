package uiautomator;

import android.os.Build;

import java.io.File;

import uiautomator.util.FileUtil;
import uiautomator.util.InternalApi;
import uiautomator.util.NumUtil;

public class DeviceCode {
    public static String otherWay() {
        String prop =  ExtSystem.shell("getprop | grep imei | head -1");
        if (prop.contains("[") && prop.contains(":")) {
            return prop.substring(prop.lastIndexOf("[") + 1, prop.length() - 1);
        } else {
            return ExtSystem.shell("stat -f / | grep ID").replace(" ", "").replace("ID","").replace("NameLen", "");
        }
    }

    public static String getDeviceCode() {
        // 安卓7特殊适配
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            String[] paths = new String[]{"/persist/dc", "/cust/cust/dc"};
            for (String path: paths) {
                if (new File(path).exists()) {
                    String content =  FileUtil.getText(path);
                    if (content.length() > 8)
                    {
                        return content;
                    }
                }
            }
        }
        // 以小米imei为首
        String[] imeiKeys = new String[]{"gsm.sim.imei", "persist.radio.imei", "persist.radio.meid", "ro.ril.oem.imei", "ro.ril.miui.imei0", "gsm.sim.hw_atr", "gsm.sim.hw_atr1", "persist.patch.sys.imei", "ril.IMSI"};
        for (String imeiKey:imeiKeys) {
            String imeiTry = ExtSystem.shell("getprop " + imeiKey);
            if (imeiTry.length() > 8 && NumUtil.isNumeric(imeiTry)) {
                return imeiTry;
            }
        }
        // 其它机型 尝试通过服务获取
        String imeiTry2 = ExtSystem.shell("service call iphonesubinfo 1 i64 0 | cut -c 52-66 | tr -d '.[:space:]'");
        if (imeiTry2.length() < 20 && imeiTry2.length() > 8 && NumUtil.isNumeric(imeiTry2)) {
            return imeiTry2;
        }
        // 通过反射系统获取
        try {
            final String cls = "com.android.internal.telephony.IPhoneSubInfo$Stub";
            final Object iphoneSubInfo = InternalApi.getServiceAsInterface("iphonesubinfo", cls);
            final String callingPackage = "android";
            String ret =  (String)iphoneSubInfo.getClass()
                    .getMethod("getDeviceIdWithFeature", String.class, String.class)
                    .invoke(iphoneSubInfo, callingPackage, null);
            if (ret == null || ret.length() < 5) {
                return otherWay();
            }
            return ret;
        } catch (Exception e) {
                return otherWay();
        }
    }
}
