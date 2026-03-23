package com.tencent.yyds

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.caz.xp.ui.ContextAction
import pyengine.ApkPackageHelper
import pyengine.EngineClient
import pyengine.YyProject
import uiautomator.ExtSystem
import java.io.File
import kotlin.concurrent.thread

/**
 * APK打包配置Activity
 * 用户可配置应用名、版本号、图标等参数，然后触发打包
 */
class PackageActivity : AppCompatActivity() {

    private lateinit var etAppName: TextInputEditText
    private lateinit var etVersion: TextInputEditText
    private lateinit var etPackageName: TextInputEditText
    private lateinit var iconPreview: ImageView
    private lateinit var btnBuild: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    // 运行行为开关
    private lateinit var swAutoRun: MaterialSwitch
    private lateinit var swKeepScreen: MaterialSwitch
    private lateinit var swShowLog: MaterialSwitch
    private lateinit var swExitOnStop: MaterialSwitch
    private lateinit var swEncrypt: MaterialSwitch

    private var selectedIconPath: String? = null
    private var clonedIconPkgName: String? = null  // 克隆图标来源的包名
    private var currentProject: YyProject? = null

    companion object {
        private const val PICK_ICON_REQUEST = 1001

        // 主题色常量，与项目 color.xml 保持一致
        private const val COLOR_PRIMARY_BG = "#FBF7F1"
        private const val COLOR_CARD_BG = "#F8F9FA"
        private const val COLOR_ACCENT = "#264B6F"
        private const val COLOR_ACCENT_LIGHT = "#D6E5ED"
        private const val COLOR_TEXT_PRIMARY = "#1A1A1A"
        private const val COLOR_TEXT_SECONDARY = "#5A6A7A"
        private const val COLOR_TEXT_HINT = "#8A95A0"
        private const val COLOR_DIVIDER = "#E8EAED"
        private const val COLOR_SUCCESS = "#008305"
        private const val COLOR_ICON_BG = "#EAECF0"
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val project = intent.getSerializableExtra(App.KEY_PROJECT) as YyProject?
        if (project == null) {
            finish()
            return
        }
        currentProject = project

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
            ).toInt()
        }

        // ===== 根布局: ConstraintLayout 模拟，用 FrameLayout + fitsSystemWindows =====
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(COLOR_PRIMARY_BG))
            fitsSystemWindows = true
        }

        // ===== 顶部 Toolbar（与 ProjectConfigActivity / LogcatActivity 一致） =====
        val appBarLayout = AppBarLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        val toolbar = MaterialToolbar(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, resources.displayMetrics).toInt()
            )
            setBackgroundColor(Color.parseColor(COLOR_PRIMARY_BG))
            title = getString(R.string.pkg_title)
            setTitleTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        appBarLayout.addView(toolbar)
        rootFrame.addView(appBarLayout)

        // ===== 主内容区域：MaterialCardView 包裹 ScrollView =====
        val contentCard = MaterialCardView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = dp(56 + 8) // toolbar高度 + 间距
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(8)
            }
            radius = dp(8).toFloat()
            setCardBackgroundColor(Color.parseColor(COLOR_CARD_BG))
            cardElevation = dp(1).toFloat()
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }
        scrollView.addView(contentRoot)
        contentCard.addView(scrollView)
        rootFrame.addView(contentCard)

        // ===== 项目名称标签 =====
        contentRoot.addView(TextView(this).apply {
            text = project.name
            setTextColor(Color.parseColor(COLOR_ACCENT))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(20))
        })

        // ===== 基本配置区块 =====
        contentRoot.addView(createSectionLabel(getString(R.string.pkg_section_basic), dp))

        // 应用名输入（OutlinedBox 避免 hint 与输入文字重叠）
        val appNameLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.pkg_app_name_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(
                android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            )
            setHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_TEXT_HINT)))
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(2)
            setBoxCornerRadii(dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat())
            setStartIconDrawable(R.drawable.ic_edit)
            setStartIconTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        etAppName = TextInputEditText(this).apply {
            setText(project.name)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 14f
        }
        appNameLayout.addView(etAppName)
        contentRoot.addView(appNameLayout)

        // 版本号输入
        val versionLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.pkg_version_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(
                android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            )
            setHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_TEXT_HINT)))
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(2)
            setBoxCornerRadii(dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat())
            setStartIconDrawable(R.drawable.ic_label)
            setStartIconTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            setEndIconMode(TextInputLayout.END_ICON_CLEAR_TEXT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        etVersion = TextInputEditText(this).apply {
            setText(project.version)
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 14f
        }
        versionLayout.addView(etVersion)
        contentRoot.addView(versionLayout)

        // 包名输入
        val pkgNameLayout = TextInputLayout(
            this, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.pkg_package_name_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColorStateList(
                android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            )
            setHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_TEXT_HINT)))
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(2)
            setBoxCornerRadii(dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat(), dp(6).toFloat())
            setStartIconDrawable(R.drawable.ic_label)
            setStartIconTintList(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT)))
            helperText = getString(R.string.pkg_package_name_helper)
            setHelperTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_TEXT_HINT)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }
        etPackageName = TextInputEditText(this).apply {
            setText("")
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 14f
        }
        pkgNameLayout.addView(etPackageName)
        contentRoot.addView(pkgNameLayout)

        // ===== 图标选择区块 =====
        contentRoot.addView(createSectionLabel(getString(R.string.pkg_section_icon), dp))

        // 图标选择卡片
        val iconCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor(COLOR_DIVIDER))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }

        iconPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_ICON_BG))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        iconCard.addView(iconPreview)

        // 图标操作按钮组
        val iconBtnGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            }
        }

        iconBtnGroup.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.pkg_btn_choose_icon)
            setTextColor(Color.parseColor(COLOR_ACCENT))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            cornerRadius = dp(6)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { pickIcon() }
        })

        iconBtnGroup.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.pkg_btn_clone_icon)
            setTextColor(Color.parseColor(COLOR_ACCENT))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_ACCENT))
            cornerRadius = dp(6)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { showCloneIconDialog() }
        })

        iconBtnGroup.addView(MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(R.string.pkg_btn_reset_icon)
            setTextColor(Color.parseColor(COLOR_TEXT_HINT))
            strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor(COLOR_DIVIDER))
            cornerRadius = dp(6)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
            )
            setOnClickListener {
                selectedIconPath = null
                clonedIconPkgName = null
                iconPreview.setImageResource(R.mipmap.ic_launcher)
            }
        })

        iconCard.addView(iconBtnGroup)
        contentRoot.addView(iconCard)

        // ===== 运行行为区块 =====
        contentRoot.addView(createSectionLabel(getString(R.string.pkg_section_behavior), dp))

        val behaviorCard = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
            radius = dp(8).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = dp(1).toFloat()
        }
        val behaviorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        swAutoRun = createSwitchRow(behaviorLayout, getString(R.string.pkg_sw_auto_run),
            getString(R.string.pkg_sw_auto_run_desc), false, dp)
        addBehaviorDivider(behaviorLayout, dp)
        swKeepScreen = createSwitchRow(behaviorLayout, getString(R.string.pkg_sw_keep_screen),
            getString(R.string.pkg_sw_keep_screen_desc), true, dp)
        addBehaviorDivider(behaviorLayout, dp)
        swShowLog = createSwitchRow(behaviorLayout, getString(R.string.pkg_sw_show_log),
            getString(R.string.pkg_sw_show_log_desc), true, dp)
        addBehaviorDivider(behaviorLayout, dp)
        swExitOnStop = createSwitchRow(behaviorLayout, getString(R.string.pkg_sw_exit_on_stop),
            getString(R.string.pkg_sw_exit_on_stop_desc), false, dp)
        addBehaviorDivider(behaviorLayout, dp)
        swEncrypt = createSwitchRow(behaviorLayout, getString(R.string.pkg_sw_encrypt),
            getString(R.string.pkg_sw_encrypt_desc), false, dp)

        behaviorCard.addView(behaviorLayout)
        contentRoot.addView(behaviorCard)

        // ===== 打包说明区块 =====
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F4F8"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) }
        }

        infoCard.addView(TextView(this).apply {
            text = getString(R.string.pkg_notes_title)
            setTextColor(Color.parseColor(COLOR_ACCENT))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        })

        val infoItems = listOf(
            getString(R.string.pkg_note_1),
            getString(R.string.pkg_note_2),
            getString(R.string.pkg_note_3),
            getString(R.string.pkg_note_4),
            getString(R.string.pkg_note_5),
            getString(R.string.pkg_note_6)
        )
        for (item in infoItems) {
            infoCard.addView(TextView(this).apply {
                text = "·  $item"
                setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
                textSize = 12f
                lineHeight = dp(20)
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        contentRoot.addView(infoCard)

        // ===== 进度与状态 =====
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            isIndeterminate = true
            visibility = View.GONE
        }
        contentRoot.addView(progressBar)

        tvStatus = TextView(this).apply {
            setTextColor(Color.parseColor(COLOR_ACCENT))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        contentRoot.addView(tvStatus)

        // ===== 打包按钮 =====
        btnBuild = MaterialButton(this).apply {
            text = getString(R.string.pkg_btn_start)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(COLOR_ACCENT))
            cornerRadius = dp(8)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        }
        btnBuild.setOnClickListener { startBuild() }
        contentRoot.addView(btnBuild)

        setContentView(rootFrame)
    }

    /** 创建区块标题标签 */
    private fun createSectionLabel(title: String, dp: (Int) -> Int): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(COLOR_TEXT_SECONDARY))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            setPadding(0, 0, 0, dp(10))
        }
    }

    /** 创建开关行 */
    private fun createSwitchRow(
        parent: LinearLayout, title: String, subtitle: String,
        defaultOn: Boolean, dp: (Int) -> Int
    ): MaterialSwitch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(COLOR_TEXT_PRIMARY))
            textSize = 14f
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor(COLOR_TEXT_HINT))
            textSize = 11.5f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol)
        val sw = MaterialSwitch(this).apply { isChecked = defaultOn; textOn = ""; textOff = "" }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun addBehaviorDivider(parent: LinearLayout, dp: (Int) -> Int) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1
            ).apply { marginStart = dp(16); marginEnd = dp(16) }
            setBackgroundColor(Color.parseColor(COLOR_DIVIDER))
        })
    }

    private fun pickIcon() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(intent, PICK_ICON_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_ICON_REQUEST && resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val tempIcon = File(cacheDir, "pack_icon.png")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempIcon.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                selectedIconPath = tempIcon.absolutePath
                clonedIconPkgName = null
                iconPreview.setImageBitmap(BitmapFactory.decodeFile(selectedIconPath))
            } catch (e: Exception) {
                ContextAction.toast(getString(R.string.pkg_icon_select_fail, e.message))
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showCloneIconDialog() {
        val pd = ProgressDialog(this).apply {
            setMessage(getString(R.string.pkg_loading_apps))
            setCancelable(true)
            show()
        }
        thread {
            val engineOk = EngineClient.ensureEngineRunning()
            if (!engineOk) {
                runOnUiThread {
                    pd.dismiss()
                    ContextAction.toast(getString(R.string.pkg_engine_not_started))
                }
                return@thread
            }
            val apps = EngineClient.getInstalledApps()
            runOnUiThread {
                pd.dismiss()
                if (apps.isEmpty()) {
                    ContextAction.toast(getString(R.string.pkg_no_apps))
                    return@runOnUiThread
                }
                val names = apps.map { "${it["appName"]}  (${it["packageName"]})" }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.pkg_clone_icon_title))
                    .setItems(names) { _, which ->
                        val selected = apps[which]
                        val pkg = selected["packageName"] ?: return@setItems
                        clonedIconPkgName = pkg
                        selectedIconPath = null
                        // 后台获取图标预览
                        thread {
                            val iconBytes = EngineClient.getAppIconBytes(pkg)
                            if (iconBytes != null) {
                                val bmp = BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size)
                                // 同时保存到缓存供打包使用
                                val tempIcon = File(cacheDir, "clone_icon.png")
                                tempIcon.writeBytes(iconBytes)
                                selectedIconPath = tempIcon.absolutePath
                                runOnUiThread {
                                    iconPreview.setImageBitmap(bmp)
                                    ContextAction.toast(getString(R.string.pkg_icon_cloned, selected["appName"]))
                                }
                            } else {
                                runOnUiThread { ContextAction.toast(getString(R.string.pkg_icon_fail)) }
                            }
                        }
                    }
                    .setNegativeButton(getString(R.string.pkg_btn_cancel), null)
                    .show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startBuild() {
        val appName = etAppName.text?.toString()?.trim()
        if (appName.isNullOrBlank()) {
            ContextAction.toast(getString(R.string.pkg_enter_app_name))
            return
        }
        val version = etVersion.text?.toString()?.trim() ?: "1.0"
        val projectName = currentProject?.folderName ?: return

        btnBuild.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.pkg_packaging)
        tvStatus.setTextColor(Color.parseColor(COLOR_ACCENT))

        thread {
            try {
                runOnUiThread { tvStatus.text = getString(R.string.pkg_starting_engine) }
                val engineOk = EngineClient.ensureEngineRunning()
                if (!engineOk) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        btnBuild.isEnabled = true
                        tvStatus.setTextColor(Color.RED)
                        tvStatus.text = getString(R.string.pkg_engine_fail)
                    }
                    return@thread
                }

                // 处理图标：本地文件 -> base64传输到工作进程
                var iconPathForEngine: String? = null
                if (selectedIconPath != null) {
                    val iconFile = File(selectedIconPath!!)
                    if (iconFile.exists()) {
                        iconPathForEngine = "/data/local/tmp/pack_icon.png"
                        val iconBytes = iconFile.readBytes()
                        val b64 = android.util.Base64.encodeToString(iconBytes, android.util.Base64.NO_WRAP)
                        EngineClient.writeFileText("/data/local/tmp/pack_icon.b64", b64)
                        EngineClient.writeFileText(
                            "/data/local/tmp/pack_icon_decode.sh",
                            "base64 -d /data/local/tmp/pack_icon.b64 > /data/local/tmp/pack_icon.png"
                        )
                        ExtSystem.shell("sh /data/local/tmp/pack_icon_decode.sh 2>/dev/null")
                    }
                }

                // 包名（空则不传，使用默认）
                val packageName = etPackageName.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

                runOnUiThread { tvStatus.text = getString(R.string.pkg_packaging) }

                val configMap = mutableMapOf<String, Any?>()
                configMap["appName"] = appName
                configMap["projectName"] = projectName
                configMap["version"] = version
                configMap["iconPath"] = iconPathForEngine
                if (packageName != null) configMap["packageName"] = packageName
                // 运行行为
                configMap["autoRunOnOpen"] = swAutoRun.isChecked
                configMap["autoStart"] = swAutoRun.isChecked  // 向下兼容
                configMap["keepScreenOn"] = swKeepScreen.isChecked
                configMap["showLog"] = swShowLog.isChecked
                configMap["exitOnScriptStop"] = swExitOnStop.isChecked
                configMap["encryptScripts"] = swEncrypt.isChecked
                val configJson = com.google.gson.Gson().toJson(configMap)
                val result = EngineClient.buildApkViaEngine(configJson)

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnBuild.isEnabled = true
                    if (result != null && result.success) {
                        tvStatus.setTextColor(Color.parseColor(COLOR_SUCCESS))
                        val sizeMb = String.format("%.1f", result.fileSize / 1024.0 / 1024.0)
                        tvStatus.text = getString(R.string.pkg_success, sizeMb, result.durationMs / 1000, result.outputPath)
                    } else {
                        tvStatus.setTextColor(Color.RED)
                        tvStatus.text = getString(R.string.pkg_fail, result?.error ?: getString(R.string.pkg_no_response))
                    }
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("package build failed", e)
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    btnBuild.isEnabled = true
                    tvStatus.setTextColor(Color.RED)
                    tvStatus.text = getString(R.string.pkg_exception, e.message)
                }
            }
        }
    }
}
