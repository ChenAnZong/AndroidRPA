package com.tencent.yyds;

public class AiConfig {
    public static final boolean isSupportAiDetect = true;

    // 内置 YOLOv8n COCO 模型（从 assets 自动释放）
    public static final String BUILTIN_YOLO_DIR = "/data/local/tmp/cache/assets";
    public static final String BUILTIN_YOLO_PARAM = "yolov8n.ncnn.param";
    public static final String BUILTIN_YOLO_BIN = "yolov8n.ncnn.bin";

    // 用户自定义模型目录（优先级高于内置模型）
    public static final String USER_YOLO_DIR = "/data/local/tmp/yyds_yolo";

    // 兼容旧路径
    public static final String DEFAULT_YOLO_MODEL_NAME = "/data/local/tmp/yyds";
}
