package uiautomator;


import static uiautomator.AppProcess.appContext;
import static uiautomator.AppProcess.systemContext;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.media.Image;
import android.nfc.FormatException;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import com.tencent.yyds.AiConfig;
import com.tencent.yyds.App;
import com.tencent.yyds.BuildConfig;
import com.tencent.yyds.PpOcrNcnn;
import com.tencent.yyds.ViewObject;
import com.tencent.yyds.YoloNcnn;
import com.topjohnwu.superuser.ShellUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import common.BootService;
import common.YyInputService;
import image.ColorHelper;
import image.ImageHelper;
import io.github.hexhacking.xunwind.XUnwind;
import kotlin.Suppress;
import myt.LanguageUtil;
import pyengine.ContextUtil;
import pyengine.PyEngine;
import scrcpy.ClipboardManagerWrapper;
import uiautomator.compat.NotificationWrapper;
import uiautomator.input.InputUtil;
import uiautomator.input.RootAutomator;
import uiautomator.test.TouchAction;
import uiautomator.tool.Foreground;
import uiautomator.tool.ScreenCapture;
import uiautomator.u2.HierarchyDumper;
import uiautomator.u2.HierarchyParser;
import uiautomator.util.DateUtil;
import uiautomator.util.FileUtil;
import uiautomator.util.InternalApi;
import uiautomator.util.NetUtils;
import uiautomator.util.NumUtil;
import uiautomator.util.ZipUtils;
import yyapp.AppDataHelper;
import yyapp.ReflectUtil;
import yyapp.YyInput;

import yyapp.register.HttpUtil;

public class ExportApi {
    private static final String TAG = "ExportApi";
    private static final String mainProcessName = "yyds.auto";
    private static final String keepProcessName = "yyds.keep";
    private static PpOcrNcnn ppOcrNcnn = null;
    private static YoloNcnn yoloNcnn = null;
    private static final int timeoutSecond = 30;
    private static String codePath;
    private static boolean isNotApkInstallMode = false;

    @SuppressLint("StaticFieldLeak")
    public static ExportApi instance;
    // Touch injection mode:
    // "java"   = TouchController (framework layer, detectable)
    // "kernel" = RootAutomator real device (/dev/input/eventX, indistinguishable from real)
    // "uinput" = RootAutomator uinput clone (/dev/uinput, clones real device identity)
    // "auto"   = try kernel -> uinput -> java (kernel is undetectable, preferred)
    private static String touchMode = "auto";
    private static boolean notUseRaClick = true;

    private static volatile boolean isHasCheck = Objects.equals(Build.SUPPORTED_ABIS[0], "x86_64");

    private static RootAutomator ra;

    // ═══════════════════════════════════════════════════════════════
    // YOLO model auto-init helper
    // ═══════════════════════════════════════════════════════════════
    /**
     * 确保 YOLO 模型已加载。三级 fallback:
     * 1. 用户自定义目录 /data/local/tmp/yyds_yolo
     * 2. 旧路径 /data/local/tmp/yyds.param+.bin
     * 3. APK 内置 assets（仅 APK 安装模式）/ 内置释放目录
     * @return null=成功, 非null=错误信息
     */
    private static synchronized String ensureYoloLoaded() {
        if (yoloNcnn != null) return null;

        yoloNcnn = new YoloNcnn();
        boolean initState = false;

        // 1. 优先检查用户自定义模型目录
        File userDir = new File(AiConfig.USER_YOLO_DIR);
        File[] userParams = userDir.listFiles((d, n) -> n.endsWith(".param"));
        File[] userBins = userDir.listFiles((d, n) -> n.endsWith(".bin"));
        if (userParams != null && userParams.length > 0 && userBins != null && userBins.length > 0) {
            initState = yoloNcnn.init(userBins[0].getAbsolutePath(), userParams[0].getAbsolutePath());
            if (initState) {
                ExtSystem.printDebugLog("@YOLO 加载用户自定义模型: " + userDir);
            }
        }

        // 2. 兼容旧路径 /data/local/tmp/yyds.param + .bin
        if (!initState) {
            String legacyBin = AiConfig.DEFAULT_YOLO_MODEL_NAME + ".bin";
            String legacyParam = AiConfig.DEFAULT_YOLO_MODEL_NAME + ".param";
            if (new File(legacyParam).exists() && new File(legacyBin).exists()) {
                initState = yoloNcnn.init(legacyBin, legacyParam);
                if (initState) ExtSystem.printDebugLog("@YOLO 加载旧路径模型");
            }
        }

        // 3. 内置模型：APK 安装模式用 assets，非 APK 模式用释放目录
        if (!initState) {
            if (!isNotApkInstallMode && appContext != null) {
                initState = yoloNcnn.init(appContext.getAssets(), "yolov8n");
                if (initState) ExtSystem.printDebugLog("@YOLO 加载APK内置模型(assets)");
            } else {
                String builtinParam = AiConfig.BUILTIN_YOLO_DIR + "/" + AiConfig.BUILTIN_YOLO_PARAM;
                String builtinBin = AiConfig.BUILTIN_YOLO_DIR + "/" + AiConfig.BUILTIN_YOLO_BIN;
                if (new File(builtinParam).exists() && new File(builtinBin).exists()) {
                    initState = yoloNcnn.init(builtinBin, builtinParam);
                    if (initState) ExtSystem.printDebugLog("@YOLO 加载内置释放目录模型");
                }
            }
        }

        if (!initState) {
            yoloNcnn = null;
            return "YOLO模型加载失败。\n"
                + "方式1: 将 .param + .bin 文件放到 " + AiConfig.USER_YOLO_DIR + " 目录\n"
                + "方式2: 使用 set_yolo_model(dir='模型目录') 指定路径\n"
                + "模型导出: yolo export model=yolov8n.pt format=ncnn";
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // JSON response helpers
    // ═══════════════════════════════════════════════════════════════
    private static String jsonOk(Object data) {
        try {
            JSONObject j = new JSONObject();
            j.put("ok", true);
            if (data instanceof JSONObject || data instanceof JSONArray) {
                j.put("data", data);
            } else {
                j.put("data", data == null ? JSONObject.NULL : data);
            }
            return j.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"data\":" + (data == null ? "null" : "\"" + data.toString().replace("\"", "\\\"") + "\"") + "}";
        }
    }

    private static String jsonErr(String msg) {
        try {
            JSONObject j = new JSONObject();
            j.put("ok", false);
            j.put("error", msg);
            return j.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg.replace("\"", "\\\"") + "\"}";
        }
    }

    /** Build a color JSON object */
    private static JSONObject colorJson(int r, int g, int b) throws Exception {
        JSONObject c = new JSONObject();
        c.put("r", r);
        c.put("g", g);
        c.put("b", b);
        return c;
    }

    // ═══════════════════════════════════════════════════════════════
    // Touch channel init (dual-channel adaptive fallback)
    // ═══════════════════════════════════════════════════════════════
    private static synchronized void initTouchChannel(String mode) {
        touchMode = mode;
        if ("java".equals(mode)) {
            notUseRaClick = true;
            return;
        }
        if (ExtSystem.uid() != 0) {
            ExtSystem.printDebugLog("@Touch non-root, forced java mode");
            notUseRaClick = true;
            touchMode = "java";
            return;
        }

        // Explicit uinput mode
        if ("uinput".equals(mode)) {
            try {
                ra = new RootAutomator(true);
                notUseRaClick = false;
                touchMode = ra.isUinputMode() ? "uinput" : "kernel";
                ExtSystem.printDebugLog("@Touch init uinput success, protocol=" + (ra.getProtocol() == 2 ? "B" : "A"));
            } catch (Throwable e) {
                ExtSystem.printDebugLog("@Touch uinput failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                ra = null;
                notUseRaClick = true;
                touchMode = "java";
            }
            ExtSystem.printDebugLog("@Touch final mode: " + touchMode);
            return;
        }

        // "kernel" or "auto" — prefer kernel (direct /dev/input/eventX write)
        // Kernel mode writes to the real touch device fd, events are 100% indistinguishable
        // from real hardware touches (same device name, bus, sysfs path).
        try {
            ra = new RootAutomator(false);
            notUseRaClick = false;
            touchMode = "kernel";
            ExtSystem.printDebugLog("@Touch kernel mode success, protocol=" + (ra.getProtocol() == 2 ? "B" : "A"));
        } catch (Throwable e) {
            ExtSystem.printDebugLog("@Touch kernel init failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            ra = null;
            if ("auto".equals(mode)) {
                // Fallback: try uinput clone (clones real device identity)
                try {
                    ra = new RootAutomator(true);
                    notUseRaClick = false;
                    touchMode = ra.isUinputMode() ? "uinput" : "kernel";
                    ExtSystem.printDebugLog("@Touch fallback to uinput clone success");
                } catch (Throwable e2) {
                    ExtSystem.printDebugLog("@Touch uinput fallback also failed: " + e2.getClass().getSimpleName() + ": " + e2.getMessage());
                    ra = null;
                    notUseRaClick = true;
                    touchMode = "java";
                }
            } else {
                notUseRaClick = true;
                touchMode = "java";
            }
        }
        ExtSystem.printDebugLog("@Touch final mode: " + touchMode + " notUseRaClick=" + notUseRaClick);
    }

    private void ensureTouch() {
        if (ra == null && "auto".equals(touchMode)) initTouchChannel("auto");
    }

    public String exportLocalHandle(String uri, Map<String,String> params) throws Exception {
        return this.handle(uri, null, params, null);
    }

    static ExecutorService pool = Executors.newCachedThreadPool();
    static String startDate = DateUtil.INSTANCE.getCommonDateYear();

    private static void setArgV0(String text) {
        try {
            java.lang.reflect.Method setter = Process.class.getMethod("setArgV0", String.class);
            setter.invoke(Process.class, text);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Main request handler
    // ═══════════════════════════════════════════════════════════════
    @SuppressLint("DefaultLocale")
    private String handle(String uri,
                          Map<String, String> headers, Map<String, String> params,
                          Map<String, String> files) throws IllegalAccessException, FormatException {
        try {
            return dispatch(uri, params, files);
        } catch (Exception e) {
            return jsonErr(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressLint("DefaultLocale")
    private String dispatch(String uri, Map<String, String> params,
                            Map<String, String> files) throws Exception {

        // ── System / Debug ──────────────────────────────────────
        if (uri.equals("/ping"))        return jsonOk("pong");
        if (uri.equals("/version"))     return jsonOk(BuildConfig.VERSION_CODE);
        if (uri.equals("/pid"))         return jsonOk(Process.myPid());
        if (uri.equals("/uid"))         return jsonOk(Process.myUid());
        if (uri.equals("/start_time"))  return jsonOk(startDate);
        if (uri.equals("/imei"))        return jsonOk(DeviceCode.getDeviceCode());

        if (uri.equals("/register")) {
            ExtSystem.printDebugLog("进入注册接口!");
            if (FileUtil.writeText("/sdcard/Yyds.Auto/.a", params.get("code"))) {
            return jsonErr("register not supported");
            }
            return jsonErr("register failed");
        }
        if (uri.equals("/exit")) {
            return jsonOk(ExtSystem.shell("killall " + mainProcessName) + ExtSystem.shell("killall " + keepProcessName));
        }
        if (uri.equals("/reboot")) {
            AppProcess.rebootAutoEngine(params.getOrDefault("reason", "reboot api"));
            return jsonOk("rebooting");
        }
        if (uri.equals("/sleep")) {
            String ms = params.get("ms");
            if (ms != null) {
                SystemClock.sleep(Long.parseLong(ms));
                return jsonOk(Long.parseLong(ms));
            }
            return jsonOk(0);
        }

        // ── Touch mode ──────────────────────────────────────────
        if (uri.equals("/set_touch_mode")) {
            String mode = params.getOrDefault("mode", "");
            // Legacy compat: enable=true → java, enable=false → auto
            if (mode.isEmpty()) {
                boolean disableRa = Boolean.parseBoolean(params.getOrDefault("enable", "false"));
                mode = disableRa ? "java" : "auto";
            }
            initTouchChannel(mode);
            JSONObject d = new JSONObject();
            d.put("mode", touchMode);
            d.put("use_ra", !notUseRaClick);
            return jsonOk(d);
        }
        if (uri.equals("/get_touch_mode")) {
            JSONObject d = new JSONObject();
            d.put("mode", touchMode);
            d.put("use_ra", !notUseRaClick);
            if (ra != null) {
                d.put("protocol", ra.getProtocol() == 2 ? "B" : "A");
                d.put("uinput", ra.isUinputMode());
            }
            try {
                String devName = InputUtil.getDeviceName();
                if (devName != null && !devName.isEmpty()) d.put("device_name", devName);
            } catch (Throwable ignored) {}
            return jsonOk(d);
        }

        // ── Toast / Notification ────────────────────────────────
        if (uri.equals("/toast")) {
            String toastContent = Objects.requireNonNull(params.getOrDefault("content", ""))
                    .replace("\"", "").replace("'", "");
            boolean ok = ExtSystem.shell(String.format(
                    "content call --uri content://yyds.boot --method toast --arg \"%s\"", toastContent)).contains("Bundle");
            return jsonOk(ok);
        }
        if (uri.equals("/notify_cancel")) {
            NotificationWrapper.cancel(systemContext);
            return jsonOk(true);
        }

        // ── Network / HTTP ──────────────────────────────────────
        if (uri.equals("/http_get")) {
            return jsonOk(HttpUtil.INSTANCE.getUrlString(params.get("url")));
        }
        if (uri.equals("/is_net_online")) {
            return jsonOk(NetUtils.isNetOnline());
        }

        // ── Input: YY输入法 ─────────────────────────────────────
        if (uri.equals("/xinput_text")) {
            boolean ok = YyInputService.Companion.sendInputText(
                    Objects.requireNonNull(params.getOrDefault("text", "")));
            return jsonOk(ok);
        }
        if (uri.equals("/xinput_clear")) {
            return jsonOk(YyInputService.Companion.sendClearText());
        }
        if (uri.equals("/set_yy_input")) {
            boolean enable = Objects.equals(params.getOrDefault("enable", "true"), "true");
            if (enable) YyInput.INSTANCE.enableYyInputMethod();
            else YyInput.INSTANCE.resetInputMethod();
            return jsonOk(enable);
        }
        if (uri.equals("/inject_text")) {
            int count = ClipboardManagerWrapper.inputManager.injectText(
                    params.getOrDefault("text", ""));
            return jsonOk(count);
        }

        // ── Clipboard ───────────────────────────────────────────
        if (uri.equals("/set_clipboard")) {
            String text = params.getOrDefault("text", "");
            ClipboardManagerWrapper.setClipboardText(text);
            YyInputService.Companion.setClipboardText(text);
            return jsonOk(true);
        }
        if (uri.equals("/get_clipboard")) {
            String ret = "";
            boolean retry = true;
            if (Build.VERSION.SDK_INT <= 28) {
                try { return jsonOk(ClipboardManagerWrapper.getClipboardText()); }
                catch (ClipboardManagerWrapper.ErrorClipBoardException ex) { retry = false; }
            }
            if (retry) ret = YyInputService.Companion.invokeGetClipBoardText();
            return jsonOk(ret);
        }

        // ── Shell ───────────────────────────────────────────────
        if (uri.equals("/shell")) {
            return jsonOk(ExtSystem.shell(params.get("cmd")));
        }

        isHasCheck = true;

        // ── Touch / Click / Swipe ───────────────────────────────
        if (uri.equals("/touch")) {
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            int time = Integer.parseInt(params.getOrDefault("time", "1"));
            long interval = Integer.parseInt(params.getOrDefault("interval", "0"));
            ensureTouch();
            if (notUseRaClick) {
                boolean r = false;
                while (time-- > 0) {
                    r = TouchAction.click(x, y);
                    if (interval > 0) SystemClock.sleep(interval);
                }
                JSONObject d = new JSONObject();
                d.put("result", r);
                d.put("mode", "java");
                return jsonOk(d);
            } else {
                while (time-- > 0) {
                    ra.tap(x, y, 0);
                    if (interval > 0) SystemClock.sleep(interval);
                }
                JSONObject d = new JSONObject();
                d.put("result", true);
                d.put("mode", touchMode);
                return jsonOk(d);
            }
        }
        if (uri.equals("/touch_down")) {
            int x = Integer.parseInt(Objects.requireNonNull(params.get("x")));
            int y = Integer.parseInt(Objects.requireNonNull(params.get("y")));
            ensureTouch();
            if (notUseRaClick) {
                return jsonOk(TouchAction.touchController.touchDown(x, y));
            } else {
                ra.touchDown(x, y);
                return jsonOk(true);
            }
        }
        if (uri.equals("/touch_up")) {
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            if (notUseRaClick) {
                return jsonOk(TouchAction.touchController.touchUp(x, y));
            } else {
                ra.touchUp();
                return jsonOk(true);
            }
        }
        if (uri.equals("/touch_move")) {
            int x = Integer.parseInt(params.get("x"));
            int y = Integer.parseInt(params.get("y"));
            if (notUseRaClick) {
                return jsonOk(TouchAction.touchController.touchMove(x, y));
            } else {
                ra.touchMove(x, y);
                return jsonOk(true);
            }
        }
        if (uri.equals("/swipe")) {
            int x1 = Integer.parseInt(params.get("x1"));
            int y1 = Integer.parseInt(params.get("y1"));
            int x2 = Integer.parseInt(params.get("x2"));
            int y2 = Integer.parseInt(params.get("y2"));
            int durationMs = Integer.parseInt(params.get("duration"));
            boolean isRandom = params.containsKey("is_random") && Boolean.parseBoolean(params.get("is_random"));
            ensureTouch();
            if (notUseRaClick) {
                return jsonOk(TouchAction.swipe(x1, y1, x2, y2, durationMs, isRandom));
            } else {
                ra.swipe(x1, y1, x2, y2, durationMs);
                return jsonOk(true);
            }
        }
        if (uri.equals("/input_device")) {
            return jsonOk(InputUtil.getDevice());
        }
        if (uri.equals("/get_input_id")) {
            return jsonOk(InputUtil.getDeviceId());
        }

        // ── Keyboard ────────────────────────────────────────────
        if (uri.equals("/key_confirm")) {
            return jsonOk(ClipboardManagerWrapper.injectKeyConfirm());
        }
        if (uri.equals("/key_code")) {
            if (params.containsKey("code")) {
                return jsonOk(ClipboardManagerWrapper.pressReleaseKeycodeSync(
                        Integer.parseInt(Objects.requireNonNull(params.get("code")))));
            }
            return jsonErr("missing param: code");
        }

        // ── Screen ──────────────────────────────────────────────
        if (uri.equals("/screen_size")) {
            Point point = MyDevice.INSTANCE.getScreenSizeCurrent();
            JSONObject d = new JSONObject();
            d.put("width", point.x);
            d.put("height", point.y);
            return jsonOk(d);
        }
        if (uri.equals("/screenshot")) {
            String path = params.getOrDefault("path", "/sdcard/screenshot.png");
            ScreenCapture.writeTo(path);
            return jsonOk(path);
        }
        if (uri.equals("/image_area")) {
            String path = params.getOrDefault("path", "/sdcard/screenshot.png");
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            ImageHelper.areaImage(ImageHelper.bitmap_ensure_ARGB8888(bitmap), 300, 400, 300, 200);
            return jsonOk(true);
        }

        // ── Foreground / App ────────────────────────────────────
        if (uri.equals("/foreground")) {
            return jsonOk(Foreground.INSTANCE.getCurrentForegroundString());
        }
        if (uri.equals("/foreground_activity")) {
            return jsonOk(Foreground.INSTANCE.getCurrentForegroundFaster());
        }
        if (uri.equals("/foreground_package")) {
            return jsonOk(Foreground.INSTANCE.getForegroundPackage());
        }
        if (uri.equals("/is_app_running")) {
            return jsonOk(Foreground.INSTANCE.isAppRunning(Objects.requireNonNull(params.get("pkg"))));
        }
        if (uri.equals("/bring_to_top")) {
            return jsonOk(Foreground.INSTANCE.moveToTop(Objects.requireNonNull(params.get("pkg"))));
        }
        if (uri.equals("/dump_windows")) {
            return jsonOk(Foreground.INSTANCE.dumpPopupWindow(params.getOrDefault("key", "PopupWindow")));
        }
        if (uri.equals("/open_app")) {
            String packageName = params.get("pkg");
            ExportUtil.INSTANCE.monkeyStartApp(packageName);
            return jsonOk(packageName);
        }
        if (uri.equals("/open_url")) {
            String url = params.get("url");
            return jsonOk(ExtSystem.shell("am start -a android.intent.action.VIEW -d " + url));
        }

        // ── UIA (UI Automator) ──────────────────────────────────
        if (uri.equals("/uia_dump")) {
            final String path = params.getOrDefault("path", "/data/local/tmp/dump.xml");
            final boolean isAllWindow = Boolean.parseBoolean(params.getOrDefault("all_window", "false"));
            HierarchyDumper.INSTANCE.dump(path, isAllWindow);
            return jsonOk(path);
        }
        if (uri.equals("/uia_match")) {
            int matchCount = Integer.parseInt(params.getOrDefault("limit", "999"));
            boolean matchFromCache = Boolean.parseBoolean(params.getOrDefault("match_from_cache", "false"));
            boolean matchAllWindow = Boolean.parseBoolean(params.getOrDefault("all_window", "false"));
            params.remove("match_from_cache");
            params.remove("all_window");
            params.remove("limit");
            String raw = HierarchyParser.INSTANCE.findRectApi(params, matchCount, matchFromCache, matchAllWindow);
            return jsonOk(raw);
        }
        if (uri.equals("/uia_relation")) {
            long ms = Long.parseLong(params.getOrDefault("dump_time_ms", "0"));
            int index = Integer.parseInt(params.getOrDefault("hashcode", "999"));
            String type = params.getOrDefault("type", "");
            return jsonOk(HierarchyParser.INSTANCE.fetchRelation(ms, index, type));
        }
        if (uri.equals("/media_scan")) {
            return jsonOk(YyInputService.Companion.mediaScanFile(params.get("path")));
        }

        // ── OCR ─────────────────────────────────────────────────
        if (uri.equals("/screen_ocr")) {
            Bitmap bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
            if (ppOcrNcnn == null) {
                ppOcrNcnn = new PpOcrNcnn();
                boolean ocr_init = false;
                if (isNotApkInstallMode) {
                    ocr_init = ppOcrNcnn.init();
                } else {
                    ocr_init = ppOcrNcnn.init(appContext.getAssets(), null);
                }
                ExtSystem.printDebugLog("load ocr model states:" + ocr_init);
                if (!ocr_init) ppOcrNcnn = null;
            }
            boolean useGpu = Boolean.parseBoolean(params.getOrDefault("use_gpu", "false"));
            int threshold = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("threshold", "-1")));
            String ocrRet = ViewObject.getOcrString(
                    ppOcrNcnn.detect(bitmap, useGpu, threshold));
            ExtSystem.printDebugLog(ocrRet);
            ImageHelper.jvmBitmapRecycle(bitmap);
            return jsonOk(ocrRet);
        }
        if (uri.equals("/image_ocr")) {
            String path = params.get("path");
            if (path == null) return jsonErr("missing param: path");
            if (!new File(path).exists()) return jsonErr("文件不存在: " + path);
            Bitmap bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(path));
            if (ppOcrNcnn == null) {
                ppOcrNcnn = new PpOcrNcnn();
                boolean ocr_init = false;
                if (isNotApkInstallMode) {
                    ocr_init = ppOcrNcnn.init();
                } else {
                    ocr_init = ppOcrNcnn.init(appContext.getAssets(), null);
                }
                ExtSystem.printDebugLog("load ocr model states:" + ocr_init);
                if (!ocr_init) ppOcrNcnn = null;
            }
            boolean useGpu = Boolean.parseBoolean(params.getOrDefault("use_gpu", "false"));
            int threshold = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("threshold", "-1")));
            String ocrRet = ViewObject.getOcrString(
                    ppOcrNcnn.detect(bitmap, useGpu, threshold));
            ExtSystem.printDebugLog(ocrRet);
            ImageHelper.jvmBitmapRecycle(bitmap);
            return jsonOk(ocrRet);
        }

        // ── SoM (Set-of-Mark) 标注截图 ───────────────────────────
        if (uri.equals("/som_screenshot")) {
            try {
                // 1. 截图
                Bitmap screenshot = ImageHelper.bitmap_ensure_ARGB8888(
                        Objects.requireNonNull(ScreenCapture.getBitmap()));

                // 2. OCR 检测
                PpOcrNcnn.Objs[] ocrResults = null;
                if (ppOcrNcnn == null) {
                    ppOcrNcnn = new PpOcrNcnn();
                    boolean ocr_init = isNotApkInstallMode
                            ? ppOcrNcnn.init()
                            : ppOcrNcnn.init(appContext.getAssets(), null);
                    ExtSystem.printDebugLog("SoM: load ocr model:" + ocr_init);
                    if (!ocr_init) ppOcrNcnn = null;
                }
                if (ppOcrNcnn != null) {
                    ocrResults = ppOcrNcnn.detect(screenshot, false, -1);
                }

                // 3. UI 控件树 XML
                String uiXml = null;
                boolean useUi = Boolean.parseBoolean(params.getOrDefault("use_ui", "true"));
                if (useUi) {
                    try {
                        String dumpPath = "/data/local/tmp/som_dump.xml";
                        HierarchyDumper.INSTANCE.dump(dumpPath, false);
                        File dumpFile = new File(dumpPath);
                        if (dumpFile.exists()) {
                            uiXml = FileUtil.getText(dumpPath);
                        }
                    } catch (Exception uiEx) {
                        ExtSystem.printDebugLog("SoM: UI dump failed (skipping): " + uiEx.getMessage());
                    }
                }

                // 4. 缩小截图以避免 OOM（SoM 标注不需要全分辨率）
                int maxDim = 1280;
                int sw = screenshot.getWidth(), sh = screenshot.getHeight();
                float scaleRatio = 1.0f;
                Bitmap somBitmap = screenshot;
                if (Math.max(sw, sh) > maxDim) {
                    scaleRatio = (float) maxDim / Math.max(sw, sh);
                    int nw = Math.round(sw * scaleRatio);
                    int nh = Math.round(sh * scaleRatio);
                    somBitmap = Bitmap.createScaledBitmap(screenshot, nw, nh, true);
                }

                // 5. SoM 标注（仅生成marks，跳过图片绘制避免native crash）
                int quality = Integer.parseInt(params.getOrDefault("quality", "80"));
                image.SomAnnotator.SomResult result =
                        image.SomAnnotator.annotateMarksOnly(somBitmap, ocrResults, uiXml, quality);
                if (somBitmap != screenshot) {
                    somBitmap.recycle();
                }
                ImageHelper.jvmBitmapRecycle(screenshot);

                // 6. 将标记坐标映射回原始分辨率
                if (scaleRatio != 1.0f) {
                    float inv = 1.0f / scaleRatio;
                    for (image.SomAnnotator.Mark m : result.marks) {
                        m.cx = Math.round(m.cx * inv);
                        m.cy = Math.round(m.cy * inv);
                        m.x = Math.round(m.x * inv);
                        m.y = Math.round(m.y * inv);
                        m.w = Math.round(m.w * inv);
                        m.h = Math.round(m.h * inv);
                    }
                }

                // 5. 保存标注图到临时文件
                String imgPath = "/data/local/tmp/som_annotated.jpg";
                java.io.FileOutputStream fos = new java.io.FileOutputStream(imgPath);
                fos.write(result.annotatedJpeg);
                fos.close();

                // 6. 返回 JSON: { marks: [...], image_path: "..." }
                JSONObject ret = result.toJson();
                ret.put("image_path", imgPath);
                ret.put("mark_count", result.marks.size());
                return jsonOk(ret.toString());
            } catch (Exception e) {
                ExtSystem.printDebugError(e);
                return jsonErr("SoM annotate failed: " + e.getMessage());
            }
        }

        // ── YOLO 预加载 ──────────────────────────────────────────
        if (uri.equals("/yolo_init")) {
            String msg = ensureYoloLoaded();
            if (msg != null) return jsonErr(msg);
            return jsonOk("YOLO模型已就绪");
        }

        // ── YOLO ────────────────────────────────────────────────
        if (uri.equals("/yolo_detect")) {
            String initErr = ensureYoloLoaded();
            if (initErr != null) return jsonErr(initErr);

            // 可选参数：运行时调整阈值和输入尺寸（安全解析）
            try {
                if (params.containsKey("threshold")) {
                    float prob = Float.parseFloat(params.get("threshold"));
                    float nms = Float.parseFloat(params.getOrDefault("nms_threshold", "0.45"));
                    yoloNcnn.setThreshold(prob, nms);
                }
                if (params.containsKey("target_size")) {
                    yoloNcnn.setTargetSize(Integer.parseInt(params.get("target_size")));
                }
            } catch (NumberFormatException e) {
                return jsonErr("参数格式错误: threshold/nms_threshold 需为小数, target_size 需为整数");
            }

            Bitmap bitmap = null;
            try {
                if (params.get("image") != null) {
                    bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image")));
                } else {
                    bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
                }
                ImageHelper.autoAreaBitmap(bitmap, params);
                boolean useGpu = Boolean.parseBoolean(params.getOrDefault("use_gpu", "false"));
                ExtSystem.printInfo("Detecting...");
                YoloNcnn.Obj[] results = yoloNcnn.Detect(bitmap, useGpu);
                String ret = ViewObject.getRetString(results);

                // 可选：按标签过滤
                if (params.containsKey("labels") && results != null) {
                    String[] filterLabels = params.get("labels").split(",");
                    StringBuilder filtered = new StringBuilder();
                    for (YoloNcnn.Obj obj : results) {
                        for (String fl : filterLabels) {
                            if (obj.label.trim().equals(fl.trim())) {
                                if (filtered.length() > 0) filtered.append("|");
                                filtered.append(obj.x).append(",").append(obj.y).append(",")
                                        .append(obj.w).append(",").append(obj.h).append(",")
                                        .append(obj.label).append(",").append(obj.prob);
                                break;
                            }
                        }
                    }
                    ret = filtered.toString();
                }
                return jsonOk(ret);
            } catch (Exception e) {
                ExtSystem.printDebugError(e);
                return jsonErr("YOLO检测异常: " + e.getMessage());
            } finally {
                if (bitmap != null) ImageHelper.jvmBitmapRecycle(bitmap);
            }
        }
        if (uri.equals("/set_yolo_model")) {
            String dir = params.getOrDefault("dir", "");
            yoloNcnn = null;
            if (!dir.isEmpty()) {
                yoloNcnn = new YoloNcnn();
                File modelDir = new File(dir);
                File[] paramFiles = modelDir.listFiles((d, n) -> n.endsWith(".param"));
                File[] binFiles = modelDir.listFiles((d, n) -> n.endsWith(".bin"));
                if (paramFiles != null && paramFiles.length > 0 && binFiles != null && binFiles.length > 0) {
                    boolean ok = yoloNcnn.init(binFiles[0].getAbsolutePath(), paramFiles[0].getAbsolutePath());
                    if (!ok) {
                        yoloNcnn = null;
                        return jsonErr("模型加载失败: " + dir);
                    }
                    // classes.txt 由 C++ 层自动加载
                    return jsonOk("模型加载成功: " + paramFiles[0].getName());
                } else {
                    // 兼容旧的 bin_file_path + param_file_path 参数
                    String binPath = params.getOrDefault("bin_file_path", "");
                    String paramPath = params.getOrDefault("param_file_path", "");
                    if (!binPath.isEmpty() && !paramPath.isEmpty()) {
                        boolean ok = yoloNcnn.init(binPath, paramPath);
                        if (!ok) {
                            yoloNcnn = null;
                            return jsonErr("模型加载失败");
                        }
                        return jsonOk("模型加载成功");
                    }
                    yoloNcnn = null;
                    return jsonErr("目录中未找到 .param/.bin 模型文件: " + dir);
                }
            }
            return jsonOk("YOLO模型已重置，下次检测时将自动加载默认模型");
        }
        if (uri.equals("/yolo_model_info")) {
            JSONObject info = new JSONObject();
            info.put("loaded", yoloNcnn != null);
            info.put("user_model_dir", AiConfig.USER_YOLO_DIR);
            info.put("builtin_model_dir", AiConfig.BUILTIN_YOLO_DIR);
            File userDir = new File(AiConfig.USER_YOLO_DIR);
            info.put("has_user_model", userDir.exists() && userDir.listFiles((d, n) -> n.endsWith(".param")) != null && userDir.listFiles((d, n) -> n.endsWith(".param")).length > 0);
            String builtinParam = AiConfig.BUILTIN_YOLO_DIR + "/" + AiConfig.BUILTIN_YOLO_PARAM;
            info.put("has_builtin_model", new File(builtinParam).exists());
            return jsonOk(info.toString());
        }

        // ── Color ───────────────────────────────────────────────
        if (uri.equals("/get_color")) {
            Bitmap bitmap;
            if (params.get("image") != null) {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image")));
            } else {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
            }
            StringBuilder result = new StringBuilder();
            if (params.containsKey("points")) {
                String[] points = params.get("points").split(" ");
                for (String point: points) {
                    String[] xy = point.split(",");
                    int x = Integer.parseInt(xy[0]);
                    int y = Integer.parseInt(xy[1]);
                    int pixel = bitmap.getPixel(x, y);
                    int redValue = Color.red(pixel);
                    int greenValue = Color.green(pixel);
                    int blueValue = Color.blue(pixel);
                    if (result.toString().length() != 0) result.append(" ");
                    result.append(String.format("%d,%d,%d", redValue, greenValue, blueValue));
                }
            } else {
                int x = Integer.parseInt(Objects.requireNonNull(params.get("x")));
                int y = Integer.parseInt(Objects.requireNonNull(params.get("y")));
                int pixel = bitmap.getPixel(x, y);
                int redValue = Color.red(pixel);
                int greenValue = Color.green(pixel);
                int blueValue = Color.blue(pixel);
                result.append(String.format("%d,%d,%d", redValue, greenValue, blueValue));
            }
            ImageHelper.jvmBitmapRecycle(bitmap);
            return jsonOk(result.toString());
        }
        if (uri.equals("/find_color")) {
            Bitmap bitmap;
            if (params.get("image") != null) {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image")));
            } else {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
            }
            if (bitmap == null) {
                throw new NullPointerException("获取目标图像失败:" + params.get("image"));
            }
            int threshold = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("prob", "3")));
            int maxMatchCount = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("max_counts", "3")));
            int stepX = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("step_x", "3")));
            int stepY = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("step_y", "3")));
            String color = ColorHelper.rgbDecString2HexString(Objects.requireNonNull(params.get("rgb")));
            String[] biasPointAndColor = Objects.requireNonNull(params.get("points")).split("\n");
            ExtSystem.printDebugLog("字符串数组", Arrays.toString(biasPointAndColor));
            String ret = ImageHelper.findMultiColor(params, bitmap, threshold, maxMatchCount, color, stepX, stepY, biasPointAndColor);
            ImageHelper.jvmBitmapRecycle(bitmap);
            return jsonOk(ret);
        }

        // ── Image matching ──────────────────────────────────────
        if (uri.equals("/find_image")) {
            if (!params.containsKey("templates")) {
                return jsonErr("need templates");
            }
            String dsts = params.get("templates");
            String[] templates = dsts.split(";");
            if (templates.length == 0) {
                return jsonErr("size of templates == 0");
            }
            Bitmap bitmap;
            if (params.get("image") != null) {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image")));
            } else {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
            }
            int threshold = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("threshold", "-1")));
            String ret = ImageHelper.findImageInImage(params, bitmap, threshold, templates);
            ImageHelper.jvmBitmapRecycle(bitmap);
            return jsonOk(ret);
        }
        if (uri.equals("/match_image")) {
            Bitmap bitmap;
            if (params.get("image") != null) {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image")));
            } else {
                bitmap = ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap()));
            }
            if (bitmap == null) {
                throw new NullPointerException("获取目标图像失败:" + params.get("image"));
            }
            Bitmap templateBitmap = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("template")));
            int threshold = Integer.parseInt(Objects.requireNonNull(params.getOrDefault("threshold", "0")));
            double prob = Double.parseDouble(Objects.requireNonNull(params.getOrDefault("prob", "0.8")));
            String ret = ImageHelper.matchImages(params, bitmap, templateBitmap, threshold, prob);
            ImageHelper.jvmBitmapRecycle(bitmap, templateBitmap);
            return jsonOk(ret);
        }
        if (uri.equals("/image_similarity")) {
            Bitmap bitmap1 = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image1")));
            Bitmap bitmap2 = ImageHelper.bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(params.get("image2")));
            if (bitmap1 == null) {
                throw new NullPointerException("获取目标图像失败:" + params.get("image1"));
            }
            if (bitmap2 == null) {
                throw new NullPointerException("获取目标图像失败:" + params.get("image2"));
            }
            String ret = Double.toString(ImageHelper.imageSimilarity(bitmap1, bitmap2, 0));
            ImageHelper.jvmBitmapRecycle(bitmap1, bitmap2);
            return jsonOk(ret);
        }

        // ── Language ────────────────────────────────────────────
        if (uri.equals("/update_language")) {
            String code = params.getOrDefault("code", "");
            ExtSystem.printInfo("update language:" + code);
            return jsonOk(code);
        }

        // ── Stop app ────────────────────────────────────────────
        if (uri.equals("/stop_app")) {
            return jsonOk(ExtSystem.shell("am force-stop " + params.get("pkg")));
        }

        // ── App data backup / recovery ──────────────────────────
        if (uri.equals("/backup_app_data")) {
            String app = params.get("package");
            String backupPath = params.get("path");
            if (app != null && backupPath != null) {
                return jsonOk(AppDataHelper.INSTANCE.appBackupSdcard(backupPath, app));
            } else {
                throw new IllegalArgumentException("app与路径参数缺失");
            }
        }
        if (uri.equals("/recovery_app_data")) {
            String app = params.get("package");
            String backupPath = params.get("path");
            if (app != null && backupPath != null) {
                return jsonOk(AppDataHelper.INSTANCE.appRecoverySdcard(backupPath, app));
            } else {
                throw new IllegalArgumentException("app与路径参数缺失");
            }
        }
        if (uri.equals("/backup_apk")) {
            String pkg = params.get("pkg");
            String path = params.get("path");
            try {
                Context ctx = appContext != null ? appContext : systemContext;
                String apkPath;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    apkPath = ctx.getPackageManager().getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).sourceDir;
                } else {
                    //noinspection deprecation
                    apkPath = ctx.getPackageManager().getApplicationInfo(pkg, 0).sourceDir;
                }
                ExtSystem.shell("cp " + apkPath + " " + path);
                return jsonOk(true);
            } catch (PackageManager.NameNotFoundException e) {
                return jsonErr("package not found: " + pkg);
            }
        }
        if (uri.equals("/install_apk")) {
            String path = params.get("path");
            return jsonOk(ExtSystem.shell("pm install -r " + path));
        }

        // ── Remote login ────────────────────────────────────────
        if (uri.equals("/remote_login")) {
            String ip = params.getOrDefault("ip", "");
            String port = params.getOrDefault("port", "8080");
            String code = params.getOrDefault("code", "");
            String url = "http://" + ip + ":" + port + "/auto/remote_login?code=" + code;
            return jsonOk(HttpUtil.INSTANCE.getUrlString(url));
        }
        if (uri.equals("/remote_logoff")) {
            return jsonOk("ok");
        }

        // ── File operations ─────────────────────────────────────
        if (uri.equals("/post_file")) {
            if (files != null && files.containsKey("file")) {
                String src = files.get("file");
                String dest = params.getOrDefault("dest", "/sdcard/");
                ExtSystem.shell("cp " + src + " " + dest);
                return jsonOk(dest);
            }
            return jsonErr("missing file");
        }

        // ── HTTP API server ─────────────────────────────────────
        if (uri.equals("/open_http_api")) {
            if (ExportHandle.isHttpServerRunning()) {
                return "running";
            } else {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ExportHandle.httpServerStart();
                    }
                });
                return "starting";
            }
        }

        // ── Device info ─────────────────────────────────────────
        if (uri.equals("/device_code")) {
            return jsonOk(DeviceCode.getDeviceCode());
        }
        if (uri.equals("/device_model")) {
            return jsonOk(Build.MODEL);
        }

        // ── Priority ────────────────────────────────────────────
        if (uri.equals("/priority")) {
            int pid = Process.myPid();
            Process.setThreadPriority(pid, Process.THREAD_PRIORITY_FOREGROUND);
            return jsonOk(true);
        }

        return jsonErr("unknown endpoint: " + uri);
    }

    public static float defaultCloseProd = 0.7F;

    public static void clickObject(YoloNcnn.Obj... objs) {
        if (objs == null || objs.length == 0) return;
        YoloNcnn.Obj obj = objs[0];
        int cx = (int) (obj.x + obj.w / 2);
        int cy = (int) (obj.y + obj.h / 2);
        TouchAction.click(cx, cy);
    }

    public static void mainEngine() {
        try {
            YoloNcnn.Obj[] targets = yoloNcnn.Detect(ImageHelper.bitmap_ensure_ARGB8888(Objects.requireNonNull(ScreenCapture.getBitmap())), true);
            if (targets.length == 0) return;
            System.out.println("AutoEngine Scan:" + Arrays.deepToString(targets));
            Log.w(TAG, Arrays.deepToString(targets));
            YoloNcnn.Obj target1 = targets[0];
            if (targets.length == 1) {
                if (target1.label.equals("close") || target1.label.equals("jump")) {
                    clickObject(target1);
                }
                if (target1.label.equals("interact_select")) {
                    if (target1.prob > defaultCloseProd) {
                        clickObject(target1);
                    }
                }
            }
        } catch (Exception e) {
            ExtSystem.printDebugError(e);
        }
    }

    public static void autoEngine() throws InterruptedException {
        System.out.println("- 启动自动化引擎");
        Thread scanThread = new Thread(() -> {
            while (true) {
                SystemClock.sleep(ThreadLocalRandom.current().nextInt(1500, 5000));
                mainEngine();
            }
        });
        scanThread.start();
        scanThread.join();
    }

    private static boolean initTip = true;
    private static long lastApkModify = 0;

    public static boolean hasApkUpdate() throws Exception {
        if (codePath == null) return false;
        File f = new File(codePath);
        if (!f.exists()) return false;
        long mod = f.lastModified();
        if (lastApkModify == 0) { lastApkModify = mod; return false; }
        if (mod != lastApkModify) { lastApkModify = mod; return true; }
        return false;
    }

    public static void initAppContext() throws Exception {
        if (systemContext == null) {
            Context contextInstace = ContextUtil.getSystemContext();
            systemContext = contextInstace;
        }
        if (!isNotApkInstallMode) {
            appContext = systemContext.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        }
    }

    @SuppressLint("DefaultLocale")
    public static void keepMain() throws Exception {
        setArgV0(keepProcessName);
        initAppContext();
        int timeoutSecond = 30;
        //noinspection deprecation
        Looper.prepareMainLooper();
        Runnable keepAutoRunnable = new Runnable() {
            @Override
            public void run() {
                Future<Object> wait = pool.submit(new Callable<Object>() {
                    @Override
                    public Object call() throws Exception {
                        if (hasApkUpdate()) return null;
                        Future<Boolean> isPyEngineRunning = pool.submit(new Callable<Boolean>() {
                            @Override
                            public Boolean call() throws Exception {
                                String ret = ExtSystem.shell("pidof yyds.py && echo yyds.pyOk").replace("\n", "");
                                ExtSystem.printInfo("Keep pidof yyds.py:", ret);
                                if (ret.contains("yyds.pyOk")) return true;
                                for (int i = 0; i < 2; i++) {
                                    try {
                                        ret = HttpUtil.INSTANCE.getUrlString("http://127.0.0.1:" + PyEngine.enginePort);
                                        if (ret != null && ret.contains("engine")) return true;
                                    } catch (Throwable e) {
                                        ExtSystem.printInfo("连接脚本引擎异常:" + i);
                                        SystemClock.sleep(1000);
                                    }
                                }
                                return false;
                            }
                        });
                        try {
                            Boolean isEngineRunningCheck = isPyEngineRunning.get(20, TimeUnit.SECONDS);
                            ExtSystem.printDebugLog("isEngineRunningCheck:" + isEngineRunningCheck);
                            if (!isEngineRunningCheck) {
                                AppProcess.rebootPyEngine("连接脚本引擎进程, 执行引擎重激活!" + isEngineRunningCheck + "\n");
                            }
                        } catch (Exception e) {
                            AppProcess.rebootPyEngine("连接脚本引擎异常, 执行引擎重激活！");
                        }
                        ExtSystem.printInfo("Keeping " + codePath);
                        return codePath;
                    }
                });
                try {
                    Object res = wait.get(timeoutSecond, TimeUnit.SECONDS);
                    if (res == null) {
                        initAppContext();
                        ExtSystem.printDebugLog("APK 更新启动!");
                        AppProcess.rebootPyEngine("APK更新");
                        AppProcess.rebootAutoEngine("APK更新");
                    }
                } catch (Exception e) {
                    if(ShellUtils.fastCmdResult("pidof yyds.auto")) {
                        ExtSystem.printInfo("[检查yyds.auto遇到异常]进程存在但获取错误"+ e + "\t" + e.getMessage());
                        ExtSystem.printDebugError(e);
                    } else {
                        AppProcess.rebootAutoEngine("进程不存在Pid:"+ Os.getpid() + "#Keep reboot error:" + e.getMessage() + "\n" + Log.getStackTraceString(e));
                    }
                } finally {
                    new Handler(Looper.getMainLooper()).postDelayed(this, 10_000);
                }
            }
        };
        Runnable keepPyRunnable = new Runnable() {
            @Override
            public void run() {
                Future<Boolean> isPyEngineRunning = pool.submit(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        String ret = ExtSystem.shell("pidof yyds.py && echo yyds.pyOk").replace("\n", "");
                        ExtSystem.printInfo("Keep pidof yyds.py:", ret);
                        if (ret.contains("yyds.pyOk")) return true;
                        for (int i = 0; i < 2; i++) {
                            try {
                                ret = HttpUtil.INSTANCE.getUrlString("http://127.0.0.1:" + PyEngine.enginePort);
                                if (ret != null && ret.contains("engine")) return true;
                            } catch (Throwable e) {
                                ExtSystem.printInfo("连接脚本引擎异常:" + i);
                                SystemClock.sleep(1000);
                            }
                        }
                        return false;
                    }
                });
                try {
                    Boolean isPyEngineRunningRet  = isPyEngineRunning.get(timeoutSecond, TimeUnit.SECONDS);
                    ExtSystem.printDebugLog("isPyEngineRunning:" + isPyEngineRunningRet);
                    if (!isPyEngineRunningRet) AppProcess.rebootPyEngine("检查到脚本引擎异常");
                } catch (Exception e) {
                    if(ShellUtils.fastCmdResult("pidof yyds.py")) {
                        ExtSystem.printInfo("[检查yyds.py遇到异常]进程存在:" + e.getMessage());
                    } else {
                        AppProcess.rebootPyEngine("进程不存在Pid:"+ Os.getpid() + "#Keep reboot error:" + e.getMessage() + "\n" + Log.getStackTraceString(e));
                    }
                } finally {
                    new Handler(Looper.getMainLooper()).postDelayed(this, 15_000);
                }
            }
        };
        ZipUtils.unZipSoFile(codePath, AppProcess.unzipTo);
        if (isNotApkInstallMode) {
            ZipUtils.unZipAssetsFile(codePath, AppProcess.unzipTo);
        }
        ExtSystem.shell("chown shell:shell -R " + AppProcess.unzipTo);
        Android14File.doCopy();
        new Handler(Looper.getMainLooper()).postDelayed(keepAutoRunnable, 20_000);
        new Handler(Looper.getMainLooper()).postDelayed(keepPyRunnable, 30_000);
        Looper.loop();
    }

    @SuppressLint({"DefaultLocale", "UnsafeDynamicallyLoadedCode"})
    public static void main(String[] args) throws Exception {
        codePath = System.getenv("CLASSPATH");
        isNotApkInstallMode = codePath != null && codePath.startsWith("/data/local/tmp");
        if (args.length > 0 && args[0].contains("keep")) {
            keepMain();
            return;
        }
        AppProcess.ensureBusyboxInit();
        ExtSystem.shell("killall " + mainProcessName);
        setArgV0(mainProcessName);
        new Thread(HttpUtil.INSTANCE::checkUpdate).start();
        ExtSystem.printInfo(String.format(">> pid=%d uid=%d args:%s isMyt:%s CodePath:%s",
                ExtSystem.pid(), ExtSystem.uid(), Arrays.toString(args), AppProcess.isMytDevice(),
                codePath));
        try {
            //noinspection deprecation
            Looper.prepareMainLooper();
            Context contextInstace = ContextUtil.getSystemContext();
            systemContext = contextInstace;
            if (!isNotApkInstallMode) {
                appContext = contextInstace.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            }
            try {
                ZipUtils.unZipSoFile(codePath, AppProcess.unzipTo);
                ExtSystem.shell("chown shell:shell -R " + AppProcess.unzipTo);
                Android14File.doCopy();
                ContextUtil.InstallLoader.install(ExportApi.class.getClassLoader(), new File(AppProcess.libPath));
            } catch (Throwable e) {
                ExtSystem.printDebugLog("解压/安装So失败", Log.getStackTraceString(e));
            }
            // Load native libs in dependency order: xdl → xunwind → ncnn → ai
            // Must use System.load with absolute paths — app_process has no classloader lib path
            String libDir = AppProcess.libPath;
            String[] soLoadOrder = {"libxdl.so", "libxunwind.so", "libncnn.so", "libai.so"};
            for (String soName : soLoadOrder) {
                try {
                    String soPath = libDir + "/" + soName;
                    if (new File(soPath).exists()) {
                        System.load(soPath);
                        ExtSystem.printDebugLog("加载成功: " + soName);
                    }
                } catch (Throwable e) {
                    ExtSystem.printDebugLog("加载" + soName + "失败: " + e.getMessage());
                }
            }
            try { XUnwind.init(); } catch (Throwable ignored) {}
            ReflectUtil.dropFinalStaticFlag(Class.forName("com.tencent.yyds.App").getField("llIII1lIIIlI"));
            System.out.println("============================================");
            ReflectUtil.changeDebugLog();
            AppProcess.checkMySignInThread();
            if (args.length > 0 && args[0].equals("--engine")) {
                autoEngine();
            } else {
                ExtSystem.printDebugLog("@start...");
                System.setProperty("java.io.tmpdir", "/data/local/tmp");
                ExtSystem.printInfo("启动守护进程:"  + "\t" + ExtSystem.shell(AppProcess.getActiveEngineKeeperCmd()));
                instance = new ExportApi();
                boolean isForceUseHttp = (args.length > 0 && Objects.equals(args[0], "-h"));
                new Thread("y_hapi") {
                    @Override
                    public void run() {
                        ExportHandle.httpServerStart();
                    }
                }.start();
                if (ExtSystem.uid() == 0 && !isForceUseHttp
                        && (ShellUtils.fastCmdResult("which magiskpolicy") || ExtSystem.shell("getenforce").equalsIgnoreCase("Permissive"))
                        && !Build.MANUFACTURER.equalsIgnoreCase("HUAWEI")
                        && !AppProcess.isMytDevice()) {
                    ExtSystem.printDebugLog("@register server");
                    ExportHandle.addToSystem();
                }
                ExtSystem.printDebugLog("@loop");
                Looper.loop();
            }
            ExtSystem.printInfo("server died!!");
        } catch (Throwable e) {
            ExtSystem.printDebugError("gone!", e);
        } finally {
            System.out.println("gone!");
            System.exit(2);
        }
    }
}

// su -c "CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) exec app_process /system/bin uiautomator.ExportApi"