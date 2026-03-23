package com.tencent.yyds

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.AdapterView.OnItemSelectedListener
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tencent.yyds.databinding.ActivityProjectConfigBinding
import me.caz.xp.ui.ContextAction
import okhttp3.internal.toImmutableMap
import org.yaml.snakeyaml.Yaml
import pyengine.EngineClient
import pyengine.RPC_MAP_KEY
import pyengine.YyProject
import uiautomator.Const
import uiautomator.ExtSystem
import kotlin.concurrent.thread


class ProjectConfigActivity : AppCompatActivity() {
    lateinit var binding: ActivityProjectConfigBinding

    private val defaultGson = GsonBuilder().apply { setLenient(); }.create()
    private var initJson:String? = null
    private var cacheConfig = mutableMapOf<String, Any>()
    private lateinit var uiPath:String
    private lateinit var configSavePath:String
    private val requiredFields = mutableMapOf<String, View>()
    private var currentProject: YyProject? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectConfigBinding.inflate(layoutInflater)
        supportActionBar?.hide()
        val project = intent.getSerializableExtra(App.KEY_PROJECT) as YyProject?
        if (project == null) {
            finish()
            return
        }
        uiPath = project.getUiYamlPath()
        configSavePath = project.getConfigFilePath()
        setContentView(binding.root)
        currentProject = project
        binding.topAppBar.setOnMenuItemClickListener { item ->
            when(item.itemId) {
                R.id.save_config -> {
                    if (!validateRequired()) return@setOnMenuItemClickListener true
                    thread {
                        val saved = saveConfig()
                        runOnUiThread {
                            if (saved) {
                                ContextAction.toast(getString(R.string.projconfig_config_saved))
                            } else {
                                ContextAction.toast(getString(R.string.projconfig_config_no_change))
                            }
                        }
                    }
                }
            }
            true
        }
        binding.topAppBar.setNavigationIcon(R.drawable.ic_back)
        binding.topAppBar.setNavigationOnClickListener {
            val intent = Intent(this,
                MainActivity::class.java).apply { putExtra(Const.NAV_TO_ACTIVITY_FRAGMENT, 1) }
            startActivity(intent, intent.extras)
        }
        binding.topAppBar.title = getString(R.string.projconfig_config_title_fmt, project.name)
        // 通过守护进程读取外部存储文件（守护进程有ROOT/SHELL权限，避免App进程权限问题）
        thread {
            EngineClient.ensureEngineRunning()
            loadConfig()
            val yamlContent = EngineClient.readFileText(uiPath)
            runOnUiThread {
                if (yamlContent == null) {
                    showEmptyGuide()
                } else {
                    try {
                        readYaml(yamlContent)
                    } catch (e:Exception) {
                        val v = LayoutInflater.from(this).inflate(R.layout.m_textview, null) as TextView
                        val textView = v as TextView
                        v.setTextColor(Color.RED)
                        v.textSize = v.textSize / 2
                        textView.text = getString(R.string.projconfig_yaml_error) + "\n" + Log.getStackTraceString(e)
                        binding.configRootLayout.addView(textView)
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        if (requiredFields.isEmpty() || validateRequired()) {
            thread { saveConfig() }
        }
    }

    override fun onDestroy() {
        if (cacheConfig.isNotEmpty()) {
            thread { saveConfig() }
        }
        super.onDestroy()
    }

    private fun saveConfig():Boolean {
        try {
            cacheConfig.forEach { t, u -> ExtSystem.printDebugLog("Save: ${t}=${u}") }
            val json = defaultGson.toJson(cacheConfig, LinkedHashMap::class.java)
            if (json != initJson && cacheConfig.isNotEmpty()) {
                ExtSystem.printDebugLog(json)
                // ContextAction.toast(json)
                return EngineClient.writeFileText(configSavePath, json)
            }
        } catch (e:Exception) {
            ExtSystem.printDebugError("配置保存失败", e)
        }
        return false
    }

    private fun loadConfig() {
        val text = EngineClient.readFileText(configSavePath)
        if (text != null) {
            initJson = text
            cacheConfig = defaultGson.fromJson(initJson, cacheConfig::class.java)
        }
    }

    private fun validateRequired(): Boolean {
        for ((name, view) in requiredFields) {
            when (view) {
                is TextInputLayout -> {
                    val editText = view.findViewById<TextInputEditText>(R.id.edit_value)
                    if (editText.text.isNullOrBlank()) {
                        view.error = getString(R.string.projconfig_field_required_short)
                        editText.requestFocus()
                        ContextAction.toast(getString(R.string.projconfig_fill_required))
                        return false
                    } else {
                        view.error = null
                    }
                }
            }
        }
        return true
    }

    @SuppressLint("SetTextI18n")
    private fun showEmptyGuide() {
        val root = binding.configRootLayout
        val dp = { value: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt() }

        // 标题
        val titleTv = TextView(this).apply {
            text = getString(R.string.projconfig_no_ui_config)
            setTextColor(Color.parseColor("#264B6F"))
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(titleTv)

        // 说明
        val descTv = TextView(this).apply {
            text = getString(R.string.projconfig_no_config_desc)
            setTextColor(Color.parseColor("#555555"))
            textSize = 14f
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(descTv)

        // 分割线
        root.addView(MaterialDivider(this).apply {
            setPadding(0, dp(4), 0, dp(4))
        })

        // 示例标题
        val exTitleTv = TextView(this).apply {
            text = getString(R.string.projconfig_example_title)
            setTextColor(Color.parseColor("#264B6F"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(exTitleTv)

        // 示例代码
        val exampleYaml = """
# ====== ui.yml 配置示例 ======
# 支持 6 种组件，命名格式: 类型-名称

# 文本显示
text-notice:
  value: "欢迎使用自动化脚本"
  color: "#008305"
  size: 16

div-1:

# 开关按钮 (值类型: bool)
check-enable:
  title: "启用功能"
  value: true

space-1:
  height: 20

# 下拉选择 (值类型: str)
select-mode:
  title: "运行模式"
  value: ["模式一", "模式二", "模式三"]

space-2:
  height: 20

# 输入框 (值类型: str)
edit-user:
  title: "账号"
  value: ""
  hint: "请输入手机号或邮箱"   # 占位提示
  required: true               # 必填校验

edit-password:
  title: "密码"
  value: ""
  input: password              # 密码掩码

edit-delay:
  title: "循环间隔(秒)"
  value: "3"
  input: number                # 数字键盘
        """.trimIndent()

        val codeTv = TextView(this).apply {
            text = exampleYaml
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(codeTv)

        // 分割线
        root.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        // Python 读取示例
        val pyTitleTv = TextView(this).apply {
            text = getString(R.string.projconfig_python_title)
            setTextColor(Color.parseColor("#264B6F"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(pyTitleTv)

        val pyCodeTv = TextView(this).apply {
            text = """from yyds import Config

# 读取输入框的值
user = Config.read_config_value("edit-user")

# 读取开关的值 (bool)
enabled = Config.read_config_value("check-enable")

# 读取下拉选择的值 (str)
mode = Config.read_config_value("select-mode")

# 读取数字并转换
delay = int(Config.read_config_value("edit-delay") or "3")"""
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        root.addView(pyCodeTv)

        // input 属性说明
        root.addView(View(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor("#DDDDDD"))
        })

        val attrTitleTv = TextView(this).apply {
            text = getString(R.string.projconfig_attr_title)
            setTextColor(Color.parseColor("#264B6F"))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(attrTitleTv)

        val attrDescTv = TextView(this).apply {
            text = """input: text        单行文本 (默认)
input: password     密码掩码输入
input: number       数字键盘
input: multiline    多行文本

hint: "提示文字"    输入框占位提示
required: true      保存时校验非空"""
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(attrDescTv)

        // 底部按钮
        root.addView(Space(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))
        })

        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(24))
        }

        val btnRun = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.projconfig_run_script)
            setOnClickListener {
                currentProject?.start()
                ContextAction.toast(getString(R.string.projconfig_script_starting))
                finish()
            }
        }
        btnLayout.addView(btnRun)
        root.addView(btnLayout)
    }

    private fun readYaml(yamlContent: String) {
        ExtSystem.printDebugLog("解析YML")
        val yaml = Yaml()
        val obj: Map<String, LinkedHashMap<String, Any>?> =
            yaml.load(yamlContent)
        for (o in obj) {
            ExtSystem.printDebugLog(o.key, o?.key?.javaClass)
            val uiName = o.key as String
            var v: View? = null
            val uiComName = uiName.split("-")[0]
            val isRequired = o.value?.get("required") == true
            try {
                when (uiComName) {
                    "text" -> {
                        v = LayoutInflater.from(this).inflate(R.layout.m_textview, null) as TextView
                        val textView = v as TextView
                        if (cacheConfig[uiName] != null) {
                            textView.text = cacheConfig[uiName] as String
                        } else {
                            (o.value?.get("value") as String?).let { textView.text = it as String }
                            cacheConfig[uiName] = textView.text.toString()
                        }
                        o.value?.get("color")
                            ?.let { textView.setTextColor(Color.parseColor(it as String)) }
                        o.value?.get("bold")
                            ?.let { if (it as Boolean) textView.typeface = Typeface.DEFAULT }
                        o.value?.get(RPC_MAP_KEY.CACHE_FILE_SIZE)?.let { textView.textSize = (it as Int).toFloat() }
                    }
                    "check" -> {
                        v = LayoutInflater.from(this).inflate(R.layout.m_checkbox, null) as MaterialCheckBox
                        val checkBox = v as MaterialCheckBox
                        checkBox.text = o.value?.get("title") as String
                        // 保存 bool 配置
                        if (cacheConfig[uiName] != null) {
                            if (cacheConfig[uiName] is String) {
                                checkBox.isChecked = (cacheConfig[uiName] as String).equals("true", true)
                            } else {
                                checkBox.isChecked = cacheConfig[uiName] as Boolean
                            }
                        } else {
                            checkBox.isChecked = o.value?.get("value") as Boolean
                            cacheConfig[uiName] = checkBox.isChecked
                        }
                        checkBox.setOnCheckedChangeListener { _, isChecked ->
                            cacheConfig[uiName] = isChecked
                            ExtSystem.printDebugLog("Cb改变:${isChecked}")
                        }
                    }
                    "edit" -> {
                        v = (LayoutInflater.from(this)
                            .inflate(R.layout.m_edit, null) as TextInputLayout).apply {
                            hint = o.value?.get("title") as CharSequence? ?: getString(R.string.projconfig_edit_default_hint)
                        }
                        val editText = v.findViewById<TextInputEditText>(R.id.edit_value)

                        // P1: input 属性 (password / number / multiline)
                        val inputMode = o.value?.get("input") as String? ?: "text"
                        when (inputMode) {
                            "password" -> {
                                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                                editText.transformationMethod = PasswordTransformationMethod.getInstance()
                                (v as TextInputLayout).endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                            }
                            "number" -> {
                                editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            }
                            "multiline" -> {
                                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                editText.isSingleLine = false
                                editText.maxLines = 8
                                editText.minLines = 3
                            }
                        }

                        // P1: hint 属性
                        o.value?.get("hint")?.let {
                            editText.hint = it as String
                        }

                        // P1: required 属性 — 标题加 * 标记
                        if (isRequired) {
                            val title = o.value?.get("title") as String? ?: getString(R.string.projconfig_edit_default_title)
                            (v as TextInputLayout).hint = "$title *"
                            requiredFields[uiName] = v
                        }

                        // 保存 string 配置
                        val c = cacheConfig[uiName] as String?
                        if (c != null) {
                            editText.setText(c)
                        } else {
                            editText.setText(o.value?.get("value") as CharSequence)
                            cacheConfig[uiName] = editText.text.toString()
                        }
                        editText.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                            ) {}

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                            ) {}

                            override fun afterTextChanged(s: Editable?) {
                                cacheConfig[uiName] = editText.text.toString()
                                // 输入后清除 required 错误提示
                                if (isRequired && !editText.text.isNullOrBlank()) {
                                    (v as TextInputLayout).error = null
                                }
                            }
                        })
                    }
                    "space" -> {
                        v = (LayoutInflater.from(this)
                            .inflate(R.layout.m_space, null) as Space).apply {
                            layoutParams =
                                ViewGroup.LayoutParams(width, o.value?.get("height") as Int)
                        }
                    }
                    "div" -> {
                        v = (LayoutInflater.from(this)
                            .inflate(R.layout.m_div, null) as MaterialDivider)
                    }
                    "select" -> {
                        v = (LayoutInflater.from(this)
                            .inflate(R.layout.m_spinner, null) as LinearLayout)
                        val spinnerTitle = v.findViewById<TextView>(R.id.spinner_title)
                        o.value?.get("title")?.let { spinnerTitle.text = it as String }
                        o.value?.get("color")
                            ?.let { spinnerTitle.setTextColor(Color.parseColor(it as String)) }
                        o.value?.get("background")?.let {
                            v.setBackgroundColor(Color.parseColor(it as String))
                        }
                        val spinner = v.findViewById<Spinner>(R.id.spinner)
                        val items =  o.value?.get("value") as ArrayList<String>
                        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
                        val c = cacheConfig[uiName] as String?
                        if (c != null) {
                            spinner.setSelection(items.indexOf(c))
                        } else {
                            cacheConfig[uiName] = items[0]
                        }
                        spinner.onItemSelectedListener = object : OnItemSelectedListener {
                            override fun onItemSelected(
                                parent: AdapterView<*>?,
                                view: View?,
                                position: Int,
                                id: Long
                            ) {
                                cacheConfig[uiName] = spinner.selectedItem.toString()
                            }
                            override fun onNothingSelected(parent: AdapterView<*>?) {
                            }
                        }
                    }
                }
            } catch (e: java.lang.Exception) {
                ExtSystem.printDebugError(e)
            }
            if (v != null) {
                binding.configRootLayout.addView(v)
            }
        }
    }
}