#include "main.h"
#include <cmath>
#include <sstream>
#include "log.h"

#include "syscall.h"
#include "unistd.h"
#include "thread"
#include "string"
#include "wait.h"
#include "sys/stat.h"
#include <sys/sysinfo.h>
#include <dirent.h>
#include "time.h"
#include "sys/resource.h"
#include "fstream"
#include "fcntl.h"
#include "sys/stat.h"
#include "fcntl.h"
#include "sys/syscall.h"
#include "yyds/yyds.h"
#include "xunwind.h"
#include "string_view"

JavaVM *global_jvm;

#define JNI_HIDDEN __attribute__((visibility("hidden")))

uint64_t timeSinceEpochMillisec() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

using namespace std;

extern char* s11;
extern char* sig1;
extern char* sig2;


static int registerNativeMethods(JNIEnv *env,
                                 const char *className,
                                 JNINativeMethod *gMethods,
                                 int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);

    if (clazz == nullptr) {
        return JNI_FALSE;
    }

    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}


extern jboolean init_ocr_from_assetManager(JNIEnv *env, jobject thiz, jobject assetManager, jstring name);
extern jboolean init_ocr_from_path(JNIEnv *env, jobject thiz, jobject assetManager, jstring bin, jstring param, jstring path);
extern jboolean init_ocr_from_no_apk(JNIEnv *env, jobject thiz);
extern jboolean init_ocr_v5(JNIEnv *env, jobject thiz, jobject assetManager, jint modelType);
extern jboolean init_ocr_from_files(JNIEnv *env, jobject thiz, jstring detParam, jstring detBin, jstring recParam, jstring recBin, jstring keyFile, jint dstHeightHint);
extern void set_det_target_size(JNIEnv *env, jobject thiz, jint size);
extern jboolean init_det_from_assetManager(JNIEnv *env, jobject thiz, jobject assetManager, jstring name);
extern jboolean init_det_from_path(JNIEnv *env, jobject thiz, jstring bin, jstring param);
extern void init_det_set_target_size(JNIEnv *env, jobject thiz, jint size);
extern void init_det_set_threshold(JNIEnv *env, jobject thiz, jfloat prob, jfloat nms);
extern jboolean init_det_load_class_names(JNIEnv *env, jobject thiz, jstring path);

inline void crash_exit() {
    int _ = 1;
    delete &_;
    int _a = _ + 1;
}

inline void crash_exit2() {
    int _ = 9;
    delete &_;
    int _a = _ * 3;
}

inline void crash_exit3() {
    for (int i=0; i < 197; i++) {
        malloc(timeSinceEpochMillisec());
    }
}

namespace app {
    static jobject getContext(JNIEnv *env) {
        jclass jAppAppGlobalsClass = env->FindClass("com/tencent/yyds/App");
        jfieldID appField = env->GetStaticFieldID(jAppAppGlobalsClass, "app", "Lcom/tencent/yyds/App;");
        jobject jGetInitialApplication = env->GetStaticObjectField(jAppAppGlobalsClass, appField);
        return jGetInitialApplication;
    }

    static long getBuildTime(JNIEnv *env) {
        jclass jAppAppGlobalsClass = env->FindClass("com/tencent/yyds/App");
        jfieldID appField = env->GetStaticFieldID(jAppAppGlobalsClass, "bt", "J");
        jlong bt = env->GetStaticLongField(jAppAppGlobalsClass, appField);
        return bt;
    }

    static const char* getSignature(JNIEnv *env) {
        jobject thiz = getContext(env);

        // Context
        jclass native_context = env->FindClass("android/content/Context");

        // Context#getPackageManager()
        jmethodID methodID_func = env->GetMethodID(native_context, "getPackageManager", "()Landroid/content/pm/PackageManager;");
        jobject package_manager = env->CallObjectMethod(thiz, methodID_func);
        jclass pm_clazz = env->GetObjectClass(package_manager);

        // PackageManager#getPackageInfo()
        jmethodID methodId_pm = env->GetMethodID(pm_clazz, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");

        // Context#getPackageName()
        jmethodID methodID_packagename = env->GetMethodID(native_context, "getPackageName", "()Ljava/lang/String;");
        jstring name_str = static_cast<jstring>(env->CallObjectMethod(thiz, methodID_packagename));
        jobject package_info = env->CallObjectMethod(package_manager, methodId_pm, name_str, 64);
        jclass pi_clazz = env->GetObjectClass(package_info);

        // PackageInfo#signatures
        jfieldID fieldID_signatures = env->GetFieldID(pi_clazz, sig1, sig2);
        jobject signatur = env->GetObjectField(package_info, fieldID_signatures);
        jobjectArray signatures = reinterpret_cast<jobjectArray>(signatur);

        // PackageInfo#signatures[0]
        jobject signature = env->GetObjectArrayElement(signatures, 0);
        jclass s_clazz = env->GetObjectClass(signature);

        // PackageInfo#signatures[0].toCharString()
        jmethodID methodId_ts = env->GetMethodID(s_clazz, "toCharsString", "()Ljava/lang/String;");
        jstring ts = (jstring) env->CallObjectMethod(signature, methodId_ts);

        return env->GetStringUTFChars(ts, 0);
    }
}

void ward() {
    if (getuid() == 0) {
        // 第一次复制进程
        pid_t pid_child = fork();

        if (pid_child > 0) {
            LOGD("ward pid_child=%d", pid_child);
            return;
        } else if (pid_child == 0) {
            pid_t ppid = getppid();
            umask(0);
            setsid();
            LOGD("ward pid_parent=%d", ppid);
            auto ms1 = std::chrono::milliseconds(1000);

            while (true) {
//                LOGV("wait die..%d", ppid);
                std::this_thread::sleep_for(ms1);
                if (kill(ppid, 0) == -1 && errno == ESRCH) {
                    LOGV("===========RESTART==========");
                    execl("/system/bin/sh", "sh", "-c", "CLASSPATH=$(cut -d ':' -f2 <<< `pm path com.yyds.auto`) setsid app_process /system/bin uiautomator.ExportApi", (char*)nullptr);
                    exit(0);
                    break;
                }
            }
        }
    }
}

void ward_java() {
    // 第二次复制进程
    pid_t pid_child2 = fork();
    if (pid_child2 == 0) {
        umask(0);
        setsid();
    }
}

JNI_HIDDEN void __attribute__((constructor)) ini1() { LOGD("ini1-------"); }
JNI_HIDDEN void __attribute__((constructor)) ini2() { LOGD("ini2-------"); }

static inline bool str_eql(string_view a, string_view b) { return a == b; }

template<bool str_op(string_view, string_view) = &str_eql>
static bool proc_name_match(int pid, string_view name) {
    char buf[4019];
    sprintf(buf, "/proc/%d/cmdline", pid);
    if (auto fp = fopen(buf, "re")) {
        fgets(buf, sizeof(buf), fp);
        if (str_op(buf, name)) {
            fclose(fp);
            return true;
        }
        fclose(fp);
    }
    return false;
}

static DIR *procfp;

/*
 * Bionic's atoi runs through strtol().
 * Use our own implementation for faster conversion.
 */
int parse_int(string_view s) {
    int val = 0;
    for (char c : s) {
        if (!c) break;
        if (c > '9' || c < '0')
            return -1;
        val = val * 10 + c - '0';
    }
    return val;
}

template<class F>
static void crawl_procfs(const F &fn) {
    rewinddir(procfp);
    dirent *dp;
    int pid;
    while ((dp = readdir(procfp))) {
        pid = parse_int(dp->d_name);
        if (pid > 0 && !fn(pid))
            break;
    }
}

template<bool matcher(int, string_view) = &proc_name_match> int check_process(const char* process_name) {
    int yy_pid = -1;
    crawl_procfs([&yy_pid, process_name](int pid) -> bool {
        if (matcher(pid, process_name)) {
            yy_pid = pid;
            return false;
        }
        return true;
    });
    return yy_pid;
}

/**运行命令
 *1. cmd    要执行的命令
 *2. 参数type可使用“r”代表读取，“w”代表写入。
 */
std::string shell(const char *cmd)
{
    std::string result;
    FILE * pp = popen(cmd, "r");
    if (!pp) {
        LOGD("ERR TO OPEN");
    } else {
        char buf[128];
        while(true)  //每次读取一行内容
        {
            auto rt = fgets(buf,128,pp);
            if (rt == nullptr) {
                break;
            } else {
                result.append(buf);
            }
        }
        pclose(pp);
    }
    return result;
}


void sample_sig_handler(int signum, siginfo_t *siginfo, void *context) {
    LOGE(">>> CATCH FATAL SIGNAL %d %d:", signum, gettid());
    xunwind_cfi_log(getpid(), gettid(), context, "!@YY", ANDROID_LOG_FATAL, "");
    int fd = open("/data/local/tmp/.xunwind.log", O_WRONLY|O_APPEND);
    xunwind_cfi_dump(getpid(), gettid(), context, fd,"!@YY");
    close(fd);
    exit(0);
}

void sample_signal_register()
{
    struct sigaction act {};
    memset(&act, 0, sizeof(act));
    sigfillset(&act.sa_mask);
    sigdelset(&act.sa_mask, SIGSEGV);
    act.sa_sigaction = sample_sig_handler;
    act.sa_flags = SA_SIGINFO | SA_ONSTACK;
    if (sigaction(SIGSEGV, &act, nullptr)  != 0) {
        LOGE("register SIGABRT error");
    }
}

void process_auto(const char *path) {
    char *const argv[] = {"uiautomator.ExportApi", NULL};
    char *const envp[] = {strdup(path), NULL};
    execvpe("app_process", argv, envp);
}

string get_process_name() {
    char processName[1024] = {0};
    FILE* fd = fopen("/proc/self/cmdline", "r");
    if (fd != nullptr) {
        size_t len = fread(processName, sizeof(char), sizeof(processName), fd);
        if (len > 0) {
            processName[len - 1] = '\0';
        }
        fclose(fd);
    }
    return processName;
}

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **) (&env), JNI_VERSION_1_6) != JNI_OK) {
        return -1;
    }
    // LOGD("JNI_OnLoad2");
    // 初始化NDK环境
    env->GetJavaVM(&global_jvm);
    setsid();
    umask(0);
//    sample_signal_register();

//    if (get_process_name() == "yyds.keep") {
//        LOGD("守护进程");
//        return JNI_VERSION_1_4;
//    }

    long build_time_millis = app::getBuildTime(env);

    // 签名校验1
    if (getuid() > 2010) {
        LOGW("ASc|>");
        const char* check_sig = app::getSignature(env);
        // 开两个线程风控
        std::thread([=]() {
            pthread_setname_np(pthread_self(), "TSS");
            // 首先签名，包是否被篡改
            if (strstr(check_sig, std::string("ccce08b4ab4").append(s11).data()) == nullptr) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1000));
                syscall(__NR_exit_group, 0);
                crash_exit();
            } else {
                LOGW("ASO|>");
            }
        }).detach();
        LOGW("ASc|<");
    }

    // app 不允许加载这个so, 因为这个so包含注册逻辑,一加载就崩溃
    if (getuid() > 10029) crash_exit2();
    // 仅允许在主线程执行
    if (getpid() != gettid()) {
        if (fork()) {
            // 父 或 错误
            kill(getpid(), 11);
        } else {
            // 子
            kill(getppid(), 19);
        }
    };
    // 调用冷门syscall，加大模拟执行难度
    struct sysinfo s_info {};
    struct rusage s_rusage {};
    syscall(__NR_sysinfo, &s_info);
    syscall(__NR_getrusage, &s_rusage);
    syscall(__NR_unshare, 0);
    yyds::Yyds_OnLoad(vm, reserved);
    JNINativeMethod det_method[] = {
            {"init",  "(Landroid/content/res/AssetManager;Ljava/lang/String;)Z", (void *) (init_det_from_assetManager)},
            {"init",  "(Ljava/lang/String;Ljava/lang/String;)Z", (void *) (init_det_from_path)},
            {"setTargetSize", "(I)V", (void *) (init_det_set_target_size)},
            {"setThreshold", "(FF)V", (void *) (init_det_set_threshold)},
            {"loadClassNames", "(Ljava/lang/String;)Z", (void *) (init_det_load_class_names)},
    };
    JNINativeMethod ocr_method[] = {
            {"init",  "(Landroid/content/res/AssetManager;Ljava/lang/String;)Z", (void *) (init_ocr_from_assetManager)},
            {"init",  "(Landroid/content/res/AssetManager;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z", (void *) (init_ocr_from_path)},
            {"init",  "()Z", (void *) (init_ocr_from_no_apk)},
            {"initV5", "(Landroid/content/res/AssetManager;I)Z", (void *) (init_ocr_v5)},
            {"initFromFiles", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z", (void *) (init_ocr_from_files)},
            {"setTargetSize", "(I)V", (void *) (set_det_target_size)},
    };
    registerNativeMethods(env, "com/tencent/yyds/YoloNcnn", det_method, sizeof(det_method) / sizeof(*det_method));
    registerNativeMethods(env, "com/tencent/yyds/PpOcrNcnn", ocr_method, sizeof(ocr_method) / sizeof(*ocr_method));
    LOGI("registerNativeMethods");

    return JNI_VERSION_1_4;
}


/** 以下是没啥卵用的静态注册，会被动态注册所覆盖 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_tencent_yyds_YoloNcnn_init_Landroid_content_res_AssetManager_2Ljava_lang_String_2(
        JNIEnv *env, jobject thiz, jobject mgr, jstring name) {
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_tencent_yyds_YoloNcnn_init_Ljava_lang_String_2Ljava_lang_String_2(JNIEnv *env,
                                                                              jobject thiz,
                                                                              jstring bin,
                                                                              jstring param) {
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_tencent_yyds_PpOcrNcnn_init_Landroid_content_res_AssetManager_2Ljava_lang_String_2(
        JNIEnv *env, jobject thiz, jobject mgr, jstring name) {
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_tencent_yyds_PpOcrNcnn_init_Ljava_lang_String_2Ljava_lang_String_2(JNIEnv *env,
                                                                             jobject thiz,
                                                                             jstring bin,
                                                                             jstring param) {
    return JNI_FALSE;
}
