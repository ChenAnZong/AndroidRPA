package image;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.tencent.yyds.PpOcrNcnn;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import uiautomator.ExtSystem;

/**
 * Set-of-Mark (SoM) 标注器
 * 将截图上的可交互区域（OCR文字 + UI控件）标注编号，
 * VLM 只需输出编号即可精准定位坐标。
 *
 * 架构职责：纯图像处理，不涉及业务逻辑。
 */
public class SomAnnotator {

    private static final String TAG = "SomAnnotator";

    /** 单个标记 */
    public static class Mark {
        public int id;
        public int cx, cy;          // 中心点（像素）
        public int x, y, w, h;      // 包围盒
        public String label;        // 文字内容或控件描述
        public String source;       // "ocr" | "ui"

        public Mark(int id, int cx, int cy, int x, int y, int w, int h,
                    String label, String source) {
            this.id = id;
            this.cx = cx;
            this.cy = cy;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.source = source;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("cx", cx);
            o.put("cy", cy);
            o.put("x", x);
            o.put("y", y);
            o.put("w", w);
            o.put("h", h);
            o.put("label", label);
            o.put("source", source);
            return o;
        }
    }

    /** 标注结果 */
    public static class SomResult {
        public final byte[] annotatedJpeg;
        public final List<Mark> marks;

        public SomResult(byte[] annotatedJpeg, List<Mark> marks) {
            this.annotatedJpeg = annotatedJpeg;
            this.marks = marks;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Mark m : marks) {
                arr.put(m.toJson());
            }
            root.put("marks", arr);
            return root;
        }
    }

    // ---- 颜色表 ----
    private static final int[] MARK_COLORS = {
        Color.rgb(255,  59,  48),   // red
        Color.rgb(  0, 122, 255),   // blue
        Color.rgb( 52, 199,  89),   // green
        Color.rgb(255, 149,   0),   // orange
        Color.rgb(175,  82, 222),   // purple
        Color.rgb(255,  45,  85),   // pink
        Color.rgb( 90, 200, 250),   // teal
        Color.rgb(255, 204,   0),   // yellow
    };

    /**
     * 核心方法：标注截图
     *
     * @param screenshot  原始截图 Bitmap (ARGB_8888)
     * @param ocrResults  OCR 检测结果（可为 null）
     * @param uiXml       UI 控件树 XML（可为 null）
     * @param jpegQuality JPEG 压缩质量 (1-100)
     * @return SomResult 包含标注后图片和标记列表
     */
    public static SomResult annotate(Bitmap screenshot,
                                     PpOcrNcnn.Objs[] ocrResults,
                                     String uiXml,
                                     int jpegQuality) {
        List<RawBox> rawBoxes = new ArrayList<>();

        // 1. 收集 OCR 区域
        if (ocrResults != null) {
            collectOcrBoxes(ocrResults, rawBoxes);
        }

        // 2. 收集 UI 控件区域
        if (uiXml != null && !uiXml.isEmpty()) {
            collectUiBoxes(uiXml, rawBoxes);
        }

        // 3. 去重合并（IoU > 0.5 的保留 UI 源优先）
        List<RawBox> merged = deduplicateBoxes(rawBoxes);

        // 4. 分配编号，生成 Mark 列表
        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            RawBox b = merged.get(i);
            int cx = b.rect.left + b.rect.width() / 2;
            int cy = b.rect.top + b.rect.height() / 2;
            marks.add(new Mark(i + 1, cx, cy,
                    b.rect.left, b.rect.top, b.rect.width(), b.rect.height(),
                    b.label, b.source));
        }

        // 5. 在截图上绘制标记
        Bitmap annotated = drawMarks(screenshot, marks);

        // 6. 压缩为 JPEG
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        annotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos);
        if (annotated != screenshot) {
            annotated.recycle();
        }

        return new SomResult(bos.toByteArray(), marks);
    }

    /**
     * 仅生成标记列表，不在图片上绘制（避免 Canvas native crash）
     * 返回原始截图 JPEG + marks 列表
     */
    public static SomResult annotateMarksOnly(Bitmap screenshot,
                                              PpOcrNcnn.Objs[] ocrResults,
                                              String uiXml,
                                              int jpegQuality) {
        List<RawBox> rawBoxes = new ArrayList<>();

        if (ocrResults != null) {
            collectOcrBoxes(ocrResults, rawBoxes);
        }
        if (uiXml != null && !uiXml.isEmpty()) {
            collectUiBoxes(uiXml, rawBoxes);
        }

        List<RawBox> merged = deduplicateBoxes(rawBoxes);

        List<Mark> marks = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            RawBox b = merged.get(i);
            int cx = b.rect.left + b.rect.width() / 2;
            int cy = b.rect.top + b.rect.height() / 2;
            marks.add(new Mark(i + 1, cx, cy,
                    b.rect.left, b.rect.top, b.rect.width(), b.rect.height(),
                    b.label, b.source));
        }

        // 直接压缩原始截图，不绘制标记
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        screenshot.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos);

        return new SomResult(bos.toByteArray(), marks);
    }

    // ================================================================
    // 内部数据结构
    // ================================================================

    private static class RawBox {
        Rect rect;
        String label;
        String source; // "ocr" | "ui"

        RawBox(Rect rect, String label, String source) {
            this.rect = rect;
            this.label = label;
            this.source = source;
        }
    }

    // ================================================================
    // OCR 区域收集
    // ================================================================

    private static void collectOcrBoxes(PpOcrNcnn.Objs[] objs, List<RawBox> out) {
        for (PpOcrNcnn.Objs o : objs) {
            if (o.label == null || o.label.trim().isEmpty()) continue;
            if (o.prob < 0.5f) continue; // 低置信度跳过

            // 四点取包围矩形
            int left   = (int) Math.min(Math.min(o.x0, o.x1), Math.min(o.x2, o.x3));
            int top    = (int) Math.min(Math.min(o.y0, o.y1), Math.min(o.y2, o.y3));
            int right  = (int) Math.max(Math.max(o.x0, o.x1), Math.max(o.x2, o.x3));
            int bottom = (int) Math.max(Math.max(o.y0, o.y1), Math.max(o.y2, o.y3));

            if (right - left < 5 || bottom - top < 5) continue; // 太小跳过
            out.add(new RawBox(new Rect(left, top, right, bottom), o.label.trim(), "ocr"));
        }
    }

    // ================================================================
    // UI 控件树解析 — 从 XML 提取 clickable 元素的 bounds
    // ================================================================

    private static void collectUiBoxes(String xml, List<RawBox> out) {
        // 简单解析：找所有 bounds="[l,t][r,b]" 的 clickable="true" 节点
        // 不用 XML parser 避免依赖，用字符串扫描
        int idx = 0;
        while (idx < xml.length()) {
            int nodeStart = xml.indexOf('<', idx);
            if (nodeStart < 0) break;
            int nodeEnd = xml.indexOf('>', nodeStart);
            if (nodeEnd < 0) break;
            idx = nodeEnd + 1;

            String node = xml.substring(nodeStart, nodeEnd + 1);
            if (node.startsWith("</") || node.startsWith("<?")) continue;

            // 只取 clickable="true" 或 checkable="true" 的节点
            boolean clickable = node.contains("clickable=\"true\"")
                    || node.contains("checkable=\"true\"");
            if (!clickable) continue;

            // 提取 bounds
            Rect bounds = parseBounds(node);
            if (bounds == null) continue;
            if (bounds.width() < 5 || bounds.height() < 5) continue;

            // 提取 text / content-desc 作为 label
            String label = extractAttr(node, "text");
            if (label.isEmpty()) {
                label = extractAttr(node, "content-desc");
            }
            if (label.isEmpty()) {
                label = extractAttr(node, "class");
                // 简化类名
                int dot = label.lastIndexOf('.');
                if (dot >= 0) label = label.substring(dot + 1);
            }

            out.add(new RawBox(bounds, label, "ui"));
        }
    }

    private static Rect parseBounds(String node) {
        // bounds="[left,top][right,bottom]"
        int bi = node.indexOf("bounds=\"");
        if (bi < 0) return null;
        bi += 8; // skip bounds="
        int be = node.indexOf('"', bi);
        if (be < 0) return null;
        String bs = node.substring(bi, be); // [l,t][r,b]
        try {
            // parse [l,t][r,b]
            int i1 = bs.indexOf('[');
            int i2 = bs.indexOf(']', i1);
            int i3 = bs.indexOf('[', i2);
            int i4 = bs.indexOf(']', i3);
            String[] lt = bs.substring(i1 + 1, i2).split(",");
            String[] rb = bs.substring(i3 + 1, i4).split(",");
            return new Rect(
                Integer.parseInt(lt[0]), Integer.parseInt(lt[1]),
                Integer.parseInt(rb[0]), Integer.parseInt(rb[1])
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractAttr(String node, String attr) {
        String key = attr + "=\"";
        int start = node.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = node.indexOf('"', start);
        if (end < 0) return "";
        return node.substring(start, end);
    }

    // ================================================================
    // 去重：IoU > 0.5 时合并，优先保留 UI 源
    // ================================================================

    private static List<RawBox> deduplicateBoxes(List<RawBox> boxes) {
        boolean[] suppressed = new boolean[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) continue;
            for (int j = i + 1; j < boxes.size(); j++) {
                if (suppressed[j]) continue;
                float iou = computeIoU(boxes.get(i).rect, boxes.get(j).rect);
                if (iou > 0.5f) {
                    // 保留 UI 源优先，其次保留面积更大的
                    RawBox bi = boxes.get(i);
                    RawBox bj = boxes.get(j);
                    if (bi.source.equals("ui") && bj.source.equals("ocr")) {
                        // 把 OCR label 合并到 UI box
                        if (bi.label.isEmpty() || bi.label.equals(extractClassName(bi.label))) {
                            bi.label = bj.label;
                        }
                        suppressed[j] = true;
                    } else if (bj.source.equals("ui") && bi.source.equals("ocr")) {
                        if (bj.label.isEmpty() || bj.label.equals(extractClassName(bj.label))) {
                            bj.label = bi.label;
                        }
                        suppressed[i] = true;
                        break;
                    } else {
                        // 同源，保留面积大的
                        int ai = bi.rect.width() * bi.rect.height();
                        int aj = bj.rect.width() * bj.rect.height();
                        suppressed[ai >= aj ? j : i] = true;
                        if (suppressed[i]) break;
                    }
                }
            }
        }
        List<RawBox> result = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (!suppressed[i]) result.add(boxes.get(i));
        }
        return result;
    }

    private static String extractClassName(String s) {
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static float computeIoU(Rect a, Rect b) {
        int x1 = Math.max(a.left, b.left);
        int y1 = Math.max(a.top, b.top);
        int x2 = Math.min(a.right, b.right);
        int y2 = Math.min(a.bottom, b.bottom);
        if (x2 <= x1 || y2 <= y1) return 0f;
        float inter = (float)(x2 - x1) * (y2 - y1);
        float areaA = (float) a.width() * a.height();
        float areaB = (float) b.width() * b.height();
        return inter / (areaA + areaB - inter);
    }

    // ================================================================
    // 绘制标记
    // ================================================================

    private static Bitmap drawMarks(Bitmap src, List<Mark> marks) {
        if (marks == null || marks.isEmpty()) return src;
        Bitmap canvas_bmp = src.copy(Bitmap.Config.ARGB_8888, true);
        if (canvas_bmp == null) {
            // OOM — 直接在原图上绘制（如果可变），否则返回原图
            if (src.isMutable()) {
                canvas_bmp = src;
            } else {
                return src;
            }
        }
        Canvas canvas = new Canvas(canvas_bmp);

        // 根据屏幕大小自适应字号
        float scale = Math.max(src.getWidth(), src.getHeight()) / 1080f;
        float textSize = 28 * scale;
        float strokeWidth = 3 * scale;
        float badgeRadius = 18 * scale;
        float badgeTextSize = 22 * scale;

        // 边框画笔
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(strokeWidth);

        // 半透明填充
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        // 编号圆圈背景
        Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgePaint.setStyle(Paint.Style.FILL);

        // 编号文字
        Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        numPaint.setTextSize(badgeTextSize);
        numPaint.setColor(Color.WHITE);
        numPaint.setTextAlign(Paint.Align.CENTER);
        numPaint.setFakeBoldText(true);

        for (Mark m : marks) {
            int color = MARK_COLORS[(m.id - 1) % MARK_COLORS.length];

            // 绘制半透明填充矩形
            fillPaint.setColor(color);
            fillPaint.setAlpha(30);
            canvas.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, fillPaint);

            // 绘制边框
            boxPaint.setColor(color);
            boxPaint.setAlpha(200);
            canvas.drawRect(m.x, m.y, m.x + m.w, m.y + m.h, boxPaint);

            // 绘制编号圆圈（左上角）
            float bx = m.x + badgeRadius;
            float by = m.y + badgeRadius;
            // 确保不超出屏幕
            bx = Math.max(badgeRadius, Math.min(bx, src.getWidth() - badgeRadius));
            by = Math.max(badgeRadius, Math.min(by, src.getHeight() - badgeRadius));

            badgePaint.setColor(color);
            badgePaint.setAlpha(220);
            canvas.drawCircle(bx, by, badgeRadius, badgePaint);

            // 绘制编号文字
            float textY = by - (numPaint.ascent() + numPaint.descent()) / 2;
            canvas.drawText(String.valueOf(m.id), bx, textY, numPaint);
        }

        return canvas_bmp;
    }
}
