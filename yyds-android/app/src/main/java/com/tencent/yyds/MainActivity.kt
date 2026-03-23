package com.tencent.yyds

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.tencent.yyds.databinding.ActivityMainBinding
import com.tencent.yyds.widget.AppBanner
import com.tencent.yyds.databinding.SideNavHeaderBinding
import com.tencent.yyds.frag.AgentFragment
import com.tencent.yyds.frag.HomeFragment
import com.tencent.yyds.frag.RemoteFragment
import com.tencent.yyds.frag.ScriptFragment
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import common.BootService
import me.caz.xp.ui.ContextAction
import pyengine.ApkPackageHelper
import pyengine.EngineClient
import uiautomator.AppProcess
import uiautomator.Const
import uiautomator.ExtSystem
import uiautomator.util.FileUtil
import yyapp.YyInput
import yyapp.register.ActiveParam
import yyapp.register.HttpUtil
import yyapp.register.MyAccount
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread


class MainActivity : AppCompatActivity(), OnPageChangeListener,
    NavigationBarView.OnItemSelectedListener {
    private val mFragments = ArrayList<Fragment>()
    private val bottomMenuItems = ArrayList<MenuItem>()
    private lateinit var binding: ActivityMainBinding


    /**
     * Android 9开始限制开发者调用非官方API方法和接口(即用反射直接调用源码)
     * 弹框提示 Detected problems with API compatibility(visit g.co/dev/appcompat for more info)
     *
     *
     * 隐藏警告弹框
     */
    @SuppressLint("SoonBlockedPrivateApi")
    private fun closeDetectedProblemApiDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        try {
            @SuppressLint("PrivateApi") val clsPkgParser =
                Class.forName("android.content.pm.PackageParser\$Package")
            val constructor = clsPkgParser.getDeclaredConstructor(String::class.java)
            constructor.isAccessible = true
            @SuppressLint("PrivateApi") val clsActivityThread =
                Class.forName("android.app.ActivityThread")
            val method: Method = clsActivityThread.getDeclaredMethod("currentActivityThread")
            method.isAccessible = true
            val activityThread: Any = method.invoke(null)
            val hiddenApiWarning: Field =
                clsActivityThread.getDeclaredField("mHiddenApiWarningShown")
            hiddenApiWarning.isAccessible = true
            hiddenApiWarning.setBoolean(activityThread, true)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun checkDeviceTime() {
        if (System.currentTimeMillis() - App.bt > 39 * 81234500L) {
            MaterialAlertDialogBuilder(
                this@MainActivity,
                R.style.MyDialog
            )
                .setIcon(R.drawable.ic_agreement)
                .setTitle(getString(R.string.main_hint_title))
                .setMessage(getString(R.string.main_time_check_msg))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.main_agree)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    @SuppressLint("SetTextI18n", "SdCardPath", "SoonBlockedPrivateApi")
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Runner模式检测：打包后的APK assets中包含 pack_config.json
        if (ApkPackageHelper.isRunnerMode(this)) {
            startActivity(Intent(this, RunnerActivity::class.java))
            finish()
            return
        }

        closeDetectedProblemApiDialog()
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (supportActionBar != null) supportActionBar!!.hide()

        // 监听 IME（软键盘）insets，动态调整内容区域 bottom padding，
        // 防止键盘遮挡输入框同时避免底部导航被顶上来。
        // 在 root（DrawerLayout）上监听，手动将 IME 高度作用到内容 LinearLayout，
        // 不依赖 adjustResize（Android 11+ 已废弃）。
        val contentContainer = binding.viewpager.parent as? android.view.ViewGroup
        if (contentContainer != null) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val bottomPad = if (imeHeight > 0) imeHeight - navBarHeight else 0
                binding.bar.setPadding(
                    binding.bar.paddingLeft,
                    statusBarHeight,
                    binding.bar.paddingRight,
                    binding.bar.paddingBottom
                )
                contentContainer.setPadding(
                    contentContainer.paddingLeft,
                    contentContainer.paddingTop,
                    contentContainer.paddingRight,
                    bottomPad.coerceAtLeast(0)
                )
                insets
            }
        }

        binding.viewpager.addOnPageChangeListener(this)
        binding.bottomNavView.setOnItemSelectedListener(this)
        mFragments.add(HomeFragment())
        mFragments.add(ScriptFragment())
        mFragments.add(AgentFragment())
        mFragments.add(RemoteFragment())
        for (i in 0 until binding.bottomNavView.menu.size()) {
            bottomMenuItems.add(binding.bottomNavView.menu.getItem(i))
        }
        binding.viewpager.adapter = object :
            FragmentPagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
            override fun getCount(): Int {
                return mFragments.size
            }

            override fun getItem(position: Int): Fragment {
                return mFragments[position]
            }
        }
        binding.viewpager.offscreenPageLimit = mFragments.size

        binding.topAppBar.setOnClickListener {
            if (BuildConfig.DEBUG) {
                ContextAction.showNotification(this)
            }
        }

        binding.topAppBar.setNavigationOnClickListener {
            binding.drawerLayout.open()
            if (Debug.isDebuggerConnected()) {
                while (true) {
                    Debug.dumpHprofData("/sdcard/yyds.dump")
                }
            }
            val headerView = binding.navigationView.getHeaderView(0)
            val side = SideNavHeaderBinding.bind(headerView)
            side.tvBuildTime.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            side.tvBuildVersion.text = BuildConfig.BUILD_DATE
            // 通过守护进程读取认证信息（避免App进程权限问题）
            thread {
                EngineClient.ensureEngineRunning()
                val activeParam = if (MyAccount.isNoRegister()) {
                    MyAccount.emptyActiveParam
                } else {
                    MyAccount.readActiveParam()
                }
                // 释放备份的认证文件, 支持备份应用数据（通过守护进程写入外部存储）
                val cacheRet = App.Main_Sp.getString(App.KEY_CACHE_REGISTER_RET, null)
                try {
                    if (!EngineClient.fileExists(Const.ACTIVE_KEY_FILE) && cacheRet != null) {
                        EngineClient.writeFileText(Const.ACTIVE_KEY_FILE, cacheRet)
                    }
                } catch (e: Exception) {
                    ExtSystem.printDebugError("备份认证文件失败", e)
                }
                runOnUiThread {
                    if (Build.SUPPORTED_ABIS[0] == "x86_64") {
                        side.cardUserInfo.visibility = View.GONE
                    } else {
                        if (activeParam != null) {
                            side.cardUserInfo.visibility = View.VISIBLE
                            side.tvActiveType.text = activeParam.fmtActiveType()
                            side.tvActiveCode.text = activeParam.registerVip ?: "—"
                            side.tvActiveExpire.text = if (activeParam.isActiveValid()) {
                                activeParam.fmtActiveExpireDate()
                            } else { "—" }
                            side.tvActiveCode.setOnLongClickListener {
                                val m = App.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                m.setPrimaryClip(ClipData.newPlainText("", activeParam.registerVip))
                                ContextAction.toast(getString(R.string.main_copy_auth_success))
                                true
                            }
                        }
                    }
                }
            }
        }

        // 左侧菜单点击事件
        binding.navigationView.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.upload_log -> {
                    ContextAction.toast(getString(R.string.main_feature_developing))
                }
                R.id.nav_update1 -> {
                    val randomLanChar = "bcefhijkl".random()
                    App.app.jumpBrowserUrl("https://chensiji.lanzou${randomLanChar}.com/b00qihxte")
                    ContextAction.toast(getString(R.string.main_extract_password))
                    ContextAction.toast(getString(R.string.main_lanzouy_hint))
                }
                R.id.nav_update2 -> {
                    App.app.jumpBrowserUrl("https://www.123pan.com/s/Q5ybVv-UTRCh.html")
                }
                R.id.nav_position_tool -> {
                    val new = if(ExtSystem.shell("settings get system pointer_location").contains("1")) "0" else "1"
                    ExtSystem.shell("settings put system pointer_location $new")
                }
                R.id.nav_touch_tool -> {
                    val new = if(ExtSystem.shell("settings get system show_touches").contains("1")) "0" else "1"
                    ExtSystem.shell("settings put system show_touches $new")
                    ContextAction.toast(getString(R.string.main_touch_enabled))
                }
                R.id.nav_debug_mode -> {
                    ExtSystem.shell("settings put global adb_enabled 1")
                    ExtSystem.shell("settings put global development_settings_enabled 1")
                    ContextAction.toast(getString(R.string.main_dev_mode_on))
                }
                R.id.nav_close_debug_mode -> {
                    ExtSystem.shell("settings put global adb_enabled 0")
                    ExtSystem.shell("settings put global development_settings_enabled 0;stop adbd")
                    ContextAction.toast(getString(R.string.main_dev_mode_off))
                }
                R.id.nav_floating_window -> {
                    toggleFloatingWindow()
                }
                R.id.nav_pip_manager -> {
                    startActivity(Intent(this, PipManagerActivity::class.java))
                }
                R.id.nav_input_method -> {
                    YyInput.enableYyInputMethod()
                    ContextAction.toast(getString(R.string.main_ime_started))
                }
                R.id.nav_clear_cache -> {
                    ExtSystem.shell("rm -rf /data/local/tmp/*;rm -rf /sdcard/Yyds.Auto")
                    ExtSystem.shell(AppProcess.getActivePyEngineCmd())
                    ContextAction.toast(getString(R.string.main_cache_cleared))
                }
                R.id.nav_log -> {
                    startActivity(Intent(this, LogcatActivity::class.java))
                }
                R.id.nav_user_agreement -> {
                    showUserAgreement()
                }
                R.id.nav_privacy_agreement -> {
                    MaterialAlertDialogBuilder(
                        this@MainActivity,
                        R.style.MyDialog
                    )
                        .setIcon(R.drawable.ic_agreement)
                        .setTitle(getString(R.string.main_privacy_title))
                        .setCancelable(false)
                        .setMessage(Const.PRIVACY_AGREEMENT)
                        .setPositiveButton(getString(R.string.main_agree)) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setNegativeButton(
                            getString(R.string.main_disagree),
                            DialogInterface.OnClickListener { _, _ -> System.exit(1) })
                        .show()
                }
            }
            true
        }

        val isX86 = Build.SUPPORTED_ABIS[0] == "x86_64"
        //binding.navigationView.menu.findItem(R.id.nav_register).isVisible = !isX86
        //binding.navigationView.menu.findItem(R.id.nav_py_engine_app).isVisible = !isX86

        

        // boolean ocr_init = ppOcrNcnn.init(getAssets(), null)
        thread {
            // 向面具申请权限!
            ShellUtils.fastCmd("su -c id")
            App.app.reqRootMount()
            // 破解暗桩 shell 签名检查 PackageSignatures 7ebbed6b
            val packageInfo =
                ExtSystem.shell("echo `dumpsys package ${BuildConfig.APPLICATION_ID}`")
            ExtSystem.printDebugLog("My packageInfo: $packageInfo")
            if (packageInfo.contains("resourcePath") && !packageInfo.contains(Const.DUMPSYS_PACKAGE_SIG_HASH)) {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                val mainIntent = Intent.makeRestartActivityTask(
                    intent!!.component
                )
                // 卡死你
                while (true) {
                    startActivity(mainIntent)
                    SystemClock.sleep(100)
                    ExtSystem.printDebugLog("读取文件错误，疑似安卓系统兼容问题，正在重试")
                }
            }
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            AppBanner.showWithAction(
                this,
                getString(R.string.main_notification_hint),
                AppBanner.Type.WARNING,
                actionText = getString(R.string.main_notification_dismiss),
                onAction = { /* dismiss */ }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            startForegroundService(Intent(this, BootService::class.java))
        } else {
            startService(Intent(this, BootService::class.java))
        }

        if (!App.Main_Sp.getBoolean(App.KEY_AGREE, false)) {
            showUserAgreement()
        }

        // 自动恢复悬浮窗（如果之前已开启且权限仍有效）
        if (FloatingWindowService.isEnabled(this)) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                FloatingWindowService.start(this)
            }
        }
    }

    public override fun onResume() {
        super.onResume()
        val navToFragmentIndex = intent.getIntExtra(Const.NAV_TO_ACTIVITY_FRAGMENT, -1)
        ExtSystem.printDebugLog("onResume()", intent, navToFragmentIndex)
        if (navToFragmentIndex < 0 || navToFragmentIndex >= mFragments.size) return
        binding.viewpager.currentItem = navToFragmentIndex
    }

    private fun showUserAgreement() {
//        val rootLayout  = LinearLayout(this)
//        rootLayout.orientation = LinearLayout.VERTICAL
//        val scrollView = ScrollView(this@MainActivity)
//        val layout = LinearLayout(this)
//        layout.orientation = LinearLayout.VERTICAL
//        layout.setPadding(10, 10, 10, 10)
//        val tv = MaterialTextView(this)
//        tv.setText(Const.USER_AGREEMENT)
//        layout.addView(tv)
//        scrollView.addView(layout)
//        rootLayout.addView(scrollView)

//        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        MaterialAlertDialogBuilder(
            this@MainActivity,
            R.style.MyDialog
        )
            .setIcon(R.drawable.ic_agreement)
            .setTitle(getString(R.string.main_user_agreement_title))
            .setMessage(Const.USER_AGREEMENT)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.main_agree)) { dialog, _ ->
                dialog.dismiss()
                App.Main_Sp.edit().putBoolean(App.KEY_AGREE, true).apply()
            }
            .setNegativeButton(getString(R.string.main_disagree)) { _, _ -> System.exit(1) }
            .show()
    }

    public fun checkPermission(): Boolean {
        // 所有文件访问均已转移到守护进程(yyds.py)执行，App进程不再需要存储权限
        return true
    }

    public fun reqPermission() {
        // 所有文件访问均已转移到守护进程(yyds.py)执行，App进程不再需要存储权限
    }

    private fun showRegister(activeParam: ActiveParam, st:Long) {
        runOnUiThread {
            binding.drawerLayout.closeDrawers()
            MaterialAlertDialogBuilder(this, R.style.MyDialog)
                .setIcon(if (activeParam.isActiveValid()) R.drawable.ic_check else R.drawable.ic_problem)
                .setTitle(getString(R.string.main_register_hint_title_fmt, (System.currentTimeMillis() - st)/1000))
                .setCancelable(false)
                .setMessage(activeParam.tip)
                .setNegativeButton(getString(R.string.main_confirm)) { dialog, _ ->
                    dialog.cancel()
                }
                .show()
            binding.drawerLayout.open()
        }
    }

    private fun showRegisterError(err: String) {
        runOnUiThread {
            MaterialAlertDialogBuilder(this, R.style.MyDialog)
                .setIcon(R.drawable.ic_problem)
                .setTitle(getString(R.string.main_register_hint_title))
                .setCancelable(false)
                .setMessage(err)
                .setNegativeButton(getString(R.string.main_confirm)) { dialog, _ ->
                    dialog.cancel()
                }
                .show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ExtSystem.printDebugLog("onRequestPermissionsResult requestCode:$requestCode res:${grantResults.contentToString()}")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                FloatingWindowService.start(this)
                ContextAction.toast(getString(R.string.main_floating_opened))
            } else {
                ContextAction.toast(getString(R.string.main_floating_no_permission))
            }
        }
    }

    private fun toggleFloatingWindow() {
        if (FloatingWindowService.isEnabled(this)) {
            FloatingWindowService.stop(this)
            ContextAction.toast(getString(R.string.main_floating_closed))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this, R.style.MyDialog)
                .setIcon(R.drawable.ic_floating_window)
                .setTitle(getString(R.string.main_floating_dialog_title))
                .setMessage(getString(R.string.main_floating_dialog_msg))
                .setCancelable(true)
                .setPositiveButton(getString(R.string.main_floating_go_settings)) { dialog, _ ->
                    dialog.dismiss()
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                    } catch (e: Exception) {
                        ContextAction.toast(getString(R.string.main_floating_open_failed))
                    }
                }
                .setNegativeButton(getString(R.string.main_cancel)) { dialog, _ -> dialog.cancel() }
                .show()
        } else {
            FloatingWindowService.start(this)
            ContextAction.toast(getString(R.string.main_floating_opened))
        }
    }

    @Throws(FileNotFoundException::class)
    private fun decodeUri(selectedImage: Uri): Bitmap {
        // Decode image size
        val o = BitmapFactory.Options()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            o.outConfig = Bitmap.Config.ARGB_8888
        }
        o.inJustDecodeBounds = true
        BitmapFactory.decodeStream(contentResolver.openInputStream(selectedImage), null, o)

        // The new size we want to scale to
        val REQUIRED_SIZE = 640

        // Find the correct scale value. It should be the power of 2.
        var width_tmp = o.outWidth
        var height_tmp = o.outHeight
        var scale = 1
        while (true) {
            if (width_tmp / 2 < REQUIRED_SIZE
                || height_tmp / 2 < REQUIRED_SIZE
            ) {
                break
            }
            width_tmp /= 2
            height_tmp /= 2
            scale *= 2
        }

        // Decode with inSampleSize
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = scale
        val bitmap =
            BitmapFactory.decodeStream(contentResolver.openInputStream(selectedImage), null, o2)
                ?: throw FileNotFoundException("Failed to decode image: $selectedImage")

        // Rotate according to EXIF
        var rotate = 0
        try {
            val exif = ExifInterface(contentResolver.openInputStream(selectedImage)!!)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate = 270
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate = 180
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate = 90
            }
        } catch (e: IOException) {
            Log.e("MainActivity", "ExifInterface IOException")
        }
        val matrix = Matrix()
        matrix.postRotate(rotate.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun toastLong(toastContent: String) {
        Toast.makeText(applicationContext, toastContent, Toast.LENGTH_LONG).show()
    }

    private fun toastShort(toastContent: String) {
        Toast.makeText(applicationContext, toastContent, Toast.LENGTH_SHORT).show()
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
        // None
    }

    override fun onPageSelected(position: Int) {
        binding.bottomNavView.menu.getItem(position).isChecked = true
    }

    override fun onPageScrollStateChanged(state: Int) {}

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        ExtSystem.printDebugLog(
            "# onNavigationItemSelected " + item.itemId,
            bottomMenuItems.indexOf(item),
            item.order
        )
        item.isChecked = true
        binding.viewpager.currentItem = bottomMenuItems.indexOf(item)
        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        init {
            // 不需要加载这个so到app进程
            if (Debug.isDebuggerConnected()) {
                System.loadLibrary("ai")
            }
        }
    }
}