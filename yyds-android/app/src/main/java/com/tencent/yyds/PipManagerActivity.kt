package com.tencent.yyds

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import uiautomator.ExtSystem
import kotlin.concurrent.thread

class PipManagerActivity : AppCompatActivity() {

    private val gson = Gson()

    companion object {
        private const val COLOR_BG = "#FBF7F1"
        private const val COLOR_ACCENT = "#264B6F"
        private const val COLOR_ACCENT_LIGHT = "#D6E5ED"
        private const val COLOR_TEXT_PRIMARY = "#1A1A1A"
        private const val COLOR_TEXT_SECONDARY = "#5A6A7A"
        private const val COLOR_TEXT_HINT = "#8A95A0"
        private const val COLOR_DIVIDER = "#E8EAED"
        private const val COLOR_SUCCESS = "#008305"
        private const val COLOR_DANGER = "#C62828"
        private const val COLOR_UPGRADE = "#E65100"
        private const val COLOR_TAG_BG = "#E8F0FE"

        private const val PIP_MIRROR_TSINGHUA = "https://pypi.tuna.tsinghua.edu.cn/simple"
    }

    data class PipPackage(
        val name: String,
        val version: String,
        val latestVersion: String? = null
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var emptyView: TextView
    private lateinit var statusText: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var chipAll: Chip
    private lateinit var chipOutdated: Chip
    private lateinit var searchPypiCard: MaterialCardView

    private var allPackages = mutableListOf<PipPackage>()
    private var outdatedMap = mutableMapOf<String, String>()
    private var filterText = ""
    private var showOnlyOutdated = false
    private var useMirror = true

    @Volatile private var isOperating = false
    private var isDestroyed_ = false
    private var pipAdapter: PipAdapter? = null

    private val dp by lazy {
        { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
            ).toInt()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(COLOR_BG))
            fitsSystemWindows = true
        }

        // ===== Toolbar =====
        val appBarLayout = AppBarLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor(COLOR_BG))
            elevation = dp(2).toFloat()
        }
        val toolbar = MaterialToolbar(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
            )
            setBackgroundColor(Color.parseColor(COLOR_BG))
            title = getString(R.string.pip_title)
            setTitleTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.menu_pip_manager)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_refresh -> { loadPackageList(); true }
                    R.id.action_upgrade_all -> { doUpgradeAll(); true }
                    else -> false
                }
            }
        }
        appBarLayout.addView(toolbar)
        rootFrame.addView(appBarLayout)

        // ===== 主内容 =====
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { topMargin = dp(56) }
        }

        // --- 搜索栏 ---
        val searchCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12); marginEnd = dp(12)
                topMargin = dp(8); bottomMargin = dp(4)
            }
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = dp(1).toFloat()
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(0), dp(4), dp(0))
        }
        val searchInputLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.pip_search_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
            isHintAnimationEnabled = true
            setStartIconDrawable(R.drawable.ic_search)
            setStartIconTintList(ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchInput = TextInputEditText(this).apply {
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
            isSingleLine = true
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterText = s?.toString()?.trim() ?: ""
                refreshFilteredList()
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val text = searchInput.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty() && allPackages.none { it.name.equals(text, true) }) {
                    showInstallDialog(text)
                }
                hideKeyboard()
                true
            } else false
        }
        searchInputLayout.addView(searchInput)
        searchRow.addView(searchInputLayout)

        val installBtn = MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.pip_install)
            setTextColor(Color.parseColor(COLOR_ACCENT))
            strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            cornerRadius = dp(8)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)
            ).apply { marginStart = dp(4) }
            setOnClickListener {
                val text = searchInput.text?.toString()?.trim() ?: ""
                if (text.isEmpty()) {
                    ContextAction.toast(getString(R.string.pip_please_enter_package))
                } else {
                    showInstallDialog(text)
                }
                hideKeyboard()
            }
        }
        searchRow.addView(installBtn)
        searchCard.addView(searchRow)
        contentLayout.addView(searchCard)

        // --- 筛选 Chips + 镜像开关 ---
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        chipAll = Chip(this).apply {
            text = getString(R.string.pip_chip_all)
            isCheckable = true
            isChecked = true
            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT_LIGHT))
            setTextColor(Color.parseColor(COLOR_ACCENT))
            isCheckedIconVisible = false
            setOnClickListener {
                showOnlyOutdated = false
                isChecked = true
                chipOutdated.isChecked = false
                refreshFilteredList()
            }
        }
        chipOutdated = Chip(this).apply {
            text = getString(R.string.pip_chip_outdated)
            isCheckable = true
            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT_LIGHT))
            setTextColor(Color.parseColor(COLOR_ACCENT))
            isCheckedIconVisible = false
            setOnClickListener {
                showOnlyOutdated = true
                isChecked = true
                chipAll.isChecked = false
                refreshFilteredList()
            }
        }
        chipGroup.addView(chipAll)
        chipGroup.addView(chipOutdated)
        filterRow.addView(chipGroup)

        val mirrorChip = Chip(this).apply {
            text = getString(R.string.pip_chip_mirror)
            isCheckable = true
            isChecked = true
            chipBackgroundColor = ColorStateList.valueOf(Color.parseColor(COLOR_TAG_BG))
            setTextColor(Color.parseColor(COLOR_ACCENT))
            chipIcon = null
            isCheckedIconVisible = true
            setOnCheckedChangeListener { _, checked -> useMirror = checked }
        }
        filterRow.addView(mirrorChip)
        contentLayout.addView(filterRow)

        // --- 进度条 ---
        progressBar = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12); marginEnd = dp(12)
            }
            isIndeterminate = true
            visibility = View.GONE
            trackColor = Color.parseColor(COLOR_ACCENT_LIGHT)
            setIndicatorColor(Color.parseColor(COLOR_ACCENT))
        }
        contentLayout.addView(progressBar)

        // --- 状态文字 ---
        statusText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(16); marginEnd = dp(16)
                topMargin = dp(2); bottomMargin = dp(2)
            }
            textSize = 12f
            setTextColor(Color.parseColor(COLOR_TEXT_HINT))
        }
        contentLayout.addView(statusText)

        // --- 列表区域：FrameLayout 叠放 RecyclerView 与 emptyView ---
        val listContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        recyclerView = RecyclerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
            layoutManager = LinearLayoutManager(this@PipManagerActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }
        emptyView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(60)
            }
            this.gravity = Gravity.CENTER
            text = getString(R.string.pip_empty_data)
            setTextColor(Color.parseColor(COLOR_TEXT_HINT))
            textSize = 15f
            visibility = View.GONE
        }
        // --- PyPI 搜索卡片（搜索未匹配时显示）---
        searchPypiCard = MaterialCardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                marginStart = dp(16); marginEnd = dp(16)
            }
            radius = dp(12).toFloat()
            setCardBackgroundColor(Color.parseColor(COLOR_ACCENT_LIGHT))
            cardElevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
            setRippleColorResource(R.color.primary_lay4)
            visibility = View.GONE
            val inner = LinearLayout(this@PipManagerActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            val icon = TextView(this@PipManagerActivity).apply {
                text = "🔍"
                textSize = 28f
                gravity = Gravity.CENTER
            }
            val title = TextView(this@PipManagerActivity).apply {
                tag = "pypi_search_title"
                text = getString(R.string.pip_pypi_search_title)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(COLOR_ACCENT))
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(2))
            }
            val subtitle = TextView(this@PipManagerActivity).apply {
                tag = "pypi_search_subtitle"
                text = getString(R.string.pip_pypi_search_available)
                textSize = 12f
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                gravity = Gravity.CENTER
            }
            inner.addView(icon)
            inner.addView(title)
            inner.addView(subtitle)
            addView(inner)
            setOnClickListener { doPypiSearch(filterText) }
        }

        listContainer.addView(recyclerView)
        listContainer.addView(emptyView)
        listContainer.addView(searchPypiCard)
        contentLayout.addView(listContainer)

        rootFrame.addView(contentLayout)
        setContentView(rootFrame)

        // 初始化 adapter（复用，避免每次搜索重建）
        pipAdapter = PipAdapter()
        recyclerView.adapter = pipAdapter

        loadPackageList()
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed_ = true
    }

    private fun uiSafe(action: () -> Unit) {
        if (!isDestroyed_ && !isFinishing) runOnUiThread { if (!isDestroyed_ && !isFinishing) action() }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    // ============================================================
    // 操作互斥：防止并发 pip 操作
    // ============================================================
    private fun beginOperation(hint: String): Boolean {
        if (isOperating) {
            ContextAction.toast(getString(R.string.pip_operation_in_progress))
            return false
        }
        isOperating = true
        progressBar.visibility = View.VISIBLE
        statusText.setTextColor(Color.parseColor(COLOR_ACCENT))
        statusText.text = hint
        return true
    }

    private fun endOperation() {
        isOperating = false
        progressBar.visibility = View.GONE
    }

    // ============================================================
    // 数据加载：先显示列表，再异步刷新可更新信息
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun loadPackageList() {
        if (!beginOperation(getString(R.string.pip_loading_list))) return
        emptyView.visibility = View.GONE

        thread {
            val engineOk = EngineClient.ensureEngineRunning()
            if (!engineOk) {
                uiSafe {
                    endOperation()
                    statusText.text = getString(R.string.pip_engine_not_started)
                    statusText.setTextColor(Color.parseColor(COLOR_DANGER))
                    emptyView.visibility = View.VISIBLE
                    emptyView.text = getString(R.string.pip_cannot_connect)
                }
                return@thread
            }

            val listRaw = EngineClient.pipList()
            val packages = try {
                if (listRaw != null && listRaw.trimStart().startsWith("[")) {
                    val type = object : TypeToken<List<Map<String, String>>>() {}.type
                    val list: List<Map<String, String>> = gson.fromJson(listRaw, type)
                    list.map { PipPackage(it["name"] ?: "", it["version"] ?: "") }
                        .filter { it.name.isNotBlank() }
                } else {
                    ExtSystem.printDebugError("pip list returned abnormal", Exception(listRaw?.take(200) ?: "null"))
                    emptyList()
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("parse pip list failed", e)
                emptyList()
            }

            uiSafe {
                allPackages.clear()
                allPackages.addAll(packages.sortedBy { it.name.lowercase() })
                endOperation()
                updateStatusSummary()
                refreshFilteredList()

                if (allPackages.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    emptyView.text = if (listRaw == null) getString(R.string.pip_load_list_fail) else getString(R.string.pip_no_packages)
                }
            }

            // 异步加载可更新列表（不阻塞主列表展示）
            loadOutdatedAsync()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun loadOutdatedAsync() {
        thread {
            val outdatedRaw = EngineClient.pipOutdated()
            val outdated = try {
                if (outdatedRaw != null && outdatedRaw.trimStart().startsWith("[")) {
                    val type = object : TypeToken<List<Map<String, String>>>() {}.type
                    val list: List<Map<String, String>> = gson.fromJson(outdatedRaw, type)
                    list.associate { (it["name"] ?: "") to (it["latest_version"] ?: "") }
                } else emptyMap()
            } catch (e: Exception) {
                ExtSystem.printDebugError("parse pip outdated failed", e)
                emptyMap()
            }

            uiSafe {
                outdatedMap.clear()
                outdatedMap.putAll(outdated)
                chipOutdated.text = getString(R.string.pip_chip_outdated, outdatedMap.size)
                updateStatusSummary()
                refreshFilteredList()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatusSummary() {
        statusText.setTextColor(Color.parseColor(COLOR_TEXT_HINT))
        statusText.text = buildString {
            append(getString(R.string.pip_status_summary_total, allPackages.size))
            if (outdatedMap.isNotEmpty()) append(getString(R.string.pip_status_summary_outdated, outdatedMap.size))
        }
    }

    // ============================================================
    // 列表筛选（复用 adapter）
    // ============================================================
    private fun getFilteredList(): List<PipPackage> {
        var list = allPackages.toList()
        if (showOnlyOutdated) {
            list = list.filter { outdatedMap.containsKey(it.name) }
        }
        if (filterText.isNotEmpty()) {
            list = list.filter { it.name.contains(filterText, true) }
        }
        return list.map { it.copy(latestVersion = outdatedMap[it.name]) }
    }

    @SuppressLint("SetTextI18n", "NotifyDataSetChanged")
    private fun refreshFilteredList() {
        val filtered = getFilteredList()
        pipAdapter?.updateData(filtered)
        emptyView.visibility = if (filtered.isEmpty() && allPackages.isNotEmpty()) View.VISIBLE else
            if (filtered.isEmpty() && allPackages.isEmpty()) emptyView.visibility else View.GONE

        val showSearch = filtered.isEmpty() && filterText.isNotEmpty() && filterText.length >= 2
        if (filtered.isEmpty() && filterText.isNotEmpty()) {
            emptyView.visibility = View.VISIBLE
            emptyView.text = getString(R.string.pip_no_match)
        }
        if (showSearch) {
            searchPypiCard.visibility = View.VISIBLE
            searchPypiCard.findViewWithTag<TextView>("pypi_search_title")?.text =
                getString(R.string.pip_pypi_search_query, filterText)
            searchPypiCard.findViewWithTag<TextView>("pypi_search_subtitle")?.text =
                getString(R.string.pip_pypi_search_available)
        } else {
            searchPypiCard.visibility = View.GONE
        }
    }

    private fun getMirrorUrl(): String? {
        return if (useMirror) PIP_MIRROR_TSINGHUA else null
    }

    // ============================================================
    // 安装
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun showInstallDialog(packageName: String) {
        if (isOperating) { ContextAction.toast(getString(R.string.pip_operation_in_progress)); return }
        val mirrorHint = if (useMirror) getString(R.string.pip_mirror_hint) else ""
        MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(getString(R.string.pip_install_title))
            .setMessage(getString(R.string.pip_install_confirm, packageName, mirrorHint))
            .setPositiveButton(getString(R.string.pip_btn_install)) { dialog, _ ->
                dialog.dismiss()
                doInstall(packageName)
            }
            .setNegativeButton(getString(R.string.pip_btn_cancel), null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun doInstall(packageName: String) {
        if (!beginOperation(getString(R.string.pip_installing, packageName))) return

        thread {
            val result = EngineClient.pipInstall(packageName, getMirrorUrl())
            val map = parseResultMap(result)
            val success = map["success"] == true
            val output = map["output"]?.toString() ?: map["error"]?.toString() ?: getString(R.string.pip_unknown_result)

            uiSafe {
                endOperation()
                if (success) {
                    statusText.setTextColor(Color.parseColor(COLOR_SUCCESS))
                    statusText.text = getString(R.string.pip_install_success, packageName)
                    searchInput.setText("")
                    loadPackageList()
                } else {
                    statusText.setTextColor(Color.parseColor(COLOR_DANGER))
                    statusText.text = getString(R.string.pip_install_fail, packageName)
                    showOutputDialog(getString(R.string.pip_install_result_title, packageName), output)
                }
            }
        }
    }

    // ============================================================
    // 卸载
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun doUninstall(pkg: PipPackage) {
        if (isOperating) { ContextAction.toast(getString(R.string.pip_operation_in_progress)); return }
        MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(getString(R.string.pip_uninstall_title))
            .setMessage(getString(R.string.pip_uninstall_confirm, pkg.name, pkg.version))
            .setPositiveButton(getString(R.string.pip_btn_uninstall)) { dialog, _ ->
                dialog.dismiss()
                if (!beginOperation(getString(R.string.pip_uninstalling, pkg.name))) return@setPositiveButton

                thread {
                    val result = EngineClient.pipUninstall(pkg.name)
                    val map = parseResultMap(result)
                    val success = map["success"] == true
                    val output = map["output"]?.toString() ?: map["error"]?.toString() ?: getString(R.string.pip_unknown_result)

                    uiSafe {
                        endOperation()
                        if (success) {
                            statusText.setTextColor(Color.parseColor(COLOR_SUCCESS))
                            statusText.text = getString(R.string.pip_uninstall_success, pkg.name)
                            allPackages.removeAll { it.name == pkg.name }
                            outdatedMap.remove(pkg.name)
                            updateStatusSummary()
                            chipOutdated.text = getString(R.string.pip_chip_outdated, outdatedMap.size)
                            refreshFilteredList()
                        } else {
                            statusText.setTextColor(Color.parseColor(COLOR_DANGER))
                            statusText.text = getString(R.string.pip_uninstall_fail)
                            showOutputDialog(getString(R.string.pip_uninstall_result_title, pkg.name), output)
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.pip_btn_cancel), null)
            .show()
    }

    // ============================================================
    // 升级（带确认对话框）
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun doUpgrade(pkg: PipPackage) {
        if (isOperating) { ContextAction.toast(getString(R.string.pip_operation_in_progress)); return }
        val versionHint = if (pkg.latestVersion != null) "${pkg.version} → ${pkg.latestVersion}" else getString(R.string.pip_upgrade_to_latest)
        MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(getString(R.string.pip_upgrade_title))
            .setMessage(getString(R.string.pip_upgrade_confirm, pkg.name, versionHint))
            .setPositiveButton(getString(R.string.pip_btn_upgrade)) { dialog, _ ->
                dialog.dismiss()
                doUpgradeConfirmed(pkg)
            }
            .setNegativeButton(getString(R.string.pip_btn_cancel), null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun doUpgradeConfirmed(pkg: PipPackage) {
        if (!beginOperation(getString(R.string.pip_upgrading, pkg.name, pkg.latestVersion ?: "latest"))) return

        thread {
            val result = EngineClient.pipUpgrade(pkg.name, getMirrorUrl())
            val map = parseResultMap(result)
            val success = map["success"] == true
            val output = map["output"]?.toString() ?: map["error"]?.toString() ?: getString(R.string.pip_unknown_result)

            uiSafe {
                endOperation()
                if (success) {
                    statusText.setTextColor(Color.parseColor(COLOR_SUCCESS))
                    statusText.text = getString(R.string.pip_upgrade_success, pkg.name)
                    // 局部更新：从 allPackages 中更新版本号，移除 outdated 标记
                    val newVersion = pkg.latestVersion ?: pkg.version
                    val idx = allPackages.indexOfFirst { it.name == pkg.name }
                    if (idx >= 0) allPackages[idx] = PipPackage(pkg.name, newVersion)
                    outdatedMap.remove(pkg.name)
                    chipOutdated.text = getString(R.string.pip_chip_outdated, outdatedMap.size)
                    updateStatusSummary()
                    refreshFilteredList()
                } else {
                    statusText.setTextColor(Color.parseColor(COLOR_DANGER))
                    statusText.text = getString(R.string.pip_upgrade_fail)
                    showOutputDialog(getString(R.string.pip_upgrade_result_title, pkg.name), output)
                }
            }
        }
    }

    // ============================================================
    // 全部升级
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun doUpgradeAll() {
        if (outdatedMap.isEmpty()) {
            ContextAction.toast(getString(R.string.pip_no_outdated))
            return
        }
        if (isOperating) { ContextAction.toast(getString(R.string.pip_operation_in_progress)); return }
        val names = outdatedMap.keys.toList()
        MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(getString(R.string.pip_upgrade_all_title))
            .setMessage(getString(R.string.pip_upgrade_all_confirm, names.size, names.joinToString(", ")))
            .setPositiveButton(getString(R.string.pip_btn_upgrade_all)) { dialog, _ ->
                dialog.dismiss()
                if (!beginOperation(getString(R.string.pip_upgrading_all, names.size))) return@setPositiveButton

                thread {
                    val specList = names.joinToString(" ")
                    val result = EngineClient.pipUpgrade(specList, getMirrorUrl())
                    val map = parseResultMap(result)
                    val success = map["success"] == true
                    val output = map["output"]?.toString() ?: map["error"]?.toString() ?: getString(R.string.pip_unknown_result)

                    uiSafe {
                        endOperation()
                        if (success) {
                            statusText.setTextColor(Color.parseColor(COLOR_SUCCESS))
                            statusText.text = getString(R.string.pip_upgrade_all_success)
                            loadPackageList()
                        } else {
                            statusText.setTextColor(Color.parseColor(COLOR_DANGER))
                            statusText.text = getString(R.string.pip_upgrade_all_partial)
                            showOutputDialog(getString(R.string.pip_upgrade_all_result_title), output)
                            loadPackageList()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.pip_btn_cancel), null)
            .show()
    }

    // ============================================================
    // PyPI 搜索
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun doPypiSearch(query: String) {
        if (query.isBlank()) return
        if (!beginOperation(getString(R.string.pip_searching_pypi, query))) return
        hideKeyboard()

        thread {
            val raw = EngineClient.pipSearch(query, getMirrorUrl())
            val result = try {
                if (raw != null) gson.fromJson(raw, Map::class.java) ?: emptyMap()
                else emptyMap<String, Any>()
            } catch (e: Exception) { emptyMap<String, Any>() }

            uiSafe {
                endOperation()
                val found = result["found"] == true
                if (found) {
                    val name = result["name"]?.toString() ?: query
                    val latest = result["latest_version"]?.toString() ?: getString(R.string.pip_not_found_raw)
                    val installedVer = result["installed_version"]?.toString()
                    @Suppress("UNCHECKED_CAST")
                    val versions = (result["versions"] as? List<String>)
                        ?.take(15)?.joinToString(", ") ?: ""

                    showSearchResultDialog(name, latest, installedVer, versions)
                } else {
                    val rawOutput = result["raw"]?.toString() ?: getString(R.string.pip_not_found_raw)
                    statusText.setTextColor(Color.parseColor(COLOR_TEXT_HINT))
                    statusText.text = getString(R.string.pip_pypi_not_found, query)
                    MaterialAlertDialogBuilder(this@PipManagerActivity, R.style.MyDialog)
                        .setTitle(getString(R.string.pip_not_found_title))
                        .setMessage(getString(R.string.pip_not_found_message, query, rawOutput))
                        .setNegativeButton(getString(R.string.pip_btn_close), null)
                        .show()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showSearchResultDialog(
        name: String, latestVersion: String,
        installedVersion: String?, versionsStr: String
    ) {
        val msg = buildString {
            append("📦 $name\n\n")
            append(getString(R.string.pip_detail_latest, latestVersion))
            if (installedVersion != null) {
                append(getString(R.string.pip_detail_installed, installedVersion))
            }
            if (versionsStr.isNotEmpty()) {
                append(getString(R.string.pip_detail_versions, versionsStr))
            }
        }

        val builder = MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(getString(R.string.pip_pypi_result_title))
            .setMessage(msg)
            .setNegativeButton(getString(R.string.pip_btn_close), null)

        if (installedVersion != null && latestVersion != installedVersion) {
            builder.setPositiveButton(getString(R.string.pip_upgrade_to_version, latestVersion)) { d, _ ->
                d.dismiss()
                doUpgradeConfirmed(PipPackage(name, installedVersion, latestVersion))
            }
        } else if (installedVersion == null) {
            builder.setPositiveButton(getString(R.string.pip_install_name, name)) { d, _ ->
                d.dismiss()
                doInstall(name)
            }
        }

        builder.show()
    }

    // ============================================================
    // 详情
    // ============================================================
    @SuppressLint("SetTextI18n")
    private fun showPackageDetail(pkg: PipPackage) {
        if (isOperating) return
        if (!beginOperation(getString(R.string.pip_loading_detail, pkg.name))) return

        thread {
            val raw = EngineClient.pipShow(pkg.name)
            val info = try {
                if (raw != null) {
                    val type = object : TypeToken<Map<String, String>>() {}.type
                    gson.fromJson<Map<String, String>>(raw, type) ?: emptyMap()
                } else emptyMap()
            } catch (e: Exception) { emptyMap<String, String>() }

            uiSafe {
                endOperation()
                updateStatusSummary()

                if (info.isEmpty()) {
                    ContextAction.toast(getString(R.string.pip_detail_fail))
                    return@uiSafe
                }

                val detail = buildString {
                    info["Name"]?.let { append(getString(R.string.pip_detail_name, it)) }
                    info["Version"]?.let { append(getString(R.string.pip_detail_version, it)) }
                    if (pkg.latestVersion != null) append(getString(R.string.pip_detail_latest, pkg.latestVersion))
                    info["Summary"]?.let { append(getString(R.string.pip_detail_summary, it)) }
                    info["Author"]?.let { if (it.isNotBlank()) append(getString(R.string.pip_detail_author, it)) }
                    info["Home-page"]?.let { if (it.isNotBlank()) append(getString(R.string.pip_detail_homepage, it)) }
                    info["License"]?.let { if (it.isNotBlank()) append(getString(R.string.pip_detail_license, it)) }
                    info["Location"]?.let { append(getString(R.string.pip_detail_location, it)) }
                    info["Requires"]?.let { if (it.isNotBlank()) append(getString(R.string.pip_detail_requires, it)) }
                    info["Required-by"]?.let { if (it.isNotBlank()) append(getString(R.string.pip_detail_required_by, it)) }
                }

                val builder = MaterialAlertDialogBuilder(this@PipManagerActivity, R.style.MyDialog)
                    .setTitle(pkg.name)
                    .setMessage(detail.trim())
                    .setNegativeButton(getString(R.string.pip_btn_close), null)

                if (pkg.latestVersion != null) {
                    builder.setPositiveButton(getString(R.string.pip_btn_upgrade)) { d, _ -> d.dismiss(); doUpgrade(pkg) }
                }
                builder.setNeutralButton(getString(R.string.pip_btn_uninstall)) { d, _ -> d.dismiss(); doUninstall(pkg) }
                builder.show()
            }
        }
    }

    // ============================================================
    // 工具
    // ============================================================
    private fun parseResultMap(json: String?): Map<*, *> {
        return try {
            gson.fromJson(json, Map::class.java) ?: emptyMap<String, Any>()
        } catch (e: Exception) { emptyMap<String, Any>() }
    }

    private fun showOutputDialog(title: String, output: String) {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = output
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        MaterialAlertDialogBuilder(this, R.style.MyDialog)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(getString(R.string.pip_btn_close), null)
            .show()
    }

    // ============================================================
    // RecyclerView Adapter（复用，通过 updateData 更新）
    // ============================================================
    inner class PipAdapter : RecyclerView.Adapter<PipAdapter.VH>() {

        private var items = listOf<PipPackage>()

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newItems: List<PipPackage>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
            lateinit var tvName: TextView
            lateinit var tvVersion: TextView
            lateinit var tvLatest: TextView
            lateinit var btnUpgrade: MaterialButton
            lateinit var btnUninstall: MaterialButton
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = MaterialCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                    topMargin = dp(4); bottomMargin = dp(4)
                }
                radius = dp(10).toFloat()
                setCardBackgroundColor(Color.WHITE)
                cardElevation = dp(1).toFloat()
                setContentPadding(dp(16), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                setRippleColorResource(R.color.primary_lay4)
            }

            val rootRow = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val infoCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvName = TextView(parent.context).apply {
                setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
                textSize = 14.5f
                typeface = Typeface.DEFAULT_BOLD
            }
            val versionRow = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, 0)
            }
            val tvVersion = TextView(parent.context).apply {
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                textSize = 12f
            }
            val tvLatest = TextView(parent.context).apply {
                setTextColor(Color.parseColor(COLOR_UPGRADE))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
                visibility = View.GONE
            }
            versionRow.addView(tvVersion)
            versionRow.addView(tvLatest)
            infoCol.addView(tvName)
            infoCol.addView(versionRow)
            rootRow.addView(infoCol)

            val btnCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val btnUpgrade = MaterialButton(
                parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.pip_btn_upgrade)
                setTextColor(Color.parseColor(COLOR_UPGRADE))
                strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_UPGRADE))
                cornerRadius = dp(6)
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
                ).apply { marginEnd = dp(4) }
                visibility = View.GONE
            }
            val btnUninstall = MaterialButton(
                parent.context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(R.string.pip_btn_uninstall)
                setTextColor(Color.parseColor(COLOR_DANGER))
                strokeColor = ColorStateList.valueOf(Color.parseColor(COLOR_DIVIDER))
                cornerRadius = dp(6)
                textSize = 12f
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
                )
            }
            btnCol.addView(btnUpgrade)
            btnCol.addView(btnUninstall)
            rootRow.addView(btnCol)

            card.addView(rootRow)

            return VH(card).also {
                it.tvName = tvName
                it.tvVersion = tvVersion
                it.tvLatest = tvLatest
                it.btnUpgrade = btnUpgrade
                it.btnUninstall = btnUninstall
            }
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val pkg = items[position]
            holder.tvName.text = pkg.name
            holder.tvVersion.text = pkg.version

            if (pkg.latestVersion != null) {
                holder.tvLatest.visibility = View.VISIBLE
                holder.tvLatest.text = "→ ${pkg.latestVersion}"
                holder.btnUpgrade.visibility = View.VISIBLE
            } else {
                holder.tvLatest.visibility = View.GONE
                holder.btnUpgrade.visibility = View.GONE
            }

            holder.card.setOnClickListener { showPackageDetail(pkg) }
            holder.btnUpgrade.setOnClickListener { doUpgrade(pkg) }
            holder.btnUninstall.setOnClickListener { doUninstall(pkg) }
        }

        override fun getItemCount() = items.size
    }
}
