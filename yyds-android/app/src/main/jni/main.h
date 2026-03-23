#ifndef MAIN_H
#define MAIN_H

#include <chrono>
#include "jni.h"
#include "log.h"

extern JavaVM *global_jvm;

class JavaEnv {
public:
    JNIEnv *get_env() {
        if (global_jvm == nullptr) return nullptr;

        attach = 0;
        JNIEnv *jni_env = nullptr;

        int status = global_jvm->GetEnv((void **) &jni_env, JNI_VERSION_1_6);

        if (status == JNI_EDETACHED || jni_env == nullptr) {
            status = global_jvm->AttachCurrentThread(&jni_env, nullptr);
            if (status < 0) {
                jni_env = nullptr;
            } else {
                attach = 1;
            }
        }
        return jni_env;
    }

    void del_env_if_attach() const {
        if (attach == 1) {
            global_jvm->DetachCurrentThread();
        }
    }

private:
    int attach = 0;

    ~JavaEnv() {
        del_env_if_attach();
    }
};

uint64_t timeSinceEpochMillisec();

static int registerNativeMethods(JNIEnv *env,
                                 const char *className,
                                 JNINativeMethod *gMethods,
                                 int numMethods);
#endif