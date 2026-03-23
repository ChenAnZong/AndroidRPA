package common

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import com.tencent.yyds.App
import com.tencent.yyds.R
import uiautomator.ExtSystem
import uiautomator.MediaScannerUtil
import yyapp.YyInput
import java.io.File
import kotlin.system.exitProcess


class YyInputService : InputMethodService() {

    override fun onCreateInputView(): View {
        val view =  layoutInflater.inflate(R.layout.input_view, null)
        val tv = view.findViewById<TextView>(R.id.tv_yy_input)
        tv.setOnClickListener {
            recoveryInputMethod()
        }
        return view
    }

    private fun recoveryInputMethod() {
        ExtSystem.printDebugLog("点击切换为上个输入法")
        requestHideSelf(InputMethodManager.HIDE_NOT_ALWAYS)
        hideWindow()
        onFinishInput()
        onFinishCandidatesView(false)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            YyInput.resetInputMethod()
        }
        updateInputViewShown()
        currentInputConnection.closeConnection()
        onDestroy()
        exitProcess(0)
    }


    private val receiver = object : BootReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            super.onReceive(context, intent)
            val method = intent.getStringExtra("method")
            try {
                when (method) {
                    M_INPUT -> {
                        currentInputConnection ?: return
                        val text = intent.getStringExtra("text")
                        currentInputConnection.commitText(text, 1)
                        onFinishInput()
                        hideWindow()
                    }
                    M_SET_CLIPBOARD -> {
                        val text = intent.getStringExtra("text")
                        if (text != null) {
                            setClipContent(text)
                        }
                    }
                    M_CLEAR -> {
                        currentInputConnection ?: return
                        val curPos = currentInputConnection.getExtractedText(
                            ExtractedTextRequest(), 0)?.text
                        if (curPos != null) {
                            val bPos = currentInputConnection.getTextBeforeCursor(curPos.length, 0)
                            val aPos = currentInputConnection.getTextAfterCursor(curPos.length, 0)
                            currentInputConnection.deleteSurroundingText(bPos!!.length, aPos!!.length)
                        }
                    }
                    M_GET_CLIPBOARD -> {
                        try {
                            File(FILE_CLIPBOARD_TEXT).writeText(getClipContent())
                        } catch (e:Exception) {
                            ExtSystem.printDebugError(e)
                        }
                    }
                    M_INPUT_METHOD_RECOVER -> {
                        recoveryInputMethod()
                    }
                    M_SCAN_FILE -> {
                        try {
                            MediaScannerUtil.scan(context, intent.getStringExtra("path")!!.split(";").toTypedArray())
                        } catch (e:Exception) {
                            ExtSystem.printDebugLog(e)
                        }
                    }
                }
            } catch (e: Exception) {
                ExtSystem.printDebugError("Yyds.Input", e)
            }
        }
    }
    val intent = IntentFilter("yy-input-action")

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerBroadcast() {
        registerReceiver(receiver, intent)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_NOT_STICKY;
    }

    private fun getClipContent(): String {
        val manager: ClipboardManager = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (manager.primaryClip != null) {
            if (manager.hasPrimaryClip() && manager.primaryClip!!.itemCount > 0) {
                val addedText: CharSequence = manager.primaryClip!!.getItemAt(0).text
                val addedTextString = addedText.toString()
                if (!TextUtils.isEmpty(addedTextString)) {
                    return addedTextString
                }
            }
        }
        ExtSystem.printDebugLog("获取剪辑版内容为空")
        return ""
    }

    private fun setClipContent(text: String) {
        val manager: ClipboardManager = this.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("", text))
    }

    override fun onCreate() {
        ExtSystem.printDebugLog("====YYInputOnCreate")
        registerBroadcast()
        super.onCreate()
    }

    companion object {
        private const val M_CLEAR = "CLEAR"
        private const val M_INPUT = "INPUT"
        private const val M_GET_CLIPBOARD = "M_GET_CLIPBOARD"
        private const val M_SET_CLIPBOARD = "M_SET_CLIPBOARD"
        private const val M_SCAN_FILE = "M_SCAN_FILE"
        private const val M_INPUT_METHOD_RECOVER = "M_INPUT_METHOD_RECOVER"
        private val FILE_CLIPBOARD_TEXT get() = App.app.cacheDir.absolutePath + "/.clipboard.txt"
        // -------------- 输入系列 ------------
        fun sendClearText():Boolean {
            return !ExtSystem.shell("am broadcast -a yy-input-action -e method $M_CLEAR").contains("Error")
        }

        fun sendInputText(text:String):Boolean {
            return !ExtSystem.shell("am broadcast -a yy-input-action -e method $M_INPUT -e text \"$text\"").contains("Error")
        }

        fun setClipboardText(text:String):Boolean {
            return !ExtSystem.shell("am broadcast -a yy-input-action -e method $M_SET_CLIPBOARD -e text \"$text\"").contains("Error")
        }

        // am broadcast -a yy-input-action -e method M_INPUT_METHOD_RECOVER
        fun invokeGetClipBoardText():String {
            return if(!ExtSystem.shell("am broadcast -a yy-input-action -e method $M_GET_CLIPBOARD").contains("Error")) {
                try {
                    File(FILE_CLIPBOARD_TEXT).readText()
                } catch (e:Exception) {
                    return ""
                }
            } else {
                ""
            }
        }

        fun mediaScanFile(path:String):Boolean {
            return !ExtSystem.shell("am broadcast -a yy-input-action -e method $M_SCAN_FILE -e path \"$path\"").contains("Error")
        }
    }
}