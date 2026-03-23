package com.tencent.yyds.frag

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tencent.yyds.AgentSettingsActivity
import com.tencent.yyds.databinding.FragmentAgentBinding
import com.tencent.yyds.R
import org.json.JSONArray
import org.json.JSONObject
import pyengine.EngineClient
import kotlin.concurrent.thread

class AgentFragment : Fragment() {

    private var _binding: FragmentAgentBinding? = null
    private val binding get() = _binding!!

    private val logAdapter = AgentLogAdapter()
    private val shortcutsAdapter = ShortcutsAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var pollRunnable: Runnable? = null
    private var currentPhase = "idle"
    private var planDialogShowing = false
    private var logSeenCount = 0  // P0-2: 增量日志传输计数器

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAgentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupButtons()
        checkConfig()
    }

    override fun onResume() {
        super.onResume()
        checkConfig()
        if (isRunning) startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopPolling()
        _binding = null
    }

    // ================================================================
    // 初始化
    // ================================================================

    private fun setupRecyclerView() {
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLogs.adapter = logAdapter
        // ④ 快捷指令横向列表
        binding.rvShortcuts.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvShortcuts.adapter = shortcutsAdapter
        shortcutsAdapter.onClickListener = { sc ->
            binding.etInstruction.setText(sc.instruction)
            binding.etInstruction.setSelection(sc.instruction.length)
        }
        shortcutsAdapter.onLongClickListener = { sc ->
            showDeleteShortcutConfirm(sc)
        }
        loadShortcuts()
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(requireContext(), AgentSettingsActivity::class.java))
        }

        binding.btnHelp.setOnClickListener {
            showHelpSheet()
        }

        binding.btnGoSettings.setOnClickListener {
            startActivity(Intent(requireContext(), AgentSettingsActivity::class.java))
        }

        // 示例指令点击 → 填入输入框
        val exampleClickListener = View.OnClickListener { v ->
            val tv = v as? android.widget.TextView ?: return@OnClickListener
            // 去掉前面的 emoji 前缀
            val text = tv.text.toString().replace(Regex("^[\\p{So}\\p{Cn}\\s]+"), "").trim()
            binding.etInstruction.setText(text)
            binding.etInstruction.setSelection(text.length)
            binding.cardWelcome.visibility = View.GONE
        }
        binding.tvExample1.setOnClickListener(exampleClickListener)
        binding.tvExample2.setOnClickListener(exampleClickListener)
        binding.tvExample3.setOnClickListener(exampleClickListener)
        binding.tvExample4.setOnClickListener(exampleClickListener)

        binding.btnSend.setOnClickListener {
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()

            val instruction = binding.etInstruction.text?.toString()?.trim() ?: ""
            if (instruction.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.msg_agent_input_empty), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startAgent(instruction)
            binding.cardWelcome.visibility = View.GONE
        }

        binding.btnStop.setOnClickListener { stopAgent() }

        // ④ 快捷指令管理按钮 — 长按输入框中内容可保存为快捷指令
        binding.tvShortcutsEdit.setOnClickListener {
            val instruction = binding.etInstruction.text?.toString()?.trim() ?: ""
            if (instruction.isNotEmpty()) {
                showSaveShortcutDialog(instruction)
            } else {
                Toast.makeText(requireContext(), "请先在输入框填写指令再保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showHelpSheet() {
        val act = activity ?: return
        val dialog = BottomSheetDialog(act, R.style.BottomSheetDialogTheme)
        val sheetView = LayoutInflater.from(act).inflate(R.layout.bottom_sheet_agent_help, null)
        sheetView.findViewById<View>(R.id.btnHelpClose).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    // ================================================================
    // 配置检查
    // ================================================================

    private fun checkConfig() {
        thread {
            val body = EngineClient.instance?.httpGetPublic("/agent/config")
            handler.post {
                if (_binding == null) return@post
                // 输入框始终可见
                binding.cardInput.visibility = View.VISIBLE

                var configured = false
                if (body != null) {
                    try {
                        val json = JSONObject(body)
                        configured = when {
                            // CPython 引擎已注入 is_configured 字段
                            json.has("is_configured") -> json.optBoolean("is_configured", false)
                            // Ktor 旧版本直接透传 agent.json，用 api_key 判断
                            else -> json.optString("api_key", "").trim().isNotEmpty()
                        }
                    } catch (_: Exception) { }
                }
                // 引擎不可达或解析失败时，用本地 SharedPreferences 兜底
                if (!configured) {
                    val localConfigured = requireContext().getSharedPreferences("agent", 0)
                        .getBoolean("is_configured", false)
                    if (localConfigured) configured = true
                }

                binding.cardNotConfigured.visibility = if (configured) View.GONE else View.VISIBLE
                val hasLogs = logAdapter.itemCount > 0
                binding.cardWelcome.visibility = if (configured && !hasLogs && !isRunning) View.VISIBLE else View.GONE
                binding.tvHeroSubtitle.text = if (configured) {
                    getString(R.string.agent_desc)
                } else {
                    getString(R.string.agent_need_config)
                }
                checkAgentStatus()
            }
        }
    }

    private fun checkAgentStatus() {
        thread {
            val body = EngineClient.instance?.httpGetPublic("/agent/status") ?: return@thread
            handler.post {
                if (_binding == null) return@post
                try {
                    val json = JSONObject(body)
                    val running = json.optBoolean("running", false)
                    if (running) {
                        val instruction = json.optString("instruction", "")
                        val step = json.optInt("current_step", 0)
                        val maxSteps = json.optInt("max_steps", 25)
                        val action = json.optString("current_action", "")
                        updateRunningUI(true, instruction, step, maxSteps, action)
                        // 加载已有日志
                        val logs = json.optJSONArray("logs")
                        if (logs != null) appendLogs(logs)
                        startPolling()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ================================================================
    // Agent 控制
    // ================================================================

    private fun showAnswer(answer: String) {
        if (answer.isBlank()) return
        binding.cardAnswer.visibility = View.VISIBLE
        binding.tvAnswer.text = answer
        binding.cardWelcome.visibility = View.GONE
    }

    private fun hideAnswer() {
        binding.cardAnswer.visibility = View.GONE
    }

    private fun startAgent(instruction: String) {
        binding.etInstruction.setText("")
        logAdapter.clear()
        logSeenCount = 0  // P0-2: 重置增量计数
        hideAnswer()
        binding.cardResultScreenshot.visibility = View.GONE
        planDialogShowing = false
        updateRunningUI(true, instruction, 0, 25, getString(R.string.agent_starting))

        thread {
            val json = JSONObject().apply { put("instruction", instruction) }
            val result = EngineClient.instance?.httpPostJsonPublic("/agent/run", json.toString())
            handler.post {
                if (_binding == null) return@post
                if (result != null) {
                    try {
                        val resp = JSONObject(result)
                        if (resp.optBoolean("success", false)) {
                            isRunning = true
                            startPolling()
                        } else {
                            val error = resp.optString("error", getString(R.string.msg_agent_start_failed))
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                            updateRunningUI(false)
                        }
                    } catch (_: Exception) {
                        updateRunningUI(false)
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.msg_agent_request_failed), Toast.LENGTH_SHORT).show()
                    updateRunningUI(false)
                }
            }
        }
    }

    private fun stopAgent() {
        thread {
            EngineClient.instance?.httpGetPublic("/agent/stop")
            handler.post {
                if (_binding == null) return@post
                updateRunningUI(false)
                isRunning = false
                stopPolling()
            }
        }
    }

    // ================================================================
    // 状态轮询
    // ================================================================

    private fun startPolling() {
        stopPolling()
        pollRunnable = object : Runnable {
            override fun run() {
                pollStatus()
                handler.postDelayed(this, 1500)
            }
        }
        handler.post(pollRunnable!!)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun pollStatus() {
        thread {
            // P0-2: 使用 ?since=N 增量传输，只取新日志
            val url = "/agent/status?since=$logSeenCount"
            val body = EngineClient.instance?.httpGetPublic(url) ?: return@thread
            handler.post {
                if (_binding == null) return@post
                try {
                    val json = JSONObject(body)
                    val running = json.optBoolean("running", false)
                    val step = json.optInt("current_step", 0)
                    val maxSteps = json.optInt("max_steps", 25)
                    val phase = json.optString("phase", "idle")
                    val plan = json.optString("plan", "")
                    val instruction = json.optString("instruction", "")

                    // ① 计划确认弹窗
                    if (phase == "confirm_pending" && plan.isNotBlank() && !planDialogShowing) {
                        showPlanConfirmDialog(plan)
                    }

                    // ③ 人话化状态播报
                    val humanAction = getHumanActionFromLogs(json.optJSONArray("logs"))
                    updateRunningUI(running, instruction, step, maxSteps, humanAction, phase)

                    // 更新 token 用量
                    val tokenUsage = json.optJSONObject("token_usage")
                    if (tokenUsage != null) {
                        val totalTokens = tokenUsage.optInt("total_tokens", 0)
                        val callCount = tokenUsage.optInt("call_count", 0)
                        if (totalTokens > 0) {
                            binding.tvTokenUsage.visibility = View.VISIBLE
                            binding.tvTokenUsage.text = "🔤 $totalTokens tokens · ${callCount}次调用"
                        }
                    }

                    // P0-2: 增量日志更新 — 追加新日志，记录新的 log_total
                    val newLogs = json.optJSONArray("logs")
                    val logTotal = json.optInt("log_total", -1)
                    if (newLogs != null && newLogs.length() > 0) {
                        appendLogs(newLogs)
                    }
                    if (logTotal >= 0) logSeenCount = logTotal

                    if (!running && isRunning) {
                        isRunning = false
                        stopPolling()
                        planDialogShowing = false
                        // 展示答案（设备查询快速回答 或 任务完成信息）
                        val answer = json.optString("answer", "")
                        if (answer.isNotBlank()) {
                            showAnswer(answer)
                        }
                        // ② 任务完成后展示截图
                        val shotPath = json.optString("result_screenshot_path", "")
                        if (shotPath.isNotBlank()) {
                            loadResultScreenshot(shotPath)
                        }
                    }
                    currentPhase = phase
                    isRunning = running
                } catch (_: Exception) {}
            }
        }
    }

    /** ③ 从最新日志中提取 human_msg 作为进度播报文字 */
    private fun getHumanActionFromLogs(logsArray: JSONArray?): String {
        if (logsArray == null) return ""
        for (i in logsArray.length() - 1 downTo 0) {
            val obj = logsArray.optJSONObject(i) ?: continue
            val hm = obj.optString("human_msg", "")
            if (hm.isNotBlank()) return hm
        }
        return ""
    }

    // ================================================================
    // UI 更新
    // ================================================================

    private fun updateRunningUI(
        running: Boolean,
        instruction: String = "",
        step: Int = 0,
        maxSteps: Int = 25,
        action: String = "",
        phase: String = ""
    ) {
        if (_binding == null) return

        val isPlanPending = phase == "confirm_pending" || phase == "planning"
        binding.layoutRunningBar.visibility = if (running) View.VISIBLE else View.GONE
        binding.layoutStatus.visibility = if (running) View.VISIBLE else View.GONE
        if (running) binding.cardWelcome.visibility = View.GONE
        binding.etInstruction.isEnabled = !running
        binding.btnSend.isEnabled = !running

        if (running) {
            if (isPlanPending) {
                // ① 计划生成中 / 等待确认时，显示特殊状态
                binding.tvStatus.text = if (phase == "planning") "📋 正在制定计划..." else "📋 等待您确认计划"
                binding.tvRunningAction.text = instruction.take(40)
            } else {
                binding.tvStatus.text = getString(R.string.agent_running_step, step, maxSteps)
                // ③ 优先显示人话化播报，回退到通用文字
                binding.tvRunningAction.text = action.ifEmpty { getString(R.string.agent_executing) }
            }
            binding.tvHeroSubtitle.text = instruction.take(50)
        } else {
            binding.tvHeroSubtitle.text = getString(R.string.agent_desc)
        }
    }

    /** P0-2: 增量追加新日志条目，不替换已有条目，避免全量刷新 */
    private fun appendLogs(logsArray: JSONArray) {
        val newItems = mutableListOf<AgentLogItem>()
        for (i in 0 until logsArray.length()) {
            val obj = logsArray.getJSONObject(i)
            newItems.add(parseLogItem(obj))
        }
        if (newItems.isNotEmpty()) {
            logAdapter.appendLogs(newItems)
            binding.rvLogs.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun parseLogItem(obj: org.json.JSONObject) = AgentLogItem(
        step = obj.optInt("step", 0),
        type = obj.optString("type", ""),
        title = obj.optString("title", ""),
        detail = obj.optString("detail", ""),
        humanMsg = obj.optString("human_msg", ""),
        timestamp = obj.optLong("timestamp", 0),
        tokenInfo = obj.optJSONObject("token")?.let { t ->
            TokenInfo(
                role = t.optString("role", ""),
                model = t.optString("model", ""),
                promptTokens = t.optInt("prompt_tokens", 0),
                completionTokens = t.optInt("completion_tokens", 0),
                totalTokens = t.optInt("total_tokens", 0),
                latencyMs = t.optInt("latency_ms", 0),
            )
        },
        tokenTotal = obj.optJSONObject("token_total")?.let { t ->
            TokenTotal(
                totalPrompt = t.optInt("total_prompt", 0),
                totalCompletion = t.optInt("total_completion", 0),
                totalTokens = t.optInt("total_tokens", 0),
                callCount = t.optInt("call_count", 0),
            )
        },
    )

    // ================================================================
    // ① 计划确认弹窗
    // ================================================================

    private fun showPlanConfirmDialog(plan: String) {
        val act = activity ?: return
        planDialogShowing = true
        val dialog = BottomSheetDialog(act, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(act).inflate(R.layout.bottom_sheet_plan_confirm, null)
        view.findViewById<TextView>(R.id.tvPlanContent).text = plan
        view.findViewById<View>(R.id.btnPlanConfirm).setOnClickListener {
            dialog.dismiss()
            planDialogShowing = false
            thread { EngineClient.instance?.httpPostJsonPublic("/agent/plan-confirm", "{}") }
        }
        view.findViewById<View>(R.id.btnPlanReject).setOnClickListener {
            dialog.dismiss()
            planDialogShowing = false
            thread { EngineClient.instance?.httpPostJsonPublic("/agent/plan-reject", "{}") }
        }
        dialog.setOnDismissListener { planDialogShowing = false }
        dialog.setContentView(view)
        dialog.show()
    }

    // ================================================================
    // ② 结果截图展示
    // ================================================================

    private fun loadResultScreenshot(path: String) {
        thread {
            try {
                val body = EngineClient.instance?.httpGetPublic("/agent/result-screenshot") ?: return@thread
                val json = JSONObject(body)
                val data = json.optString("data", "")
                if (data.isBlank()) return@thread
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@thread
                handler.post {
                    if (_binding == null) return@post
                    binding.imgResultScreenshot.setImageBitmap(bitmap)
                    binding.cardResultScreenshot.visibility = View.VISIBLE
                }
            } catch (_: Exception) {}
        }
    }

    // ================================================================
    // ④ 快捷指令 CRUD
    // ================================================================

    private fun loadShortcuts() {
        thread {
            val body = EngineClient.instance?.httpGetPublic("/agent/shortcuts") ?: return@thread
            handler.post {
                if (_binding == null) return@post
                try {
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("shortcuts") ?: return@post
                    val list = mutableListOf<ShortcutItem>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(ShortcutItem(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            instruction = obj.optString("instruction"),
                        ))
                    }
                    shortcutsAdapter.setItems(list)
                    binding.layoutShortcuts.visibility = if (list.isNotEmpty()) View.VISIBLE else View.GONE
                } catch (_: Exception) {}
            }
        }
    }

    private fun showSaveShortcutDialog(instruction: String) {
        val act = activity ?: return
        val dialog = BottomSheetDialog(act, R.style.BottomSheetDialogTheme)
        val view = LayoutInflater.from(act).inflate(R.layout.bottom_sheet_save_shortcut, null)
        val etTitle = view.findViewById<android.widget.EditText>(R.id.etShortcutTitle)
        etTitle.setText(instruction.take(20))
        view.findViewById<View>(R.id.btnShortcutSave).setOnClickListener {
            val title = etTitle.text?.toString()?.trim() ?: instruction.take(20)
            dialog.dismiss()
            thread {
                val body = JSONObject().apply {
                    put("title", title)
                    put("instruction", instruction)
                }
                EngineClient.instance?.httpPostJsonPublic("/agent/shortcuts", body.toString())
                handler.post { loadShortcuts() }
            }
        }
        view.findViewById<View>(R.id.btnShortcutCancel).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showDeleteShortcutConfirm(sc: ShortcutItem) {
        val act = activity ?: return
        android.app.AlertDialog.Builder(act)
            .setTitle("删除快捷指令")
            .setMessage("确认删除「${sc.title}」？")
            .setPositiveButton("删除") { _, _ ->
                thread {
                    EngineClient.instance?.httpDeletePublic("/agent/shortcuts/${sc.id}")
                    handler.post { loadShortcuts() }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

// ================================================================
// 数据模型
// ================================================================

data class AgentLogItem(
    val step: Int,
    val type: String,  // thinking / action / success / error / result / llm_call
    val title: String,
    val detail: String,
    val humanMsg: String = "",  // ③ 人话化播报消息
    val timestamp: Long,
    val tokenInfo: TokenInfo? = null,
    val tokenTotal: TokenTotal? = null,
)

data class ShortcutItem(
    val id: String,
    val title: String,
    val instruction: String,
)

data class TokenInfo(
    val role: String = "",
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val latencyMs: Int = 0,
)

data class TokenTotal(
    val totalPrompt: Int = 0,
    val totalCompletion: Int = 0,
    val totalTokens: Int = 0,
    val callCount: Int = 0,
)
