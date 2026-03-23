package pyengine

import android.util.Log
import androidx.annotation.Keep
import uiautomator.AppProcess

/**
 * CPython 嵌入式桥接 - 替代 com.chaquo.python.*
 *
 * 通过 JNI 调用交叉编译的 libpython3.13.so，提供与 Chaquopy 等价的 API：
 * - initialize()  → Python.start()
 * - importModule() → Python.getInstance().getModule()
 * - PyObjectHandle.callAttr() → PyObject.callAttr()
 * - PyObjectHandle.close()    → PyObject.close()
 *
 * native 实现: jni/cpython_bridge.cpp
 */
@Keep
object CPythonBridge {

    private const val TAG = "CPythonBridge"

    /**
     * Python HOME 目录 (标准库、site-packages 所在位置)
     * 由 build 脚本交叉编译后部署到此路径
     */
    const val PYTHON_HOME = "/data/local/tmp/python3"

    /**
     * Python shim 模块目录 (pyengine.py 等兼容层)
     * 从 APK assets 或 SO 目录释放
     */
    val PYTHON_SHIMS_PATH = "${AppProcess.unzipTo}/python-shims"

    /**
     * 额外 PYTHONPATH（项目目录等）
     */
    val PYTHON_EXTRA_PATHS: String
        get() = "$PYTHON_SHIMS_PATH:${AppProcess.pyLibPath}"

    private var loaded = false

    /**
     * 加载 native 库（延迟加载，需在 InstallLoader 注册路径之后调用）
     * 加载顺序: libpython3.13.so (依赖) → libcpython_bridge.so (JNI桥接)
     */
    private fun loadNativeLibs() {
        if (loaded) return
        try {
            // 先加载 CPython 共享库（cpython_bridge 动态链接依赖它）
            System.loadLibrary("python3.13")
            Log.d(TAG, "libpython3.13.so 加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libpython3.13.so 加载失败: ${e.message}")
            Log.e(TAG, "请确认已运行 build.bat 编译 CPython 并部署到设备")
            throw e
        }
        try {
            System.loadLibrary("cpython_bridge")
            loaded = true
            Log.d(TAG, "libcpython_bridge.so 加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libcpython_bridge.so 加载失败: ${e.message}")
            throw e
        }
    }

    // ============================================================
    // 公开 API (对标 Chaquopy)
    // ============================================================

    /**
     * 初始化 CPython 解释器
     * 对标: Python.start(AndroidPlatform(ctx))
     */
    fun initialize(
        pythonHome: String = PYTHON_HOME,
        extraPaths: String = PYTHON_EXTRA_PATHS
    ) {
        // 延迟加载 native 库（此时 InstallLoader 应已注册路径）
        if (!loaded) loadNativeLibs()
        Log.d(TAG, "初始化 CPython, home=$pythonHome")
        nativeInit(pythonHome, extraPaths)
    }

    /**
     * 检查是否已初始化
     * 对标: Python.isStarted()
     */
    fun isInitialized(): Boolean {
        return loaded && nativeIsInitialized()
    }

    /**
     * 导入 Python 模块
     * 对标: Python.getInstance().getModule(name)
     *
     * @return PyObjectHandle 封装的模块句柄
     */
    fun importModule(name: String): PyObjectHandle {
        if (!isInitialized()) throw IllegalStateException("CPython not initialized")
        val handle = nativeImportModule(name)
        return PyObjectHandle(handle, name)
    }

    /**
     * 添加搜索路径到 sys.path
     */
    fun addPath(path: String) {
        if (isInitialized()) {
            nativeAddPath(path)
        }
    }

    /**
     * 执行 Python 代码字符串
     */
    fun execCode(code: String) {
        if (!isInitialized()) throw IllegalStateException("CPython not initialized")
        nativeExecCode(code)
    }

    /**
     * 关闭解释器
     */
    fun finalize() {
        if (isInitialized()) {
            nativeFinalize()
        }
    }

    // ============================================================
    // PyObjectHandle - 对标 com.chaquo.python.PyObject
    // ============================================================

    /**
     * Python 对象句柄，封装 native PyObject* 指针
     * 对标 Chaquopy 的 PyObject 类
     */
    class PyObjectHandle(
        private var handle: Long,
        private val debugName: String = "?"
    ) {
        private var closed = false

        /**
         * 调用方法，返回字符串
         * 对标: PyObject.callAttr(method, args...).toString()
         */
        fun callAttr(method: String, vararg args: String): String {
            checkValid()
            return nativeCallMethod(handle, method, args as Array<String>) ?: ""
        }

        /**
         * 调用方法，返回布尔值
         * 对标: PyObject.callAttr(method).toBoolean()
         */
        fun callAttrBool(method: String): Boolean {
            checkValid()
            return nativeCallMethodBool(handle, method)
        }

        /**
         * 调用无参无返回值方法
         * 对标: PyObject.callAttr(method) (忽略返回值)
         */
        fun callAttrVoid(method: String) {
            checkValid()
            nativeCallMethodVoid(handle, method)
        }

        /**
         * 释放引用
         * 对标: PyObject.close()
         */
        fun close() {
            if (!closed && handle != 0L) {
                nativeDecRef(handle)
                handle = 0
                closed = true
            }
        }

        /**
         * 检查句柄是否有效
         */
        fun isValid(): Boolean = !closed && handle != 0L

        private fun checkValid() {
            if (closed) throw IllegalStateException("PyObject handle already closed: $debugName")
            if (handle == 0L) throw IllegalStateException("PyObject handle is null: $debugName")
        }

        protected fun finalize() {
            if (!closed && handle != 0L) {
                Log.w(TAG, "PyObjectHandle($debugName) 未显式 close()，在 GC 中释放")
                close()
            }
        }

        override fun toString(): String = "PyObjectHandle($debugName, handle=$handle)"
    }

    // ============================================================
    // Native 方法声明
    // ============================================================

    private external fun nativeInit(pythonHome: String, extraPaths: String?)
    private external fun nativeIsInitialized(): Boolean
    private external fun nativeImportModule(moduleName: String): Long
    private external fun nativeCallMethod(objHandle: Long, methodName: String, args: Array<String>): String?
    private external fun nativeCallMethodBool(objHandle: Long, methodName: String): Boolean
    private external fun nativeCallMethodVoid(objHandle: Long, methodName: String)
    private external fun nativeDecRef(handle: Long)
    private external fun nativeExecCode(code: String)
    private external fun nativeAddPath(path: String)
    private external fun nativeFinalize()
}
