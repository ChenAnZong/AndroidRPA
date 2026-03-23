#ifndef YYDS_IMAGE_H
#define YYDS_IMAGE_H

#include "jni.h"
#if defined(__aarch64__)
#include "ncnn/arm64-v8a/include/ncnn/mat.h"
#elif defined(__x86_64__)
#include "ncnn/x86_64/include/ncnn/mat.h"
#elif defined(__i386__)
#include "ncnn/x86/include/ncnn/mat.h"
#elif defined(__arm__)
#include "ncnn/armeabi-v7a/include/ncnn/mat.h"
#endif

#include <string>
#include "thread"
#include <unordered_map>
#include <map>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <opencv2/core.hpp>
#include "opencv2/imgproc/imgproc_c.h"

#define ASSERT_RET(status, ret)     if (!(status)) { LOGE(#status); return ret; } // 打印错误, 方便调试
#define ASSERT_FALSE_RET(status)    ASSERT_RET(status, false)

std::string string_replace(const std::string& input, const std::string& oldStr, const std::string& newStr)
{
    std::string result = input;
    size_t pos = 0;
    while ((pos = result.find(oldStr, pos)) != std::string::npos)
    {
        result.replace(pos, oldStr.length(), newStr);
        pos += newStr.length();
    }
    return result;
}

using namespace std;

#include <mutex>
#include <condition_variable>
#include <utility>

#include "main.h"

class CountDownLatch {
public:
    CountDownLatch(uint32_t count) : m_count(count) {}

    void countDown() noexcept {
        std::lock_guard<std::mutex> guard(m_mutex);
        if (0 == m_count) {
            return;
        }
        --m_count;
        if (0 == m_count) {
            m_cv.notify_all();
        }
    }

    void await() noexcept {
        std::unique_lock<std::mutex> lock(m_mutex);
        m_cv.wait(lock, [this] { return 0 == m_count; });
    }

    uint32_t remain() noexcept {
        std::unique_lock<std::mutex> lock(m_mutex);
        return m_count;
    }

private:
    std::mutex m_mutex;
    std::condition_variable m_cv;
    uint32_t m_count;
};

bool MatrixToBitmap(JNIEnv * env, cv::Mat & matrix, jobject obj_bitmap) {
    void * bitmapPixels;                                            // Save picture pixel data
    AndroidBitmapInfo bitmapInfo;                                   // Save picture parameters

    ASSERT_FALSE_RET(AndroidBitmap_getInfo(env, obj_bitmap, &bitmapInfo) >= 0);        // Get picture parameters
    ASSERT_FALSE_RET(bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888
                     || bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGB_565 );          // Only ARGB? 8888 and RGB? 565 are supported
    ASSERT_FALSE_RET(matrix.dims == 2
                     && bitmapInfo.height == (uint32_t)matrix.rows
                     && bitmapInfo.width == (uint32_t)matrix.cols );                   // It must be a 2-dimensional matrix with the same length and width
    ASSERT_FALSE_RET(matrix.type() == CV_8UC1 || matrix.type() == CV_8UC3 || matrix.type() == CV_8UC4 );
    ASSERT_FALSE_RET(AndroidBitmap_lockPixels(env, obj_bitmap, &bitmapPixels) >= 0 );  // Get picture pixels (lock memory block)
    ASSERT_FALSE_RET(bitmapPixels );
    if (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGD("Format: ANDROID_BITMAP_FORMAT_RGBA_8888, %d", matrix.type());
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC4, bitmapPixels);
        switch (matrix.type()) {
            case CV_8UC1:   cv::cvtColor(matrix, tmp, cv::COLOR_GRAY2RGBA);     break;
            case CV_8UC3:   cv::cvtColor(matrix, tmp, cv::COLOR_RGB2RGBA);      break;
            case CV_8UC4:   matrix.copyTo(tmp);                                 break;
            default:        AndroidBitmap_unlockPixels(env, obj_bitmap);        return false;
        }
    } else {
        LOGD("Format: OTHER %d", matrix.type());
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC2, bitmapPixels);
        switch (matrix.type()) {
            case CV_8UC1:   cv::cvtColor(matrix, tmp, cv::COLOR_GRAY2BGR565);   break;
            case CV_8UC3:   cv::cvtColor(matrix, tmp, cv::COLOR_RGB2BGR565);    break;
            case CV_8UC4:   cv::cvtColor(matrix, tmp, cv::COLOR_RGBA2BGR565);   break;
            default:        AndroidBitmap_unlockPixels(env, obj_bitmap);        return false;
        }
    }
    AndroidBitmap_unlockPixels(env, obj_bitmap);                // Unlock
    return true;
}

bool BitmapToMatrixOrigin(JNIEnv * env, jobject obj_bitmap, cv::Mat & matrix) {
    void * bitmapPixels;                                            // Save picture pixel data
    AndroidBitmapInfo bitmapInfo;                                   // Save picture parameters

    ASSERT_FALSE_RET(AndroidBitmap_getInfo(env, obj_bitmap, &bitmapInfo) >= 0);        // Get picture parameters
    ASSERT_FALSE_RET(bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888
                     || bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGB_565 );          // Only ARGB? 8888 and RGB? 565 are supported
    ASSERT_FALSE_RET(AndroidBitmap_lockPixels(env, obj_bitmap, &bitmapPixels) >= 0 );  // Get picture pixels (lock memory block)
    ASSERT_FALSE_RET(bitmapPixels );

    if (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC4, bitmapPixels);    // Establish temporary mat
        tmp.copyTo(matrix);                                                         // Copy to target matrix
    } else {
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC2, bitmapPixels);
        cv::cvtColor(tmp, matrix, cv::COLOR_BGR5652RGB);
    }

    cv::resize(matrix, matrix, cv::Size(matrix.cols, matrix.rows));
    // convert RGB to BGR
    cv::cvtColor(matrix, matrix, cv::COLOR_RGB2BGR);
    AndroidBitmap_unlockPixels(env, obj_bitmap);
    return true;
}

bool BitmapToMatrixScale2_3(JNIEnv * env, jobject obj_bitmap, cv::Mat & matrix, int threshold, int ratioIndex) {
    void * bitmapPixels;                                            // Save picture pixel data
    AndroidBitmapInfo bitmapInfo;                                   // Save picture parameters

    ASSERT_FALSE_RET(AndroidBitmap_getInfo(env, obj_bitmap, &bitmapInfo) >= 0);        // Get picture parameters
    ASSERT_FALSE_RET(bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888
                     || bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGB_565 );          // Only ARGB? 8888 and RGB? 565 are supported
    ASSERT_FALSE_RET(AndroidBitmap_lockPixels(env, obj_bitmap, &bitmapPixels) >= 0 );  // Get picture pixels (lock memory block)
    ASSERT_FALSE_RET(bitmapPixels);

    if (bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC4, bitmapPixels);    // Establish temporary mat
        tmp.copyTo(matrix);                                                         // Copy to target matrix
    } else {
        cv::Mat tmp(bitmapInfo.height, bitmapInfo.width, CV_8UC2, bitmapPixels);
        cv::cvtColor(tmp, matrix, cv::COLOR_BGR5652RGB);
    }

    // 自动模式
    if (ratioIndex == -1) {
        if (matrix.rows > matrix.cols) {
            // 默认2x缩小读取
            cv::resize(matrix, matrix, cv::Size(matrix.cols/2, matrix.rows/3));
        } else {
            cv::resize(matrix, matrix, cv::Size(matrix.cols/3, matrix.rows/2));
        }
    } else {
        if (ratioIndex > 0) {
            // 默认2x缩小读取
            cv::resize(matrix, matrix, cv::Size(matrix.cols/2, matrix.rows/3));
        } else {
            cv::resize(matrix, matrix, cv::Size(matrix.cols/3, matrix.rows/2));
        }
    }


    // convert RGB to BGR
    // 速度更快
    if (threshold >= 0) {
        cv::cvtColor(matrix, matrix, cv::COLOR_RGB2GRAY);
        // 反相
        for (int row = 0; row < matrix.rows; row++)
        {
            for (int col = 0; col < matrix.cols; col++)
            {
                int gray = matrix.at<uchar>(row, col);
                matrix.at<uchar>(row, col) = 255 - gray;
            }
        }
        if (threshold > 0) {
            cv::threshold(matrix, matrix, threshold, 255, 0);
        }
    } else {
        cv::cvtColor(matrix, matrix, cv::COLOR_RGB2Luv);
    }
    // cv::threshold(matrix, matrix, 125, 255, CV_THRESH_BINARY);
    AndroidBitmap_unlockPixels(env, obj_bitmap);            // Unlock
    return true;
}

#endif // YYDS_IMAGE_H