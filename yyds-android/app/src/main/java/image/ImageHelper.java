package image;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.util.LruCache;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.tencent.yyds.BuildConfig;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import uiautomator.ExtSystem;
import uiautomator.MyDevice;
import uiautomator.tool.ScreenCapture;


public class ImageHelper {
    private static final String TAG = "ImageHelper";

    public static synchronized native String[] findImage(Bitmap src, int threshold, Bitmap ...dst);
    public static synchronized native String matchImage(Bitmap src, Bitmap dst,
                                                          int x, int y, int w, int h,
                                                          int threshold, double prob);
    public static native String findMultiColor(Bitmap src,
                                               int x, int y, int w, int h,
                                               int threshold, int maxCount,
                                               String baseColor, int stepX, int stepY,
                                               Object[] colors);

    public static native double nativeImageSimilarity(Bitmap src1, Bitmap src2, int type);

    /**
     * 计算两张图片的相似度，优先使用native OpenCV实现，失败时回退到纯Java实现
     * @return 0~1.0的相似度，1.0表示完全相同
     */
    public static double imageSimilarity(Bitmap src1, Bitmap src2, int type) {
        try {
            return nativeImageSimilarity(src1, src2, type);
        } catch (UnsatisfiedLinkError e) {
            ExtSystem.printDebugLog("native imageSimilarity unavailable, using Java fallback");
            return imageSimilarityJava(src1, src2, type);
        }
    }

    private static final int H_BINS = 50;
    private static final int S_BINS = 60;

    /**
     * 纯Java实现的图片相似度（HSV直方图相关性），与native OpenCV算法一致
     */
    public static double imageSimilarityJava(Bitmap src1, Bitmap src2, int type) {
        double[] hist1 = computeNormalizedHSHistogram(src1);
        double[] hist2 = computeNormalizedHSHistogram(src2);
        return histogramCorrelation(hist1, hist2);
    }

    private static double[] computeNormalizedHSHistogram(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        double[] hist = new double[H_BINS * S_BINS];
        float[] hsv = new float[3];

        for (int pixel : pixels) {
            Color.colorToHSV(pixel, hsv);
            // H: 0~360 → 0~H_BINS, S: 0~1.0 → 0~S_BINS
            int hBin = Math.min((int) (hsv[0] / 360.0f * H_BINS), H_BINS - 1);
            int sBin = Math.min((int) (hsv[1] * S_BINS), S_BINS - 1);
            hist[hBin * S_BINS + sBin]++;
        }

        // L1 normalize (same as cv::normalize with NORM_L1)
        double sum = 0;
        for (double v : hist) sum += v;
        if (sum > 0) {
            for (int i = 0; i < hist.length; i++) hist[i] /= sum;
        }
        return hist;
    }

    private static double histogramCorrelation(double[] h1, double[] h2) {
        // Pearson correlation (same as cv::HISTCMP_CORREL)
        int n = h1.length;
        double mean1 = 0, mean2 = 0;
        for (int i = 0; i < n; i++) {
            mean1 += h1[i];
            mean2 += h2[i];
        }
        mean1 /= n;
        mean2 /= n;

        double num = 0, den1 = 0, den2 = 0;
        for (int i = 0; i < n; i++) {
            double d1 = h1[i] - mean1;
            double d2 = h2[i] - mean2;
            num += d1 * d2;
            den1 += d1 * d1;
            den2 += d2 * d2;
        }
        double den = Math.sqrt(den1 * den2);
        return den == 0 ? 0 : num / den;
    }

    public static native void areaImage(Bitmap bitmap, int x, int y, int h, int w);

    static BitmapFactory.Options options = new BitmapFactory.Options();
    static boolean isAndroidNLower = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N;

    static {
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            options.outConfig = Bitmap.Config.ARGB_8888;
        }
    }

    public static Bitmap bitmap_ensure_ARGB8888(Bitmap bitmap) {
        if (bitmap == null) return null;
        if (!isAndroidNLower && bitmap.getConfig() == Bitmap.Config.ARGB_8888) {
            return bitmap;
        }
        // ExtSystem.printDebugLog("bitmap_ensure_ARGB8888, originConfig:", bitmap.getConfig(), isAndroidNLower);
        Bitmap bitmapCopy =  bitmap.copy(Bitmap.Config.ARGB_8888, false);
        bitmap.recycle();
        return bitmapCopy;
    }

    public static void jvmBitmapRecycle(Bitmap ...bitmaps) {
        for (Bitmap bitmap:bitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public static String findImage(Object src, int threshold, String ...dsts) {
        if (src == null) {
            throw new IllegalStateException("findImage src == null");
        }
        if (dsts.length == 0) {
            throw new IllegalStateException("dsts == 0");
        }

        Bitmap srcBitmap;
        if (src instanceof String) {
            if (!new File((String) src).exists()) {
                throw new IllegalStateException(src + " 文件不存在");
            }
            srcBitmap = bitmap_ensure_ARGB8888(BitmapFactory.decodeFile((String)src, options));
        } else if (src instanceof Bitmap) {
            srcBitmap = bitmap_ensure_ARGB8888((Bitmap) src);
        } else {
            throw new IllegalStateException("src type must be path or bitmap :" + src.getClass());
        }
        Bitmap[] dstBitmaps = new Bitmap[dsts.length];

        // 缓存找图!
        for (int i=0; i<dsts.length; i++) {
            File imgFile = new File(dsts[i]);
            if (!new File(dsts[i]).exists()) {
                throw new IllegalStateException(imgFile.getAbsolutePath() + " 文件不存在");
            }
            Bitmap dstBitmap = bitmap_ensure_ARGB8888(BitmapFactory.decodeFile(dsts[i], options));
            dstBitmaps[i] = dstBitmap;
        }

        String[] resultList = findImage(srcBitmap, threshold, dstBitmaps);
        jvmBitmapRecycle(srcBitmap);
        jvmBitmapRecycle(dstBitmaps);
        final StringBuilder resultString = new StringBuilder();
        for (int i=0; i<dsts.length; i++) {
            resultString.append(dsts[i]).append("\t")
                                .append(resultList[i])
                                .append("\n");
        }
        return resultString.toString();
    }

    public static void saveBitmapFile(String path, Bitmap bitmap) {
        File file = new File(path);//将要保存图片的路径
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bos);
            bos.flush();
            bos.close();
        } catch (Exception e) {
            ExtSystem.printDebugError("saveBitmapFile", e);
        }
    }

    public static String findImageInImage(Map<String, String> params, Bitmap bitmap, int threshold,  String ...dsts) {
        autoAreaBitmap(bitmap, params);
        String result = findImage(bitmap, threshold, dsts);
        jvmBitmapRecycle(bitmap);
        return result;
    }

    public static String matchImages(Map<String, String> params,
                                   Bitmap screenImage,
                                   Bitmap templateImage,
                                   int threshold,
                                   double prob) {
        ImageRegion region = parseRect(screenImage, params);
        return matchImage(screenImage, templateImage,
                region.x, region.y, region.w, region.h,
                threshold, prob);
    }

    public static String findMultiColor(Map<String, String> params,
                                        Bitmap src,
                                        int threshold, int maxCount,
                                        String baseColor,
                                        int stepX, int stepY,
                                        String[] colors) {
        Object[] realColors = Arrays.stream(colors).filter((String c) -> c.length() > 4).toArray();
        ImageRegion region = parseRect(src, params);
        return findMultiColor(src,
                region.x, region.y, region.w, region.h,
                threshold, maxCount,
                baseColor,
                stepX, stepY,
                realColors);
    }

    public static Rect parseScreenRect(Point point, Map<String, String> params) {
        String x = params.getOrDefault("x","0");
        String y = params.getOrDefault("y","0");
        String width = params.getOrDefault("w","1");
        String height = params.getOrDefault("h","1");
        int maxWidth = point.x;
        int maxHeight = point.y;
        // 全范围
        if (Objects.equals(x, "0") && Objects.equals(y, "0") && Objects.equals(width, "1") && Objects.equals(height, "1"))
            return new Rect(0, 0, maxWidth, maxHeight);

        if (Objects.equals(x, "1")) width = "1";
        if (Objects.equals(y, "1")) height = "1";
        float fx = Float.parseFloat(Objects.requireNonNull(x));
        float fy = Float.parseFloat(Objects.requireNonNull(y));
        float fw = Float.parseFloat(Objects.requireNonNull(width));
        float fh = Float.parseFloat(Objects.requireNonNull(height));
        int rx = (int) fx;
        int ry = (int) fy;
        int rw = (int) fw;
        int rh = (int) fh;
        // 不得超出边界
        if (rx <= 1) rx = Math.min(maxWidth, Math.round(fx * maxWidth));
        if (ry <= 1) ry = Math.min(maxHeight, Math.round(fy * maxHeight));
        if (rw <= 1) rw = Math.min(Math.round(fw * maxWidth), maxWidth - rx);
        if (rh <= 1) rh = Math.min(Math.round(fh * maxHeight), maxHeight - ry);
        // 不得少于0
        if (rx < 0) rx = 0;
        if (ry < 0) ry = 0;
        if (rw < 0) rw = 0;
        if (rh < 0) rh = 0;
        return new Rect(rx, ry, rx + rw, ry + rh);
    }

    public static ImageRegion parseRect(Bitmap bitmap, Map<String,String> params) {
        String x = params.getOrDefault("x","0");
        String y = params.getOrDefault("y","0");
        String width = params.getOrDefault("w","1");
        String height = params.getOrDefault("h","1");
        int maxWidth = bitmap.getWidth();
        int maxHeight = bitmap.getHeight();
        // 全图
        if (Objects.equals(x, "0") && Objects.equals(y, "0") && Objects.equals(width, "1") && Objects.equals(height, "1"))
            return new ImageRegion(0, 0, maxWidth, maxHeight);

        if (Objects.equals(x, "1")) width = "1";
        if (Objects.equals(y, "1")) height = "1";
        float fx = Float.parseFloat(Objects.requireNonNull(x));
        float fy = Float.parseFloat(Objects.requireNonNull(y));
        float fw = Float.parseFloat(Objects.requireNonNull(width));
        float fh = Float.parseFloat(Objects.requireNonNull(height));
        int rx = (int) fx;
        int ry = (int) fy;
        int rw = (int) fw;
        int rh = (int) fh;
        // 不得超出边界
        if (rx <= 1) rx = Math.min(maxWidth, Math.round(fx * maxWidth));
        if (ry <= 1) ry = Math.min(maxHeight, Math.round(fy * maxHeight));
        if (rw <= 1) rw = Math.min(Math.round(fw * maxWidth), maxWidth - rx);
        if (rh <= 1) rh = Math.min(Math.round(fh * maxHeight), maxHeight - ry);
        // 不得少于0
        if (rx < 0) rx = 0;
        if (ry < 0) ry = 0;
        if (rw < 0) rw = 0;
        if (rh < 0) rh = 0;
        return new ImageRegion(rx, ry, rw, rh);
    }

    public static void autoAreaBitmap(@NonNull  Bitmap bitmap, Map<String,String> params) {
        ImageRegion parseRegion = parseRect(bitmap, params);
        ExtSystem.printInfo("图像裁剪！", parseRegion);
        areaImage(bitmap, parseRegion.x, parseRegion.y, parseRegion.h, parseRegion.w);
        // ImageHelper.saveBitmapFile("/sdcard/crop.png", bitmap);
    }
}
