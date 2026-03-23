package pyengine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import uiautomator.AppProcess
import uiautomator.ExtSystem
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * APK打包助手 — 将Python脚本项目打包为独立APK
 *
 * 打包流程:
 * 1. 读取模板APK（当前安装的Yyds.Auto APK）
 * 2. 注入脚本文件到 assets/project/
 * 3. 注入打包配置 assets/pack_config.json
 * 4. 修改resources.arsc中的应用名
 * 5. 替换应用图标（res/mipmap-* 中的ic_launcher.png）
 * 6. V1签名
 * 7. 输出最终APK
 *
 * 支持自定义包名（修改后的APK可与主App共存安装）
 */
object ApkPackageHelper {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private const val TAG = "ApkPackageHelper"
    private const val ASSETS_PROJECT_DIR = "assets/project/"
    private const val ASSETS_PACK_CONFIG = "assets/pack_config.json"
    private const val ORIGINAL_APP_NAME = "Yyds.Auto"
    private const val ORIGINAL_PACKAGE_NAME = "com.yyds.auto"

    // 输出目录
    val outputDir: String get() = PyEngine.getProjectDir() + "/output"

    /**
     * 打包配置
     */
    data class PackConfig(
        val appName: String,                // 自定义应用名
        val projectName: String,            // 项目文件夹名
        val version: String = "1.0",        // 版本号
        val packageName: String? = null,    // 自定义包名（null则保持原包名com.yyds.auto）
        val iconPath: String? = null,       // 自定义图标路径（PNG），null则使用默认
        // ---- 运行行为（打包时预设，用户可在Runner中覆盖） ----
        val autoStart: Boolean = false,     // 兼容旧字段（旧版pack_config.json可能只有此字段）
        val autoRunOnOpen: Boolean = false,  // 打开应用时自动运行脚本
        val keepScreenOn: Boolean = true,   // 运行时保持屏幕常亮
        val showLog: Boolean = true,        // 显示运行日志面板
        val showFloating: Boolean = false,  // 显示悬浮控制按钮
        val exitOnScriptStop: Boolean = false, // 脚本停止后自动退出应用
        val encryptScripts: Boolean = false, // 是否加密脚本
        val encryptionSalt: String? = null  // 加密盐值（Base64），encryptScripts=true 时由打包器自动生成
    ) : java.io.Serializable {
        /** autoRunOnOpen 优先，向下兼容旧的 autoStart 字段 */
        fun shouldAutoRun(): Boolean = autoRunOnOpen || autoStart
    }

    data class BuildResult(
        val success: Boolean,
        val outputPath: String? = null,
        val fileSize: Long = 0,
        val error: String? = null,
        val durationMs: Long = 0
    )

    /**
     * 执行APK打包 — 主入口
     *
     * @param config 打包配置
     * @return 打包结果
     */
    fun buildApk(config: PackConfig): BuildResult {
        val startTime = System.currentTimeMillis()
        ExtSystem.printDebugLog("$TAG: 开始打包APK, config=$config")

        try {
            // 1. 验证参数
            val templateApkPath = AppProcess.getCodePath()
                ?: return BuildResult(false, error = "无法获取模板APK路径")

            if (!File(templateApkPath).exists()) {
                return BuildResult(false, error = "模板APK不存在: $templateApkPath")
            }

            val projectDir = File(PyEngine.getProjectDir(), config.projectName)
            if (!projectDir.exists() || !projectDir.isDirectory) {
                return BuildResult(false, error = "项目目录不存在: ${projectDir.absolutePath}")
            }

            // 2. 准备输出目录
            val outDir = File(outputDir)
            if (!outDir.exists()) outDir.mkdirs()

            val safeAppName = config.appName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]"), "_")
            val outputFileName = "${safeAppName}_v${config.version}.apk"
            val unsignedApk = File(outDir, "unsigned_$outputFileName")
            val signedApk = File(outDir, outputFileName)

            // 3. 加密处理（如需要）
            var effectiveProjectDir = projectDir
            var effectiveConfig = config
            var encTempDir: File? = null

            if (config.encryptScripts) {
                ExtSystem.printDebugLog("$TAG: 启用脚本加密...")
                val salt = ScriptEncryptor.generateSalt()
                encTempDir = File(outDir, ".enc_temp_${System.currentTimeMillis()}")
                val encCount = ScriptEncryptor.encryptProject(projectDir, encTempDir, salt)
                ExtSystem.printDebugLog("$TAG: 已加密 $encCount 个脚本文件")

                effectiveProjectDir = encTempDir
                effectiveConfig = config.copy(
                    encryptionSalt = ScriptEncryptor.saltToBase64(salt)
                )
            }

            try {
                // 4. 构建未签名APK
                buildUnsignedApk(templateApkPath, unsignedApk.absolutePath, effectiveProjectDir, effectiveConfig)
                ExtSystem.printDebugLog("$TAG: 未签名APK构建完成: ${unsignedApk.length()} bytes")
            } finally {
                // 清理加密临时目录
                encTempDir?.deleteRecursively()
            }

            // 5. V1签名
            val unalignedApk = File(outDir, "unaligned_$outputFileName")
            ApkV1Signer.signApk(unsignedApk.absolutePath, unalignedApk.absolutePath)
            ExtSystem.printDebugLog("$TAG: APK签名完成: ${unalignedApk.length()} bytes")

            // 6. ZIP对齐（STORED条目4字节对齐，.so文件4096字节页对齐）
            ApkV1Signer.zipalignApk(unalignedApk.absolutePath, signedApk.absolutePath)
            ExtSystem.printDebugLog("$TAG: ZIP对齐完成: ${signedApk.length()} bytes")

            // 7. 删除中间文件
            unsignedApk.delete()
            unalignedApk.delete()

            val duration = System.currentTimeMillis() - startTime
            ExtSystem.printDebugLog("$TAG: 打包完成! 输出: ${signedApk.absolutePath}, 耗时: ${duration}ms")

            return BuildResult(
                success = true,
                outputPath = signedApk.absolutePath,
                fileSize = signedApk.length(),
                durationMs = duration
            )
        } catch (e: Exception) {
            ExtSystem.printDebugError("$TAG: 打包失败", e)
            return BuildResult(
                false,
                error = "${e.javaClass.simpleName}: ${e.message}",
                durationMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * 构建未签名APK
     */
    private fun buildUnsignedApk(
        templatePath: String,
        outputPath: String,
        projectDir: File,
        config: PackConfig
    ) {
        val templateZip = ZipFile(templatePath)
        val zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(outputPath)))

        try {
            val processedEntries = mutableSetOf<String>()

            // 遍历模板APK的所有条目
            val entries = templateZip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                // 跳过旧的签名文件（将用新签名替换）
                if (name.startsWith("META-INF/")) continue

                // 跳过已处理的条目
                if (processedEntries.contains(name)) continue
                processedEntries.add(name)

                val inputBytes = templateZip.getInputStream(entry).use { it.readBytes() }

                when {
                    // 修改 AndroidManifest.xml：替换包名
                    name == "AndroidManifest.xml" && !config.packageName.isNullOrBlank()
                        && config.packageName != ORIGINAL_PACKAGE_NAME -> {
                        val modified = BinaryXmlEditor.editManifestPackageName(
                            inputBytes, ORIGINAL_PACKAGE_NAME, config.packageName
                        )
                        writeZipEntry(zos, name, modified, ZipEntry.STORED)
                        ExtSystem.printDebugLog("$TAG: 已修改包名 '$ORIGINAL_PACKAGE_NAME' -> '${config.packageName}'")
                    }

                    // 修改resources.arsc：替换应用名（+包名）
                    name == "resources.arsc" -> {
                        var modified = BinaryXmlEditor.editResourcesAppName(
                            inputBytes, ORIGINAL_APP_NAME, config.appName
                        )
                        // resources.arsc 中也可能包含包名字符串，一并替换
                        if (!config.packageName.isNullOrBlank() && config.packageName != ORIGINAL_PACKAGE_NAME) {
                            modified = BinaryXmlEditor.editResourcesAppName(
                                modified, ORIGINAL_PACKAGE_NAME, config.packageName
                            )
                        }
                        writeZipEntry(zos, name, modified, ZipEntry.STORED)
                        ExtSystem.printDebugLog("$TAG: 已修改应用名 '$ORIGINAL_APP_NAME' -> '${config.appName}'")
                    }

                    // 替换应用图标
                    config.iconPath != null && isLauncherIcon(name) -> {
                        val iconFile = File(config.iconPath)
                        if (iconFile.exists()) {
                            val iconSize = getLauncherIconSize(name)
                            val resizedIcon = resizeIcon(config.iconPath, iconSize)
                            if (resizedIcon != null) {
                                writeZipEntry(zos, name, resizedIcon, ZipEntry.STORED)
                                ExtSystem.printDebugLog("$TAG: 已替换图标 $name (${iconSize}x${iconSize})")
                            } else {
                                writeZipEntry(zos, name, inputBytes, entry.method)
                            }
                        } else {
                            writeZipEntry(zos, name, inputBytes, entry.method)
                        }
                    }

                    // 其他文件原样复制
                    else -> {
                        // .so 文件强制 DEFLATED，避免 STORED 模式下 ZIP 对齐丢失导致安装失败
                        val method = if (name.endsWith(".so")) ZipEntry.DEFLATED else entry.method
                        writeZipEntry(zos, name, inputBytes, method)
                    }
                }
            }

            // 注入脚本项目文件到 assets/project/
            injectProjectFiles(zos, projectDir, config)

            // 注入打包配置
            val packConfigJson = gson.toJson(config)
            writeZipEntry(zos, ASSETS_PACK_CONFIG, packConfigJson.toByteArray(Charsets.UTF_8), ZipEntry.DEFLATED)
            ExtSystem.printDebugLog("$TAG: 已注入打包配置")

        } finally {
            zos.close()
            templateZip.close()
        }
    }

    /**
     * 注入项目脚本文件
     */
    private fun injectProjectFiles(zos: ZipOutputStream, projectDir: File, config: PackConfig) {
        val files = projectDir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue // 跳过隐藏文件
            if (file.name.endsWith(".yyp.zip")) continue // 跳过打包zip
            if (file.isDirectory) {
                injectDirectory(zos, file, ASSETS_PROJECT_DIR + file.name + "/")
            } else {
                val data = file.readBytes()
                writeZipEntry(zos, ASSETS_PROJECT_DIR + file.name, data, ZipEntry.DEFLATED)
            }
        }
        ExtSystem.printDebugLog("$TAG: 已注入项目文件: ${projectDir.absolutePath}")
    }

    /**
     * 递归注入目录
     */
    private fun injectDirectory(zos: ZipOutputStream, dir: File, zipPath: String) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                injectDirectory(zos, file, zipPath + file.name + "/")
            } else {
                writeZipEntry(zos, zipPath + file.name, file.readBytes(), ZipEntry.DEFLATED)
            }
        }
    }

    /**
     * 写入ZIP条目
     */
    private fun writeZipEntry(zos: ZipOutputStream, name: String, data: ByteArray, method: Int) {
        val entry = ZipEntry(name)
        if (method == ZipEntry.STORED) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(data)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    /**
     * 判断是否为启动器图标文件
     */
    private fun isLauncherIcon(name: String): Boolean {
        return name.matches(Regex("res/mipmap-.*/ic_launcher\\.png"))
    }

    /**
     * 根据mipmap目录名获取图标尺寸
     */
    private fun getLauncherIconSize(name: String): Int {
        return when {
            name.contains("xxxhdpi") -> 192
            name.contains("xxhdpi") -> 144
            name.contains("xhdpi") -> 96
            name.contains("hdpi") -> 72
            name.contains("mdpi") -> 48
            name.contains("anydpi") -> 192 // 使用最大尺寸
            else -> 96
        }
    }

    /**
     * 缩放图标到指定尺寸，输出PNG字节
     */
    private fun resizeIcon(iconPath: String, targetSize: Int): ByteArray? {
        return try {
            val original = BitmapFactory.decodeFile(iconPath) ?: return null
            val scaled = Bitmap.createScaledBitmap(original, targetSize, targetSize, true)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
            if (original != scaled) original.recycle()
            scaled.recycle()
            baos.toByteArray()
        } catch (e: Exception) {
            ExtSystem.printDebugError("$TAG: 缩放图标失败", e)
            null
        }
    }

    /**
     * 检查是否处于Runner模式（打包后的APK）
     */
    fun isRunnerMode(context: Context): Boolean {
        return try {
            context.assets.open("pack_config.json").use { true }
        } catch (e: FileNotFoundException) {
            false
        }
    }

    /**
     * 读取打包配置（Runner模式下使用）
     */
    fun readPackConfig(context: Context): PackConfig? {
        return try {
            val json = context.assets.open("pack_config.json").bufferedReader().readText()
            gson.fromJson(json, PackConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取内嵌的项目文件（Runner模式下首次运行时调用）
     * 从 assets/project/ 提取到 /storage/emulated/0/Yyds.Py/{projectName}/
     */
    fun extractBundledProject(context: Context, projectName: String): Boolean {
        try {
            val targetDir = File(PyEngine.getProjectDir(), projectName)
            if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
                ExtSystem.printDebugLog("$TAG: 项目已存在，跳过提取: ${targetDir.absolutePath}")
                return true
            }
            targetDir.mkdirs()

            val assetManager = context.assets
            extractAssetDir(assetManager, "project", targetDir.absolutePath)
            ExtSystem.printDebugLog("$TAG: 项目文件已提取到: ${targetDir.absolutePath}")
            return true
        } catch (e: Exception) {
            ExtSystem.printDebugError("$TAG: 提取项目文件失败", e)
            return false
        }
    }

    private fun extractAssetDir(assets: android.content.res.AssetManager, assetPath: String, targetPath: String) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            // 是文件，直接复制
            val targetFile = File(targetPath)
            targetFile.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // 是目录，递归
            File(targetPath).mkdirs()
            for (item in list) {
                extractAssetDir(assets, "$assetPath/$item", "$targetPath/$item")
            }
        }
    }

    /**
     * 获取已打包的APK列表
     */
    fun getBuiltApkList(): List<Map<String, Any>> {
        val dir = File(outputDir)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "apk" && !f.name.startsWith("unsigned_") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                mapOf(
                    "name" to f.name,
                    "path" to f.absolutePath,
                    "size" to f.length(),
                    "lastModified" to f.lastModified()
                )
            } ?: emptyList()
    }
}
