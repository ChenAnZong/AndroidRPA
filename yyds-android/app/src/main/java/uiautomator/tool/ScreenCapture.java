package uiautomator.tool;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.IBinder;
import android.view.IRotationWatcher;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

import image.ImageHelper;
import kotlin.Pair;
import kotlin.jvm.internal.Intrinsics;
import scrcpy.ServiceManager;
import uiautomator.AppProcess;
import uiautomator.ExtSystem;
import uiautomator.MyDevice;
import yyapp.register.HttpUtil;


public class ScreenCapture {
    static Class<?> surfaceControl;
    static int compress = 90;
    // 12 -
    static int w;
    static int h;
    static Method screenshotMethod;
    // 12 +
    static Method captureDisplayMethod;
    static Object captureArgs;

    public static int curRotation= 0;

    public static boolean isHyperOs = !ExtSystem.shell("getprop ro.mi.os.version.code").isEmpty();

    static {
        // 先是跑了谷歌可以, 后面又搞澎湃, 会崩溃, 有空再研究
        if (!isHyperOs && Build.VERSION.SDK_INT < 34) {
            try {
                surfaceControl = Class.forName("android.view.SurfaceControl");
                if (Build.VERSION.SDK_INT < 31) {
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
                        screenshotMethod = surfaceControl.getDeclaredMethod("screenshot", Rect.class, int.class, int.class, int.class);
                    } else {
//                    private static native Bitmap nativeScreenshot(IBinder displayToken,
//                            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
//                            boolean allLayers, boolean useIdentityTransform, int rotation);
                        screenshotMethod = surfaceControl.getDeclaredMethod("nativeScreenshot", IBinder.class,
                                Rect.class, int.class, int.class, int.class, int.class,
                                boolean.class, boolean.class, int.class);
                    }
                    screenshotMethod.setAccessible(true);
                } else {

                    try {
                        // 安卓13
                        Method getInternalDisplayTokenMethod = surfaceControl.getMethod("getInternalDisplayToken");
                        Object displayToken = getInternalDisplayTokenMethod.invoke(null);
                        Class<?> argBuilder = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs$Builder");
                        Class<?> displayCaptureArgs = Class.forName("android.view.SurfaceControl$DisplayCaptureArgs");
                        Object builder = argBuilder.getConstructor(IBinder.class).newInstance(displayToken);
                        builder.getClass().getMethod("setSize", int.class, int.class).invoke(builder, w, h);
                        captureArgs = builder.getClass().getMethod("build").invoke(builder);
                        captureDisplayMethod = surfaceControl.getMethod("captureDisplay", displayCaptureArgs);
                    } catch (Exception e) {
                        try {
                            // 安卓14
                            long[] ids = DisplayControlWrapper.getPhysicalDisplayIds();
                            Object displayToken = DisplayControlWrapper.getPhysicalDisplayToken(ids[0]);
                            Class<?> argBuilder = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
                            Object builder = argBuilder.getConstructor(IBinder.class).newInstance(displayToken);
                            builder.getClass().getMethod("setSize", int.class, int.class).invoke(builder, w, h);
                            captureArgs = builder.getClass().getMethod("build").invoke(builder);
                            captureDisplayMethod = Class.forName("android.window.ScreenCapture").getMethod("captureDisplay", captureArgs.getClass());
                            ExtSystem.printInfo("截图成功~");
                        } catch (Exception ee) {
                            ExtSystem.printDebugError(ee);
                        }
                    }
                }

                try {
                    // 安卓12混淆后可能错误!
                    ServiceManager.INSTANCE.getWindowManager().registerRotationWatcher(new IRotationWatcher.Stub() {
                        @Override
                        public void onRotationChanged(int rotation) {
                            int newRotation = (rotation & 1) ^ 1; // 0->1, 1->0, 2->1, 3->0
                            String newRotationString = newRotation != 0 ? "portrait" : "landscape";
                            int th = h;
                            h = w;
                            w = th;
                            ExtSystem.printInfo("!!Rotation changed:" + newRotationString + " h:" + h + " w:" + w + " rotation:" + rotation);
                            curRotation = rotation;
                        }
                    }, 0);
                } catch (Throwable e) {
                    ExtSystem.printDebugError("ScreenCap.RotationWatch", e);
                }

                int rotation = ServiceManager.INSTANCE.getWindowManager().getRotation();
                int r = (rotation & 1) ^ 1;
                curRotation = rotation;
                Point point = MyDevice.INSTANCE.getScreenSizeCurrent();
                w = point.x;
                h = point.y;
                ExtSystem.printDebugLog("当前初始当前屏幕方向:" + curRotation + " w:" + w + " h:" + h);
            } catch (Throwable e) {
                ExtSystem.printDebugError(e);
            }
        }
    }

    public static Bitmap getScreenFromMytHttp() {
        String path = "/sdcard/Yyds.Py/screenshot.png";
        Pair<String,String> ret = HttpUtil.INSTANCE.downloadFile("http://127.0.0.1:9082/task=snap&level=3",path);
        if (ret.component1() == null) return null;
        File f = new File(path);
        if (f.exists()) {
            return BitmapFactory.decodeFile(path);
        }
        ExtSystem.printInfo("ScreenShotError2:" + Build.BRAND + " " + Build.MODEL);
        return null;
    }

    public static Bitmap getScreenFromShell() {
        String path = "/sdcard/Yyds.Py/screenshot.png";
        ExtSystem.shell("screencap -p > " + path);
        File f = new File(path);
        if (f.exists()) {
            return BitmapFactory.decodeFile(path);
        }
        ExtSystem.printInfo("ScreenShotError1:" + Build.BRAND + " " + Build.MODEL);
        return null;
    }

    public static Bitmap getBitmap() {
        Bitmap bitmap = null;
        try {
            if (AppProcess.isMytDevice()) {
                Bitmap b = getScreenFromMytHttp();
                if (b != null) return b;
            }
            if (isHyperOs)  {
                return getScreenFromShell();
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
                    // 安卓7
                    // bmp = (Bitmap) screenshotMethod.invoke(null, w, h);
                    // https://cs.android.com/android/platform/superproject/+/android-7.1.2_r24:frameworks/base/core/java/android/view/SurfaceControl.java
                    Object token = surfaceControl.getMethod("getBuiltInDisplay", int.class).invoke(null, 0);
                    int rotation = curRotation;
                    if (rotation == 1) {
                        rotation = 3;
                    }
                    bitmap = (Bitmap) screenshotMethod.invoke(null, token, new Rect(), w, h, 0, 0, true, false, rotation);
                } else {
                    bitmap = (Bitmap) screenshotMethod.invoke(null, new Object[]{new Rect(), w, h, curRotation});
                }
            } else {
                if (captureDisplayMethod != null) {
                    // 大于等于安卓12
                    Object screenshotBuffer = captureDisplayMethod.invoke(null, captureArgs);
                    final Bitmap screenShot = screenshotBuffer == null ? null : (Bitmap) screenshotBuffer.getClass().getMethod("asBitmap").invoke(screenshotBuffer);
                    final HardwareBuffer buffer = (HardwareBuffer)screenshotBuffer.getClass().getMethod("getHardwareBuffer").invoke(screenshotBuffer);
                    bitmap = ImageHelper.bitmap_ensure_ARGB8888(screenShot);
                    if (buffer != null) buffer.close();
                } else {
                    ExtSystem.printInfo("captureDisplayMethod == null");
                }
            }
        } catch (Throwable e) {
            ExtSystem.printDebugError(e);
        }
        if (bitmap == null) {
            bitmap = getScreenFromShell();
        }
        return bitmap;
    }


    // =====================================================================
    // 工业级推流引擎：WebP编码 + 脏区域检测 + 自适应画质/画幅
    // =====================================================================

    // --- 可复用缓冲区 ---
    private static final ByteArrayOutputStream streamBuffer = new ByteArrayOutputStream(256 * 1024);

    // --- 自适应参数（由前端反馈闭环动态调整）---
    private static volatile int streamMaxHeight = 1280;
    private static volatile int streamQuality = 70;

    // --- 脏区域检测状态 ---
    private static final int BLOCK_COLS = 8;   // 水平分块数
    private static final int BLOCK_ROWS = 12;  // 垂直分块数
    private static long[] prevBlockHashes = null;
    private static int prevWidth = 0;
    private static int prevHeight = 0;
    private static int unchangedFrameCount = 0;
    private static final int MAX_UNCHANGED_SKIP = 60; // 连续60帧无变化后强制发送一次关键帧

    // --- 帧类型标记（1字节头部协议）---
    // [0x01] + WebP data = 关键帧（完整帧）
    // [0x02] = 跳过帧（屏幕无变化）
    // [0x03] + regionCount(2B) + [x(2B)+y(2B)+w(2B)+h(2B)+dataLen(4B)+WebP data]... = 增量帧
    public static final byte FRAME_KEY = 0x01;
    public static final byte FRAME_SKIP = 0x02;
    public static final byte FRAME_DELTA = 0x03;

    /**
     * 设置自适应推流参数（由前端反馈闭环调用）
     */
    public static void setStreamParams(int maxHeight, int quality) {
        if (maxHeight > 0) streamMaxHeight = Math.max(360, Math.min(1920, maxHeight));
        if (quality > 0) streamQuality = Math.max(20, Math.min(95, quality));
    }

    public static int getStreamMaxHeight() { return streamMaxHeight; }
    public static int getStreamQuality() { return streamQuality; }

    /**
     * 核心推流方法：截图 → 缩放 → 脏区域检测 → WebP编码
     * 返回带帧类型头部的二进制数据
     */
    public static byte[] getStreamData(int quality) {
        Bitmap bitmap = getBitmap();
        if (bitmap == null) bitmap = getScreenFromShell();
        if (bitmap == null) return new byte[]{};

        try {
            // 1. 等比缩放到目标高度
            Bitmap scaled = scaleForStream(bitmap);
            if (scaled != bitmap) {
                bitmap.recycle();
            }
            bitmap = null;

            // HARDWARE Bitmap 不支持 getPixel()，需要转为软件配置
            if (scaled.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap sw = scaled.copy(Bitmap.Config.ARGB_8888, false);
                scaled.recycle();
                scaled = sw;
            }

            int bw = scaled.getWidth();
            int bh = scaled.getHeight();

            // 2. 计算分块哈希，检测脏区域
            long[] blockHashes = computeBlockHashes(scaled, BLOCK_COLS, BLOCK_ROWS);
            boolean fullFrame = (prevBlockHashes == null
                    || prevWidth != bw || prevHeight != bh
                    || unchangedFrameCount >= MAX_UNCHANGED_SKIP);

            if (!fullFrame) {
                // 找出变化的块
                boolean[] dirty = new boolean[BLOCK_COLS * BLOCK_ROWS];
                int dirtyCount = 0;
                for (int i = 0; i < blockHashes.length; i++) {
                    if (blockHashes[i] != prevBlockHashes[i]) {
                        dirty[i] = true;
                        dirtyCount++;
                    }
                }

                if (dirtyCount == 0) {
                    // 屏幕完全无变化 → 发送跳过帧
                    unchangedFrameCount++;
                    scaled.recycle();
                    return new byte[]{FRAME_SKIP};
                }

                // 脏块超过60%，直接发关键帧更高效
                if (dirtyCount > blockHashes.length * 0.6) {
                    fullFrame = true;
                } else {
                    // 增量帧：只编码脏区域
                    byte[] deltaFrame = encodeDeltaFrame(scaled, dirty, dirtyCount, quality, bw, bh);
                    prevBlockHashes = blockHashes;
                    prevWidth = bw;
                    prevHeight = bh;
                    unchangedFrameCount = 0;
                    scaled.recycle();
                    return deltaFrame;
                }
            }

            // 3. 关键帧：WebP编码整帧
            streamBuffer.reset();
            compressWebP(scaled, quality, streamBuffer);
            byte[] webpData = streamBuffer.toByteArray();

            // 更新状态
            prevBlockHashes = blockHashes;
            prevWidth = bw;
            prevHeight = bh;
            unchangedFrameCount = 0;
            scaled.recycle();

            // 组装：[FRAME_KEY] + webpData
            byte[] result = new byte[1 + webpData.length];
            result[0] = FRAME_KEY;
            System.arraycopy(webpData, 0, result, 1, webpData.length);
            return result;

        } catch (Exception e) {
            ExtSystem.printDebugError(e);
            return new byte[]{};
        }
    }

    /**
     * 等比缩放到 streamMaxHeight
     */
    private static Bitmap scaleForStream(Bitmap src) {
        int origH = src.getHeight();
        int origW = src.getWidth();
        int maxH = streamMaxHeight;
        if (origH <= maxH) return src;
        float ratio = (float) maxH / origH;
        int newW = Math.round(origW * ratio);
        return Bitmap.createScaledBitmap(src, newW, maxH, false);
    }

    /**
     * 分块哈希：将图像分成 cols×rows 块，每块采样像素计算哈希
     * 使用稀疏采样（每块采样16×16个点）平衡精度和速度
     */
    private static long[] computeBlockHashes(Bitmap bmp, int cols, int rows) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        int blockW = bw / cols;
        int blockH = bh / rows;
        long[] hashes = new long[cols * rows];

        // 每块采样 SAMPLE×SAMPLE 个像素点
        final int SAMPLE = 8;

        for (int by = 0; by < rows; by++) {
            for (int bx = 0; bx < cols; bx++) {
                long hash = 0x811c9dc5L; // FNV-1a offset basis
                int startX = bx * blockW;
                int startY = by * blockH;
                int stepX = Math.max(1, blockW / SAMPLE);
                int stepY = Math.max(1, blockH / SAMPLE);

                for (int sy = 0; sy < SAMPLE && (startY + sy * stepY) < bh; sy++) {
                    for (int sx = 0; sx < SAMPLE && (startX + sx * stepX) < bw; sx++) {
                        int pixel = bmp.getPixel(startX + sx * stepX, startY + sy * stepY);
                        // FNV-1a hash
                        hash ^= (pixel & 0xFFL);
                        hash *= 0x01000193L;
                        hash ^= ((pixel >> 8) & 0xFFL);
                        hash *= 0x01000193L;
                        hash ^= ((pixel >> 16) & 0xFFL);
                        hash *= 0x01000193L;
                    }
                }
                hashes[by * cols + bx] = hash;
            }
        }
        return hashes;
    }

    /**
     * 编码增量帧：合并相邻脏块为矩形区域，逐区域WebP编码
     * 协议：[0x03] + [regionCount:2B] + [x:2B + y:2B + w:2B + h:2B + dataLen:4B + webpData]...
     */
    private static byte[] encodeDeltaFrame(Bitmap bmp, boolean[] dirty, int dirtyCount,
                                           int quality, int bw, int bh) {
        int blockW = bw / BLOCK_COLS;
        int blockH = bh / BLOCK_ROWS;

        // 合并相邻脏块为矩形区域（贪心行扫描）
        java.util.List<int[]> regions = mergeDirtyRegions(dirty, BLOCK_COLS, BLOCK_ROWS, blockW, blockH, bw, bh);

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        out.write(FRAME_DELTA);
        // regionCount (big-endian 2 bytes)
        int regionCount = regions.size();
        out.write((regionCount >> 8) & 0xFF);
        out.write(regionCount & 0xFF);

        ByteArrayOutputStream regionBuf = new ByteArrayOutputStream(32 * 1024);

        for (int[] rect : regions) {
            int rx = rect[0], ry = rect[1], rw = rect[2], rh = rect[3];
            // 裁剪区域
            Bitmap region = Bitmap.createBitmap(bmp, rx, ry, rw, rh);
            regionBuf.reset();
            compressWebP(region, quality, regionBuf);
            region.recycle();
            byte[] data = regionBuf.toByteArray();

            // 写入区域头：x(2B) + y(2B) + w(2B) + h(2B) + dataLen(4B)
            writeShort(out, rx);
            writeShort(out, ry);
            writeShort(out, rw);
            writeShort(out, rh);
            writeInt(out, data.length);
            try { out.write(data); } catch (Exception ignored) {}
        }

        return out.toByteArray();
    }

    /**
     * 合并相邻脏块为最小覆盖矩形区域
     */
    private static java.util.List<int[]> mergeDirtyRegions(boolean[] dirty, int cols, int rows,
                                                            int blockW, int blockH, int bw, int bh) {
        java.util.List<int[]> regions = new java.util.ArrayList<>();
        boolean[] visited = new boolean[dirty.length];

        for (int i = 0; i < dirty.length; i++) {
            if (!dirty[i] || visited[i]) continue;
            // BFS 找连通脏块
            int minBx = i % cols, maxBx = minBx;
            int minBy = i / cols, maxBy = minBy;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(i);
            visited[i] = true;

            while (!queue.isEmpty()) {
                int idx = queue.poll();
                int bx = idx % cols;
                int by = idx / cols;
                minBx = Math.min(minBx, bx);
                maxBx = Math.max(maxBx, bx);
                minBy = Math.min(minBy, by);
                maxBy = Math.max(maxBy, by);

                // 4-邻域
                int[] neighbors = {idx - 1, idx + 1, idx - cols, idx + cols};
                int[] nx = {bx - 1, bx + 1, bx, bx};
                int[] ny = {by, by, by - 1, by + 1};
                for (int n = 0; n < 4; n++) {
                    int ni = neighbors[n];
                    if (ni >= 0 && ni < dirty.length && !visited[ni] && dirty[ni]
                            && nx[n] >= 0 && nx[n] < cols && ny[n] >= 0 && ny[n] < rows) {
                        visited[ni] = true;
                        queue.add(ni);
                    }
                }
            }

            int rx = minBx * blockW;
            int ry = minBy * blockH;
            int rw = Math.min((maxBx + 1) * blockW, bw) - rx;
            int rh = Math.min((maxBy + 1) * blockH, bh) - ry;
            regions.add(new int[]{rx, ry, rw, rh});
        }
        return regions;
    }

    private static void writeShort(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /**
     * 通用 WebP 编码工具方法（推流/缩略图共用）
     */
    private static void compressWebP(Bitmap bmp, int quality, ByteArrayOutputStream out) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out);
        } else {
            bmp.compress(Bitmap.CompressFormat.WEBP, quality, out);
        }
    }

    public static byte[] getBitmapData(int quality) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Bitmap bitmap = getBitmap();
        if (bitmap == null) {
            ExtSystem.printInfo("getFromShell();");
            bitmap = getScreenFromShell();
        }
        if (bitmap != null) {
            // 缩略图场景也做等比缩放，减少编码耗时
            int origH = bitmap.getHeight();
            Bitmap toCompress = bitmap;
            if (origH > 1280) {
                float ratio = 1280f / origH;
                int newW = Math.round(bitmap.getWidth() * ratio);
                toCompress = Bitmap.createScaledBitmap(bitmap, newW, 1280, false);
                bitmap.recycle();
            }
            toCompress.compress(Bitmap.CompressFormat.JPEG, quality, stream);
            toCompress.recycle();
            return stream.toByteArray();
        }
        return new byte[]{};
    }


    public static boolean writeTo(String path) {
        Bitmap screenShot = getBitmap();
        if (screenShot == null) {
            ExtSystem.printInfo("getFromShell();");
            screenShot = getScreenFromShell();
        }
        if (screenShot == null) {
            ExtSystem.printInfo("screenShot == null");
            return false;
        }
        try {
            FileOutputStream out = new FileOutputStream(path);
            screenShot.compress(Bitmap.CompressFormat.JPEG, compress, out);
            screenShot.recycle();
            out.close();
            return true;
        } catch (Exception e) {
            ExtSystem.printDebugError(e);
            return false;
        }
    }
}
