package com.tencent.yyds.frag

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tencent.yyds.R
import com.tencent.yyds.databinding.FragmentRemoteBinding
import me.caz.xp.ui.ContextAction
import pyengine.EngineClient
import pyengine.PyEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Server configuration + Login/Register fragment.
 * Manages /sdcard/Yyds.Auto/server.conf for yyds-con connection.
 * Handles user authentication and device binding.
 */
class RemoteFragment : Fragment() {

    companion object {
        private const val CONFIG_PATH = "/sdcard/Yyds.Auto/server.conf"
        private const val DEFAULT_HOST = "192.168.11.166"
        private const val DEFAULT_PORT = 8818
    }

    private var fragmentRemoteBinding: FragmentRemoteBinding? = null
    private val binding get() = fragmentRemoteBinding!!
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var statusRunnable: Runnable? = null

    // Auth state (loaded from config)
    private var authToken: String = ""
    private var loggedUsername: String = ""
    private var loggedRole: String = ""
    private var deviceToken: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        fragmentRemoteBinding = FragmentRemoteBinding.inflate(inflater, container, false)
        initView()
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        // Load existing config
        loadConfig()

        binding.btnSaveConfig.setOnClickListener { saveConfig() }
        binding.btnLogin.setOnClickListener { doLogin() }
        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnLogout.setOnClickListener { doLogout() }

        // Start periodic status check
        startStatusPolling()
    }

    private fun loadConfig() {
        thread {
            try {
                val content = EngineClient.readFileText(CONFIG_PATH)
                if (!content.isNullOrBlank()) {
                    val json = gson.fromJson(content, JsonObject::class.java)
                    val host = json.get("host")?.asString ?: DEFAULT_HOST
                    val port = json.get("port")?.asInt ?: DEFAULT_PORT
                    val token = json.get("token")?.asString ?: ""
                    val username = json.get("username")?.asString ?: ""
                    val role = json.get("role")?.asString ?: ""
                    val userToken = json.get("user_token")?.asString ?: ""

                    authToken = userToken
                    loggedUsername = username
                    loggedRole = role
                    deviceToken = token

                    ContextAction.uiThread {
                        binding.editServerHost.setText(host)
                        binding.editServerPort.setText(port.toString())
                        updateAuthUI()
                    }
                    return@thread
                }
            } catch (_: Exception) {}

            ContextAction.uiThread {
                binding.editServerHost.setText(DEFAULT_HOST)
                binding.editServerPort.setText(DEFAULT_PORT.toString())
                updateAuthUI()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateAuthUI() {
        if (fragmentRemoteBinding == null) return
        if (authToken.isNotEmpty() && loggedUsername.isNotEmpty()) {
            binding.layoutLoginForm.visibility = View.GONE
            binding.layoutLoggedIn.visibility = View.VISIBLE
            binding.tvLoggedUsername.text = loggedUsername
            binding.tvLoggedRole.text = when (loggedRole) {
                "admin" -> getString(R.string.remote_role_admin)
                else -> getString(R.string.remote_role_user)
            }
            binding.tvDeviceBindStatus.text = if (deviceToken.isNotEmpty()) {
                getString(R.string.remote_device_bound)
            } else {
                getString(R.string.remote_device_not_bound)
            }
            binding.tvDeviceBindStatus.setTextColor(
                if (deviceToken.isNotEmpty()) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
            )
        } else {
            binding.layoutLoginForm.visibility = View.VISIBLE
            binding.layoutLoggedIn.visibility = View.GONE
        }
    }

    private fun getServerBaseUrl(): String {
        val host = binding.editServerHost.text?.toString()?.trim() ?: ""
        val port = binding.editServerPort.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        return "http://$host:$port"
    }

    private fun doLogin() {
        val username = binding.editUsername.text?.toString()?.trim() ?: ""
        val password = binding.editPassword.text?.toString()?.trim() ?: ""
        if (username.isBlank() || password.isBlank()) {
            ContextAction.toast(getString(R.string.remote_input_credentials))
            return
        }

        val baseUrl = getServerBaseUrl()
        setAuthButtonsEnabled(false)

        thread {
            try {
                val body = gson.toJson(mapOf("username" to username, "password" to password))
                val result = httpPost("$baseUrl/api/auth/login", body, null)
                val json = gson.fromJson(result, JsonObject::class.java)

                if (json.has("token")) {
                    val token = json.get("token").asString
                    val user = json.getAsJsonObject("user")
                    val uname = user.get("username").asString
                    val role = user.get("role").asString

                    authToken = token
                    loggedUsername = uname
                    loggedRole = role

                    // Auto bind device
                    bindDevice(baseUrl, token)

                    ContextAction.uiThread {
                        setAuthButtonsEnabled(true)
                        updateAuthUI()
                        ContextAction.toast(getString(R.string.remote_login_success))
                        // Auto save config with token
                        saveConfig()
                    }
                } else {
                    val error = json.get("error")?.asString ?: getString(R.string.remote_login_failed)
                    ContextAction.uiThread {
                        setAuthButtonsEnabled(true)
                        ContextAction.toast(error)
                    }
                }
            } catch (e: Exception) {
                ContextAction.uiThread {
                    setAuthButtonsEnabled(true)
                    ContextAction.toast(getString(R.string.remote_login_failed_detail, e.message))
                }
            }
        }
    }

    private fun doRegister() {
        val username = binding.editUsername.text?.toString()?.trim() ?: ""
        val password = binding.editPassword.text?.toString()?.trim() ?: ""
        if (username.isBlank() || password.isBlank()) {
            ContextAction.toast(getString(R.string.remote_input_credentials))
            return
        }
        if (password.length < 6) {
            ContextAction.toast(getString(R.string.remote_password_min_length))
            return
        }

        val baseUrl = getServerBaseUrl()
        setAuthButtonsEnabled(false)

        thread {
            try {
                val body = gson.toJson(mapOf("username" to username, "password" to password))
                val result = httpPost("$baseUrl/api/auth/register", body, null)
                val json = gson.fromJson(result, JsonObject::class.java)

                if (json.has("token")) {
                    val token = json.get("token").asString
                    val user = json.getAsJsonObject("user")
                    val uname = user.get("username").asString
                    val role = user.get("role").asString

                    authToken = token
                    loggedUsername = uname
                    loggedRole = role

                    // Auto bind device
                    bindDevice(baseUrl, token)

                    ContextAction.uiThread {
                        setAuthButtonsEnabled(true)
                        updateAuthUI()
                        ContextAction.toast(getString(R.string.remote_register_success))
                        saveConfig()
                    }
                } else {
                    val error = json.get("error")?.asString ?: getString(R.string.remote_register_failed)
                    ContextAction.uiThread {
                        setAuthButtonsEnabled(true)
                        ContextAction.toast(error)
                    }
                }
            } catch (e: Exception) {
                ContextAction.uiThread {
                    setAuthButtonsEnabled(true)
                    ContextAction.toast(getString(R.string.remote_register_failed_detail, e.message))
                }
            }
        }
    }

    /**
     * Bind current device to the logged-in user.
     * Gets a device JWT token for WebSocket authentication.
     */
    private fun bindDevice(baseUrl: String, userToken: String) {
        try {
            val imei = getDeviceImei()
            val model = android.os.Build.MODEL ?: "Unknown"
            val body = gson.toJson(mapOf("imei" to imei, "alias" to model))
            val result = httpPost("$baseUrl/api/auth/bind-device", body, userToken)
            val json = gson.fromJson(result, JsonObject::class.java)

            if (json.has("device_token")) {
                deviceToken = json.get("device_token").asString
            }
        } catch (e: Exception) {
            // Non-fatal: device binding can be retried
            ContextAction.uiThread {
                ContextAction.toast(getString(R.string.remote_bind_failed, e.message))
            }
        }
    }

    private fun doLogout() {
        authToken = ""
        loggedUsername = ""
        loggedRole = ""
        deviceToken = ""
        updateAuthUI()
        binding.editUsername.setText("")
        binding.editPassword.setText("")
        // Save config without auth info
        saveConfig()
        ContextAction.toast(getString(R.string.remote_logged_out))
    }

    private fun setAuthButtonsEnabled(enabled: Boolean) {
        binding.btnLogin.isEnabled = enabled
        binding.btnRegister.isEnabled = enabled
        binding.btnLogin.text = if (enabled) getString(R.string.remote_btn_login) else getString(R.string.remote_please_wait)
        binding.btnRegister.text = if (enabled) getString(R.string.remote_btn_register) else getString(R.string.remote_please_wait)
    }

    @SuppressLint("SetTextI18n")
    private fun saveConfig() {
        val host = binding.editServerHost.text?.toString()?.trim() ?: ""
        val portStr = binding.editServerPort.text?.toString()?.trim() ?: ""

        if (host.isBlank()) {
            ContextAction.toast(getString(R.string.remote_input_host))
            return
        }

        val port = portStr.toIntOrNull() ?: DEFAULT_PORT
        if (port < 1 || port > 65535) {
            ContextAction.toast(getString(R.string.remote_port_range))
            return
        }

        val json = JsonObject().apply {
            addProperty("host", host)
            addProperty("port", port)
            if (deviceToken.isNotEmpty()) addProperty("token", deviceToken)
            if (loggedUsername.isNotEmpty()) addProperty("username", loggedUsername)
            if (loggedRole.isNotEmpty()) addProperty("role", loggedRole)
            if (authToken.isNotEmpty()) addProperty("user_token", authToken)
        }
        val configContent = gson.toJson(json)

        binding.btnSaveConfig.isEnabled = false
        binding.btnSaveConfig.text = getString(R.string.remote_saving)

        thread {
            try {
                EngineClient.writeFileText(CONFIG_PATH, configContent)
                ContextAction.uiThread {
                    binding.btnSaveConfig.isEnabled = true
                    binding.btnSaveConfig.text = getString(R.string.remote_btn_save_config)
                    ContextAction.toast(getString(R.string.remote_saved, host, port))
                    updateConnectionStatus()
                }
            } catch (e: Exception) {
                try {
                    val dir = java.io.File("/sdcard/Yyds.Auto")
                    if (!dir.exists()) dir.mkdirs()
                    java.io.File(CONFIG_PATH).writeText(configContent)
                    ContextAction.uiThread {
                        binding.btnSaveConfig.isEnabled = true
                        binding.btnSaveConfig.text = getString(R.string.remote_btn_save_config)
                        ContextAction.toast(getString(R.string.remote_saved_direct, host, port))
                        updateConnectionStatus()
                    }
                } catch (e2: Exception) {
                    ContextAction.uiThread {
                        binding.btnSaveConfig.isEnabled = true
                        binding.btnSaveConfig.text = getString(R.string.remote_btn_save_config)
                        ContextAction.toast(getString(R.string.remote_save_failed, e2.message))
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateConnectionStatus() {
        // Read UI values on main thread first
        val host = binding.editServerHost.text?.toString()?.trim() ?: ""
        val port = binding.editServerPort.text?.toString()?.trim()?.toIntOrNull() ?: DEFAULT_PORT

        thread {
            val engineAlive = PyEngine.isEngineOpen()

            val serverReachable = if (host.isNotBlank()) {
                try {
                    val conn = URL("http://$host:$port/api/auth/login").openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"
                    // Just check TCP reachability + HTTP response, don't POST to login
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..499 // Any response means server is reachable
                } catch (_: Exception) { false }
            } else false

            if (!isAdded || fragmentRemoteBinding == null) return@thread
            ContextAction.uiThread {
                if (fragmentRemoteBinding == null) return@uiThread
                val text: String
                val color: Int
                when {
                    engineAlive && serverReachable -> {
                        text = getString(R.string.remote_status_all_ok)
                        color = 0xFF4CAF50.toInt()
                    }
                    engineAlive && !serverReachable -> {
                        text = getString(R.string.remote_status_server_unreachable, host, port)
                        color = 0xFFFF9800.toInt()
                    }
                    !engineAlive && serverReachable -> {
                        text = getString(R.string.remote_status_engine_stopped)
                        color = 0xFFFF9800.toInt()
                    }
                    else -> {
                        text = getString(R.string.remote_status_all_down)
                        color = 0xFFEF5350.toInt()
                    }
                }
                binding.tvConnectionStatus.text = text
                binding.tvConnectionStatus.setTextColor(color)
            }
        }
    }

    private fun startStatusPolling() {
        statusRunnable = object : Runnable {
            override fun run() {
                if (isAdded && fragmentRemoteBinding != null) {
                    updateConnectionStatus()
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.postDelayed(statusRunnable!!, 500)
    }

    // ── Helpers ──

    private fun getDeviceImei(): String {
        // Try android_id first (works without ROOT in App process)
        try {
            val ctx = context ?: activity
            if (ctx != null) {
                val androidId = android.provider.Settings.Secure.getString(
                    ctx.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                if (!androidId.isNullOrBlank() && androidId != "null") return androidId
            }
        } catch (_: Exception) {}
        // Fallback: try shell (only works if engine is running with ROOT)
        try {
            val imei = uiautomator.ExtSystem.shell("settings get secure android_id").trim()
            if (imei.isNotBlank() && imei != "null") return imei
        } catch (_: Exception) {}
        try {
            val serial = android.os.Build.SERIAL ?: ""
            if (serial.isNotBlank() && serial != "unknown") return serial
        } catch (_: Exception) {}
        return "unknown_${android.os.Build.MODEL.replace(" ", "_")}"
    }

    private fun httpPost(url: String, body: String, bearerToken: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (!bearerToken.isNullOrEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val result = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            // Try to extract error message from JSON response
            val errorMsg = try {
                val errJson = gson.fromJson(result, JsonObject::class.java)
                errJson.get("error")?.asString ?: "HTTP $code"
            } catch (_: Exception) {
                "HTTP $code"
            }
            throw Exception(errorMsg)
        }
        return result
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
        fragmentRemoteBinding = null
    }
}
