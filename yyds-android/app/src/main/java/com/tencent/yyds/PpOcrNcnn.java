package com.tencent.yyds;




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


import android.content.res.AssetManager;
import android.graphics.Bitmap;

import androidx.annotation.Keep;

@Keep
public class PpOcrNcnn
{
    @Keep
    public static class Objs {
        public float x0;
        public float y0;
        public float x1;
        public float y1;
        public float x2;
        public float y2;
        public float x3;
        public float y3;
        public String label;
        public float prob;

        public Objs() {

        }

        @Override
        public String toString() {
            return "OcrObjS{" +
                    "x0=" + x0 +
                    ", y0=" + y0 +
                    ", x1=" + x1 +
                    ", y1=" + y1 +
                    ", x2=" + x2 +
                    ", y2=" + y2 +
                    ", x3=" + x3 +
                    ", y3=" + y3 +
                    ", label='" + label + '\'' +
                    ", prob=" + prob +
                    '}';
        }
    }

    // ---- V2 原有初始化方法 ----
    // No apk mode!
    public native boolean init();

    public native boolean init(AssetManager mgr, String name);

    public native boolean init(AssetManager mgr, String bin, String param, String key);

    // ---- V5 新增初始化方法 ----
    // 加载 PP-OCRv5 模型 (modelType: 0=mobile, 1=server)
    public native boolean initV5(AssetManager mgr, int modelType);

    // 从文件系统加载完整模型 (det+rec+key 全部自定义路径)
    public native boolean initFromFiles(String detParam, String detBin,
                                        String recParam, String recBin,
                                        String keyFile, int dstHeight);

    // 设置检测目标尺寸 (320/400/480/560/640)
    public native void setTargetSize(int size);

    // ---- 检测 ----
    public synchronized native Objs[] detect(Bitmap bitmap, boolean use_gpu, int is_gray);
}
