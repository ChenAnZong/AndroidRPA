package com.tencent.yyds;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import uiautomator.ExtSystem;

public class ViewObject {

    public static Bitmap showObjects(YoloNcnn.Obj[] objects, Bitmap bitmaps) {
        if (objects == null)
        {
            return bitmaps;
        }

        // draw objects on bitmap
        Bitmap rgba = bitmaps.copy(Bitmap.Config.ARGB_8888, true);

        final int[] colors = new int[] {
                Color.rgb( 54,  67, 244),
                Color.rgb( 99,  30, 233),
                Color.rgb(176,  39, 156),
                Color.rgb(183,  58, 103),
                Color.rgb(181,  81,  63),
                Color.rgb(243, 150,  33),
                Color.rgb(244, 169,   3),
                Color.rgb(212, 188,   0),
                Color.rgb(136, 150,   0),
                Color.rgb( 80, 175,  76),
                Color.rgb( 74, 195, 139),
                Color.rgb( 57, 220, 205),
                Color.rgb( 59, 235, 255),
                Color.rgb(  7, 193, 255),
                Color.rgb(  0, 152, 255),
                Color.rgb( 34,  87, 255),
                Color.rgb( 72,  85, 121),
                Color.rgb(158, 158, 158),
                Color.rgb(139, 125,  96)
        };

        Canvas canvas = new Canvas(rgba);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);

        Paint textbgpaint = new Paint();
        textbgpaint.setColor(Color.WHITE);
        textbgpaint.setStyle(Paint.Style.FILL);

        Paint textpaint = new Paint();
        textpaint.setColor(Color.BLACK);
        textpaint.setTextSize(26);
        textpaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < objects.length; i++)
        {
            // 同一类别始终使用相同颜色（基于 label hashCode）
            int colorIdx = Math.abs(objects[i].label.hashCode()) % 19;
            paint.setColor(colors[colorIdx]);

            canvas.drawRect(objects[i].x, objects[i].y, objects[i].x + objects[i].w, objects[i].y + objects[i].h, paint);

            // draw filled text inside image
            {
                String text = objects[i].label + " = " + String.format("%.1f", objects[i].prob * 100) + "%";
                System.out.println("=> " + text);

                float text_width = textpaint.measureText(text);
                float text_height = - textpaint.ascent() + textpaint.descent();

                float x = objects[i].x;
                float y = objects[i].y - text_height;
                if (y < 0)
                    y = 0;
                if (x + text_width > rgba.getWidth())
                    x = rgba.getWidth() - text_width;

                canvas.drawRect(x, y, x + text_width, y + text_height, textbgpaint);

                canvas.drawText(text, x, y - textpaint.ascent(), textpaint);
            }
        }

        return rgba;
    }


    public static void showObjectsa(PpOcrNcnn.Objs[] objects, Bitmap bitmapss) {
        if (objects == null) {

            return;
        }

        // draw objects on bitmap
        Bitmap rgba = bitmapss.copy(Bitmap.Config.ARGB_8888, true);

        final int[] colors = new int[]{
                Color.rgb(54, 67, 244),
                Color.rgb(99, 30, 233),
                Color.rgb(176, 39, 156),
                Color.rgb(183, 58, 103),
                Color.rgb(181, 81, 63),
                Color.rgb(243, 150, 33),
                Color.rgb(244, 169, 3),
                Color.rgb(212, 188, 0),
                Color.rgb(136, 150, 0),
                Color.rgb(80, 175, 76),
                Color.rgb(74, 195, 139),
                Color.rgb(57, 220, 205),
                Color.rgb(59, 235, 255),
                Color.rgb(7, 193, 255),
                Color.rgb(0, 152, 255),
                Color.rgb(34, 87, 255),
                Color.rgb(72, 85, 121),
                Color.rgb(158, 158, 158),
                Color.rgb(139, 125, 96)
        };

        Canvas canvas = new Canvas(rgba);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        Paint textbgpaint = new Paint();
        textbgpaint.setColor(Color.WHITE);
        textbgpaint.setStyle(Paint.Style.FILL);

        Paint textpaint = new Paint();
        textpaint.setColor(Color.BLACK);
        textpaint.setTextSize(56);
        textpaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < objects.length; i++) {
            paint.setColor(colors[i % 19]);

            //canvas.drawRect(objects[i].x, objects[i].y, objects[i].x + objects[i].w, objects[i].y + objects[i].h, paint);
            canvas.drawLine(objects[i].x0, objects[i].y0, objects[i].x1, objects[i].y1, paint);
            canvas.drawLine(objects[i].x1, objects[i].y1, objects[i].x2, objects[i].y2, paint);
            canvas.drawLine(objects[i].x2, objects[i].y2, objects[i].x3, objects[i].y3, paint);
            canvas.drawLine(objects[i].x3, objects[i].y3, objects[i].x0, objects[i].y0, paint);
            // draw filled text inside image
            {
                String text = objects[i].label;// + " = " + String.format("%.1f", objects[i].prob * 100) + "%";
                System.out.println("showObjectsa:" + text + " = " + String.format("%.1f", objects[i].prob * 100) + "%"
                        + "|" + objects[i].x0 + " " + objects[i].y0
                        + "|" + objects[i].x1 + " " + objects[i].y1
                        + "|" + objects[i].x2 + " " + objects[i].y2
                        + "|" + objects[i].x3 + " " + objects[i].y3);


                float text_width = textpaint.measureText(text);
                float text_height = - textpaint.ascent() + textpaint.descent();

                float x = objects[i].x0;
                float y = objects[i].y0 - text_height;
                if (y < 0)
                    y = 0;
                if (x + text_width > rgba.getWidth())
                    x = rgba.getWidth() - text_width;

                canvas.drawRect(x, y, x + text_width, y + text_height, textbgpaint);

                canvas.drawText(text, x, y - textpaint.ascent(), textpaint);

            }

        }
    }

    @SuppressLint("DefaultLocale")
    public static String getOcrString(PpOcrNcnn.Objs[] objects) {
        if (objects == null) {
            System.err.println("ocr null");
            return "";
        }

        if (objects.length == 0) {
            System.err.println("ocr empty");
        }


        StringBuilder buffer = new StringBuilder();
        buffer.append("\n");
        for (PpOcrNcnn.Objs object : objects) {
            String text = object.label;
            if (text.replace(" ", "").isEmpty()) text ="[EMPTY]";
            ExtSystem.printDebugLog("# =>", object.toString(), String.format("%.1f", object.prob * 100));
            buffer.append(String.format("%.1f", object.prob * 100))
                    .append("\t")
                    .append(text)
                    .append("\t")
                    .append((int)object.x0 + "," + (int)object.y0 + " "
                            + (int)object.x1 + "," + (int)object.y1 + " "
                            + (int)object.x2 + "," + (int)object.y2 + " "
                            + (int)object.x3 + "," + (int)object.y3)
                    .append("\n");
        }
        return buffer.toString();
    }

    @SuppressLint("DefaultLocale")
    public static String getRetString(YoloNcnn.Obj[] objects) {
        if (objects == null) {
            System.err.println("ViewObject.java ret null");
            return "";
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append("\n");
        for (YoloNcnn.Obj object : objects) {
            buffer.append(object.toString()).append("\n");
        }
        return buffer.toString();
    }

}
