package uiautomator

import android.content.Intent
import com.topjohnwu.superuser.ShellUtils

object ExportUtil {
    fun jumpBrowserUrl(url:String) {
        //方式一：代码实现跳转
        val intent = Intent()
        intent.action = "android.intent.action.VIEW"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val content_url: android.net.Uri = android.net.Uri.parse(url) //此处填链接
        intent.data = content_url
        AppProcess.systemContext.startActivity(intent)
    }

    fun monkeyStartApp(pk: String) {
        val packageLaunchIntent = AppProcess.systemContext.packageManager.getLaunchIntentForPackage(pk)
        try {
            AppProcess.systemContext.startActivity(packageLaunchIntent)
        } catch (ignore:Exception) {
        }

        if(ExtSystem.shell("am start -n ${packageLaunchIntent?.component?.toShortString()?.replace("{", "}")}").contains("Error")) {
            ExtSystem.shell("/system/bin/monkey -p $pk -c android.intent.category.LAUNCHER 1")
        }
    }
}