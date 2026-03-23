#include "image.h"

#include <opencv2/opencv.hpp>

// 直方图算法
double calculateImageSimilarity(const cv::Mat &image1, const cv::Mat &image2) {
    // 将图像转换为 HSV 颜色空间
    cv::Mat hsvImage1, hsvImage2;
    cv::cvtColor(image1, hsvImage1, cv::COLOR_BGR2HSV);
    cv::cvtColor(image2, hsvImage2, cv::COLOR_BGR2HSV);

    // 定义直方图参数
    int hBins = 50, sBins = 60;
    int histSize[] = {hBins, sBins};
    float hRanges[] = {0, 180};
    float sRanges[] = {0, 256};
    const float *ranges[] = {hRanges, sRanges};
    int channels[] = {0, 1};

    // 计算直方图
    cv::MatND hist1, hist2;
    cv::calcHist(&hsvImage1, 1, channels, cv::noArray(), hist1, 2, histSize, ranges, true, false);
    cv::calcHist(&hsvImage2, 1, channels, cv::noArray(), hist2, 2, histSize, ranges, true, false);

    // 归一化直方图
    cv::normalize(hist1, hist1, 1, 0, cv::NORM_L1);
    cv::normalize(hist2, hist2, 1, 0, cv::NORM_L1);

    // 计算直方图相似度
    double similarity = cv::compareHist(hist1, hist2, cv::HISTCMP_CORREL);

    return similarity;
}

void contrastStretch2(cv::Mat &srcImage) {
    // 计算图像的最大最小值
    double pixMin, pixMax;
    cv::minMaxLoc(srcImage, &pixMin, &pixMax);
    LOGD("min_a=%f max_b=%f", pixMin, pixMax);

    //create lut table
    cv::Mat lut(1, 256, CV_8U);
    for (int i = 0; i < 256; i++) {
        if (i < pixMin) lut.at<uchar>(i) = 0;
        else if (i > pixMax) lut.at<uchar>(i) = 255;
        else lut.at<uchar>(i) = static_cast<uchar>(255.0 * (i - pixMin) / (pixMax - pixMin) + 0.5);
    }
    //apply lut
    LUT(srcImage, lut, srcImage);
}

void contract(cv::Mat &srcImage) {
    std::vector<cv::Mat> bgr;
    cv::split(srcImage, bgr);
    contrastStretch2(bgr[0]);
    contrastStretch2(bgr[1]);
    contrastStretch2(bgr[2]);
    merge(bgr, srcImage);
}

cv::Mat contrastStretch1(cv::Mat srcImage) {
    cv::Mat resultImage = srcImage.clone();
    int nRows = resultImage.rows;
    int nCols = resultImage.cols;
    // 图像连续性判断
    if (resultImage.isContinuous()) {
        nCols = nCols * nRows;
        nRows = 1;
    }

    // 计算图像的最大最小值
    double pixMin, pixMax;
    cv::minMaxLoc(resultImage, &pixMin, &pixMax);
    LOGD("min_a=%f max_b=%f", pixMin, pixMax);
    // 对比度拉伸映射
    for (int j = 0; j < nRows; j++) {
        uchar *pDataMat = resultImage.ptr<uchar>(j);
        for (int i = 0; i < nCols; i++) {
            pDataMat[i] = (pDataMat[i] - pixMin) * 255 /
                          (pixMax - pixMin);        //255/(pixMax - pixMin)是斜率 y=k(x-a)
        }
    }
    return resultImage;
}

CvMat readBitmap(JNIEnv *env, jobject bitmap) {
    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);
//    static const float mean_vals[3] = { 103.94f, 116.78f, 123.68f };
//    static const float norm_vals[3] = { 0.017f, 0.017f, 0.017f };
//    in.substract_mean_normalize(mean_vals, norm_vals);
    cv::Mat tmp = cv::Mat::zeros(in.h, in.w, CV_8UC3);
    in.to_pixels(tmp.data, ncnn::Mat::PIXEL_RGB2BGR);
    return cvMat(tmp);
}


extern "C"
JNIEXPORT jdouble JNICALL
Java_image_ImageHelper_nativeImageSimilarity(JNIEnv *env, jclass clazz, jobject src1, jobject src2,
                                       jint type) {
    cv::Mat mat1;
    cv::Mat mat2;
    bool b1 = BitmapToMatrixOrigin(env, src1, mat1);
    if (!b1) return -1;
    bool b2 = BitmapToMatrixOrigin(env, src2, mat2);
    if (!b2) return -2;
    return calculateImageSimilarity(mat1, mat2);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_image_ImageHelper_matchImage(JNIEnv *env, jclass clazz, jobject image1, jobject image2,
                                  jint x, jint y, jint w, jint h,
                                  int threshold, double prob) {
    cv::Mat imgMat1;
    cv::Mat imgMat2;
    bool b4 = BitmapToMatrixScale2_3(env, image1, imgMat1, threshold, -1);
    if (!b4) {
        LOGE("!B4");
        return nullptr;
    }
    bool b5 = BitmapToMatrixScale2_3(env, image2, imgMat2, threshold, imgMat1.rows - imgMat1.cols);
    if (!b5) {
        LOGE("!B5");
        return nullptr;
    }
    cv::Mat regionMatRegion;
    if (imgMat1.rows > imgMat1.cols) {
        regionMatRegion = imgMat1(cv::Rect(x / 2, y / 3, w / 2, h / 3));
    } else {
        regionMatRegion = imgMat1(cv::Rect(x / 3, y / 2, w / 3, h / 2));
    }

    cv::Mat resultMat; // int rows, int cols, int type
    cv::matchTemplate(regionMatRegion, imgMat2, resultMat, cv::TM_CCOEFF_NORMED);

    std::string coordinates;
    for (int px = 0; px < resultMat.cols; ++px) {
        for (int py = 0; py < resultMat.rows; ++py) {
            float s = resultMat.at<float>(py, px);
            if (s >= prob) {
                int rx, ry;
                if (imgMat1.rows > imgMat1.cols) {
                    rx = x + px * 2;
                    ry = y + py * 3;
                } else {
                    rx = x + px * 3;
                    ry = y + py * 2;
                }

                coordinates += std::to_string(s) + " " + std::to_string(rx) + "," + std::to_string(ry) + " " + std::to_string(imgMat2.cols) + "," + std::to_string(imgMat2.rows) + "\n";

                // 往下偏移继续查找!
                px = min(resultMat.cols - 1, px + imgMat2.cols);
                py = min(resultMat.rows - 1, py + imgMat2.rows);
            }
        }
    }

    return env->NewStringUTF(coordinates.c_str());
}


inline bool isMatchColor(cv::Mat mat, int x, int y, int threshold, int hexB, int hexG, int hexR) {
    if (x > mat.cols || y > mat.rows) return false;
    if (x < 0 || y < 0) return true;
    cv::Vec3b pixel = mat.at<cv::Vec3b>(y, x);
    int b = pixel[0];
    int g = pixel[1];
    int r = pixel[2];

    int diff = abs(r - hexR) + abs(g - hexG) + abs(b - hexB);
    return diff <= threshold * 3;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_image_ImageHelper_findMultiColor(JNIEnv *env, jclass clazz, jobject src,
                                      jint x, jint y, jint w, jint h,
                                      jint threshold, jint max_count,
                                      jstring base_color,
                                      jint stepX, jint stepY,
                                      jobjectArray colors) {
    // 将十六进制颜色转换为RGB值
    const char *hexColor = env->GetStringUTFChars(base_color, nullptr);
    int hexR, hexG, hexB;
    sscanf(hexColor, "#%02x%02x%02x", &hexR, &hexG, &hexB);
    env->ReleaseStringUTFChars(base_color, hexColor);
    cv::Mat searchMat;
    bool b3 = BitmapToMatrixOrigin(env, src, searchMat);
    if (!b3) return env->NewStringUTF("ERROR:BitmapToMatrixOrigin(env, src, searchMat);");
    cv::Mat searchMatResize;
    cv::resize(searchMat, searchMatResize, cv::Size(searchMat.cols / 4, searchMat.rows / 4),
               cv::INTER_NEAREST);
    jsize arrayLength = env->GetArrayLength(colors);
    // 设置匹配结果容器
    std::vector<cv::Point> matches;

    // 缩小坐标
    int sx = x / 4;
    int sy = y / 4;
    int sw = w / 4;
    int sh = h / 4;

    // 在指定区域进行颜色匹配
    for (int row = sy; row < sy + sh; row++) {
        for (int col = sx; col < sx + sw; col++) {
            if (isMatchColor(searchMatResize, col, row, threshold, hexB, hexG, hexR)) {
                bool isMatch = true;
                for (int i = 0; i < arrayLength; i++) {
                    auto colorsJstring = (jstring) env->GetObjectArrayElement(colors, i);
                    const char *colorStr = env->GetStringUTFChars(colorsJstring, nullptr);
                    auto colorString = std::string(colorStr);
                    auto colorReplaceNotString = string_replace(colorString, "~", "");
                    bool isNeedColor = colorString == colorReplaceNotString;
                    int parseX, parseY;
                    int hexR2, hexG2, hexB2;
                    if (sscanf(colorReplaceNotString.c_str(), "%d,%d|%d,%d,%d", &parseX, &parseY,
                               &hexR2, &hexG2, &hexB2) == 5) {
                        bool hasMatch = isMatchColor(searchMatResize,
                                                     col + (parseX / 4) + 1,
                                                     row + (parseY / 4) + 1, threshold, hexB2,
                                                     hexG2, hexR2);
                        isMatch = (hasMatch && isNeedColor) || (!hasMatch && !isNeedColor);
                        if (!isMatch)
                            LOGI("(%d)[%d] %d,%d 不符合的点:%s (%d,%d) %d,%d", isNeedColor, i,
                                 col, row,
                                 colorReplaceNotString.c_str(),
                                 col + (parseX / 4),
                                 row + (parseY / 4), (parseX / 4), (parseY / 4));
                    } else {
                        LOGI("格式化失败:%s", colorReplaceNotString.c_str());
                    }
                    env->ReleaseStringUTFChars(colorsJstring, colorStr);
                    env->DeleteLocalRef(colorsJstring);
                }

                if (isMatch) {
                    matches.emplace_back(col * 4, row * 4);
                    row = min(row + stepY / 4, sy + sh - 2);
                    col = min(col + stepX / 4, sx + sw - 2);
                }

                if (matches.size() >= max_count) {
                    break;
                }
            }
        }
        if (matches.size() >= max_count) {
            break;
        }
    }

    // 生成坐标字符串
    std::string coordinates;
    for (auto &match: matches) {
        coordinates += std::to_string(match.x) + "," + std::to_string(match.y) + "\n";
    }

    AndroidBitmap_unlockPixels(env, src);

    return env->NewStringUTF(coordinates.c_str());
}