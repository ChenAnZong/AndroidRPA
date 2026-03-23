// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2020 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <fstream>
// ncnn
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

#include "common.h"
#include "main.h"

// PP-OCRv5 内置字典
#include "ppocrv5_dict.h"

static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;

ncnn::Net dbNet;
ncnn::Net crnnNet;

std::vector<std::string> keys;

static bool use_gpu_compute = true;

// ============================================================
// OCR 模型版本配置（支持 v2 / v5 动态切换）
// ============================================================
enum OcrModelVersion {
    OCR_V5 = 0,     // PP-OCRv5 (默认)
};

static OcrModelVersion g_ocr_version = OCR_V5;
static int g_det_target_size = 640;
// v2: dstHeight=32, PIXEL_RGB, blob: input0/out1, input/out
// v5: dstHeight=48, PIXEL_RGB2BGR, blob: in0/out0, in0/out0

static int getRecDstHeight() {
    return (g_ocr_version == OCR_V5) ? 48 : 32;
}

static int getRecPixelType() {
    return (g_ocr_version == OCR_V5) ? ncnn::Mat::PIXEL_RGB2BGR : ncnn::Mat::PIXEL_RGB;
}

static int getDetPixelType() {
    return (g_ocr_version == OCR_V5) ? ncnn::Mat::PIXEL_RGB2BGR : ncnn::Mat::PIXEL_RGB;
}

static const char* getDetInputBlob() {
    return (g_ocr_version == OCR_V5) ? "in0" : "input0";
}

static const char* getDetOutputBlob() {
    return (g_ocr_version == OCR_V5) ? "out0" : "out1";
}

static const char* getRecInputBlob() {
    return (g_ocr_version == OCR_V5) ? "in0" : "input";
}

static const char* getRecOutputBlob() {
    return (g_ocr_version == OCR_V5) ? "out0" : "out";
}

// ============================================================
// 工具函数
// ============================================================

static char *readKeysFromFile(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return nullptr;
    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);
    char *buffer = (char *)malloc(fsize + 1);
    fread(buffer, fsize, 1, f);
    fclose(f);
    buffer[fsize] = 0;
    return buffer;
}

static bool loadKeysFromBuffer(char *buffer) {
    if (buffer == nullptr) return false;
    keys.clear();
    std::istringstream inStr(buffer);
    std::string line;
    while (getline(inStr, line)) {
        keys.emplace_back(line);
    }
    free(buffer);
    return true;
}

static void loadKeysFromV5Dict() {
    keys.clear();
    for (int i = 0; i < character_dict_size; i++) {
        keys.emplace_back(character_dict[i]);
    }
}

// ============================================================
// V2 检测后处理（保留原始 clipper unClip 逻辑）
// ============================================================

std::vector<TextBox> findRsBoxes(const cv::Mat &fMapMat, const cv::Mat &norfMapMat,
                                 const float boxScoreThresh, const float unClipRatio) {
    float minArea = 3;
    std::vector<TextBox> rsBoxes;
    rsBoxes.clear();
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(norfMapMat, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    for (int i = 0; i < contours.size(); ++i) {
        float minSideLen, perimeter;
        std::vector<cv::Point> minBox = getMinBoxes(contours[i], minSideLen, perimeter);
        if (minSideLen < minArea)
            continue;
        float score = boxScoreFast(fMapMat, contours[i]);
        if(isnan(score)) {
           score = boxScoreThresh;
        }
        if (score < boxScoreThresh)
            continue;
        std::vector<cv::Point> clipBox = unClip(minBox, perimeter, unClipRatio);
        std::vector<cv::Point> clipMinBox = getMinBoxes(clipBox, minSideLen, perimeter);

        if (minSideLen < minArea + 2)
            continue;

        for (int j = 0; j < clipMinBox.size(); ++j) {
            clipMinBox[j].x = (clipMinBox[j].x / 1.0);
            clipMinBox[j].x = (std::min)((std::max)(clipMinBox[j].x, 0), norfMapMat.cols);
            clipMinBox[j].y = (clipMinBox[j].y / 1.0);
            clipMinBox[j].y = (std::min)((std::max)(clipMinBox[j].y, 0), norfMapMat.rows);
        }

        rsBoxes.emplace_back(TextBox{clipMinBox, score});
    }
    reverse(rsBoxes.begin(), rsBoxes.end());
    return rsBoxes;
}

// ============================================================
// V5 检测后处理（参考 nihui ppocrv5.cpp，使用 RotatedRect 扩展）
// ============================================================

static double contour_score_v5(const cv::Mat& binary, const std::vector<cv::Point>& contour) {
    cv::Rect rect = cv::boundingRect(contour);
    rect.x = std::max(rect.x, 0);
    rect.y = std::max(rect.y, 0);
    if (rect.x + rect.width > binary.cols) rect.width = binary.cols - rect.x;
    if (rect.y + rect.height > binary.rows) rect.height = binary.rows - rect.y;
    cv::Mat binROI = binary(rect);
    cv::Mat mask = cv::Mat::zeros(rect.height, rect.width, CV_8U);
    std::vector<cv::Point> roiContour;
    for (size_t i = 0; i < contour.size(); i++) {
        roiContour.push_back(cv::Point(contour[i].x - rect.x, contour[i].y - rect.y));
    }
    std::vector<std::vector<cv::Point>> roiContours = {roiContour};
    cv::fillPoly(mask, roiContours, cv::Scalar(255));
    double score = cv::mean(binROI, mask).val[0];
    return score / 255.f;
}

// ============================================================
// 文本检测
// ============================================================

std::vector<TextBox>
getTextBoxes(const cv::Mat &src, float boxScoreThresh, float boxThresh, float unClipRatio) {
    int width = src.cols;
    int height = src.rows;
    int target_size = g_det_target_size;
    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h) {
        scale = (float) target_size / w;
        w = target_size;
        h = h * scale;
    } else {
        scale = (float) target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat input = ncnn::Mat::from_pixels_resize(src.data, getDetPixelType(), width, height, w, h);

    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    float pad_value = (g_ocr_version == OCR_V5) ? 114.f : 0.f;
    ncnn::copy_make_border(input, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2,
                           ncnn::BORDER_CONSTANT, pad_value);

    const float meanValues[3] = {0.485f * 255, 0.456f * 255, 0.406f * 255};
    const float normValues[3] = {1.0f / 0.229f / 255.0f, 1.0f / 0.224f / 255.0f, 1.0f / 0.225f / 255.0f};
    in_pad.substract_mean_normalize(meanValues, normValues);

    ncnn::Extractor extractor = dbNet.create_extractor();
    extractor.set_light_mode(true);
    extractor.input(getDetInputBlob(), in_pad);
    ncnn::Mat out;
    extractor.extract(getDetOutputBlob(), out);

    if (g_ocr_version == OCR_V5) {
        // V5 后处理：sigmoid 输出 -> denorm -> threshold -> contour -> RotatedRect -> 转 TextBox
        const float denorm_vals[1] = {255.f};
        out.substract_mean_normalize(0, denorm_vals);
        cv::Mat pred(out.h, out.w, CV_8UC1);
        out.to_pixels(pred.data, ncnn::Mat::PIXEL_GRAY);

        cv::Mat bitmap;
        const float threshold = 0.3f;
        cv::threshold(pred, bitmap, threshold * 255, 255, cv::THRESH_BINARY);

        const float box_thresh = 0.6f;
        const float enlarge_ratio = 1.95f;
        const float min_size = 3 * scale;
        const int max_candidates = 1000;

        std::vector<std::vector<cv::Point>> contours;
        std::vector<cv::Vec4i> hierarchy;
        cv::findContours(bitmap, contours, hierarchy, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
        if (contours.size() > (size_t)max_candidates) contours.resize(max_candidates);

        std::vector<TextBox> result;
        for (size_t i = 0; i < contours.size(); i++) {
            if (contours[i].size() <= 2) continue;
            double score = contour_score_v5(pred, contours[i]);
            if (score < box_thresh) continue;

            cv::RotatedRect rrect = cv::minAreaRect(contours[i]);
            float rrect_maxwh = std::max(rrect.size.width, rrect.size.height);
            if (rrect_maxwh < min_size) continue;

            // 角度归一化
            if (rrect.angle < -30) rrect.angle += 180;
            if (rrect.angle < 30) {
                rrect.angle += 90;
                std::swap(rrect.size.width, rrect.size.height);
            }

            // 扩展
            rrect.size.height += rrect.size.width * (enlarge_ratio - 1);
            rrect.size.width *= enlarge_ratio;

            // 还原到原图坐标
            rrect.center.x = (rrect.center.x - (wpad / 2)) / scale;
            rrect.center.y = (rrect.center.y - (hpad / 2)) / scale;
            rrect.size.width = rrect.size.width / scale;
            rrect.size.height = rrect.size.height / scale;

            // 转换 RotatedRect -> 4点 TextBox
            cv::Point2f corners[4];
            rrect.points(corners);
            std::vector<cv::Point> boxPoint(4);
            for (int j = 0; j < 4; j++) {
                boxPoint[j].x = std::max(std::min((int)corners[j].x, width - 1), 0);
                boxPoint[j].y = std::max(std::min((int)corners[j].y, height - 1), 0);
            }
            result.emplace_back(TextBox{boxPoint, (float)score});
        }
        return result;
    } else {
        // V2 后处理
        cv::Mat fMapMat(in_pad.h, in_pad.w, CV_32FC1, (float *) out.data);
        cv::Mat norfMapMat;
        norfMapMat = fMapMat > boxThresh;
        cv::dilate(norfMapMat, norfMapMat, cv::Mat(), cv::Point(-1, -1), 1);

        std::vector<TextBox> result = findRsBoxes(fMapMat, norfMapMat, boxScoreThresh, 2.0f);
        for (int i = 0; i < result.size(); i++) {
            for (int j = 0; j < result[i].boxPoint.size(); j++) {
                float x = (result[i].boxPoint[j].x - (wpad / 2)) / scale;
                float y = (result[i].boxPoint[j].y - (hpad / 2)) / scale;
                x = std::max(std::min(x, (float) (width - 1)), 0.f);
                y = std::max(std::min(y, (float) (height - 1)), 0.f);
                result[i].boxPoint[j].x = x;
                result[i].boxPoint[j].y = y;
            }
        }
        return result;
    }
}

// ============================================================
// 文本识别
// ============================================================

template<class ForwardIterator>
inline static size_t argmax(ForwardIterator first, ForwardIterator last) {
    return std::distance(first, std::max_element(first, last));
}

TextLine scoreToTextLine(const std::vector<float> &outputData, int h, int w) {
    int keySize = keys.size();
    std::string strRes;
    std::vector<float> scores;
    int lastIndex = 0;
    int maxIndex;
    float maxValue;

    for (int i = 0; i < h; i++) {
        maxIndex = 0;
        maxValue = -1000.f;
        maxIndex = int(argmax(outputData.begin() + i * w, outputData.begin() + i * w + w));
        maxValue = float(*std::max_element(outputData.begin() + i * w,
                                           outputData.begin() + i * w + w));
        if (maxIndex > 0 && maxIndex < keySize && (!(i > 0 && maxIndex == lastIndex))) {
            scores.emplace_back(maxValue);
            strRes.append(keys[maxIndex - 1]);
        }
        lastIndex = maxIndex;
    }
    return {strRes, scores};
}

TextLine getTextLine(const cv::Mat &src) {
    int dstHeight = getRecDstHeight();
    float scale = (float) dstHeight / (float) src.rows;
    int dstWidth = int((float) src.cols * scale);

    cv::Mat srcResize;
    cv::resize(src, srcResize, cv::Size(dstWidth, dstHeight));
    ncnn::Mat input = ncnn::Mat::from_pixels(srcResize.data, getRecPixelType(), srcResize.cols,
                                             srcResize.rows);
    const float mean_vals[3] = {127.5, 127.5, 127.5};
    const float norm_vals[3] = {1.0 / 127.5, 1.0 / 127.5, 1.0 / 127.5};
    input.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor extractor = crnnNet.create_extractor();
    extractor.input(getRecInputBlob(), input);

    ncnn::Mat out;
    extractor.extract(getRecOutputBlob(), out);

    float *floatArray = (float *) out.data;
    int total = out.w * out.h;
    if (out.dims == 3) total = out.w * out.h * out.c;
    std::vector<float> outputData(floatArray, floatArray + total);
    int seq_h = (out.dims == 3) ? out.c : out.h;
    int vocab_w = out.w;
    TextLine res = scoreToTextLine(outputData, seq_h, vocab_w);
    return res;
}

std::vector<TextLine> getTextLines(std::vector<cv::Mat> &partImg) {
    int size = partImg.size();
    std::vector<TextLine> textLines(size);
    for (int i = 0; i < size; ++i) {
        TextLine textLine = getTextLine(partImg[i]);
        textLines[i] = textLine;
    }
    return textLines;
}

// ============================================================
// JNI 检测入口
// ============================================================

// FIXME DeleteGlobalRef is missing for objCls
static jclass objCls = NULL;
static jmethodID constructortorId;
static jfieldID x0Id;
static jfieldID y0Id;
static jfieldID x1Id;
static jfieldID y1Id;
static jfieldID x2Id;
static jfieldID y2Id;
static jfieldID x3Id;
static jfieldID y3Id;
static jfieldID labelId;
static jfieldID probId;

static void initJniGlue(JNIEnv *env) {
    if (objCls != NULL) return;
    jclass localObjCls = env->FindClass("com/tencent/yyds/PpOcrNcnn$Objs");
    objCls = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));
    constructortorId = env->GetMethodID(objCls, "<init>", "()V");
    x0Id = env->GetFieldID(objCls, "x0", "F");
    y0Id = env->GetFieldID(objCls, "y0", "F");
    x1Id = env->GetFieldID(objCls, "x1", "F");
    y1Id = env->GetFieldID(objCls, "y1", "F");
    x2Id = env->GetFieldID(objCls, "x2", "F");
    y2Id = env->GetFieldID(objCls, "y2", "F");
    x3Id = env->GetFieldID(objCls, "x3", "F");
    y3Id = env->GetFieldID(objCls, "y3", "F");
    labelId = env->GetFieldID(objCls, "label", "Ljava/lang/String;");
    probId = env->GetFieldID(objCls, "prob", "F");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tencent_yyds_PpOcrNcnn_detect(JNIEnv *env, jobject thiz,
                                       jobject bitmap, jboolean use_gpu, jint threshold) {
    use_gpu_compute = use_gpu;
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_WARN, "PpOcrNcnn", "image format is not RGBA_8888, is %d", info.format);
        return NULL;
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
    cv::Mat rgb = cv::Mat::zeros(in.h, in.w, CV_8UC3);
    in.to_pixels(rgb.data, ncnn::Mat::PIXEL_RGB);

    std::vector<TextBox> objects;
    objects = getTextBoxes(rgb, 0.4, 0.3, 2.0);

    std::vector<cv::Mat> partImages = getPartImages(rgb, objects);
    std::vector<TextLine> textLines = getTextLines(partImages);
    if (textLines.size() > 0) {
        for (int i = 0; i < textLines.size(); i++)
            objects[i].text = textLines[i].text;
    } else {
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_DEBUG, "PpOcrNcnn", "result size %lu:", textLines.size());

    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objCls, NULL);
    for (size_t i = 0; i < objects.size(); i++) {
        jobject jObj = env->NewObject(objCls, constructortorId);
        env->SetFloatField(jObj, x0Id, objects[i].boxPoint[0].x);
        env->SetFloatField(jObj, y0Id, objects[i].boxPoint[0].y);
        env->SetFloatField(jObj, x1Id, objects[i].boxPoint[1].x);
        env->SetFloatField(jObj, y1Id, objects[i].boxPoint[1].y);
        env->SetFloatField(jObj, x2Id, objects[i].boxPoint[2].x);
        env->SetFloatField(jObj, y2Id, objects[i].boxPoint[2].y);
        env->SetFloatField(jObj, x3Id, objects[i].boxPoint[3].x);
        env->SetFloatField(jObj, y3Id, objects[i].boxPoint[3].y);
        env->SetObjectField(jObj, labelId, env->NewStringUTF(objects[i].text.c_str()));
        env->SetFloatField(jObj, probId, objects[i].score);
        env->SetObjectArrayElement(jObjArray, i, jObj);
    }
    in.release();
    return jObjArray;
}

// 前向声明
jboolean init_ocr_v5(JNIEnv *env, jobject thiz, jobject assetManager, jint modelType);

// ============================================================
// 初始化函数：从 AssetManager 加载（默认 V5 mobile）
// ============================================================
jboolean init_ocr_from_assetManager(JNIEnv *env, jobject thiz, jobject assetManager, jstring name) {
    // 直接委托给 init_ocr_v5，加载 mobile 模型
    return init_ocr_v5(env, thiz, assetManager, 0);
}

// ============================================================
// 初始化函数：自定义 rec 模型路径（det 使用 V5 mobile）
// ============================================================
jboolean init_ocr_from_path(JNIEnv *env, jobject thiz, jobject assetManager, jstring bin, jstring param, jstring key) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    dbNet.clear();
    crnnNet.clear();
    dbNet.opt = opt;
    crnnNet.opt = opt;
    auto bin1 = env->GetStringUTFChars(bin, nullptr);
    auto param2 = env->GetStringUTFChars(param, nullptr);
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);

    g_ocr_version = OCR_V5;

    if (dbNet.load_param(mgr, "PP_OCRv5_mobile_det.ncnn.param") != 0) return JNI_FALSE;
    if (crnnNet.load_param(param2) != 0) return JNI_FALSE;
    if (dbNet.load_model(mgr, "PP_OCRv5_mobile_det.ncnn.bin") != 0) return JNI_FALSE;
    if (crnnNet.load_model(bin1) != 0) return JNI_FALSE;

    const char* key_file_path = env->GetStringUTFChars(key, nullptr);
    char *buffer = readKeysFromFile(key_file_path);
    if (!loadKeysFromBuffer(buffer)) return JNI_FALSE;

    env->ReleaseStringUTFChars(bin, bin1);
    env->ReleaseStringUTFChars(param, param2);
    env->ReleaseStringUTFChars(key, key_file_path);

    initJniGlue(env);
    return JNI_TRUE;
}

// ============================================================
// 初始化函数：V5 no-APK 模式
// ============================================================
jboolean init_ocr_from_no_apk(JNIEnv *env, jobject thiz) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    dbNet.clear();
    crnnNet.clear();
    dbNet.opt = opt;
    crnnNet.opt = opt;

    g_ocr_version = OCR_V5;

    if (dbNet.load_param("/data/local/tmp/cache/assets/PP_OCRv5_mobile_det.ncnn.param") != 0) return JNI_FALSE;
    if (crnnNet.load_param("/data/local/tmp/cache/assets/PP_OCRv5_mobile_rec.ncnn.param") != 0) return JNI_FALSE;
    if (dbNet.load_model("/data/local/tmp/cache/assets/PP_OCRv5_mobile_det.ncnn.bin") != 0) return JNI_FALSE;
    if (crnnNet.load_model("/data/local/tmp/cache/assets/PP_OCRv5_mobile_rec.ncnn.bin") != 0) return JNI_FALSE;

    loadKeysFromV5Dict();

    initJniGlue(env);
    return JNI_TRUE;
}

// ============================================================
// 初始化函数：V5 从 AssetManager 加载 (mobile/server)
// ============================================================
jboolean init_ocr_v5(JNIEnv *env, jobject thiz, jobject assetManager, jint modelType) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);

    // modelType: 0=mobile, 1=server
    const char* modelTypes[] = {"mobile", "server"};
    int idx = (modelType >= 0 && modelType <= 1) ? modelType : 0;
    bool use_fp16 = (idx == 0); // server 模型 fp16 会产生 NaN

    dbNet.clear();
    crnnNet.clear();

    dbNet.opt = opt;
    crnnNet.opt = opt;
    dbNet.opt.use_fp16_packed = use_fp16;
    dbNet.opt.use_fp16_storage = use_fp16;
    dbNet.opt.use_fp16_arithmetic = use_fp16;
    crnnNet.opt.use_fp16_packed = use_fp16;
    crnnNet.opt.use_fp16_storage = use_fp16;
    crnnNet.opt.use_fp16_arithmetic = use_fp16;

    g_ocr_version = OCR_V5;

    char det_param[128], det_bin[128], rec_param[128], rec_bin[128];
    snprintf(det_param, sizeof(det_param), "PP_OCRv5_%s_det.ncnn.param", modelTypes[idx]);
    snprintf(det_bin, sizeof(det_bin), "PP_OCRv5_%s_det.ncnn.bin", modelTypes[idx]);
    snprintf(rec_param, sizeof(rec_param), "PP_OCRv5_%s_rec.ncnn.param", modelTypes[idx]);
    snprintf(rec_bin, sizeof(rec_bin), "PP_OCRv5_%s_rec.ncnn.bin", modelTypes[idx]);

    __android_log_print(ANDROID_LOG_INFO, "PpOcrNcnn", "Loading PP-OCRv5 %s model...", modelTypes[idx]);

    if (dbNet.load_param(mgr, det_param) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "PpOcrNcnn", "load v5 det param failed: %s", det_param);
        return JNI_FALSE;
    }
    if (dbNet.load_model(mgr, det_bin) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "PpOcrNcnn", "load v5 det model failed: %s", det_bin);
        return JNI_FALSE;
    }
    if (crnnNet.load_param(mgr, rec_param) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "PpOcrNcnn", "load v5 rec param failed: %s", rec_param);
        return JNI_FALSE;
    }
    if (crnnNet.load_model(mgr, rec_bin) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "PpOcrNcnn", "load v5 rec model failed: %s", rec_bin);
        return JNI_FALSE;
    }

    // V5 使用内置字典
    loadKeysFromV5Dict();

    __android_log_print(ANDROID_LOG_INFO, "PpOcrNcnn", "PP-OCRv5 %s loaded, dict size=%zu", modelTypes[idx], keys.size());

    initJniGlue(env);
    return JNI_TRUE;
}

// ============================================================
// 初始化函数：从文件系统加载完整模型（det+rec+key 全部自定义）
// ============================================================
jboolean init_ocr_from_files(JNIEnv *env, jobject thiz,
                             jstring detParam, jstring detBin,
                             jstring recParam, jstring recBin,
                             jstring keyFile, jint dstHeightHint) {
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    dbNet.clear();
    crnnNet.clear();
    dbNet.opt = opt;
    crnnNet.opt = opt;

    // 根据 dstHeight 推断版本
    g_ocr_version = OCR_V5;

    auto dp = env->GetStringUTFChars(detParam, nullptr);
    auto db = env->GetStringUTFChars(detBin, nullptr);
    auto rp = env->GetStringUTFChars(recParam, nullptr);
    auto rb = env->GetStringUTFChars(recBin, nullptr);
    auto kf = env->GetStringUTFChars(keyFile, nullptr);

    bool ok = true;
    if (dbNet.load_param(dp) != 0) ok = false;
    if (ok && dbNet.load_model(db) != 0) ok = false;
    if (ok && crnnNet.load_param(rp) != 0) ok = false;
    if (ok && crnnNet.load_model(rb) != 0) ok = false;

    if (ok) {
        if (kf != nullptr && strlen(kf) > 0) {
            char *buffer = readKeysFromFile(kf);
            if (!loadKeysFromBuffer(buffer)) ok = false;
        } else if (g_ocr_version == OCR_V5) {
            loadKeysFromV5Dict();
        }
    }

    env->ReleaseStringUTFChars(detParam, dp);
    env->ReleaseStringUTFChars(detBin, db);
    env->ReleaseStringUTFChars(recParam, rp);
    env->ReleaseStringUTFChars(recBin, rb);
    env->ReleaseStringUTFChars(keyFile, kf);

    if (!ok) return JNI_FALSE;

    initJniGlue(env);
    return JNI_TRUE;
}

// ============================================================
// 设置检测目标尺寸
// ============================================================
void set_det_target_size(JNIEnv *env, jobject thiz, jint size) {
    if (size >= 320 && size <= 1280) {
        g_det_target_size = size;
        __android_log_print(ANDROID_LOG_INFO, "PpOcrNcnn", "det target_size set to %d", size);
    }
}
