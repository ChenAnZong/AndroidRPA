package com.tencent.yyds.frag

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tencent.yyds.App
import com.tencent.yyds.R
import com.tencent.yyds.YypListAdapter
import com.tencent.yyds.databinding.FragmentScriptBinding
import com.tencent.yyds.widget.AppBanner
import common.BootService
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import pyengine.ZipUtility
import uiautomator.ExtSystem
import android.view.animation.AnimationUtils
import java.io.File
import kotlin.concurrent.thread

class ScriptFragment : Fragment() {
    private val lTag = "ScriptFragment"
    var fragStatusFragment: FragmentScriptBinding? = null
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>

    companion object {
        public var INSTANCE: ScriptFragment? = null
        private const val PROJECT_FOLDER = "/sdcard/Yyds.Py/"
    }

    val binding get() = fragStatusFragment!!

    override fun onDestroyView() {
        super.onDestroyView()
        fragStatusFragment = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        INSTANCE = this

        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            importFromUri(uri)
        }
    }

    public fun showBanner(text: String, type: AppBanner.Type = AppBanner.Type.INFO) {
        ContextAction.uiThread {
            activity?.let { AppBanner.show(it, text, type) }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public fun refreshYypProjectListAsync() {
        ContextAction.uiThread { showSkeleton() }
        thread {
            // 快速路径: 直接请求项目列表，成功说明引擎已在运行，省去 ensureEngineRunning 的多次探测
            var projects = EngineClient.getProjectList()
            var engineRunning = projects.isNotEmpty() || EngineClient.isEngineReachable()

            if (!engineRunning) {
                // 慢路径: 引擎未运行，走完整启动流程
                engineRunning = EngineClient.ensureEngineRunning()
                if (engineRunning) {
                    projects = EngineClient.getProjectList()
                }
            }

            BootService.sendRefreshProject()
            ContextAction.uiThread {
                if (fragStatusFragment == null || !isAdded) return@uiThread
                hideSkeleton()
                val adapter = YypListAdapter(projects)
                binding.yypRecycleView.adapter = adapter
                binding.yypRecycleView.adapter!!.notifyDataSetChanged()
                binding.swipeRefreshLayout.isRefreshing = false
                if (adapter.itemCount == 0) {
                    binding.projectEmptyView.visibility = View.VISIBLE
                } else {
                    binding.projectEmptyView.visibility = View.GONE
                }
                if (!engineRunning) {
                    showBanner(getString(R.string.msg_engine_not_started), AppBanner.Type.ERROR)
                }
            }
        }
    }

    private fun showSkeleton() {
        binding.projectEmptyView.visibility = View.GONE
        binding.yypRecycleView.visibility = View.GONE
        binding.skeletonContainer.visibility = View.VISIBLE
        val shimmer = AnimationUtils.loadAnimation(context, R.anim.shimmer)
        for (i in 0 until binding.skeletonContainer.childCount) {
            binding.skeletonContainer.getChildAt(i).startAnimation(shimmer)
        }
    }

    private fun hideSkeleton() {
        binding.skeletonContainer.clearAnimation()
        for (i in 0 until binding.skeletonContainer.childCount) {
            binding.skeletonContainer.getChildAt(i).clearAnimation()
        }
        binding.skeletonContainer.visibility = View.GONE
        binding.yypRecycleView.visibility = View.VISIBLE
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fabImportProject.setOnClickListener { showImportBottomSheet() }

        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshYypProjectListAsync()
        }

        refreshYypProjectListAsync()
    }

    /**
     * 显示导入项目的底部菜单
     */
    private fun showImportBottomSheet() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx, R.style.BottomSheetDialogTheme)
        val sheetView = View.inflate(ctx, R.layout.bottom_sheet_import_project, null)
        dialog.setContentView(sheetView)

        // 选择文件导入（SAF 文件选择器）
        sheetView.findViewById<View>(R.id.menuPickFile).setOnClickListener {
            dialog.dismiss()
            openFilePicker()
        }

        // 扫描常用目录
        sheetView.findViewById<View>(R.id.menuScanFolders).setOnClickListener {
            dialog.dismiss()
            scanCommonFolders()
        }

        dialog.show()
    }

    /**
     * 使用系统文件选择器选择 .yyp.zip 文件
     */
    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            showBanner(getString(R.string.msg_file_picker_failed, e.message), AppBanner.Type.ERROR)
        }
    }

    /**
     * 从 SAF Uri 导入项目包
     */
    @Suppress("DEPRECATION")
    private fun importFromUri(uri: Uri) {
        val ctx = context ?: return
        // 从 URI 推断文件名
        var fileName = "project.zip"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
            }
        }

        if (!fileName.endsWith(".zip")) {
            showBanner(getString(R.string.msg_select_zip_file), AppBanner.Type.WARNING)
            return
        }

        val projectName = extractProjectName(fileName)
        val progress = ProgressDialog(ctx).apply {
            setMessage(getString(R.string.msg_importing_project, projectName))
            setCancelable(false)
            isIndeterminate = true
        }
        progress.show()

        thread {
            try {
                val cacheDir = File(App.app.cacheDir, "yyp-import")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val zipFile = File(cacheDir, fileName)

                // 通过 ContentResolver 复制到本地缓存（无需 ROOT）
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    zipFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception(getString(R.string.msg_cannot_read_file))

                extractAndInstall(zipFile, projectName)
                showBanner(getString(R.string.msg_import_success_name, projectName), AppBanner.Type.SUCCESS)
                refreshYypProjectListAsync()
            } catch (e: Exception) {
                ExtSystem.printDebugError("import project failed", e)
                showBanner(getString(R.string.msg_import_failed_detail, e.message), AppBanner.Type.ERROR)
            } finally {
                ContextAction.uiThread { progress.dismiss() }
            }
        }
    }

    /**
     * 扫描常用目录（Download、Documents 等）查找 .yyp.zip 文件
     */
    @Suppress("DEPRECATION")
    private fun scanCommonFolders() {
        val ctx = context ?: return
        val progress = ProgressDialog(ctx).apply {
            setMessage(getString(R.string.msg_scanning))
            setCancelable(true)
            isIndeterminate = true
        }
        progress.show()

        thread {
            try {
                val scanDirs = listOfNotNull(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    File(Environment.getExternalStorageDirectory(), "Yyds.Auto"),
                    File("/sdcard/Download"),
                    File("/sdcard/Documents"),
                    File("/sdcard/Yyds.Auto")
                ).distinctBy { it.absolutePath }

                val foundFiles = mutableListOf<File>()
                for (dir in scanDirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    try {
                        dir.listFiles()?.filter {
                            it.isFile && it.name.endsWith(".yyp.zip", ignoreCase = true)
                        }?.let { foundFiles.addAll(it) }
                    } catch (_: Exception) { /* 跳过无权限目录 */ }
                }

                // 如果 App 进程无法直接访问（例如 Android 11+），通过引擎 shell 补充
                if (foundFiles.isEmpty()) {
                    try {
                        if (EngineClient.ensureEngineRunning()) {
                            val scanPaths = "/sdcard/Download /sdcard/Documents /sdcard/Yyds.Auto"
                            val cmd = "find $scanPaths -maxdepth 2 -name '*.yyp.zip' -type f 2>/dev/null | head -n 20"
                            val result = EngineClient.runShell(cmd)
                            result.split("\n")
                                .map { it.trim() }
                                .filter { it.endsWith(".yyp.zip") }
                                .forEach { foundFiles.add(File(it)) }
                        }
                    } catch (_: Exception) { /* 引擎不可用则跳过 */ }
                }

                // 按修改时间倒序，最新的排前面
                foundFiles.sortByDescending { it.lastModified() }
                val uniqueFiles = foundFiles.distinctBy { it.absolutePath }

                ContextAction.uiThread {
                    progress.dismiss()
                    if (uniqueFiles.isEmpty()) {
                        MaterialAlertDialogBuilder(ctx, R.style.MyDialog)
                            .setTitle(getString(R.string.script_scan_result_title))
                            .setMessage(getString(R.string.script_scan_no_result_message))
                            .setPositiveButton(getString(R.string.script_pick_file)) { d, _ ->
                                d.dismiss()
                                openFilePicker()
                            }
                            .setNegativeButton(getString(R.string.btn_close), null)
                            .show()
                    } else {
                        showScanResultDialog(uniqueFiles)
                    }
                }
            } catch (e: Exception) {
                ContextAction.uiThread { progress.dismiss() }
                showBanner(getString(R.string.msg_scan_failed, e.message), AppBanner.Type.ERROR)
            }
        }
    }

    /**
     * 显示扫描到的项目列表，单选导入
     */
    @Suppress("DEPRECATION")
    private fun showScanResultDialog(files: List<File>) {
        val ctx = context ?: return
        val displayNames = files.map { file ->
            val parentName = file.parentFile?.name ?: ""
            val sizeMB = String.format("%.1f", file.length() / 1024.0 / 1024.0)
            "${file.name}\n    📁 $parentName  ·  ${sizeMB}MB"
        }.toTypedArray()

        var selectedIndex = 0

        MaterialAlertDialogBuilder(ctx, R.style.MyDialog)
            .setTitle(getString(R.string.script_scan_found_count, files.size))
            .setSingleChoiceItems(displayNames, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(getString(R.string.btn_import)) { d, _ ->
                d.dismiss()
                val selectedFile = files[selectedIndex]
                val projectName = extractProjectName(selectedFile.name)
                val progress = ProgressDialog(ctx).apply {
                    setMessage(getString(R.string.msg_importing_project, projectName))
                    setCancelable(false)
                    isIndeterminate = true
                }
                progress.show()
                thread {
                    try {
                        // 如果文件在 App 可读目录，直接读取；否则通过引擎 shell 复制
                        val cacheDir = File(App.app.cacheDir, "yyp-import")
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        val zipFile: File
                        if (selectedFile.canRead()) {
                            zipFile = selectedFile
                        } else {
                            zipFile = File(cacheDir, selectedFile.name)
                            ExtSystem.shell("cp -f '${selectedFile.absolutePath}' '${zipFile.absolutePath}' && chown ${ExtSystem.uid()}:${ExtSystem.uid()} '${zipFile.absolutePath}'")
                            if (!zipFile.exists()) throw Exception(getString(R.string.msg_cannot_copy_to_cache))
                        }
                        extractAndInstall(zipFile, projectName)
                        showBanner(getString(R.string.msg_import_success_name, projectName), AppBanner.Type.SUCCESS)
                        refreshYypProjectListAsync()
                    } catch (e: Exception) {
                        ExtSystem.printDebugError("import project failed", e)
                        showBanner(getString(R.string.msg_import_failed_detail, e.message), AppBanner.Type.ERROR)
                    } finally {
                        ContextAction.uiThread { progress.dismiss() }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    /**
     * 解压 ZIP 并安装到项目目录
     */
    private fun extractAndInstall(zipFile: File, projectName: String) {
        val cacheDir = File(App.app.cacheDir, "yyp-import")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val extractFolder = File(cacheDir, projectName)

        // 清理旧的解压缓存
        if (extractFolder.exists()) extractFolder.deleteRecursively()
        extractFolder.mkdirs()

        ZipUtility.unzip(zipFile.absolutePath, extractFolder.absolutePath)

        // 通过 shell 移动到项目目录（需要 ROOT 权限写入外部存储）
        ExtSystem.shell("[ -d $PROJECT_FOLDER ] || mkdir -p $PROJECT_FOLDER")
        ExtSystem.shell("rm -rf '$PROJECT_FOLDER$projectName'")
        ExtSystem.shell("/system/bin/mv -f '${extractFolder.absolutePath}' '$PROJECT_FOLDER'")

        // 清理缓存 zip（仅清理从 SAF 复制的临时文件）
        val tempZip = File(cacheDir, zipFile.name)
        if (tempZip.exists() && tempZip.absolutePath != zipFile.absolutePath) {
            tempZip.delete()
        }
    }

    /**
     * 从文件名提取项目名：去掉 .yyp.zip / .zip 后缀
     */
    private fun extractProjectName(fileName: String): String {
        return fileName
            .removeSuffix(".yyp.zip")
            .removeSuffix(".zip")
            .ifEmpty { "imported_project" }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        fragStatusFragment = FragmentScriptBinding.inflate(inflater, container, false)
        return binding.root
    }
}