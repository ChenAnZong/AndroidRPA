/**
 * CPython JNI Bridge - 嵌入式 CPython 调用层
 *
 * 替代 Chaquopy 的 Java-Python 桥接，通过 CPython C API 实现：
 * - Python 解释器初始化/销毁
 * - 模块导入
 * - 函数调用 (callAttr)
 * - Python → Java 日志回调 (_yyds_bridge 内置模块)
 *
 * 对应 Kotlin 侧: pyengine.CPythonBridge
 */

#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include <unistd.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <android/log.h>

// CPython headers (from cross-compiled CPython)
// 路径由 CMakeLists.txt 中 target_include_directories 指定
#include <Python.h>

#define TAG "CPythonBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ============================================================
// 全局状态
// ============================================================

static JavaVM*  g_jvm = nullptr;
static jclass   g_pyout_class = nullptr;   // pyengine.PyOut
static jmethodID g_pyout_out = nullptr;    // PyOut.out(String)
static jmethodID g_pyout_err = nullptr;    // PyOut.err(String)
static int      g_initialized = 0;
static int      g_logcat_pipes[2] = {-1, -1};  // stdout/stderr → logcat 管道
static int      g_logcat_err_pipes[2] = {-1, -1};
static std::string g_tmp_dir;              // TMPDIR 路径
static std::string g_home_dir;             // HOME 路径

// ============================================================
// JNI 辅助
// ============================================================

static JNIEnv* get_env() {
    JNIEnv* env = nullptr;
    if (g_jvm) {
        int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            g_jvm->AttachCurrentThread(&env, nullptr);
        }
    }
    return env;
}

static void call_pyout(jmethodID method, const char* text) {
    JNIEnv* env = get_env();
    if (!env) {
        LOGD("call_pyout: env is null!");
        return;
    }
    if (!g_pyout_class) {
        LOGD("call_pyout: g_pyout_class is null!");
        return;
    }
    if (!method) {
        LOGD("call_pyout: method is null!");
        return;
    }
    jstring jtext = env->NewStringUTF(text);
    env->CallStaticVoidMethod(g_pyout_class, method, jtext);
    if (env->ExceptionCheck()) {
        LOGD("call_pyout: JNI exception occurred for text: %s", text);
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(jtext);
}

// ============================================================
// C层 stdout/stderr → Android logcat 重定向
//
// 问题：Python层的sys.stdout重定向只能捕获Python print()输出，
//       但native C扩展（numpy, pillow, OpenCV等）直接写C的stdout/stderr，
//       这些输出会完全丢失。Chaquopy通过fd重定向解决此问题。
//
// 方案：用pipe()创建管道，dup2()替换fd 1/2，后台线程读取并转发到logcat
// ============================================================

static void* logcat_thread_func(void* arg) {
    int is_stderr = (intptr_t)arg;
    int fd = is_stderr ? g_logcat_err_pipes[0] : g_logcat_pipes[0];
    int prio = is_stderr ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
    const char* tag = is_stderr ? "python.stderr" : "python.stdout";

    char buf[1024];
    std::string line;
    ssize_t n;

    while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        line += buf;
        // 按行输出到logcat
        size_t pos;
        while ((pos = line.find('\n')) != std::string::npos) {
            std::string single = line.substr(0, pos);
            if (!single.empty()) {
                __android_log_write(prio, tag, single.c_str());
                // 不调 call_pyout — Python 层 ConsoleOutputStream 已负责转发到 PyOut
                // C 管道仅捕获 native C 扩展的 stdout/stderr 写入 logcat
            }
            line = line.substr(pos + 1);
        }
    }
    // 处理最后一行（无换行符）
    if (!line.empty()) {
        __android_log_write(prio, tag, line.c_str());
    }
    return nullptr;
}

/**
 * 重定向C层 stdout/stderr 到 logcat
 * 必须在 Py_Initialize 之前调用
 */
static void setup_fd_redirect() {
    // 子进程模式: stdout/stderr 已被父进程管道捕获，不能重定向
    if (getenv("YYDS_SUBPROCESS")) {
        LOGD("跳过 fd 重定向 (子进程模式，输出由父进程捕获)");
        return;
    }
    // stdout
    if (pipe(g_logcat_pipes) == 0) {
        dup2(g_logcat_pipes[1], STDOUT_FILENO);
        close(g_logcat_pipes[1]);
        pthread_t tid;
        pthread_create(&tid, nullptr, logcat_thread_func, (void*)0);
        pthread_detach(tid);
    }
    // stderr
    if (pipe(g_logcat_err_pipes) == 0) {
        dup2(g_logcat_err_pipes[1], STDERR_FILENO);
        close(g_logcat_err_pipes[1]);
        pthread_t tid;
        pthread_create(&tid, nullptr, logcat_thread_func, (void*)1);
        pthread_detach(tid);
    }
}

// ============================================================
// Android 环境变量适配
//
// Android 缺少许多 Linux 标准环境，直接影响 Python 标准库：
//   TMPDIR  → tempfile 模块 (默认用/tmp，Android无此目录)
//   HOME    → pathlib, pip, 许多第三方库
//   LANG    → locale 模块 (Android无locale数据库)
//   TZ      → datetime/time 模块
//   PYTHONDONTWRITEBYTECODE → 避免在只读目录写.pyc
// ============================================================

static void setup_android_env(const char* home) {
    // TMPDIR: tempfile模块依赖此变量，Android没有/tmp
    g_tmp_dir = std::string(home) + "/tmp";
    mkdir(g_tmp_dir.c_str(), 0755);
    setenv("TMPDIR", g_tmp_dir.c_str(), 1);  // 1=强制覆盖（Android默认无TMPDIR或路径不可用）

    // HOME: 许多库依赖HOME (~/.cache, ~/.config等)
    g_home_dir = std::string(home);
    setenv("HOME", g_home_dir.c_str(), 1);  // 强制覆盖（Android root进程HOME=/，不可写）

    // LANG/LC_ALL: Android缺少locale数据库，设置UTF-8安全默认值
    // 防止 locale.getpreferredencoding() 等调用崩溃
    setenv("LANG", "C.UTF-8", 0);
    setenv("LC_ALL", "C.UTF-8", 0);

    // TZ: 从Android系统属性读取时区
    char tz_prop[PROP_VALUE_MAX] = {0};
    __system_property_get("persist.sys.timezone", tz_prop);
    if (tz_prop[0]) {
        setenv("TZ", tz_prop, 0);
        LOGD("TZ=%s (from system property)", tz_prop);
    }

    // stdout/stderr 无缓冲，确保 print() 输出立即 flush 到 fd 管道
    setenv("PYTHONUNBUFFERED", "1", 1);

    // 避免写.pyc到可能只读的目录
    setenv("PYTHONDONTWRITEBYTECODE", "1", 0);

    LOGD("Android env: TMPDIR=%s HOME=%s LANG=%s",
         g_tmp_dir.c_str(), g_home_dir.c_str(), getenv("LANG"));
}

// ============================================================
// Python 内置模块: _yyds_bridge
// 提供 log_out / log_err，供 Python 侧 pyengine.PyOut shim 调用
// ============================================================

static PyObject* bridge_log_out(PyObject* self, PyObject* args) {
    const char* text;
    if (!PyArg_ParseTuple(args, "s", &text))
        return nullptr;
    call_pyout(g_pyout_out, text);
    Py_RETURN_NONE;
}

static PyObject* bridge_log_err(PyObject* self, PyObject* args) {
    const char* text;
    if (!PyArg_ParseTuple(args, "s", &text))
        return nullptr;
    call_pyout(g_pyout_err, text);
    Py_RETURN_NONE;
}

static PyMethodDef g_bridge_methods[] = {
    {"log_out", bridge_log_out, METH_VARARGS, "Send stdout to Java PyOut.out()"},
    {"log_err", bridge_log_err, METH_VARARGS, "Send stderr to Java PyOut.err()"},
    {nullptr, nullptr, 0, nullptr}
};

static PyModuleDef g_bridge_module = {
    PyModuleDef_HEAD_INIT,
    "_yyds_bridge",                    // 模块名
    "CPython-Java bridge module",      // 文档
    -1,
    g_bridge_methods
};

PyMODINIT_FUNC PyInit__yyds_bridge(void) {
    return PyModule_Create(&g_bridge_module);
}

// ============================================================
// Python 错误处理辅助
// ============================================================

/**
 * 获取当前 Python 异常信息并清除
 * 调用者负责释放返回的字符串
 */
static std::string get_python_error() {
    if (!PyErr_Occurred()) return "";

    PyObject *ptype, *pvalue, *ptraceback;
    PyErr_Fetch(&ptype, &pvalue, &ptraceback);
    PyErr_NormalizeException(&ptype, &pvalue, &ptraceback);

    std::string msg = "PythonError";
    if (pvalue) {
        PyObject* str = PyObject_Str(pvalue);
        if (str) {
            const char* cstr = PyUnicode_AsUTF8(str);
            if (cstr) msg = cstr;
            Py_DECREF(str);
        }
    }

    // 尝试获取完整 traceback
    if (ptraceback) {
        PyObject* tb_module = PyImport_ImportModule("traceback");
        if (tb_module) {
            PyObject* format_func = PyObject_GetAttrString(tb_module, "format_exception");
            if (format_func) {
                PyObject* tb_args = PyTuple_Pack(3,
                    ptype ? ptype : Py_None,
                    pvalue ? pvalue : Py_None,
                    ptraceback ? ptraceback : Py_None);
                PyObject* tb_list = PyObject_CallObject(format_func, tb_args);
                if (tb_list) {
                    PyObject* tb_str = PyUnicode_Join(PyUnicode_FromString(""), tb_list);
                    if (tb_str) {
                        const char* cstr = PyUnicode_AsUTF8(tb_str);
                        if (cstr) msg = cstr;
                        Py_DECREF(tb_str);
                    }
                    Py_DECREF(tb_list);
                }
                Py_XDECREF(tb_args);
                Py_DECREF(format_func);
            }
            Py_DECREF(tb_module);
        }
    }

    Py_XDECREF(ptype);
    Py_XDECREF(pvalue);
    Py_XDECREF(ptraceback);

    return msg;
}

/**
 * 抛出 Java 异常并附带 Python 错误信息
 */
static void throw_python_exception(JNIEnv* env) {
    std::string msg = get_python_error();
    if (!msg.empty()) {
        jclass exc_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exc_class, msg.c_str());
    }
}

// ============================================================
// JNI 导出函数
// ============================================================

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 缓存 PyOut 类和方法引用
    jclass local_class = env->FindClass("pyengine/PyOut");
    if (local_class) {
        g_pyout_class = (jclass) env->NewGlobalRef(local_class);
        g_pyout_out = env->GetStaticMethodID(g_pyout_class, "out", "(Ljava/lang/String;)V");
        g_pyout_err = env->GetStaticMethodID(g_pyout_class, "err", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(local_class);
    }

    LOGD("JNI_OnLoad: PyOut class=%p, out=%p, err=%p", g_pyout_class, g_pyout_out, g_pyout_err);
    return JNI_VERSION_1_6;
}

/**
 * 初始化 CPython 解释器
 *
 * @param pythonHome  PYTHONHOME 路径 (如 /data/local/tmp/python3)
 * @param extraPaths  额外的 PYTHONPATH 条目，用 ":" 分隔
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeInit(JNIEnv* env, jclass clazz,
                                        jstring pythonHome,
                                        jstring extraPaths) {
    if (g_initialized) {
        LOGD("CPython 已初始化，跳过");
        return;
    }

    const char* home = env->GetStringUTFChars(pythonHome, nullptr);
    const char* paths = extraPaths ? env->GetStringUTFChars(extraPaths, nullptr) : "";

    LOGD("初始化 CPython, home=%s, extraPaths=%s", home, paths);

    // === Android 适配: C层初始化 (Py_Initialize 之前) ===
    setup_fd_redirect();
    setup_android_env(home);

    // 注册内置 _yyds_bridge 模块（必须在 Py_Initialize 之前）
    PyImport_AppendInittab("_yyds_bridge", PyInit__yyds_bridge);

    // 使用 PyConfig 配置（CPython 3.8+）
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);

    // 设置 PYTHONHOME
    PyConfig_SetBytesString(&config, &config.home, home);

    // 禁用不需要的功能
    config.install_signal_handlers = 0;
    config.write_bytecode = 0;
    config.site_import = 1;

    // 设置搜索路径
    config.module_search_paths_set = 1;

    // 标准库路径
    std::string lib_path = std::string(home) + "/lib/python3.13";
    std::string lib_dynload = lib_path + "/lib-dynload";
    std::string site_packages = lib_path + "/site-packages";

    wchar_t* wpath;
    // 添加标准库路径
    wpath = Py_DecodeLocale(lib_path.c_str(), nullptr);
    PyWideStringList_Append(&config.module_search_paths, wpath);
    PyMem_RawFree(wpath);

    wpath = Py_DecodeLocale(lib_dynload.c_str(), nullptr);
    PyWideStringList_Append(&config.module_search_paths, wpath);
    PyMem_RawFree(wpath);

    wpath = Py_DecodeLocale(site_packages.c_str(), nullptr);
    PyWideStringList_Append(&config.module_search_paths, wpath);
    PyMem_RawFree(wpath);

    // 添加额外路径
    if (paths && paths[0]) {
        std::string extra(paths);
        size_t start = 0, end;
        while ((end = extra.find(':', start)) != std::string::npos) {
            std::string p = extra.substr(start, end - start);
            if (!p.empty()) {
                wpath = Py_DecodeLocale(p.c_str(), nullptr);
                PyWideStringList_Append(&config.module_search_paths, wpath);
                PyMem_RawFree(wpath);
            }
            start = end + 1;
        }
        // 最后一段
        std::string p = extra.substr(start);
        if (!p.empty()) {
            wpath = Py_DecodeLocale(p.c_str(), nullptr);
            PyWideStringList_Append(&config.module_search_paths, wpath);
            PyMem_RawFree(wpath);
        }
    }

    PyStatus status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);

    if (PyStatus_Exception(status)) {
        LOGE("CPython 初始化失败: %s", status.err_msg ? status.err_msg : "unknown");
        env->ReleaseStringUTFChars(pythonHome, home);
        if (extraPaths) env->ReleaseStringUTFChars(extraPaths, paths);

        jclass exc = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exc, status.err_msg ? status.err_msg : "Py_Initialize failed");
        return;
    }

    g_initialized = 1;
    LOGD("CPython 解释器初始化完成");

    // === Android 适配: 初始化后运行 bootstrap ===
    // _android_bootstrap 模块负责:
    //   - SSL证书配置 (certifi / Android CA)
    //   - tempfile目录修正
    //   - multiprocessing禁用fork
    //   - locale安全默认值
    //   - 其他Android兼容性补丁
    PyGILState_STATE gstate = PyGILState_Ensure();
    PyObject* bootstrap = PyImport_ImportModule("_android_bootstrap");
    if (bootstrap) {
        LOGD("_android_bootstrap 加载成功");
        Py_DECREF(bootstrap);
    } else {
        // bootstrap 失败不是致命错误，但要输出警告
        LOGE("_android_bootstrap 加载失败 (非致命，部分Android适配可能缺失)");
        PyErr_Print();
        PyErr_Clear();
    }
    PyGILState_Release(gstate);

    // 释放 GIL，允许其他线程（Ktor HTTP handler）通过 PyGILState_Ensure 获取
    // Py_Initialize 后主线程持有 GIL，不释放的话其他线程永远阻塞
    PyEval_SaveThread();

    env->ReleaseStringUTFChars(pythonHome, home);
    if (extraPaths) env->ReleaseStringUTFChars(extraPaths, paths);
}

/**
 * 检查 CPython 是否已初始化
 */
JNIEXPORT jboolean JNICALL
Java_pyengine_CPythonBridge_nativeIsInitialized(JNIEnv* env, jclass clazz) {
    return g_initialized ? JNI_TRUE : JNI_FALSE;
}

/**
 * 导入 Python 模块
 * @return 模块的 PyObject* 句柄 (作为 jlong)
 */
JNIEXPORT jlong JNICALL
Java_pyengine_CPythonBridge_nativeImportModule(JNIEnv* env, jclass clazz,
                                                jstring moduleName) {
    if (!g_initialized) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "CPython not initialized");
        return 0;
    }

    const char* name = env->GetStringUTFChars(moduleName, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    PyObject* module = PyImport_ImportModule(name);
    if (!module) {
        LOGE("导入模块失败: %s", name);
        throw_python_exception(env);
        PyGILState_Release(gstate);
        env->ReleaseStringUTFChars(moduleName, name);
        return 0;
    }

    LOGD("导入模块成功: %s -> %p", name, module);
    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(moduleName, name);

    return (jlong)(intptr_t)module;
}

/**
 * 调用 Python 对象的方法，参数为字符串数组，返回字符串
 * 对应 Chaquopy 的 PyObject.callAttr(method, args...)
 */
JNIEXPORT jstring JNICALL
Java_pyengine_CPythonBridge_nativeCallMethod(JNIEnv* env, jclass clazz,
                                              jlong objHandle,
                                              jstring methodName,
                                              jobjectArray args) {
    if (!g_initialized || !objHandle) {
        jclass exc = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exc, "CPython not initialized or null handle");
        return nullptr;
    }

    PyObject* obj = (PyObject*)(intptr_t)objHandle;
    const char* method = env->GetStringUTFChars(methodName, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    // 构建参数元组
    int argc = args ? env->GetArrayLength(args) : 0;
    PyObject* py_args = PyTuple_New(argc);
    for (int i = 0; i < argc; i++) {
        jstring jarg = (jstring) env->GetObjectArrayElement(args, i);
        if (jarg) {
            const char* carg = env->GetStringUTFChars(jarg, nullptr);
            PyTuple_SetItem(py_args, i, PyUnicode_FromString(carg));  // steals ref
            env->ReleaseStringUTFChars(jarg, carg);
        } else {
            Py_INCREF(Py_None);
            PyTuple_SetItem(py_args, i, Py_None);
        }
        env->DeleteLocalRef(jarg);
    }

    // 获取方法
    PyObject* py_method = PyObject_GetAttrString(obj, method);
    if (!py_method) {
        LOGE("方法不存在: %s", method);
        throw_python_exception(env);
        Py_DECREF(py_args);
        PyGILState_Release(gstate);
        env->ReleaseStringUTFChars(methodName, method);
        return nullptr;
    }

    // 调用
    PyObject* result = PyObject_CallObject(py_method, py_args);
    Py_DECREF(py_method);
    Py_DECREF(py_args);

    if (!result) {
        std::string err = get_python_error();
        LOGE("调用 %s 失败: %s", method, err.c_str());
        // 检查是否是 SystemExit（项目正常退出）
        // 注意：get_python_error() 已经 fetch 了异常
        jclass exc = env->FindClass("java/lang/RuntimeException");
        std::string full_msg = std::string("PyException: ") + err;
        env->ThrowNew(exc, full_msg.c_str());
        PyGILState_Release(gstate);
        env->ReleaseStringUTFChars(methodName, method);
        return nullptr;
    }

    // 转换结果为字符串
    jstring jresult = nullptr;
    if (result == Py_None) {
        jresult = env->NewStringUTF("");
    } else {
        PyObject* str = PyObject_Str(result);
        if (str) {
            const char* cstr = PyUnicode_AsUTF8(str);
            jresult = env->NewStringUTF(cstr ? cstr : "");
            Py_DECREF(str);
        }
    }

    Py_DECREF(result);
    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(methodName, method);

    return jresult;
}

/**
 * 调用方法并返回布尔值
 */
JNIEXPORT jboolean JNICALL
Java_pyengine_CPythonBridge_nativeCallMethodBool(JNIEnv* env, jclass clazz,
                                                  jlong objHandle,
                                                  jstring methodName) {
    if (!g_initialized || !objHandle) return JNI_FALSE;

    PyObject* obj = (PyObject*)(intptr_t)objHandle;
    const char* method = env->GetStringUTFChars(methodName, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    PyObject* py_method = PyObject_GetAttrString(obj, method);
    if (!py_method) {
        PyErr_Clear();
        PyGILState_Release(gstate);
        env->ReleaseStringUTFChars(methodName, method);
        return JNI_FALSE;
    }

    PyObject* result = PyObject_CallNoArgs(py_method);
    Py_DECREF(py_method);

    jboolean ret = JNI_FALSE;
    if (result) {
        ret = PyObject_IsTrue(result) ? JNI_TRUE : JNI_FALSE;
        Py_DECREF(result);
    } else {
        PyErr_Clear();
    }

    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(methodName, method);
    return ret;
}

/**
 * 调用无参方法，无返回值
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeCallMethodVoid(JNIEnv* env, jclass clazz,
                                                  jlong objHandle,
                                                  jstring methodName) {
    if (!g_initialized || !objHandle) return;

    PyObject* obj = (PyObject*)(intptr_t)objHandle;
    const char* method = env->GetStringUTFChars(methodName, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    PyObject* py_method = PyObject_GetAttrString(obj, method);
    if (py_method) {
        PyObject* result = PyObject_CallNoArgs(py_method);
        if (!result) {
            std::string err = get_python_error();
            LOGE("调用 %s 失败: %s", method, err.c_str());
        }
        Py_XDECREF(result);
        Py_DECREF(py_method);
    } else {
        PyErr_Clear();
        LOGE("方法不存在: %s", method);
    }

    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(methodName, method);
}

/**
 * 释放 Python 对象引用
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeDecRef(JNIEnv* env, jclass clazz, jlong handle) {
    if (handle && g_initialized) {
        PyGILState_STATE gstate = PyGILState_Ensure();
        Py_DECREF((PyObject*)(intptr_t)handle);
        PyGILState_Release(gstate);
    }
}

/**
 * 执行 Python 代码字符串
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeExecCode(JNIEnv* env, jclass clazz, jstring code) {
    if (!g_initialized) return;

    const char* ccode = env->GetStringUTFChars(code, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    int ret = PyRun_SimpleString(ccode);
    if (ret != 0) {
        LOGE("执行代码失败");
        PyErr_Clear();
    }

    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(code, ccode);
}

/**
 * 添加搜索路径到 sys.path
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeAddPath(JNIEnv* env, jclass clazz, jstring path) {
    if (!g_initialized) return;

    const char* cpath = env->GetStringUTFChars(path, nullptr);

    PyGILState_STATE gstate = PyGILState_Ensure();

    PyObject* sys_module = PyImport_ImportModule("sys");
    if (sys_module) {
        PyObject* sys_path = PyObject_GetAttrString(sys_module, "path");
        if (sys_path && PyList_Check(sys_path)) {
            PyObject* py_path = PyUnicode_FromString(cpath);
            PyList_Append(sys_path, py_path);
            Py_DECREF(py_path);
        }
        Py_XDECREF(sys_path);
        Py_DECREF(sys_module);
    }

    PyGILState_Release(gstate);
    env->ReleaseStringUTFChars(path, cpath);
}

/**
 * 关闭 CPython 解释器
 */
JNIEXPORT void JNICALL
Java_pyengine_CPythonBridge_nativeFinalize(JNIEnv* env, jclass clazz) {
    if (g_initialized) {
        LOGD("正在关闭 CPython...");
        Py_FinalizeEx();
        g_initialized = 0;
        LOGD("CPython 已关闭");
    }
}

}  // extern "C"
