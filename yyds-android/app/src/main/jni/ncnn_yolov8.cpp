// YOLOv8 NCNN inference — anchor-free detection
// Based on nihui/ncnn-android-yolov8 (BSD 3-Clause License)
// Upgraded from YOLOv5 anchor-based to YOLOv8 DFL decoding

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <algorithm>
#include <cmath>

#if defined(__aarch64__)
#include "ncnn/arm64-v8a/include/ncnn/layer.h"
#include "ncnn/arm64-v8a/include/ncnn/net.h"
#include "ncnn/arm64-v8a/include/ncnn/benchmark.h"
#elif defined(__x86_64__)
#include "ncnn/x86_64/include/ncnn/layer.h"
#include "ncnn/x86_64/include/ncnn/net.h"
#include "ncnn/x86_64/include/ncnn/benchmark.h"
#elif defined(__i386__)
#include "ncnn/x86/include/ncnn/layer.h"
#include "ncnn/x86/include/ncnn/net.h"
#include "ncnn/x86/include/ncnn/benchmark.h"
#elif defined(__arm__)
#include "ncnn/armeabi-v7a/include/ncnn/layer.h"
#include "ncnn/armeabi-v7a/include/ncnn/net.h"
#include "ncnn/armeabi-v7a/include/ncnn/benchmark.h"
#endif

#include "main.h"

#define TAG "YoloNcnn"

static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;

static ncnn::Net yolov8;
static int det_target_size = 640;
static float det_prob_threshold = 0.25f;
static float det_nms_threshold = 0.45f;

// ============================================================
// COCO 80 class names — built-in default, zero config needed
// ============================================================
static const char* coco_class_names[] = {
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
    "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
    "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
    "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
    "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
    "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
    "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
    "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
    "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
};
static const int coco_class_count = 80;

// Dynamic class names (loaded from classes.txt)
static std::vector<std::string> custom_class_names;
static bool use_custom_classes = false;

static const char* get_class_name(int label) {
    if (use_custom_classes && label >= 0 && label < (int)custom_class_names.size()) {
        return custom_class_names[label].c_str();
    }
    if (label >= 0 && label < coco_class_count) {
        return coco_class_names[label];
    }
    return "unknown";
}

struct Object {
    float x;
    float y;
    float w;
    float h;
    int label;
    float prob;
};

static inline float intersection_area(const Object& a, const Object& b) {
    if (a.x > b.x + b.w || a.x + a.w < b.x || a.y > b.y + b.h || a.y + a.h < b.y)
        return 0.f;
    float inter_width = std::min(a.x + a.w, b.x + b.w) - std::max(a.x, b.x);
    float inter_height = std::min(a.y + a.h, b.y + b.h) - std::max(a.y, b.y);
    return inter_width * inter_height;
}

static void qsort_descent_inplace(std::vector<Object>& objects, int left, int right) {
    int i = left;
    int j = right;
    float p = objects[(left + right) / 2].prob;
    while (i <= j) {
        while (objects[i].prob > p) i++;
        while (objects[j].prob < p) j--;
        if (i <= j) {
            std::swap(objects[i], objects[j]);
            i++;
            j--;
        }
    }
    if (left < j) qsort_descent_inplace(objects, left, j);
    if (i < right) qsort_descent_inplace(objects, i, right);
}

static void qsort_descent_inplace(std::vector<Object>& objects) {
    if (objects.empty()) return;
    qsort_descent_inplace(objects, 0, objects.size() - 1);
}

static void nms_sorted_bboxes(const std::vector<Object>& objects, std::vector<int>& picked, float nms_threshold) {
    picked.clear();
    const int n = objects.size();
    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
        areas[i] = objects[i].w * objects[i].h;
    for (int i = 0; i < n; i++) {
        const Object& a = objects[i];
        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++) {
            const Object& b = objects[picked[j]];
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            if (inter_area / union_area > nms_threshold)
                keep = 0;
        }
        if (keep) picked.push_back(i);
    }
}

// ============================================================
// YOLOv8 anchor-free proposal generation with DFL decoding
// Output blob shape: [num_boxes, 64 + num_class]
//   - first 64 dims: DFL bbox regression (reg_max=16 x 4 directions)
//   - remaining dims: class scores (raw logits, apply sigmoid)
// ============================================================
static inline float softmax_dfl(const float* src, int len) {
    float max_val = -FLT_MAX;
    for (int i = 0; i < len; i++)
        max_val = std::max(max_val, src[i]);
    float sum = 0.f;
    float result = 0.f;
    for (int i = 0; i < len; i++) {
        float e = expf(src[i] - max_val);
        sum += e;
        result += i * e;
    }
    return result / sum;
}

static inline float sigmoid(float x) {
    return 1.f / (1.f + expf(-x));
}

static void generate_proposals(const ncnn::Mat& pred, int stride,
    const ncnn::Mat& in_pad, float prob_threshold,
    std::vector<Object>& objects)
{
    const int reg_max_1 = 16;
    const int num_class = pred.w - reg_max_1 * 4;

    const int num_grid_x = in_pad.w / stride;
    const int num_grid_y = in_pad.h / stride;

    for (int y = 0; y < num_grid_y; y++) {
        for (int x = 0; x < num_grid_x; x++) {
            const float* ptr = pred.row(y * num_grid_x + x);
            const float* class_scores = ptr + reg_max_1 * 4;

            // find best class
            int class_index = 0;
            float class_score = -FLT_MAX;
            for (int k = 0; k < num_class; k++) {
                float score = class_scores[k];
                if (score > class_score) {
                    class_score = score;
                    class_index = k;
                }
            }

            float confidence = sigmoid(class_score);
            if (confidence < prob_threshold)
                continue;

            // DFL decode bbox
            float x0 = x + 0.5f - softmax_dfl(ptr, reg_max_1);
            float y0 = y + 0.5f - softmax_dfl(ptr + reg_max_1, reg_max_1);
            float x1 = x + 0.5f + softmax_dfl(ptr + reg_max_1 * 2, reg_max_1);
            float y1 = y + 0.5f + softmax_dfl(ptr + reg_max_1 * 3, reg_max_1);

            x0 *= stride;
            y0 *= stride;
            x1 *= stride;
            y1 *= stride;

            Object obj;
            obj.x = x0;
            obj.y = y0;
            obj.w = x1 - x0;
            obj.h = y1 - y0;
            obj.label = class_index;
            obj.prob = confidence;
            objects.push_back(obj);
        }
    }
}

// ============================================================
// JNI glue
// ============================================================
static jclass objCls = NULL;
static jmethodID constructortorId;
static jfieldID xId;
static jfieldID yId;
static jfieldID wId;
static jfieldID hId;
static jfieldID labelId;
static jfieldID probId;

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "JNI_OnUnload");
}

static void init_class(JNIEnv *env) {
    jclass localObjCls = env->FindClass("com/tencent/yyds/YoloNcnn$Obj");
    objCls = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));
    constructortorId = env->GetMethodID(objCls, "<init>", "()V");
    xId = env->GetFieldID(objCls, "x", "F");
    yId = env->GetFieldID(objCls, "y", "F");
    wId = env->GetFieldID(objCls, "w", "F");
    hId = env->GetFieldID(objCls, "h", "F");
    labelId = env->GetFieldID(objCls, "label", "Ljava/lang/String;");
    probId = env->GetFieldID(objCls, "prob", "F");
}

ncnn::Option init_option(bool use_gpu) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 3;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;
#if NCNN_VULKAN
    if (use_gpu && ncnn::get_gpu_count() > 0) {
        opt.use_vulkan_compute = true;
        __android_log_print(ANDROID_LOG_INFO, TAG, "Vulkan GPU enabled (%d devices)", ncnn::get_gpu_count());
    }
#endif
    return opt;
}

static bool g_use_gpu = false;

// Try to load classes.txt from the same directory as the model
static void try_load_classes_from_dir(const std::string& model_path) {
    // Extract directory from model path
    std::string dir = model_path.substr(0, model_path.find_last_of('/'));
    std::string classes_file = dir + "/classes.txt";

    std::ifstream ifs(classes_file);
    if (!ifs.is_open()) return;

    custom_class_names.clear();
    std::string line;
    while (std::getline(ifs, line)) {
        // trim whitespace
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n' || line.back() == ' '))
            line.pop_back();
        if (!line.empty())
            custom_class_names.push_back(line);
    }
    use_custom_classes = !custom_class_names.empty();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded %d class names from %s",
        (int)custom_class_names.size(), classes_file.c_str());
}

// Validate model is YOLOv8 format by checking output blob dimensions
// YOLOv8: output shape [num_boxes, 64 + num_classes] (64 = 4 * reg_max=16)
// YOLOv5: output shape [num_boxes, 5 + num_classes] (5 = xywh + obj_conf)
static bool validate_yolov8_model() {
    // Create a tiny dummy input to probe output shape
    ncnn::Mat dummy(32, 32, 3);
    dummy.fill(0.f);

    ncnn::Extractor ex = yolov8.create_extractor();
    ex.input("images", dummy);

    // Try stride-8 output first (most models have this)
    const char* output_names[] = {"/model.22/Concat_output_0", "output0", "output"};
    ncnn::Mat out;
    bool got_output = false;
    for (const char* name : output_names) {
        if (ex.extract(name, out) == 0) {
            got_output = true;
            break;
        }
    }

    if (!got_output) {
        // Can't validate — assume ok (non-standard blob names)
        __android_log_print(ANDROID_LOG_WARN, TAG, "Cannot validate model format (unknown output blob names), proceeding anyway");
        return true;
    }

    // YOLOv8 output width = 64 + num_classes (minimum 64+1=65)
    // YOLOv5 output width = 5 + num_classes (typically 85 for COCO, but 5+1=6 minimum)
    // Key difference: YOLOv8 has no objectness score, width >= 65
    int w = out.w;
    if (w > 0 && w < 65) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Model validation FAILED: output width=%d looks like YOLOv5 (expected >=65 for YOLOv8). "
            "Please export with: yolo export model=yolov8n.pt format=ncnn", w);
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Model validated as YOLOv8 (output width=%d)", w);
    return true;
}

jboolean init_det_from_assetManager(JNIEnv *env, jobject thiz, jobject assetManager, jstring name) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    init_class(env);

    // Clear previous model to avoid memory leak
    yolov8.clear();
    g_blob_pool_allocator.clear();
    g_workspace_pool_allocator.clear();

    yolov8.opt = init_option(g_use_gpu);
    // No YoloV5Focus needed for v8

    const char* name_str = env->GetStringUTFChars(name, nullptr);
    std::string param_name = std::string(name_str) + ".ncnn.param";
    std::string bin_name = std::string(name_str) + ".ncnn.bin";

    int ret = yolov8.load_param(mgr, param_name.c_str());
    if (ret != 0) {
        // Fallback: try without .ncnn suffix
        param_name = std::string(name_str) + ".param";
        ret = yolov8.load_param(mgr, param_name.c_str());
    }
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load_param failed for %s", name_str);
        env->ReleaseStringUTFChars(name, name_str);
        return JNI_FALSE;
    }

    ret = yolov8.load_model(mgr, bin_name.c_str());
    if (ret != 0) {
        bin_name = std::string(name_str) + ".bin";
        ret = yolov8.load_model(mgr, bin_name.c_str());
    }
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load_model failed for %s", name_str);
        env->ReleaseStringUTFChars(name, name_str);
        return JNI_FALSE;
    }

    // Reset to COCO defaults for asset-loaded models
    use_custom_classes = false;
    custom_class_names.clear();

    env->ReleaseStringUTFChars(name, name_str);

    if (!validate_yolov8_model()) {
        yolov8.clear();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Model is not YOLOv8 format! Please use yolov8 exported ncnn model.");
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Model loaded from assets (COCO 80 classes)");
    return JNI_TRUE;
}

jboolean init_det_from_path(JNIEnv *env, jobject thiz, jstring bin, jstring param) {
    init_class(env);

    // Clear previous model to avoid memory leak
    yolov8.clear();
    g_blob_pool_allocator.clear();
    g_workspace_pool_allocator.clear();

    yolov8.opt = init_option(g_use_gpu);

    const char* param_path = env->GetStringUTFChars(param, nullptr);
    int ret = yolov8.load_param(param_path);
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load_param failed: %s", param_path);
        env->ReleaseStringUTFChars(param, param_path);
        return JNI_FALSE;
    }

    const char* bin_path = env->GetStringUTFChars(bin, nullptr);
    ret = yolov8.load_model(bin_path);
    if (ret != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load_model failed: %s", bin_path);
        env->ReleaseStringUTFChars(bin, bin_path);
        env->ReleaseStringUTFChars(param, param_path);
        return JNI_FALSE;
    }

    // Auto-load classes.txt from model directory
    try_load_classes_from_dir(std::string(param_path));

    if (!validate_yolov8_model()) {
        yolov8.clear();
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Model is not YOLOv8 format! Please export with: yolo export model=yolov8n.pt format=ncnn");
        env->ReleaseStringUTFChars(bin, bin_path);
        env->ReleaseStringUTFChars(param, param_path);
        return JNI_FALSE;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "Model loaded from path, classes: %s",
        use_custom_classes ? "custom" : "COCO 80");
    env->ReleaseStringUTFChars(bin, bin_path);
    env->ReleaseStringUTFChars(param, param_path);
    return JNI_TRUE;
}

void init_det_set_target_size(JNIEnv *env, jobject thiz, jint size) {
    det_target_size = size;
    __android_log_print(ANDROID_LOG_INFO, TAG, "target_size set to %d", det_target_size);
}

void init_det_set_threshold(JNIEnv *env, jobject thiz, jfloat prob, jfloat nms) {
    det_prob_threshold = prob;
    det_nms_threshold = nms;
    __android_log_print(ANDROID_LOG_INFO, TAG, "threshold set to prob=%.2f nms=%.2f", prob, nms);
}

jboolean init_det_load_class_names(JNIEnv *env, jobject thiz, jstring path) {
    const char* file_path = env->GetStringUTFChars(path, nullptr);
    std::ifstream ifs(file_path);
    if (!ifs.is_open()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to open classes file: %s", file_path);
        env->ReleaseStringUTFChars(path, file_path);
        return JNI_FALSE;
    }

    custom_class_names.clear();
    std::string line;
    while (std::getline(ifs, line)) {
        while (!line.empty() && (line.back() == '\r' || line.back() == '\n' || line.back() == ' '))
            line.pop_back();
        if (!line.empty())
            custom_class_names.push_back(line);
    }
    use_custom_classes = !custom_class_names.empty();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded %d class names from %s",
        (int)custom_class_names.size(), file_path);
    env->ReleaseStringUTFChars(path, file_path);
    return use_custom_classes ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Main detection entry — YOLOv8 inference
// ============================================================
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tencent_yyds_YoloNcnn_Detect(JNIEnv* env, jobject thiz, jobject bitmap, jboolean use_gpu)
{
    g_use_gpu = (bool)use_gpu;
    double start_time = ncnn::get_current_time();

    AndroidBitmapInfo info;
    int r = AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width;
    const int height = info.height;
    LOGI("Detect: %d %d %d", r, width, height);

    if (r < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return NULL;

    const int target_size = det_target_size;

    // letterbox resize
    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h) {
        scale = (float)target_size / w;
        w = target_size;
        h = (int)(h * scale);
    } else {
        scale = (float)target_size / h;
        h = target_size;
        w = (int)(w * scale);
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGB, w, h);
    LOGI("Bitmap Resize: %f %d %d", scale, w, h);

    // pad to multiple of 32
    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2,
        ncnn::BORDER_CONSTANT, 114.f);

    // YOLOv8 inference
    std::vector<Object> objects;
    {
        const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        in_pad.substract_mean_normalize(0, norm_vals);

        ncnn::Extractor ex = yolov8.create_extractor();

        ex.input("in0", in_pad);

        // YOLOv8 has 3 stride outputs merged into one, or separate
        // Try single output first (standard pnnx export)
        std::vector<Object> proposals;

        {
            ncnn::Mat out;
            int ret = ex.extract("out0", out);
            if (ret == 0) {
                // Single output mode — all strides merged
                // out shape: [num_boxes, 64+num_class]
                // We need to split by stride
                int stride8_count = (in_pad.w / 8) * (in_pad.h / 8);
                int stride16_count = (in_pad.w / 16) * (in_pad.h / 16);
                int stride32_count = (in_pad.w / 32) * (in_pad.h / 32);

                // stride 8
                ncnn::Mat pred8(out.w, stride8_count, out.row(0), (size_t)4u);
                generate_proposals(pred8, 8, in_pad, det_prob_threshold, proposals);

                // stride 16
                ncnn::Mat pred16(out.w, stride16_count, out.row(stride8_count), (size_t)4u);
                generate_proposals(pred16, 16, in_pad, det_prob_threshold, proposals);

                // stride 32
                ncnn::Mat pred32(out.w, stride32_count, out.row(stride8_count + stride16_count), (size_t)4u);
                generate_proposals(pred32, 32, in_pad, det_prob_threshold, proposals);
            } else {
                __android_log_print(ANDROID_LOG_ERROR, TAG, "extract out0 failed: %d", ret);
                return NULL;
            }
        }

        // NMS
        qsort_descent_inplace(proposals);
        std::vector<int> picked;
        nms_sorted_bboxes(proposals, picked, det_nms_threshold);

        int count = picked.size();
        objects.resize(count);
        for (int i = 0; i < count; i++) {
            objects[i] = proposals[picked[i]];

            // adjust offset to original unpadded
            float x0 = (objects[i].x - (wpad / 2)) / scale;
            float y0 = (objects[i].y - (hpad / 2)) / scale;
            float x1 = (objects[i].x + objects[i].w - (wpad / 2)) / scale;
            float y1 = (objects[i].y + objects[i].h - (hpad / 2)) / scale;

            // clip
            x0 = std::max(std::min(x0, (float)(width - 1)), 0.f);
            y0 = std::max(std::min(y0, (float)(height - 1)), 0.f);
            x1 = std::max(std::min(x1, (float)(width - 1)), 0.f);
            y1 = std::max(std::min(y1, (float)(height - 1)), 0.f);

            objects[i].x = x0;
            objects[i].y = y0;
            objects[i].w = x1 - x0;
            objects[i].h = y1 - y0;
        }
    }

    // Convert to Java Obj[]
    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objCls, NULL);
    for (size_t i = 0; i < objects.size(); i++) {
        jobject jObj = env->NewObject(objCls, constructortorId);
        env->SetFloatField(jObj, xId, objects[i].x);
        env->SetFloatField(jObj, yId, objects[i].y);
        env->SetFloatField(jObj, wId, objects[i].w);
        env->SetFloatField(jObj, hId, objects[i].h);
        env->SetObjectField(jObj, labelId, env->NewStringUTF(get_class_name(objects[i].label)));
        env->SetFloatField(jObj, probId, objects[i].prob);
        env->SetObjectArrayElement(jObjArray, i, jObj);
    }

    in.release();
    in_pad.release();
    double elapsed = ncnn::get_current_time() - start_time;
    __android_log_print(ANDROID_LOG_DEBUG, TAG, "%.2fms detect (%d objects)", elapsed, (int)objects.size());
    return jObjArray;
}
