package uiautomator;

import android.annotation.SuppressLint;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.tencent.yyds.App;
import com.tencent.yyds.BuildConfig;
import com.topjohnwu.superuser.Shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.stream.Collectors;

import uiautomator.util.NetUtils;

public class ExtSystem {
    public static int uid() {
        return Process.myUid();
    }
    public static int pid() {
        return Process.myPid();
    }
    public static final String globalTAG = "YYDS_EXT";

    public static boolean isRootMode = android.os.Process.myUid() == 0;

    public static boolean isInAppMode = android.os.Process.myUid() > 10000;

    public static void printDebugLog(Object ...src) {
        String printString;
        if (src.length == 1 && src[0] instanceof String) {
            printString = (String)src[0];
        } else {
            printString = Arrays.toString(src);
        }
       if (printString.startsWith("#") && !BuildConfig.DEBUG) {
            return;
        }
        if (!isInAppMode) {
            System.out.println(printString);
        }
        Log.d(globalTAG, printString);
    }

    private static String readPPID() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("PPid:")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return parts[1];
                    }
                }
            }
        } catch (IOException e) {
            ExtSystem.printDebugError(e);
        }
        return null;
    }

    public static void killParent() {
        String ppid = readPPID();
        ExtSystem.printInfo("===ppid:" + ppid);
        if (!Objects.equals(ppid, "1")) {
            try {
                Runtime.getRuntime().exec("kill -9 " + ppid);
                printInfo("===kill parent");
            } catch (Exception ignore) {}
        }
    }

    public static void printInfo(Object ...src) {
        String printString;
        if (src.length == 1 && src[0] instanceof String) {
            printString = (String)src[0];
        } else {
            printString = Arrays.toString(src);
        }
        if (!isInAppMode) {
            System.out.println(printString);
        }
        Log.i(globalTAG, printString);
    }

    public static void printDebugError(Throwable throwable) {
        Log.e(globalTAG, Log.getStackTraceString(throwable));
        throwable.printStackTrace();
    }

    public static void printDebugError(String desc, Throwable throwable) {
        Log.e(globalTAG, desc + "\n" + Log.getStackTraceString(throwable));
        throwable.printStackTrace();
    }

    public static String shell(String ...cmd) {
        // 在App或者Python调用
        if (isInAppMode) {
            try {
                String ret =  ExportHandle.getHandler().http("/shell", Collections.singletonMap("cmd", String.join("; ", cmd)));
                if (!ExportHandle.isRemoteHandleSuccess(ret)) {
                    throw new IllegalStateException(ret);
                }
                return ret;
            } catch (Exception e) {
                ExtSystem.printDebugLog(e.getMessage(), "在apk执行SH:", Arrays.toString(cmd));
                return App.shell(cmd);
            }
        } else {
            ArrayList<String> out = new ArrayList<>();
            ArrayList<String> shPath = new ArrayList<>();
            FileOutputStream fs = null;
            for (int i = 0; i < cmd.length; i++) {
                if (cmd[i].contains("\n")) {
                    @SuppressLint("DefaultLocale") File shFile = new File(String.format("/data/local/tmp/%d_%s.sh", cmd.length, SystemClock.elapsedRealtime()));
                    try {
                        fs = new FileOutputStream(shFile);
                        shPath.add(shFile.getAbsolutePath());
                        fs.write(cmd[i].getBytes(StandardCharsets.UTF_8));
                        fs.flush();
                    } catch (Exception e) {
                        ExtSystem.printDebugError("通过写出shell文件执行命令错误", e);
                    } finally {
                        try {
                            if (fs != null) fs.close();
                        } catch (Exception ignore) {}
                    }
                    cmd[i] = "sh " + shFile.getAbsolutePath();
                }
            }
            Shell.getShell().newJob().to(out).add(cmd).exec();
            String res = String.join("\n", out);
            // 写出shell文件就删除回去！
            for (String sp:shPath) {
                boolean ignore = (new File(sp)).delete();
            }
            return res;
        }
    }
    /**
     * 传入当前版本号到Shell脚本，对比服务器版本
     * */
    public static void checkUpdate() {
        if (!NetUtils.isNetOnline()) {
            printDebugLog("当前网络无效");
            return;
        }

        try {
            URL url = new URL("http://yydsxx.com:5031/wj");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            int state = con.getResponseCode();
            if (state >= 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
                String remoteShell = reader.lines().collect(Collectors.joining("\n"));
                // 设置环境变量
                printDebugLog("检查更新输出:\n" + shell("CUR_VERSION=" + BuildConfig.VERSION_CODE, remoteShell));
            } else {
                ExtSystem.printDebugLog("# checkupdata ret state code", state);
            }
        } catch (Exception ex) {
            printDebugError(ex);
        }
    }
}
