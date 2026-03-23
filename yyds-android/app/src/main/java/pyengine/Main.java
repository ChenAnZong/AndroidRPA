package pyengine;

import static java.lang.System.exit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;

import androidx.annotation.Keep;

import com.tencent.yyds.BuildConfig;

import kotlin.Suppress;
import uiautomator.AppProcess;
import uiautomator.ExportApi;
import uiautomator.ExtSystem;
import uiautomator.util.ZipUtils;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.util.Arrays;

@Keep
public
class Main {
    public static final String  PyEngineProcessName = "yyds.py";
    private static final String AutoEngineProcessName = "yyds.auto";
    private static final String KeeperProcessName = "yyds.keep";
    /** 守护线程检查间隔（毫秒） */
    private static final long GUARD_CHECK_INTERVAL = 30_000;
    /** 守护线程初始延迟（毫秒），等待其他进程启动完成 */
    private static final long GUARD_INITIAL_DELAY = 45_000;

    private static void setArgV0(String text) {
        try {
            java.lang.reflect.Method setter = Process.class.getMethod("setArgV0", String.class);
            setter.invoke(Process.class, text);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    /**
     * 守护线程：yyds.py 进程监控 yyds.auto 和 yyds.keep，实现互相守护
     * - 每30秒检查一次 yyds.auto 和 yyds.keep 是否存活
     * - 如果 yyds.auto 未运行，自动重启（yyds.auto 启动时会自动启动 yyds.keep）
     * - 如果 yyds.keep 未运行，单独重启守护进程
     */
    private static void startGuardThread() {
        new Thread(() -> {
            ExtSystem.printInfo("[yyds.py守护] 守护线程启动，初始等待" + (GUARD_INITIAL_DELAY / 1000) + "秒...");
            SystemClock.sleep(GUARD_INITIAL_DELAY);
            ExtSystem.printInfo("[yyds.py守护] 开始监控 yyds.auto 和 yyds.keep 进程");
            while (true) {
                try {
                    // 检查 yyds.auto 自动化引擎
                    String autoPid = ExtSystem.shell("pidof " + AutoEngineProcessName).trim();
                    if (autoPid.isEmpty()) {
                        ExtSystem.printInfo("[yyds.py守护] 检测到自动化引擎(yyds.auto)未运行，正在重启...");
                        AppProcess.rebootAutoEngine("yyds.py守护线程检测到进程不存在");
                    }

                    // 检查 yyds.keep native守护进程
                    String keepPid = ExtSystem.shell("pidof " + KeeperProcessName).trim();
                    if (keepPid.isEmpty()) {
                        ExtSystem.printInfo("[yyds.py守护] 检测到native守护进程(yyds.keep)未运行，正在重启...");
                        ExtSystem.shell(AppProcess.getActiveEngineKeeperCmd());
                    }
                } catch (Exception e) {
                    ExtSystem.printDebugError("[yyds.py守护] 检查进程状态异常", e);
                }
                SystemClock.sleep(GUARD_CHECK_INTERVAL);
            }
        }, "py-guard").start();
    }

    @SuppressLint({"DefaultLocale", "UnsafeDynamicallyLoadedCode"})
    @Suppress(names = "deprecation")
    public static void main(String[] args) throws Exception {
        ExtSystem.printDebugLog("--------------PyMain-----------PID=" + Os.getpid());
        ExtSystem.printDebugLog("LD_PRELOAD:" + System.getenv("LD_PRELOAD"));

        AppProcess.ensureBusyboxInit();
        ExtSystem.killParent();
        ExtSystem.shell("killall " + PyEngineProcessName);
        System.setProperty("io.ktor.http.content.multipart.skipTempFile", "true");
        ExtSystem.printInfo("设置环境变量:" + System.getProperty("io.ktor.http.content.multipart.skipTempFile"));
        Thread.setDefaultUncaughtExceptionHandler((t, e )-> {
           ExtSystem.printDebugError("线程名：" + t.getName() + " 线程ID:" + t.getId() +   " 未处理的线程异常" , e);
        });
        // 防止跑一段时间卡住！
        ExtSystem.shell("ps -ef | grep su | grep u0_ | awk '{print $1}'| xargs kill -9");
        Looper.prepareMainLooper();
        Context sysContext = ContextUtil.getSystemContext();
        boolean isApkInstalled = true;
        try {
            sysContext.getPackageManager().getPackageGids(BuildConfig.APPLICATION_ID);
        } catch (PackageManager.NameNotFoundException e) {
            isApkInstalled = false;
            ExtSystem.printInfo("!APK似乎未安装!");
        }
        if (isApkInstalled) {
            // 赋值到全局变量
            Context context = sysContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY); //android
            AppProcess.appContext = context;
            ZipUtils.unZipSoFile(
                    context.getPackageCodePath(),
                    AppProcess.pyUnzipTo);
            ExtSystem.shell("chown shell:shell -R " + AppProcess.pyUnzipTo);
            ExtSystem.shell("chown shell:shell -R /data/local/tmp/yyds.py");
            PyEngine.INSTANCE.initPythonParser(context);
            // 等待Socket端口释放完毕
            SystemClock.sleep(1500);
        }
        // Runner模式：自动提取内嵌脚本（yyds.py工作进程有ROOT/SHELL权限可写外部存储）
        if (isApkInstalled && AppProcess.appContext != null) {
            try {
                Context ctx = AppProcess.appContext;
                if (ApkPackageHelper.INSTANCE.isRunnerMode(ctx)) {
                    ApkPackageHelper.PackConfig packCfg = ApkPackageHelper.INSTANCE.readPackConfig(ctx);
                    if (packCfg != null) {
                        String projectName = packCfg.getProjectName();
                        boolean encrypted = packCfg.getEncryptScripts()
                                && packCfg.getEncryptionSalt() != null
                                && !packCfg.getEncryptionSalt().isEmpty();

                        File targetDir = new File(
                                PyEngine.INSTANCE.getProjectDir(), projectName);

                        if (encrypted) {
                            // 加密模式：每次启动清理旧文件并重新提取+解密
                            // 这样避免解密后的 .py 长期暴露在文件系统中
                            ExtSystem.printInfo("[yyds.py] Runner加密模式，清理旧文件...");
                            if (targetDir.exists()) {
                                ExtSystem.shell("rm -rf " + targetDir.getAbsolutePath());
                            }
                        }

                        ExtSystem.printInfo("[yyds.py] Runner模式检测到，提取内嵌项目: " + projectName);
                        ApkPackageHelper.INSTANCE.extractBundledProject(ctx, projectName);

                        if (encrypted) {
                            byte[] salt = ScriptEncryptor.INSTANCE.saltFromBase64(
                                    packCfg.getEncryptionSalt());
                            int decCount = ScriptEncryptor.INSTANCE.decryptProjectInPlace(
                                    targetDir, salt);
                            ExtSystem.printInfo("[yyds.py] 已解密 " + decCount + " 个脚本文件");

                            // 限制权限：仅 shell 用户可访问
                            ExtSystem.shell("chmod -R 700 " + targetDir.getAbsolutePath());

                            // 注册退出钩子：进程退出时清理解密后的脚本
                            final File cleanupDir = targetDir;
                            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                                try {
                                    ScriptEncryptor.INSTANCE.cleanupDecryptedFiles(cleanupDir);
                                } catch (Exception ignored) {}
                            }, "enc-cleanup"));
                        }
                    }
                }
            } catch (Exception e) {
                ExtSystem.printDebugError("[yyds.py] Runner模式提取项目失败", e);
            }
        }

        setArgV0(PyEngineProcessName);
        ExtSystem.shell("echo " + Process.myPid() + " > /data/local/tmp/.yyds.py.pid");
        try {
            Os.umask(0);
            Os.setsid();
        } catch (Exception ignore) {}
        ExtSystem.printDebugLog("Arg:", Arrays.toString(args));

        // 启动守护线程：监控 yyds.auto 和 yyds.keep 进程，实现互相守护
        startGuardThread();

        // 启动远程连接线程：连接 yyds-con 服务器（如果配置了 server.conf）
        new Thread(() -> WebSocketAsClient.INSTANCE.keepConnected(), "ws-client").start();

        try {
            PyEngine.INSTANCE.startWsSocket();
        } catch (BindException e) {
            exit(1);
        }
        Looper.loop();
    }
}