package com.tencent.yyds

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.tencent.yyds.databinding.ActivityFileBrowserBinding
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import pyengine.PyEngine
import pyengine.YyProject
import uiautomator.ExtSystem
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread


class FileBrowserActivity : AppCompatActivity() {
    lateinit var binding: ActivityFileBrowserBinding
    private val gson = Gson()
    private var currentPath: String = ""
    private var rootPath: String = ""
    private var projectName: String = ""
    private var fileList: List<FileItem> = emptyList()
    private var filteredFileList: List<FileItem> = emptyList()
    private lateinit var adapter: FileListAdapter
    private var currentViewingFile: String? = null
    private var searchQuery: String = ""
    private var sortMode: Int = SORT_NAME

    data class FileItem(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long,
        val readable: Boolean,
        val writable: Boolean
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        supportActionBar?.hide()
        setContentView(binding.root)

        val project = intent.getSerializableExtra(App.KEY_PROJECT) as YyProject?
        if (project == null) {
            finish()
            return
        }
        rootPath = project.folderPath
        currentPath = rootPath
        projectName = project.name

        setupToolbar()
        setupRecyclerView()
        setupCodeViewer()
        setupImagePreview()
        setupSwipeRefresh()

        loadDirectory(currentPath)
    }

    private fun setupToolbar() {
        binding.topAppBar.title = projectName
        binding.topAppBar.setNavigationIcon(R.drawable.ic_back)
        binding.topAppBar.setNavigationOnClickListener { onBackPressed() }
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> {
                    if (binding.codeViewerContainer.visibility == View.VISIBLE) {
                        showCodeSearchDialog()
                    } else {
                        showFileSearchDialog()
                    }
                }
                R.id.action_new_file -> showNewFileDialog()
                R.id.action_new_folder -> showNewFolderDialog()
                R.id.sort_name -> { sortMode = SORT_NAME; applyFilterAndSort(); ContextAction.toast(getString(R.string.fb_sort_name)) }
                R.id.sort_size -> { sortMode = SORT_SIZE; applyFilterAndSort(); ContextAction.toast(getString(R.string.fb_sort_size)) }
                R.id.sort_date -> { sortMode = SORT_DATE; applyFilterAndSort(); ContextAction.toast(getString(R.string.fb_sort_date)) }
                R.id.sort_type -> { sortMode = SORT_TYPE; applyFilterAndSort(); ContextAction.toast(getString(R.string.fb_sort_type)) }
            }
            true
        }
    }

    private fun setupRecyclerView() {
        adapter = FileListAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupCodeViewer() {
        val ws = binding.codeWebView.settings
        ws.javaScriptEnabled = true
        ws.defaultTextEncodingName = "UTF-8"
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.setSupportZoom(true)
        ws.builtInZoomControls = true
        ws.displayZoomControls = false
        ws.textZoom = 100
        ws.cacheMode = WebSettings.LOAD_NO_CACHE

        binding.btnCodeClose.setOnClickListener { hideCodeViewer() }
        binding.btnCodeEdit.setOnClickListener {
            currentViewingFile?.let { showEditFileDialog(it) }
        }
    }

    private fun setupImagePreview() {
        binding.btnImageClose.setOnClickListener { hideImagePreview() }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            loadDirectory(currentPath)
        }
        binding.swipeRefresh.setColorSchemeColors(
            Color.parseColor("#264B6F")
        )
    }

    // ================================================================
    // 目录加载与面包屑
    // ================================================================

    private fun loadDirectory(path: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE

        thread {
            EngineClient.ensureEngineRunning()
            val json = EngineClient.listDir(path)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false

                if (json == null) {
                    binding.emptyView.visibility = View.VISIBLE
                    ContextAction.toast(getString(R.string.fb_cannot_read_dir))
                    return@runOnUiThread
                }

                try {
                    val map = gson.fromJson(json, Map::class.java)
                    if (map.containsKey("error")) {
                        binding.emptyView.visibility = View.VISIBLE
                        ContextAction.toast(map["error"]?.toString() ?: getString(R.string.fb_read_fail))
                        return@runOnUiThread
                    }

                    val filesRaw = map["files"] as? List<*> ?: emptyList<Any>()
                    fileList = filesRaw.map { item ->
                        val m = item as Map<*, *>
                        FileItem(
                            name = m["name"]?.toString() ?: "",
                            path = m["path"]?.toString() ?: "",
                            isDir = m["isDir"] == true,
                            size = (m["size"] as? Number)?.toLong() ?: 0,
                            lastModified = (m["lastModified"] as? Number)?.toLong() ?: 0,
                            readable = m["readable"] == true,
                            writable = m["writable"] == true
                        )
                    }

                    currentPath = path
                    searchQuery = ""
                    applyFilterAndSort()
                    updateBreadcrumb()
                } catch (e: Exception) {
                    ExtSystem.printDebugError("parse directory list failed", e)
                    binding.emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun applyFilterAndSort() {
        var list = if (searchQuery.isBlank()) fileList
        else fileList.filter { it.name.contains(searchQuery, ignoreCase = true) }

        list = when (sortMode) {
            SORT_NAME -> list.sortedWith(compareBy<FileItem> { !it.isDir }.thenBy { it.name.lowercase() })
            SORT_SIZE -> list.sortedWith(compareBy<FileItem> { !it.isDir }.thenByDescending { it.size })
            SORT_DATE -> list.sortedWith(compareBy<FileItem> { !it.isDir }.thenByDescending { it.lastModified })
            SORT_TYPE -> list.sortedWith(compareBy<FileItem> { !it.isDir }.thenBy { it.name.substringAfterLast('.', "").lowercase() }.thenBy { it.name.lowercase() })
            else -> list
        }
        filteredFileList = list
        adapter.notifyDataSetChanged()

        if (filteredFileList.isEmpty()) {
            binding.emptyView.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
        updateFileInfo()
    }

    @SuppressLint("SetTextI18n")
    private fun updateFileInfo() {
        val dirs = filteredFileList.count { it.isDir }
        val files = filteredFileList.count { !it.isDir }
        val totalSize = filteredFileList.filter { !it.isDir }.sumOf { it.size }
        val parts = mutableListOf<String>()
        if (dirs > 0) parts.add(getString(R.string.fb_count_dirs, dirs))
        if (files > 0) parts.add(getString(R.string.fb_count_files, files))
        if (totalSize > 0) parts.add(formatFileSize(totalSize))
        if (searchQuery.isNotBlank()) parts.add(getString(R.string.fb_filter_query, searchQuery))
        binding.tvFileInfo.text = if (parts.isEmpty()) getString(R.string.fb_empty_dir) else parts.joinToString("  ·  ")
    }

    private fun updateBreadcrumb() {
        val layout = binding.breadcrumbLayout
        layout.removeAllViews()
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt() }

        // 计算相对于rootPath的路径
        val relativePath = if (currentPath.startsWith(rootPath)) {
            currentPath.removePrefix(rootPath)
        } else currentPath

        val segments = mutableListOf(projectName)
        if (relativePath.isNotEmpty()) {
            segments.addAll(relativePath.trimStart('/').split("/").filter { it.isNotEmpty() })
        }

        for (i in segments.indices) {
            if (i > 0) {
                val sep = TextView(this).apply {
                    text = "›"
                    setTextColor(Color.parseColor("#999999"))
                    textSize = 13f
                    setPadding(dp(4), 0, dp(4), 0)
                }
                layout.addView(sep)
            }

            val isLast = i == segments.lastIndex
            val tv = TextView(this).apply {
                text = segments[i]
                textSize = 13f
                if (isLast) {
                    setTextColor(Color.parseColor("#264B6F"))
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    setTextColor(Color.parseColor("#666666"))
                }
                setPadding(dp(4), dp(6), dp(4), dp(6))
            }

            if (!isLast) {
                val targetPath = if (i == 0) rootPath
                else rootPath + "/" + segments.subList(1, i + 1).joinToString("/")
                tv.setOnClickListener { navigateTo(targetPath) }
                tv.setBackgroundResource(android.R.drawable.list_selector_background)
            }
            layout.addView(tv)
        }

        // 滚动到最右侧
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun navigateTo(path: String) {
        hideCodeViewer()
        hideImagePreview()
        loadDirectory(path)
    }

    // ================================================================
    // 文件操作
    // ================================================================

    private fun openFile(item: FileItem) {
        if (item.isDir) {
            navigateTo(item.path)
            return
        }

        val ext = item.name.substringAfterLast('.', "").lowercase()
        when {
            ext in IMAGE_EXTENSIONS -> showImagePreview(item)
            ext in TEXT_EXTENSIONS || item.size < 512 * 1024 -> showCodeViewer(item)
            else -> {
                ContextAction.toast(getString(R.string.fb_unsupported_preview, formatFileSize(item.size)))
            }
        }
    }

    private fun showCodeViewer(item: FileItem) {
        binding.progressBar.visibility = View.VISIBLE
        thread {
            val content = EngineClient.readFileText(item.path)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (content == null) {
                    ContextAction.toast(getString(R.string.fb_read_file_fail))
                    return@runOnUiThread
                }

                currentViewingFile = item.path
                binding.codeViewerContainer.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.emptyView.visibility = View.GONE
                binding.tvCodeFileName.text = item.name
                val lineCount = content.count { it == '\n' } + 1
                binding.tvCodeLineCount.text = getString(R.string.fb_code_line_info, lineCount, formatFileSize(item.size))

                binding.codeWebView.loadUrl("file:///android_asset/code_viewer.html")
                binding.codeWebView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        val jsFilename = item.name.replace("'", "\\'")
                        view?.evaluateJavascript(
                            "loadCode(decodeURIComponent(escape(atob('$encoded'))), '$jsFilename')",
                            null
                        )
                    }
                }
            }
        }
    }

    private fun hideCodeViewer() {
        binding.codeViewerContainer.visibility = View.GONE
        binding.recyclerView.visibility = if (filteredFileList.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (filteredFileList.isEmpty()) View.VISIBLE else View.GONE
        currentViewingFile = null
    }

    private fun showImagePreview(item: FileItem) {
        binding.progressBar.visibility = View.VISIBLE
        thread {
            try {
                val url = java.net.URL("http://127.0.0.1:${PyEngine.enginePort}/pull-file?path=${java.net.URLEncoder.encode(item.path, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (bitmap != null) {
                        binding.imagePreviewContainer.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                        binding.emptyView.visibility = View.GONE
                        binding.imagePreview.setImageBitmap(bitmap)
                        binding.tvImageName.text = item.name
                        binding.tvImageInfo.text = "${bitmap.width}×${bitmap.height} · ${formatFileSize(item.size)}"
                    } else {
                        ContextAction.toast(getString(R.string.fb_image_load_fail))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    ContextAction.toast(getString(R.string.fb_image_load_fail_detail, e.message))
                }
            }
        }
    }

    private fun hideImagePreview() {
        binding.imagePreviewContainer.visibility = View.GONE
        binding.imagePreview.setImageBitmap(null)
        binding.recyclerView.visibility = if (filteredFileList.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyView.visibility = if (filteredFileList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditFileDialog(filePath: String) {
        binding.progressBar.visibility = View.VISIBLE
        thread {
            val content = EngineClient.readFileText(filePath)
            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                if (content == null) {
                    ContextAction.toast(getString(R.string.fb_read_file_fail))
                    return@runOnUiThread
                }

                val fileName = filePath.substringAfterLast('/')
                val editText = EditText(this).apply {
                    setText(content)
                    setTextColor(Color.parseColor("#1A1A1A"))
                    setBackgroundColor(Color.parseColor("#F8F9FA"))
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setPadding(24, 16, 24, 16)
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    setHorizontallyScrolling(true)
                    isSingleLine = false
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.fb_edit_title, fileName))
                    .setView(editText)
                    .setPositiveButton(getString(R.string.fb_btn_save)) { _, _ ->
                        val newContent = editText.text.toString()
                        thread {
                            val ok = EngineClient.writeFileText(filePath, newContent)
                            runOnUiThread {
                                if (ok) {
                                    ContextAction.toast(getString(R.string.fb_save_success))
                                    // 重新加载代码
                                    val item = fileList.find { it.path == filePath }
                                    if (item != null) showCodeViewer(item)
                                } else {
                                    ContextAction.toast(getString(R.string.fb_save_fail))
                                }
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.fb_btn_cancel), null)
                    .show()
            }
        }
    }

    // ================================================================
    // 文件上下文菜单
    // ================================================================

    @SuppressLint("SetTextI18n")
    private fun showFileContextMenu(view: View, item: FileItem) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 1, 0, getString(R.string.fb_menu_copy_path))
        popup.menu.add(0, 2, 1, getString(R.string.fb_menu_rename))
        popup.menu.add(0, 3, 2, getString(R.string.fb_menu_delete))
        if (!item.isDir) {
            popup.menu.add(0, 4, 3, getString(R.string.fb_menu_detail))
        }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> copyToClipboard(item.path)
                2 -> showRenameDialog(item)
                3 -> showDeleteConfirm(item)
                4 -> showFileDetail(item)
            }
            true
        }
        popup.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("path", text))
        ContextAction.toast(getString(R.string.fb_copied, text))
    }

    private fun showRenameDialog(item: FileItem) {
        val editText = EditText(this).apply {
            setText(item.name)
            selectAll()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_menu_rename))
            .setView(editText)
            .setPositiveButton(getString(R.string.fb_btn_ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty() || newName == item.name) return@setPositiveButton
                thread {
                    val ok = EngineClient.renameFile(item.path, newName)
                    runOnUiThread {
                        if (ok) {
                            ContextAction.toast(getString(R.string.fb_rename_success))
                            loadDirectory(currentPath)
                        } else {
                            ContextAction.toast(getString(R.string.fb_rename_fail))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    private fun showDeleteConfirm(item: FileItem) {
        val typeStr = if (item.isDir) getString(R.string.fb_type_folder) else getString(R.string.fb_type_file)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_delete_title, typeStr))
            .setMessage(getString(R.string.fb_delete_confirm, item.name, if (item.isDir) getString(R.string.fb_delete_recursive_warning) else ""))
            .setPositiveButton(getString(R.string.fb_menu_delete)) { _, _ ->
                thread {
                    val ok = EngineClient.fileDelete(item.path)
                    runOnUiThread {
                        if (ok) {
                            ContextAction.toast(getString(R.string.fb_deleted))
                            loadDirectory(currentPath)
                        } else {
                            ContextAction.toast(getString(R.string.fb_delete_fail))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    @SuppressLint("SimpleDateFormat", "SetTextI18n")
    private fun showFileDetail(item: FileItem) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val info = buildString {
            append(getString(R.string.fb_detail_name, item.name))
            append(getString(R.string.fb_detail_path, item.path))
            append(getString(R.string.fb_detail_size, formatFileSize(item.size), item.size))
            append(getString(R.string.fb_detail_modified, sdf.format(Date(item.lastModified))))
            append(getString(R.string.fb_detail_readable, if (item.readable) getString(R.string.fb_yes) else getString(R.string.fb_no)))
            append(getString(R.string.fb_detail_writable, if (item.writable) getString(R.string.fb_yes) else getString(R.string.fb_no)))
            val ext = item.name.substringAfterLast('.', "")
            if (ext.isNotEmpty()) append(getString(R.string.fb_detail_ext, ext))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_detail_title))
            .setMessage(info)
            .setPositiveButton(getString(R.string.fb_menu_copy_path)) { _, _ -> copyToClipboard(item.path) }
            .setNegativeButton(getString(R.string.fb_btn_close), null)
            .show()
    }

    // ================================================================
    // 新建文件/文件夹
    // ================================================================

    private fun showNewFileDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.fb_new_file_hint)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_new_file_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.fb_btn_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val newPath = "$currentPath/$name"
                thread {
                    val ok = EngineClient.writeFileText(newPath, "")
                    runOnUiThread {
                        if (ok) {
                            ContextAction.toast(getString(R.string.fb_create_success))
                            loadDirectory(currentPath)
                        } else {
                            ContextAction.toast(getString(R.string.fb_create_fail))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    private fun showNewFolderDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.fb_new_folder_hint)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_new_folder_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.fb_btn_create)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val newPath = "$currentPath/$name"
                thread {
                    val ok = EngineClient.mkDir(newPath)
                    runOnUiThread {
                        if (ok) {
                            ContextAction.toast(getString(R.string.fb_create_success))
                            loadDirectory(currentPath)
                        } else {
                            ContextAction.toast(getString(R.string.fb_create_fail))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    // ================================================================
    // 返回键处理
    // ================================================================

    override fun onBackPressed() {
        when {
            binding.codeViewerContainer.visibility == View.VISIBLE -> hideCodeViewer()
            binding.imagePreviewContainer.visibility == View.VISIBLE -> hideImagePreview()
            currentPath != rootPath -> {
                val parent = currentPath.substringBeforeLast('/')
                if (parent.isNotEmpty() && currentPath.startsWith(rootPath)) {
                    if (parent.length >= rootPath.length) {
                        navigateTo(parent)
                    } else {
                        super.onBackPressed()
                    }
                } else {
                    super.onBackPressed()
                }
            }
            else -> super.onBackPressed()
        }
    }

    // ================================================================
    // RecyclerView Adapter
    // ================================================================

    inner class FileListAdapter : RecyclerView.Adapter<FileListAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.ivFileIcon)
            val tvName: TextView = view.findViewById(R.id.tvFileName)
            val tvSize: TextView = view.findViewById(R.id.tvFileSize)
            val tvDate: TextView = view.findViewById(R.id.tvFileDate)
            val ivArrow: ImageView = view.findViewById(R.id.ivFileArrow)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.row_file_item, parent, false)
            return VH(view)
        }

        @SuppressLint("SimpleDateFormat")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = filteredFileList[position]
            holder.tvName.text = item.name

            // 文件图标
            holder.ivIcon.setImageResource(getFileIcon(item))

            // 文件大小
            holder.tvSize.text = if (item.isDir) getString(R.string.fb_type_folder) else formatFileSize(item.size)

            // 修改时间
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            holder.tvDate.text = if (item.lastModified > 0) sdf.format(Date(item.lastModified)) else ""

            // 文件夹显示箭头
            holder.ivArrow.visibility = if (item.isDir) View.VISIBLE else View.GONE

            // 隐藏文件半透明
            holder.itemView.alpha = if (item.name.startsWith(".")) 0.5f else 1.0f

            // 点击
            holder.itemView.setOnClickListener { openFile(item) }
            holder.itemView.setOnLongClickListener {
                showFileContextMenu(it, item)
                true
            }
        }

        override fun getItemCount() = filteredFileList.size
    }

    // ================================================================
    // 搜索功能
    // ================================================================

    private fun showFileSearchDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.fb_search_file_hint)
            setText(searchQuery)
            selectAll()
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_search_file_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.fb_btn_search)) { _, _ ->
                searchQuery = editText.text.toString().trim()
                applyFilterAndSort()
            }
            .setNeutralButton(getString(R.string.fb_btn_clear)) { _, _ ->
                searchQuery = ""
                applyFilterAndSort()
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showCodeSearchDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.fb_code_search_hint)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_code_search_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.fb_btn_search)) { _, _ ->
                val query = editText.text.toString()
                if (query.isNotEmpty()) {
                    val jsQuery = query.replace("'", "\\'")
                    binding.codeWebView.evaluateJavascript("searchCode('$jsQuery')") { result ->
                        val count = result?.replace("\"", "")?.toIntOrNull() ?: 0
                        binding.tvCodeLineCount.text = getString(R.string.fb_code_search_result, count)
                    }
                }
            }
            .setNeutralButton(getString(R.string.fb_btn_clear)) { _, _ ->
                binding.codeWebView.evaluateJavascript("searchCode('')", null)
            }
            .setNegativeButton(getString(R.string.fb_btn_cancel), null)
            .show()
    }

    companion object {
        const val SORT_NAME = 0
        const val SORT_SIZE = 1
        const val SORT_DATE = 2
        const val SORT_TYPE = 3

        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "ico")
        val TEXT_EXTENSIONS = setOf(
            "py", "pyw", "txt", "md", "json", "yml", "yaml", "xml", "html", "htm",
            "css", "js", "ts", "jsx", "tsx", "sh", "bash", "zsh", "bat", "cmd",
            "config", "conf", "cfg", "ini", "env", "properties", "toml",
            "csv", "log", "sql", "kt", "java", "c", "cpp", "h", "hpp",
            "gradle", "pro", "gitignore", "dockerignore", "editorconfig",
            "svg", "less", "scss", "sass"
        )
        val CODE_EXTENSIONS = setOf(
            "py", "pyw", "js", "ts", "jsx", "tsx", "kt", "java", "c", "cpp",
            "h", "hpp", "sh", "bash", "css", "scss", "less", "sql", "gradle"
        )

        fun getFileIcon(item: FileItem): Int {
            if (item.isDir) return R.drawable.ic_folder
            val ext = item.name.substringAfterLast('.', "").lowercase()
            return when {
                ext in setOf("py", "pyw") -> R.drawable.ic_file_python
                ext in CODE_EXTENSIONS -> R.drawable.ic_file_code
                ext in IMAGE_EXTENSIONS -> R.drawable.ic_file_image
                ext in setOf("yml", "yaml", "json", "xml", "config", "conf", "cfg", "ini", "env", "properties", "toml") -> R.drawable.ic_file_config
                else -> R.drawable.ic_file_text
            }
        }

        fun formatFileSize(size: Long): String {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
            val idx = digitGroups.coerceIn(0, units.lastIndex)
            return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, idx.toDouble())) + " " + units[idx]
        }
    }
}
