package com.tencent.yyds

import android.os.Bundle
import android.os.SystemClock
import android.text.Html
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.tencent.yyds.databinding.ActivityLogcatBinding
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import uiautomator.ExtSystem
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LogcatActivity : AppCompatActivity() {
    lateinit var binding: ActivityLogcatBinding
    private val isViewDestroy: AtomicBoolean = AtomicBoolean(false)
    // 后面得改
    private fun receiveLogThread() {
        thread(isDaemon = true) {
            var isEndNewLine = false
            while(true) {
                if (isViewDestroy.get()) break
                if (EngineClient.logHasnext()) {
                    var nextLogLine = EngineClient.nextLog()
                    if (nextLogLine != null) {
                        // 过滤悬浮控制台内部命令，不在日志页面显示
                        if (nextLogLine.contains(EngineClient.CONSOLE_CMD_PREFIX)) continue
                        nextLogLine = nextLogLine.replace("\n\n", "\n")
                        if (!isEndNewLine && nextLogLine.isNotBlank()) {
                            nextLogLine = "\n" + nextLogLine
                        }
                        isEndNewLine = nextLogLine.endsWith("\n")

                        val a = if (nextLogLine.contains("error", true) || nextLogLine.contains("Exception") ||nextLogLine.contains("err:")) {
                            nextLogLine = nextLogLine.replace("err:", "").replace("out:", "")
                            Html.fromHtml("<font color=red>$nextLogLine</font>", Html.FROM_HTML_MODE_LEGACY)
                        } else {
                            nextLogLine.replace("out:", "")
                        }

                        ContextAction.uiThread {
                            binding.tvConsole.append(a)
                            binding.tvConsole.postDelayed({ binding.sv.fullScroll(ScrollView.FOCUS_DOWN) }, 100)
                        }
                    }
                } else {
                    SystemClock.sleep(500)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isViewDestroy.set(true)
        // EngineClient.closeLogConnect()
    }

    override fun onResume() {
        super.onResume()
        EngineClient.ensureLogConnect()
        isViewDestroy.set(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = ActivityLogcatBinding.inflate(layoutInflater)
        supportActionBar?.hide()
        binding.topAppBar.setNavigationIcon(R.drawable.ic_back)
        binding.topAppBar.setNavigationOnClickListener {
            onBackPressed()
        }
        setContentView(binding.root)
        binding.tvConsole.setOnLongClickListener {
            binding.tvConsole.text = ""
            true
        }
        receiveLogThread()
        ExtSystem.printDebugLog("onCreate!")
    }
}