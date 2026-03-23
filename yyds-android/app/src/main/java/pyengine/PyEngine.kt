package pyengine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.tencent.yyds.BuildConfig
import com.topjohnwu.superuser.Shell
import common.BootService
import uiautomator.AppProcess
import uiautomator.ExtSystem
import uiautomator.util.DateUtil
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket


object PyEngine : WebSocketAsServer() {
    private const val TAG = "PyEngine"
    public const val enginePort = 61140
    private val isAppProcess = ExtSystem.uid() > 10000

    var startProjectTime:Long = 0
    var startProjectName:String = ""

    // ============================================================
    // 子进程隔离：每个项目运行在独立 app_process 中
    // 停止 = kill 进程，100% 确定性，零代码侵入
    // ============================================================
    @Volatile private var projectProcess: java.lang.Process? = null
    @Volatile private var projectProcessPid: Int = -1

    public fun isEngineOpen():Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", enginePort), 1500) // Timeout 2 seconds
                return true
            }
        } catch (e: IOException) {
            // Port is not open or connection failed
            return false
        }
    }

    val isOuterEngineRunning
        get() = try { !isAppProcess || isEngineOpen() } catch(e:Exception) {
            ExtSystem.printDebugError("判断外部引擎运行错误", e)
            false
        }

    @SuppressLint("SdCardPath")
    fun getProjectDir():String {
        return "/storage/emulated/0/Yyds.Py"
    }

    fun getPipBin(): String {
        ensurePip()
        val home = CPythonBridge.PYTHON_HOME
        return "HOME=$home XDG_CACHE_HOME=$home/.cache PYTHONHOME=$home LD_LIBRARY_PATH=$home/lib:\$LD_LIBRARY_PATH $home/bin/python3 -m pip"
    }

    private var pipEnsured = false

    /**
     * 确保 pip 可用。pip 已预装在 PYTHONHOME/lib/python3.x/site-packages/ 中，
     * 随 APK 内的 python3-stdlib.zip 一起释放，无需网络下载。
     * 此方法仅做验证，并确保 site-packages 目录权限正确。
     */
    fun ensurePip() {
        if (pipEnsured) return
        val home = CPythonBridge.PYTHON_HOME
        val pythonBin = "PYTHONHOME=$home LD_LIBRARY_PATH=$home/lib:\$LD_LIBRARY_PATH $home/bin/python3"

        // 确保 site-packages 目录存在且有写权限（pip install 需要写入）
        val sitePackages = "$home/lib/python3.13/site-packages"
        try {
            ExtSystem.shell("mkdir -p $sitePackages && chmod 755 $sitePackages")
        } catch (_: Exception) {}

        // 验证内置 pip 是否可用
        val check = try {
            ExtSystem.shell("$pythonBin -c \"import pip; print(pip.__version__)\" 2>&1")
        } catch (_: Exception) { "error" }

        if (check.contains("No module") || check.contains("ModuleNotFoundError") || check.contains("error")) {
            Log.e("PyEngine", "内置 pip 不可用: $check")
            Log.e("PyEngine", "请确认 python3-stdlib.zip 包含 site-packages/pip/")
            // 最后手段：尝试 ensurepip
            try {
                val result = ExtSystem.shell("$pythonBin -m ensurepip --upgrade 2>&1")
                Log.i("PyEngine", "ensurepip fallback result: $result")
            } catch (e: Exception) {
                Log.e("PyEngine", "ensurepip fallback failed: ${e.message}")
            }
        } else {
            Log.i("PyEngine", "内置 pip 已就绪: v$check")
        }
        pipEnsured = true
    }

    @SuppressLint("PrivateApi")
    fun initPythonParser(ctx:Context?=null) {
        if (CPythonBridge.isInitialized() || (isAppProcess && isOuterEngineRunning)) {
            return
        }
        // 创建这个工程目录，后面用到
        try {
          val projectDirFile = File(getProjectDir())
          if (!projectDirFile.exists()) {
              projectDirFile.mkdirs()
          }
            val noMediaFile = File(projectDirFile, ".nomedia")
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile()
            }
        } catch(ignore:Exception) {}

        if (!isAppProcess) {
            // 工作进程: 初始化 CPython
            // 1. 注册 native library 搜索路径，使 System.loadLibrary 能找到
            //    libpython3.13.so 和 libcpython_bridge.so
            try {
                ContextUtil.InstallLoader.install(
                    PyEngine::class.java.classLoader,
                    File(AppProcess.libPath)
                )
                ExtSystem.printDebugLog("native library path 已注册: ${AppProcess.libPath}")
            } catch (e: Throwable) {
                ExtSystem.printDebugError("注册 native library path 失败", e)
            }

            // 2. 从 APK assets 释放 python-shims (entry.py, pyengine.py)
            extractPythonShims(ctx)

            // 3. 确保 PYTHONHOME 存在（从 assets/python3-stdlib.zip 自动释放）
            ensurePythonHome(ctx)

            // 4. 初始化 CPython 解释器
            CPythonBridge.initialize(
                pythonHome = CPythonBridge.PYTHON_HOME,
                extraPaths = CPythonBridge.PYTHON_EXTRA_PATHS
            )
            ExtSystem.printDebugLog("CPython 初始化完成 (工作进程)")

            // 5. 导入 entry 模块，注册线程异常处理等运行时初始化
            //    print() 输出通过 C层fd重定向 → PyOut → WebSocket 到达 IDE 控制台
            try {
                val entryModule = CPythonBridge.importModule("entry")
                entryModule.close()
                ExtSystem.printDebugLog("entry 模块已加载")
            } catch (e: Throwable) {
                ExtSystem.printDebugError("entry 模块加载失败 (print输出可能无法到达IDE控制台)", e)
            }
        }
        // App进程不初始化CPython（通过RPC与工作进程通信）
    }

    /**
     * 从 APK assets 释放 python-shims 到设备（递归支持子目录）
     * assets/python-shims/ → /data/local/tmp/cache/python-shims/
     * 内置 yyds/ SDK 包也通过此机制释放
     */
    private fun extractPythonShims(ctx: Context?) {
        val targetDir = File(CPythonBridge.PYTHON_SHIMS_PATH)
        targetDir.mkdirs()
        val assetDir = "python-shims"
        try {
            val assetManager = ctx?.assets ?: AppProcess.appContext?.assets ?: return
            extractPythonShimsRecursive(assetManager, assetDir, targetDir)
            // AAPT2 会过滤与 Java 包名同名的 asset 文件（pyengine.py 与 pyengine 包冲突）
            // 直接内联写入，保证模块始终可用
            writePyengineShim(targetDir)
            ExtSystem.printDebugLog("python-shims 释放完成 (含内置 yyds SDK)")
        } catch (e: Exception) {
            ExtSystem.printDebugError("释放 python-shims 失败", e)
        }
    }

    /**
     * 递归释放 assets 子目录到目标目录
     */
    private fun extractPythonShimsRecursive(assetManager: android.content.res.AssetManager, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val entries = assetManager.list(assetPath) ?: return
        for (entry in entries) {
            val assetEntryPath = "$assetPath/$entry"
            val targetFile = File(targetDir, entry)
            val subEntries = assetManager.list(assetEntryPath)
            if (!subEntries.isNullOrEmpty()) {
                // 是子目录，递归释放
                extractPythonShimsRecursive(assetManager, assetEntryPath, targetFile)
            } else {
                // 是文件，复制
                assetManager.open(assetEntryPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun writePyengineShim(targetDir: File) {
        val content = """
import _yyds_bridge

class PyOut:
    @staticmethod
    def out(text):
        _yyds_bridge.log_out(str(text))
    @staticmethod
    def err(text):
        _yyds_bridge.log_err(str(text))
""".trimIndent()
        File(targetDir, "pyengine.py").writeText(content)
    }

    /**
     * 确保 PYTHONHOME 存在：从 APK assets/python3-stdlib.zip 自动释放
     * 使用 APK 构建时间戳作为版本标记，APK 更新后自动重新释放
     */
    private fun ensurePythonHome(ctx: Context?) {
        val pythonHome = File(CPythonBridge.PYTHON_HOME)
        val versionMarker = File(pythonHome, ".stdlib_version")
        val currentVersion = BuildConfig.BUILD_TIME_MILLIS.toString()

        // 已存在且版本匹配 → 跳过
        if (pythonHome.exists() && versionMarker.exists()) {
            val installedVersion = try { versionMarker.readText().trim() } catch (_: Exception) { "" }
            if (installedVersion == currentVersion) {
                ExtSystem.printDebugLog("PYTHONHOME 已是最新版本，跳过释放")
                return
            }
            ExtSystem.printDebugLog("PYTHONHOME 版本不匹配 ($installedVersion → $currentVersion)，重新释放")
        }

        val assetManager = ctx?.assets ?: AppProcess.appContext?.assets
        if (assetManager == null) {
            ExtSystem.printDebugLog("警告: 无法获取 AssetManager，无法释放 PYTHONHOME")
            ExtSystem.printDebugLog("请手动运行: adb push CPython-android/build/<arch>/install /data/local/tmp/python3")
            return
        }

        // 检查 assets 中是否有 python3-stdlib.zip
        val zipName = "python3-stdlib.zip"
        try {
            assetManager.open(zipName).use { } // 测试是否存在
        } catch (_: Exception) {
            ExtSystem.printDebugLog("警告: assets/$zipName 不存在，请先运行 Gradle packagePythonStdlib 任务")
            ExtSystem.printDebugLog("或手动运行: adb push CPython-android/build/<arch>/install /data/local/tmp/python3")
            return
        }

        ExtSystem.printDebugLog("正在从 assets/$zipName 释放 Python 标准库到 ${CPythonBridge.PYTHON_HOME} ...")
        val startTime = System.currentTimeMillis()
        try {
            // 清理旧目录（APK更新时确保干净）
            if (pythonHome.exists()) {
                pythonHome.deleteRecursively()
            }
            pythonHome.mkdirs()

            // 解压 zip
            assetManager.open(zipName).use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(pythonHome, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                zis.copyTo(out)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // 设置 bin/ 下可执行文件权限，并创建必要的符号链接（zip中排除了符号链接）
            val binDir = File(pythonHome, "bin")
            if (binDir.exists()) {
                binDir.listFiles()?.forEach { it.setExecutable(true, false) }
                // 创建 python3 -> python3.13 符号链接（pip 等工具依赖此名称）
                val python3Link = File(binDir, "python3")
                val python313 = File(binDir, "python3.13")
                if (!python3Link.exists() && python313.exists()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("ln", "-sf", "python3.13", python3Link.absolutePath)).waitFor()
                    } catch (_: Exception) {
                        // fallback: 直接复制
                        python313.copyTo(python3Link)
                        python3Link.setExecutable(true, false)
                    }
                }
                // 创建 pip -> pip3 符号链接（内置pip的可执行脚本）
                val pip3File = File(binDir, "pip3")
                val pipLink = File(binDir, "pip")
                if (pip3File.exists() && !pipLink.exists()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("ln", "-sf", "pip3", pipLink.absolutePath)).waitFor()
                    } catch (_: Exception) {
                        pip3File.copyTo(pipLink)
                        pipLink.setExecutable(true, false)
                    }
                }
            }

            // 创建 tmp 目录（CPython 需要 TMPDIR）
            File(pythonHome, "tmp").mkdirs()

            // 写入版本标记
            versionMarker.writeText(currentVersion)

            val elapsed = System.currentTimeMillis() - startTime
            ExtSystem.printDebugLog("Python 标准库释放完成，耗时 ${elapsed}ms")
        } catch (e: Exception) {
            ExtSystem.printDebugError("释放 Python 标准库失败", e)
        }
    }

    fun startOuterPyEngine() {
        ExtSystem.shell(AppProcess.getActivePyEngineCmd())
        ExtSystem.shell("chown -R ${ExtSystem.uid()}:${ExtSystem.uid()} /data/user/0/${BuildConfig.APPLICATION_ID}")
    }

    /**
     * 启动所有引擎进程
     * 默认先启动 native yyds.keep 守护进程，由它派生启动 yyds.auto 和 yyds.py
     * yyds.keep 是 native 进程（非 zygote），减少系统对脚本进程的影响
     */
    fun startAllEngines() {
        ExtSystem.printDebugLog("正在启动native守护进程(yyds.keep)，由其派生启动工作进程...")
        if (isAppProcess) {
            startAllEnginesFromApp()
        } else {
            startAllEnginesFromWorker()
        }
    }

    /**
     * App进程启动引擎
     * App进程(uid>10000)无法写入/data/local/tmp/cache（属于shell:shell），
     * 通过root权限复制SO文件并启动守护进程。
     *
     * 关键点：
     * 1. nativeLibraryDir可能不包含libyyds_keep.so（executable非shared lib），需从APK unzip
     * 2. libsu的stdout/stderr是pipe而非terminal，nohup不会自动重定向，后台进程写入pipe
     *    在libsu StreamGobbler结束后会收到SIGPIPE被杀。所以必须显式 >/dev/null 2>&1
     */
    private fun startAllEnginesFromApp() {
        val nativeLibDir = AppProcess.appContext?.applicationInfo?.nativeLibraryDir
        val codePath = AppProcess.appContext?.packageCodePath
        val abi = AppProcess.defaultABI
        ExtSystem.printDebugLog("App进程启动引擎, nativeLibDir=$nativeLibDir, codePath=$codePath, abi=$abi")

        try {
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                ExtSystem.printDebugLog("未获取root(status=${shell.status}), 无法启动引擎")
                return
            }

            // 阶段1: 准备SO文件和权限
            val setupCmds = mutableListOf<String>()
            setupCmds.add("mkdir -p ${AppProcess.libPath}")
            if (nativeLibDir != null) {
                setupCmds.add("cp -f $nativeLibDir/*.so ${AppProcess.libPath}/ 2>/dev/null")
            }
            if (codePath != null) {
                setupCmds.add("unzip -o $codePath 'lib/$abi/*.so' -d ${AppProcess.unzipTo} 2>/dev/null")
            }
            setupCmds.add("chown shell:shell -R ${AppProcess.unzipTo}")
            setupCmds.add("chmod +x ${AppProcess.nativeKeeperPath}")

            val ret = ArrayList<String>()
            shell.newJob().to(ret).add(*setupCmds.toTypedArray()).exec()
            ExtSystem.printDebugLog("SO文件准备完成, output=${ret.take(3)}")

            // 阶段2: 启动keeper - 显式重定向所有fd到/dev/null，防止SIGPIPE
            // libsu的shell是pipe-based，不是terminal。nohup只在terminal模式下重定向stdout。
            // 后台进程的stdout仍连接到libsu的pipe，StreamGobbler结束后pipe断裂→SIGPIPE杀死进程。
            // 解决：</dev/null >/dev/null 2>&1 确保所有fd都脱离libsu的pipe。
            val apkPath = codePath ?: AppProcess.apkPathShell
            val keeperPath = AppProcess.nativeKeeperPath
            // 直接启动native keeper，显式关闭所有fd
            // 阶段1已通过unzip提取+chmod +x，keeper二进制一定存在
            val launchCmd = "$keeperPath $apkPath ${AppProcess.LD_PRE_LOAD_CMD} </dev/null >/dev/null 2>&1 &"

            val ret2 = ArrayList<String>()
            shell.newJob().to(ret2).add(launchCmd).exec()
            ExtSystem.printDebugLog("keeper启动命令已执行: $launchCmd")

            // 验证keeper是否启动
            val ret3 = ArrayList<String>()
            shell.newJob().to(ret3).add("sleep 1; pidof yyds.keep").exec()
            ExtSystem.printDebugLog("keeper pid=${ret3.joinToString()}")

            shell.newJob().add("chown -R ${ExtSystem.uid()}:${ExtSystem.uid()} /data/user/0/${BuildConfig.APPLICATION_ID}").exec()
        } catch (e: Exception) {
            ExtSystem.printDebugError("App进程启动引擎失败", e)
        }
    }

    /**
     * 工作进程启动引擎：已有shell权限，直接执行
     */
    private fun startAllEnginesFromWorker() {
        try {
            val codePath = AppProcess.getCodePath()
            if (codePath != null && java.io.File(codePath).exists()) {
                uiautomator.util.ZipUtils.unZipSoFile(codePath, AppProcess.unzipTo)
                ExtSystem.shell("chown shell:shell -R ${AppProcess.unzipTo}")
                ExtSystem.shell("chmod +x ${AppProcess.nativeKeeperPath}")
            }
        } catch (e: Exception) {
            ExtSystem.printDebugError("释放SO文件失败", e)
        }
        ExtSystem.shell(AppProcess.getActiveEngineKeeperCmd())
        ExtSystem.shell("chown -R ${ExtSystem.uid()}:${ExtSystem.uid()} /data/user/0/${BuildConfig.APPLICATION_ID}")
        ExtSystem.printDebugLog("native守护进程启动命令已执行")
    }

    fun abortProject() {
        if (isAppProcess && isOuterEngineRunning) {
            EngineClient.sendStopProject()
            return
        }
        val proc = projectProcess ?: return
        val pid = projectProcessPid
        val runLostTime = (System.currentTimeMillis() - startProjectTime) / 1000
        BootService.sendCurrentRunningStatus(startProjectName, "结束工程 $startProjectName",
            "结束时间: ${DateUtil.getCommonDate()}, 执行用时${runLostTime}S")
        PyOut.out("正在停止项目 $startProjectName (PID=$pid)...\n")

        // Phase 1: SIGTERM — 给脚本一个优雅退出的机会
        proc.destroy()

        // Phase 2: 等待500ms，若仍存活则 SIGKILL（100%确定）
        Thread.sleep(500)
        if (proc.isAlive) {
            proc.destroyForcibly()
            PyOut.out("项目进程已强制终止 (SIGKILL)\n")
        } else {
            PyOut.out("项目进程已正常退出\n")
        }

        projectProcess = null
        projectProcessPid = -1
    }

    fun startProject(projectName: String) {
        if (isAppProcess && isOuterEngineRunning) {
            EngineClient.sendStartProject(projectName)
            return
        }
        // 先停止已有项目
        abortProject()

        startProjectTime = System.currentTimeMillis()
        startProjectName = projectName
        BootService.sendCurrentRunningStatus(projectName, "启动工程 $projectName",
            "运行开始时间: ${DateUtil.getCommonDate()}")

        try {
            // 构建子进程命令: 用 PyProcess 入口独立运行项目
            val codePath = AppProcess.getCodePath() ?: AppProcess.apkPathShell
            val cmd = "cd /data/local/tmp && " +
                "YYDS_SUBPROCESS=1 " +  // 告知 cpython_bridge 跳过 fd 重定向（输出走管道到父进程）
                "LD_LIBRARY_PATH=${AppProcess.libPath} " +
                "CLASSPATH=$codePath " +
                "exec app_process /system/bin pyengine.PyProcess '${projectName.replace("'", "'\\''")}'"

            // 通过 Runtime.exec 启动子进程，自动建立 stdout/stderr 管道
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            projectProcess = proc
            projectProcessPid = getPid(proc)

            ExtSystem.printDebugLog("项目子进程已启动: PID=$projectProcessPid, project=$projectName")
            PyOut.out("项目 $projectName 已在子进程启动 (PID=$projectProcessPid)\n")

            // 后台线程: 转发子进程 stdout → PyOut（供 WebSocket /log 端点使用）
            Thread({
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        PyOut.out(line + "\n")
                    }
                } catch (_: Exception) {}
            }, "pyrun-stdout").apply { isDaemon = true; start() }

            // 后台线程: 转发子进程 stderr → PyOut.err
            Thread({
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        PyOut.err(line + "\n")
                    }
                } catch (_: Exception) {}
            }, "pyrun-stderr").apply { isDaemon = true; start() }

            // 后台线程: 等待子进程结束并更新状态
            Thread({
                try {
                    val exitCode = proc.waitFor()
                    val elapsed = (System.currentTimeMillis() - startProjectTime) / 1000
                    if (exitCode == 0) {
                        PyOut.out("${DateUtil.getCommonDate()} 项目($projectName)已正常退出\n")
                    } else if (exitCode == 137 || exitCode == 9) {
                        PyOut.out("${DateUtil.getCommonDate()} 项目($projectName)已被终止\n")
                    } else {
                        PyOut.err("${DateUtil.getCommonDate()} 项目($projectName)异常退出, code=$exitCode\n")
                    }
                    BootService.sendCurrentRunningStatus(projectName,
                        "运行完毕 $projectName",
                        "${DateUtil.getCommonDate()}, 执行用时${elapsed}S")
                } catch (_: InterruptedException) {}
                // 清理引用（仅当仍是同一个进程时）
                if (projectProcess === proc) {
                    projectProcess = null
                    projectProcessPid = -1
                }
            }, "pyrun-wait").apply { isDaemon = true; start() }

        } catch (e: Exception) {
            ExtSystem.printDebugError("启动项目子进程失败", e)
            PyOut.err("启动项目子进程失败: ${e.message}\n")
            projectProcess = null
            projectProcessPid = -1
        }
    }

    /** prelude 环境是否已加载（首次 runCodeSnippet 时自动注入） */
    private var preludeLoaded = false

    fun runCodeSnippet(code: String) {
        try {
            // 代码片段仍在主进程内执行（轻量、交互式）
            if (CPythonBridge.isInitialized()) {
                // 首次执行时将 yyds_prelude 中的所有符号注入 __main__
                // 使 click/ocr/screenshot 等官方 API 及 os/time/json 等常用库无需 import
                if (!preludeLoaded) {
                    try {
                        CPythonBridge.execCode(
                            "exec('from yyds_prelude import *', vars(__import__(\"__main__\")))"
                        )
                        preludeLoaded = true
                        ExtSystem.printDebugLog("yyds_prelude 已注入 __main__ 命名空间")
                    } catch (e: Exception) {
                        ExtSystem.printDebugError("加载 yyds_prelude 失败，代码片段仍可运行但缺少预导入环境", e)
                    }
                }
                CPythonBridge.execCode(code)
            } else {
                PyOut.err("CPython 未初始化，无法执行代码片段\n")
            }
        } catch (e: java.lang.Exception) {
            ExtSystem.printDebugError("运行代码段出错", e)
        }
    }

    fun getProjectRunningStatus(): Pair<Boolean, String?>? {
        return if (isAppProcess && isOuterEngineRunning) {
            EngineClient.getProjectRunningStatus()
        } else {
            val proc = projectProcess
            if (proc != null && proc.isAlive) {
                true to startProjectName
            } else {
                false to null
            }
        }
    }

    /**
     * 获取 Process 的 PID（兼容 Android API 24+）
     */
    private fun getPid(process: java.lang.Process): Int {
        return try {
            // Java 9+ / Android API 33+
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as Long).toInt()
        } catch (_: Exception) {
            try {
                // Fallback: 反射读取内部 pid 字段
                val field = process.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getInt(process)
            } catch (_: Exception) {
                -1
            }
        }
    }

}