package com.tencent.yyds;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

import androidx.annotation.Keep;

/**
 * YOLOv8 NCNN 推理封装
 * <p>
 * 内置 COCO 80 类默认模型，开箱即用。
 * 支持运行时加载自定义模型和类名。
 */
@Keep
public class YoloNcnn {

    static {
        System.loadLibrary("ai");
    }

    /**
     * 从 APK assets 加载模型
     * @param mgr AssetManager
     * @param name 模型名称前缀（如 "yolov8n"，自动查找 .ncnn.param/.ncnn.bin 或 .param/.bin）
     */
    public native boolean init(AssetManager mgr, String name);

    /**
     * 从文件路径加载模型（自动查找同目录 classes.txt）
     * @param bin  .bin 文件绝对路径
     * @param param .param 文件绝对路径
     */
    public native boolean init(String bin, String param);

    /**
     * 设置推理输入尺寸（默认 640）
     * 较小值更快但精度降低，较大值更准但更慢
     * 推荐: 320(极速) / 640(均衡) / 1280(高精度)
     */
    public synchronized native void setTargetSize(int size);

    /**
     * 设置检测阈值
     * @param prob 置信度阈值（默认 0.25，范围 0~1）
     * @param nms  NMS 阈值（默认 0.45，范围 0~1）
     */
    public synchronized native void setThreshold(float prob, float nms);

    /**
     * 从文件加载自定义类名（每行一个类名）
     * @param path classes.txt 文件绝对路径
     * @return 是否加载成功
     */
    public synchronized native boolean loadClassNames(String path);

    /**
     * 执行目标检测
     * @param bitmap 输入图片（ARGB_8888 格式）
     * @param use_gpu 是否使用 GPU 加速
     * @return 检测结果数组，无结果时返回空数组
     */
    public synchronized native Obj[] Detect(Bitmap bitmap, boolean use_gpu);

    /**
     * 便捷方法：使用 GPU 检测
     */
    public Obj[] detect(Bitmap bitmap) {
        return Detect(bitmap, true);
    }

    @Keep
    public static class Obj {
        public float x;
        public float y;
        public float w;
        public float h;
        public String label;
        public float prob;

        @Override
        public String toString() {
            return "Obj{label='" + label + "', prob=" + String.format("%.2f", prob)
                    + ", x=" + (int) x + ", y=" + (int) y
                    + ", w=" + (int) w + ", h=" + (int) h + '}';
        }
    }
}
