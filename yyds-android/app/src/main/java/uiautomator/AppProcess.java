package uiautomator;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.tencent.yyds.BuildConfig;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ShellUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import pyengine.PyEngine;
import uiautomator.util.DateUtil;
import uiautomator.util.FileUtil;


/**
 通过命令行启动无界面App的相关配置，比较杂碎
 cd /data/local/tmp; CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) nohup app_process /system/bin uiautomator.ExportApi --keep 2>&1 &
 * */
public class AppProcess {
    public static String defaultABI = Build.SUPPORTED_ABIS[0];

    @SuppressLint("StaticFieldLeak")
    public static Context systemContext;

    @SuppressLint("StaticFieldLeak")
    public static Context appContext;
    // 纯POSIX shell兼容：不依赖cut/sed/awk/bash here-string，所有Android sh均支持
    public static String apkPathShell = "$(pm path com.yyds.auto | grep -o '/.*')";

    public static String LD_PRE_LOAD_CMD = "";

    /**
     * 纯POSIX shell提取APK路径（不依赖sed/awk/cut，兼容所有Android sh）
     * pm path输出格式: "package:/data/app/.../base.apk"
     * 用 grep -o '/.*' 提取从第一个/开始的部分；若grep不可用则用shell内置参数展开
     */
    private static final String APK_PATH_SHELL_EXPR = "$(pm path com.yyds.auto | grep -o '/.*')";
    private static final String APK_PATH_SHELL_FALLBACK = "$(p=$(pm path com.yyds.auto) && echo ${p#*:})";

    public static void updateApkShell() {
        // 优先通过Java层pm path查询，完全不依赖外部命令
        String freshPath = ExtSystem.shell("pm path com.yyds.auto").replace("package:", "").trim();
        if (!freshPath.isEmpty() && new File(freshPath).exists()) {
            apkPathShell = freshPath;
        } else if (appContext != null && new File(appContext.getPackageCodePath()).exists()) {
            apkPathShell = appContext.getPackageCodePath();
        } else if (System.getenv("CLASSPATH") != null && !System.getenv("CLASSPATH").contains("/data/local/")) {
            apkPathShell = System.getenv("CLASSPATH");
        } else {
            // shell内置参数展开 ${p#*:} 去掉"package:"前缀，零外部依赖
            apkPathShell = APK_PATH_SHELL_FALLBACK;
        }
    }

    public static String getCodePath() {
        if (appContext != null && new File(appContext.getPackageCodePath()).exists()) {
            return appContext.getPackageCodePath();
        }
        String fetchInstall = ExtSystem.shell("pm path com.yyds.auto").replace("package:", "");
        if (new File(fetchInstall).exists()) return fetchInstall;
        return System.getenv("CLASSPATH");
    }

    /**
     * 启动 yyds.auto 自动化引擎命令
     * 注意：不使用nohup（libsu pipe环境下nohup无效），显式重定向所有fd到/dev/null防止SIGPIPE
     */
    public static String getActiveEngineCmd() {
        updateApkShell();
        return String.format("cd /data/local/tmp; %s CLASSPATH=%s app_process /system/bin uiautomator.ExportApi </dev/null >/dev/null 2>&1 &\n", LD_PRE_LOAD_CMD, apkPathShell);
    }

    /**
     * 启动 yyds.py Python引擎命令
     * LD_LIBRARY_PATH 包含 libpython3.13.so 所在目录
     */
    public static String getActivePyEngineCmd() {
        updateApkShell();
        return String.format("cd /data/local/tmp; %s LD_LIBRARY_PATH=%s CLASSPATH=%s app_process /system/bin pyengine.Main </dev/null >/dev/null 2>&1 &\n", LD_PRE_LOAD_CMD, libPath, apkPathShell);
    }

    /**
     * 获取启动守护进程的shell命令
     * 优先使用native二进制（不依赖zygote），若native不存在则回退到Java版keepMain
     * 不使用nohup，不依赖sed/awk/cut，纯POSIX shell兼容
     */
    public static String getActiveEngineKeeperCmd() {
        updateApkShell();
        String nativeCmd;
        if (apkPathShell.contains("$")) {
            // apkPathShell是shell表达式，需要先求值
            // 用shell内置参数展开 ${p#*:} 替代 sed 's/package://'
            nativeCmd = String.format(
                    "p=$(pm path com.yyds.auto) && APK_PATH=${p#*:}; " +
                    "if [ -x %s ]; then %s \"$APK_PATH\" %s </dev/null >/dev/null 2>&1 &; " +
                    "else cd /data/local/tmp; CLASSPATH=\"$APK_PATH\" app_process /system/bin uiautomator.ExportApi --keep </dev/null >/dev/null 2>&1 &; fi\n",
                    nativeKeeperPath, nativeKeeperPath, LD_PRE_LOAD_CMD);
        } else {
            nativeCmd = String.format(
                    "if [ -x %s ]; then %s %s %s </dev/null >/dev/null 2>&1 &; " +
                    "else cd /data/local/tmp; CLASSPATH=%s app_process /system/bin uiautomator.ExportApi --keep </dev/null >/dev/null 2>&1 &; fi\n",
                    nativeKeeperPath, nativeKeeperPath, apkPathShell, LD_PRE_LOAD_CMD, apkPathShell);
        }
        return nativeCmd;
    }
    // 使用java函数把所有so释放到这里
    public final static String unzipTo = "/data/local/tmp/cache";
    // native守护进程二进制文件路径（从APK lib目录释放）
    public static String nativeKeeperPath = String.format("%s/lib/%s/libyyds_keep.so", unzipTo, defaultABI);
    public static String libPath = String.format("%s/lib/%s", unzipTo, defaultABI);

    public final static String pyUnzipTo = "/data/local/tmp/pylib";
    public static String pyLibPath = String.format("%s/lib/%s", unzipTo, defaultABI);

    static String bbSoPath = String.format("%s/lib/%s/libbusybox.so", unzipTo, defaultABI);
    static String bbInstallPath = "/data/local/tmp/BB";
    static String bbBinaryPath = "/data/local/tmp/BB/busybox";

    // 随机检查一个‘冷门’文件有没有释放
    static String bbInstallCheck = "/data/local/tmp/BB/flash_eraseall";

    // sqlite3 二进制：从APK assets提取到BB目录，使shell默认可用sqlite3命令
    static String sqlite3BinaryPath = bbInstallPath + "/sqlite3";
    static String sqlite3AssetName = "sqlite3_arm64";

    static String apkKeepPathRecord = "/data/local/tmp/cache/keep.apkpath";

    public static void appendKeepLog(String log) {
        FileUtil.appendText("/data/local/tmp/yyds.log", log);
    }

    // content call --uri content://yyds.boot --method api --arg /shell --extra params:s:'{"cmd"\:"wget"}'
    public static void rebootAutoEngine(String reason) {
        if (new File(getCodePath()).exists()) {
            String log = String.format("\n[yyds.auto]当前日期:%s\n运行进程:%s\n执行启动:%s  %s\n启动原因:%s",
                    DateUtil.INSTANCE.getCommonDate(),
                    ShellUtils.fastCmdResult("ps -ef | grep yyds.auto"),
                    AppProcess.getActiveEngineCmd(),
                    ShellUtils.fastCmdResult("chdir /data/local/tmp", AppProcess.getActiveEngineCmd()),
                    reason);
            appendKeepLog(log);
            ExtSystem.printInfo(log);
        } else {
            ExtSystem.printInfo("[yyds.auto]APK 不存在:" + getCodePath());
        }
    }

    public static void rebootPyEngine(String reason) {
        if (new File(getCodePath()).exists()) {
            SystemClock.sleep(5_000);
            String sc = AppProcess.getActivePyEngineCmd();
            String log = String.format("\n[yyds.py]当前日期:%s\n运行进程:%s\n执行启动:%s\n启动原因:%s",
                    DateUtil.INSTANCE.getCommonDate(),
                    ShellUtils.fastCmdResult("pidof yyds.py"),
                    sc,
                    reason);

            String lastPid = ExtSystem.shell("cat /data/local/tmp/.yyds.py.pid");
            String scanProcess3 = "\n进程分析3[PID]:" + lastPid +  "\t" + ExtSystem.shell(String.format("ps -p %s 2> /dev/null", lastPid));
            // 用Java socket探测端口替代curl，零外部依赖
            boolean engineAlive = PyEngine.INSTANCE.isEngineOpen();
            String scanProcess4 = "\n进程分析4(端口61140):" + (engineAlive ? "alive" : "dead");
            appendKeepLog(log + scanProcess3 + scanProcess4);
            if (engineAlive) {
                return;
            }
            if (!lastPid.contains("No such file")) {
                ExtSystem.shell(String.format("logcat --pid %s > /data/local/tmp/.engine.log &", lastPid));
                SystemClock.sleep(3000);
                ExtSystem.shell("killall logcat");
            }
            ExtSystem.printInfo("[yyds.py]执行重启!");
            ExtSystem.shell(sc);
            SystemClock.sleep(10_000);
            ExtSystem.printInfo(log);
        } else {
            ExtSystem.printInfo("\n[yyds.py]APK 不存在:" + getCodePath());
        }
    }

    public static void ensureBusyboxInit() {
        // 后面再看看app是否需要BB环境
        if (!ExtSystem.isInAppMode) {
            File bbPath = new File(bbInstallPath);
            if (!bbPath.exists()) bbPath.mkdirs();
            // 如果不存在这个冷门文件，安装释放这个文件
            if (!new File(bbInstallCheck).exists() && new File(bbSoPath).exists()) {
                ShellUtils.fastCmd(String.format("/system/bin/mv -f %s %s", bbSoPath, bbBinaryPath));
                ShellUtils.fastCmd("chmod +x " + bbBinaryPath);
                ShellUtils.fastCmd(String.format("%s --install -s %s", bbBinaryPath, bbInstallPath));
            } else {
                try {
                    Shell.Initializer bbInitializer = new Shell.Initializer() {
                        @Override
                        public boolean onInit(@NonNull Context context, @NonNull Shell shell) {
                            String fixCmd = String.format("export PATH=%s:$PATH:/data/local/tmp; echo $PATH", bbInstallPath);
                            Shell.Result ret = shell.newJob()
                                    .add(fixCmd)
                                    .exec();
                            return super.onInit(context, shell);
                        }
                    };
                    Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER | Shell.FLAG_REDIRECT_STDERR).setInitializers(bbInitializer.getClass()));
                } catch (Exception e) {
                    ExtSystem.printDebugError("InitShell", e);
                }
            }

            // 从APK assets提取sqlite3到BB目录，使shell默认可用sqlite3命令
            ensureSqlite3Init();
        }

        // 验证apkPathShell是否能正确解析到.apk路径
        if (!apkPathShell.contains(".apk")) {
            // 优先用shell内置参数展开，不依赖awk/sed/cut
            if (!ShellUtils.fastCmdResult(apkPathShell)) {
                apkPathShell = "$(p=$(pm path com.yyds.auto) && echo ${p#*:})";
                if (!ShellUtils.fastCmdResult(apkPathShell)) {
                    // 最后回退：grep -o 在绝大多数Android上可用
                    apkPathShell = "$(pm path com.yyds.auto | grep -o '/.*')";
                }
            }
        }
    }

    /**
     * 从APK assets中提取sqlite3二进制到BB目录（已在PATH中），使shell默认可用sqlite3命令
     * 类似busybox的处理方式，放在同一个PATH目录下
     */
    private static void ensureSqlite3Init() {
        try {
            File sqlite3File = new File(sqlite3BinaryPath);
            if (sqlite3File.exists()) return; // 已存在，跳过

            // 获取APK路径，从中提取assets文件
            String codePath = getCodePath();
            if (codePath == null || !new File(codePath).exists()) return;

            ZipFile apkZip = new ZipFile(codePath);
            ZipEntry entry = apkZip.getEntry("assets/" + sqlite3AssetName);
            if (entry == null) {
                apkZip.close();
                return;
            }

            // 确保BB目录存在
            File bbDir = new File(bbInstallPath);
            if (!bbDir.exists()) bbDir.mkdirs();

            // 提取sqlite3到BB目录
            InputStream is = apkZip.getInputStream(entry);
            FileOutputStream fos = new FileOutputStream(sqlite3File);
            byte[] buffer = new byte[4096];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
            fos.close();
            is.close();
            apkZip.close();

            // 设置可执行权限
            ShellUtils.fastCmd("chmod +x " + sqlite3BinaryPath);
            ExtSystem.printInfo("[sqlite3] 已提取到 " + sqlite3BinaryPath);
        } catch (Exception e) {
            ExtSystem.printDebugError("ensureSqlite3Init", e);
        }
    }


    private static Boolean isMytOs = null;
    public static boolean isMytDevice() {
        if (isMytOs != null) return isMytOs;
        if (Build.VERSION.SDK_INT < 34) {
            isMytOs = !ExtSystem.shell("getprop sys.rkadb.root").isEmpty() || !ExtSystem.shell("getprop ro.vendor.rk_sdk").isEmpty();
        } else {
            isMytOs = ExtSystem.shell("getprop ro.boot.hardware").startsWith("rk")
                    && ExtSystem.shell("getprop ro.product.odm.brand").equals("dobox");
        }
        return isMytOs;
    }

    /**
     * 检查指定进程名是否正在运行。
     * 遍历 /proc 目录下所有数字子目录（即所有进程），读取 /proc/PID/cmdline 匹配进程名。
     * 不硬编码PID上限，兼容所有Android版本。
     */
    public static Boolean isProcessRunning(String processName) {
        try {
            File procDir = new File("/proc");
            File[] entries = procDir.listFiles();
            if (entries == null) return null;
            for (File entry : entries) {
                String name = entry.getName();
                // 只处理数字目录（PID）
                if (!name.chars().allMatch(Character::isDigit)) continue;
                File cmdlineFile = new File(entry, "cmdline");
                if (!cmdlineFile.exists()) continue;
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new FileReader(cmdlineFile));
                    String cmdline = reader.readLine();
                    if (cmdline != null && cmdline.contains(processName)) {
                        return true;
                    }
                } catch (IOException ignored) {
                } finally {
                    if (reader != null) try { reader.close(); } catch (IOException ignored) {}
                }
            }
            return false;
        } catch (Exception e) {
            ExtSystem.printDebugError(e);
        }
        return null;
    }

    /** 用户执行的 ADB 激活命令（脚本路径） */
    public static final String ACTIVATE_SCRIPT_ADB_CMD =
            "adb shell sh /sdcard/Android/data/com.yyds.auto/activate.sh";

    /**
     * 生成 ADB 激活 shell 脚本内容
     * 脚本自动完成：检测安装 → 释放SO → 启动守护进程 → 验证结果
     */
    public static String getActivateScriptContent() {
        return "#!/system/bin/sh\n" +
            "echo '=== Yyds.Auto 引擎激活 ==='\n" +
            "echo ''\n" +
            "# 检查是否已在运行\n" +
            "if pidof yyds.keep >/dev/null 2>&1; then\n" +
            "  echo '[OK] yyds.keep 守护进程已在运行'\n" +
            "  pidof yyds.auto >/dev/null 2>&1 && echo '[OK] yyds.auto 自动化引擎已在运行'\n" +
            "  pidof yyds.py >/dev/null 2>&1 && echo '[OK] yyds.py 脚本引擎已在运行'\n" +
            "  echo ''\n" +
            "  echo '引擎已激活，无需重复操作'\n" +
            "  exit 0\n" +
            "fi\n\n" +
            "# 获取APK路径\n" +
            "APK_PATH=$(pm path com.yyds.auto | grep -o '/.*')\n" +
            "if [ -z \"$APK_PATH\" ]; then\n" +
            "  p=$(pm path com.yyds.auto) && APK_PATH=${p#*:}\n" +
            "fi\n" +
            "if [ -z \"$APK_PATH\" ]; then\n" +
            "  echo '[FAIL] 未找到 Yyds.Auto 应用，请先安装'\n" +
            "  exit 1\n" +
            "fi\n" +
            "echo \"[i] APK路径: $APK_PATH\"\n\n" +
            "# 获取设备架构\n" +
            "ABI=$(getprop ro.product.cpu.abi)\n" +
            "echo \"[i] 设备架构: $ABI\"\n\n" +
            "# 释放引擎文件\n" +
            "CACHE=/data/local/tmp/cache\n" +
            "LIB=$CACHE/lib/$ABI\n" +
            "mkdir -p $LIB\n" +
            "echo '[*] 正在释放引擎文件...'\n" +
            "unzip -o \"$APK_PATH\" \"lib/$ABI/*.so\" -d $CACHE 2>/dev/null\n" +
            "chmod +x $LIB/libyyds_keep.so\n\n" +
            "# 检查释放结果\n" +
            "KEEPER=$LIB/libyyds_keep.so\n" +
            "if [ ! -x \"$KEEPER\" ]; then\n" +
            "  echo '[FAIL] 引擎文件释放失败，请检查应用是否完整安装'\n" +
            "  exit 1\n" +
            "fi\n" +
            "echo '[OK] 引擎文件释放完成'\n\n" +
            "# 启动守护进程\n" +
            "echo '[*] 正在启动引擎...'\n" +
            "LD_LIBRARY_PATH=$LIB nohup $KEEPER \"$APK_PATH\" LD_LIBRARY_PATH=$LIB >/dev/null 2>&1 &\n" +
            "sleep 2\n\n" +
            "# 验证启动结果\n" +
            "if pidof yyds.keep >/dev/null 2>&1; then\n" +
            "  echo '[OK] 守护进程已启动'\n" +
            "  echo '[*] 等待工作进程启动（约10秒）...'\n" +
            "  sleep 10\n" +
            "  echo ''\n" +
            "  pidof yyds.auto >/dev/null 2>&1 && echo '[OK] 自动化引擎已启动' || echo '[..] 自动化引擎启动中，请稍候'\n" +
            "  pidof yyds.py >/dev/null 2>&1 && echo '[OK] 脚本引擎已启动' || echo '[..] 脚本引擎启动中，请稍候'\n" +
            "  echo ''\n" +
            "  echo '=== 引擎激活成功！请返回App查看状态 ==='\n" +
            "else\n" +
            "  echo '[FAIL] 引擎启动失败'\n" +
            "  echo '请确认：'\n" +
            "  echo '  1. USB调试已开启'\n" +
            "  echo '  2. 已允许此电脑调试'\n" +
            "  exit 1\n" +
            "fi\n";
    }

    /**
     * 将激活脚本写入外部存储，供 adb shell 直接执行
     * @return 写入是否成功
     */
    public static boolean writeActivateScript(Context context) {
        try {
            // 写入应用外部数据根目录（/sdcard/Android/data/com.yyds.auto/）
            File extDir = context.getExternalFilesDir(null);
            if (extDir == null) return false;
            File targetDir = extDir.getParentFile();
            if (targetDir == null) targetDir = extDir;
            if (!targetDir.exists()) targetDir.mkdirs();
            File scriptFile = new File(targetDir, "activate.sh");
            FileOutputStream fos = new FileOutputStream(scriptFile);
            fos.write(getActivateScriptContent().getBytes());
            fos.close();
            return true;
        } catch (Exception e) {
            ExtSystem.printDebugError("写入激活脚本失败", e);
            return false;
        }
    }

    public static void checkMySignInThread() {
        if (getCodePath().contains("/data/local/tmp")) return;
        new Thread(new Runnable() {
            @SuppressLint("DefaultLocale")
            @Override
            public void run() {
                String ret = ExtSystem.shell(String.format("dumpsys package %s", BuildConfig.APPLICATION_ID));
                if (ret.contains("version")) {
                    if (!ret.contains(Const.DUMPSYS_PACKAGE_SIG_HASH)) {
                        ExtSystem.printDebugLog("sign err!!!!!");
                        ExtSystem.shell("killall " + BuildConfig.APPLICATION_ID);
                    }
                } else {
                    ExtSystem.printDebugLog("dumpsys err!!!!!!!!!!");
                }
            }
        }).start();
    }
}
