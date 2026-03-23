package pyengine

import com.tencent.yyds.App
import uiautomator.ExtSystem
import java.io.File

object YyProjectUtil {
    fun extractZipProject(zipPath:String, extractProjectName:String) {
        val zipExtractFolder = File(App.app.cacheDir, extractProjectName).absolutePath
        val projectFolder = "/sdcard/Yyds.Py/"
        ExtSystem.shell("rm -rf $zipExtractFolder")
        ZipUtility.unzip(zipPath, zipExtractFolder)
        ExtSystem.shell("[ -d $projectFolder ] || mkdir -p $projectFolder")
        ExtSystem.shell("rm -rf $projectFolder$extractProjectName")
        ExtSystem.shell("/system/bin/mv -f $zipExtractFolder $projectFolder")
    }
}