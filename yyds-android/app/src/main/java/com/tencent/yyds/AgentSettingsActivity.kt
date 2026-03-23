package com.tencent.yyds

import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tencent.yyds.databinding.ActivityAgentSettingsBinding
import kotlinx.coroutines.*
import org.json.JSONObject

class AgentSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentSettingsBinding

    // 服务商 ID 列表（与 Python PRESETS 对齐，autoglm 为默认推荐）
    private val providerIds = listOf(
        "autoglm", "doubao", "qwen", "zhipu", "deepseek", "moonshot", "baichuan", "minimax",
        "siliconflow", "openai", "anthropic", "gemini", "mistral", "groq", "xai",
        "openrouter", "custom"
    )

    // 服务商显示名（与 providerIds 一一对应）
    private val providerLabels by lazy {
        listOf(
            getString(R.string.agent_provider_autoglm),
            getString(R.string.agent_provider_volcengine),
            getString(R.string.agent_provider_aliyun),
            getString(R.string.agent_provider_zhipu),
            getString(R.string.agent_provider_deepseek),
            getString(R.string.agent_provider_moonshot),
            getString(R.string.agent_provider_baichuan),
            getString(R.string.agent_provider_minimax),
            getString(R.string.agent_provider_siliconflow),
            getString(R.string.agent_provider_openai),
            getString(R.string.agent_provider_anthropic),
            getString(R.string.agent_provider_gemini),
            getString(R.string.agent_provider_mistral),
            getString(R.string.agent_provider_groq),
            getString(R.string.agent_provider_xai),
            getString(R.string.agent_provider_openrouter),
            getString(R.string.agent_provider_custom),
        )
    }

    // 每个服务商的预置模型列表
    private val providerModels = mapOf(
        "autoglm" to listOf("autoglm-phone"),
        "doubao" to listOf("doubao-1.5-vision-pro-32k", "doubao-1.5-vision-pro-256k", "doubao-vision-pro-32k"),
        "qwen" to listOf("qwen-vl-max", "qwen-vl-plus", "qwen-omni-turbo"),
        "zhipu" to listOf("glm-4v-plus-0111", "glm-4v-flash", "glm-4v"),
        "deepseek" to listOf("deepseek-chat"),
        "moonshot" to listOf("moonshot-v1-auto", "moonshot-v1-128k", "moonshot-v1-32k"),
        "baichuan" to listOf("Baichuan4-Turbo", "Baichuan4-Air"),
        "minimax" to listOf("MiniMax-VL-01", "abab6.5s-chat"),
        "siliconflow" to listOf("Pro/Qwen/Qwen2.5-VL-7B-Instruct", "deepseek-ai/DeepSeek-V3"),
        "openai" to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1", "o1-mini"),
        "anthropic" to listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022"),
        "gemini" to listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.5-pro"),
        "mistral" to listOf("mistral-large-latest", "pixtral-large-latest", "mistral-small-latest"),
        "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768"),
        "xai" to listOf("grok-2-vision-1212", "grok-2-1212", "grok-beta"),
        "openrouter" to listOf("openai/gpt-4o", "anthropic/claude-sonnet-4-20250514", "google/gemini-2.5-flash"),
    )

    // 服务商提示文案
    private val providerHints by lazy {
        mapOf(
            "autoglm" to getString(R.string.agent_hint_autoglm),
            "doubao" to getString(R.string.agent_hint_doubao),
            "qwen" to getString(R.string.agent_hint_qwen),
            "zhipu" to getString(R.string.agent_hint_zhipu),
            "deepseek" to getString(R.string.agent_hint_deepseek),
            "moonshot" to getString(R.string.agent_hint_moonshot),
            "baichuan" to getString(R.string.agent_hint_baichuan),
            "minimax" to getString(R.string.agent_hint_minimax),
            "siliconflow" to getString(R.string.agent_hint_siliconflow),
            "openai" to getString(R.string.agent_hint_openai),
            "anthropic" to getString(R.string.agent_hint_anthropic),
            "gemini" to getString(R.string.agent_hint_gemini),
            "mistral" to getString(R.string.agent_hint_mistral),
            "groq" to getString(R.string.agent_hint_groq),
            "xai" to getString(R.string.agent_hint_xai),
            "openrouter" to getString(R.string.agent_hint_openrouter),
            "custom" to getString(R.string.agent_hint_custom),
        )
    }

    private var currentProvider = "autoglm"
    private var currentModelList = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 每个服务商独立缓存 API Key / 模型 / Base URL
    private data class ProviderCache(var apiKey: String = "", var model: String = "", var baseUrl: String = "")
    private val providerCacheMap = mutableMapOf<String, ProviderCache>()
    private var suppressProviderChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProviderSpinner()
        setupModelSpinner()
        setupAdvancedOptions()
        setupButtons()
        loadConfig()
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter
        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val provider = providerIds[pos]
                onProviderChanged(provider)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupModelSpinner() {
        binding.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (pos >= 0 && pos < currentModelList.size) {
                    val model = currentModelList[pos]
                    // 最后一项"自定义模型..."显示输入框
                    val isCustom = model == CUSTOM_MODEL_ITEM
                    binding.etCustomModel.visibility = if (isCustom) View.VISIBLE else View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun saveCurrentToCache() {
        val cache = providerCacheMap.getOrPut(currentProvider) { ProviderCache() }
        cache.apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        cache.model = getSelectedModel()
        cache.baseUrl = binding.etBaseUrl.text?.toString()?.trim() ?: ""
        persistCache()
    }

    private fun restoreFromCache(provider: String) {
        val cache = providerCacheMap[provider]
        binding.etApiKey.setText(cache?.apiKey ?: "")
        binding.etBaseUrl.setText(cache?.baseUrl ?: "")
        // 恢复模型选择
        val model = cache?.model ?: ""
        if (model.isNotEmpty()) {
            val modelIndex = currentModelList.indexOf(model)
            if (modelIndex >= 0) {
                binding.spinnerModel.setSelection(modelIndex)
            } else {
                val customIdx = currentModelList.indexOf(CUSTOM_MODEL_ITEM)
                if (customIdx >= 0) {
                    binding.spinnerModel.setSelection(customIdx)
                    binding.etCustomModel.visibility = View.VISIBLE
                    binding.etCustomModel.setText(model)
                }
            }
        } else {
            binding.spinnerModel.setSelection(0)
            binding.etCustomModel.visibility = View.GONE
        }
    }

    private fun onProviderChanged(provider: String) {
        if (suppressProviderChange) return
        // 切换前：将当前 UI 值存入旧服务商缓存
        if (currentProvider != provider && binding.etApiKey.text != null) {
            saveCurrentToCache()
        }
        currentProvider = provider
        TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)

        // 更新提示文案
        binding.tvProviderHint.text = providerHints[provider] ?: ""

        // 更新模型下拉列表
        val models = providerModels[provider] ?: emptyList()
        currentModelList.clear()
        currentModelList.addAll(models)
        currentModelList.add(CUSTOM_MODEL_ITEM) // 末尾加"自定义模型..."
        val modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currentModelList)
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = modelAdapter

        // custom 服务商显示 Base URL 输入
        binding.layoutCustomConfig.visibility = if (provider == "custom") View.VISIBLE else View.GONE
        // 隐藏自定义模型输入
        binding.etCustomModel.visibility = View.GONE

        // 恢复目标服务商的缓存
        restoreFromCache(provider)
    }

    private fun setupAdvancedOptions() {
        binding.layoutAdvancedBody.visibility = View.GONE
        binding.layoutAdvancedHeader.setOnClickListener {
            TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
            val show = binding.layoutAdvancedBody.visibility != View.VISIBLE
            binding.layoutAdvancedBody.visibility = if (show) View.VISIBLE else View.GONE
            binding.ivAdvancedArrow.animate().rotation(if (show) 180f else 0f).setDuration(200).start()
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTestConnection.setOnClickListener { testConnection() }
    }

    private fun getSelectedModel(): String {
        val pos = binding.spinnerModel.selectedItemPosition
        if (pos < 0 || pos >= currentModelList.size) return ""
        val model = currentModelList[pos]
        return if (model == CUSTOM_MODEL_ITEM) {
            binding.etCustomModel.text?.toString()?.trim() ?: ""
        } else {
            model
        }
    }

    private fun loadConfig() {
        // 先从 SharedPreferences 恢复本地缓存
        loadCache()

        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    pyengine.EngineClient.httpGet("/agent/config")
                }
                val obj = JSONObject(json)
                val provider = obj.optString("provider", "autoglm")
                val model = obj.optString("model", "")
                val apiKey = obj.optString("api_key", "")
                val baseUrl = obj.optString("base_url", "")
                val maxSteps = obj.optInt("max_steps", 20)
                val useReflector = obj.optBoolean("use_reflector", true)
                val useUiDump = obj.optBoolean("use_ui_dump", true)

                // 将服务端返回的当前配置写入对应服务商缓存
                val cache = providerCacheMap.getOrPut(provider) { ProviderCache() }
                cache.apiKey = apiKey
                cache.model = model
                cache.baseUrl = baseUrl
                persistCache()

                // 阻止 Spinner 触发 onProviderChanged 覆盖缓存
                suppressProviderChange = true
                val providerIndex = providerIds.indexOf(provider).coerceAtLeast(0)
                binding.spinnerProvider.setSelection(providerIndex)
                currentProvider = provider
                suppressProviderChange = false

                // 手动触发 UI 更新（模型列表等）
                TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
                binding.tvProviderHint.text = providerHints[provider] ?: ""
                val models = providerModels[provider] ?: emptyList()
                currentModelList.clear()
                currentModelList.addAll(models)
                currentModelList.add(CUSTOM_MODEL_ITEM)
                val modelAdapter = ArrayAdapter(this@AgentSettingsActivity, android.R.layout.simple_spinner_item, currentModelList)
                modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                binding.spinnerModel.adapter = modelAdapter
                binding.layoutCustomConfig.visibility = if (provider == "custom") View.VISIBLE else View.GONE
                binding.etCustomModel.visibility = View.GONE

                // 延迟设置模型（等 adapter 生效后）
                binding.spinnerModel.post {
                    if (model.isNotEmpty()) {
                        val modelIndex = currentModelList.indexOf(model)
                        if (modelIndex >= 0) {
                            binding.spinnerModel.setSelection(modelIndex)
                        } else {
                            val customIdx = currentModelList.indexOf(CUSTOM_MODEL_ITEM)
                            if (customIdx >= 0) {
                                binding.spinnerModel.setSelection(customIdx)
                                binding.etCustomModel.visibility = View.VISIBLE
                                binding.etCustomModel.setText(model)
                            }
                        }
                    }
                }

                binding.etApiKey.setText(apiKey)
                binding.etBaseUrl.setText(baseUrl)
                binding.etMaxSteps.setText(maxSteps.toString())
                binding.switchReflector.isChecked = useReflector
                binding.switchUiDump.isChecked = useUiDump

                binding.switchFloatingWindow.isChecked = obj.optBoolean("show_floating_window", true)

            } catch (e: Exception) {
                // 首次使用，无配置；若本地有缓存也会被恢复
            }
        }
    }

    private fun saveConfig() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.agent_api_key_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val model = getSelectedModel()
        // 保存时同步更新本地缓存
        saveCurrentToCache()
        val body = JSONObject().apply {
            put("provider", currentProvider)
            put("api_key", apiKey)
            put("model", model)
            if (currentProvider == "custom") {
                put("base_url", binding.etBaseUrl.text?.toString()?.trim() ?: "")
            }
            put("max_steps", binding.etMaxSteps.text?.toString()?.toIntOrNull() ?: 20)
            put("use_reflector", binding.switchReflector.isChecked)
            put("use_ui_dump", binding.switchUiDump.isChecked)
            put("show_floating_window", binding.switchFloatingWindow.isChecked)
        }
        // 关闭悬浮窗时停止当前运行的服务（开启时不需要主动启动，Agent运行时会自动启动）
        if (!binding.switchFloatingWindow.isChecked && AgentOverlayService.isRunning()) {
            AgentOverlayService.stop(this)
        }
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    pyengine.EngineClient.httpPostJson("/agent/config", body.toString())
                }
                val ok = resp != null && JSONObject(resp).optBoolean("success", false)
                if (ok) {
                    // 本地标记已配置，供 AgentFragment 兜底判断
                    getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("is_configured", true).apply()
                    Toast.makeText(this@AgentSettingsActivity, getString(R.string.agent_save_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@AgentSettingsActivity, getString(R.string.agent_save_fail), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AgentSettingsActivity, getString(R.string.agent_save_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testConnection() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.agent_api_key_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val model = getSelectedModel()
        val body = JSONObject().apply {
            put("provider", currentProvider)
            put("api_key", apiKey)
            put("model", model)
            if (currentProvider == "custom") {
                put("base_url", binding.etBaseUrl.text?.toString()?.trim() ?: "")
            }
        }
        binding.tvTestResult.text = getString(R.string.agent_test_connecting)
        binding.tvTestResult.visibility = View.VISIBLE
        binding.tvTestResult.setTextColor(0xFF888888.toInt())

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    pyengine.EngineClient.httpPostJson("/agent/test-connection", body.toString())
                }
                val obj = JSONObject(result)
                if (obj.optBoolean("success", false)) {
                    binding.tvTestResult.setTextColor(0xFF4CAF50.toInt())
                    binding.tvTestResult.text = getString(R.string.agent_test_success)
                } else {
                    binding.tvTestResult.setTextColor(0xFFF44336.toInt())
                    binding.tvTestResult.text = obj.optString("error", getString(R.string.agent_test_request_fail))
                }
            } catch (e: Exception) {
                binding.tvTestResult.setTextColor(0xFFF44336.toInt())
                binding.tvTestResult.text = getString(R.string.agent_test_request_fail)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---- 本地缓存持久化（SharedPreferences） ----

    private fun persistCache() {
        val prefs = getSharedPreferences("agent_provider_cache", MODE_PRIVATE)
        val editor = prefs.edit()
        val root = JSONObject()
        for ((pid, c) in providerCacheMap) {
            root.put(pid, JSONObject().apply {
                put("api_key", c.apiKey)
                put("model", c.model)
                put("base_url", c.baseUrl)
            })
        }
        editor.putString("cache", root.toString()).apply()
    }

    private fun loadCache() {
        val prefs = getSharedPreferences("agent_provider_cache", MODE_PRIVATE)
        val raw = prefs.getString("cache", null) ?: return
        try {
            val root = JSONObject(raw)
            for (pid in root.keys()) {
                val obj = root.getJSONObject(pid)
                providerCacheMap[pid] = ProviderCache(
                    apiKey = obj.optString("api_key", ""),
                    model = obj.optString("model", ""),
                    baseUrl = obj.optString("base_url", "")
                )
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val CUSTOM_MODEL_ITEM = "✏️ 自定义模型名..."
    }
}

