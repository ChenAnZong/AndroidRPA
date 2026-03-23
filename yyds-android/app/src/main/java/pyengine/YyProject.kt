package pyengine

import android.annotation.SuppressLint
import android.icu.text.SimpleDateFormat
import androidx.annotation.Keep
import uiautomator.ExtSystem
import uiautomator.util.DateUtil
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.sql.Timestamp
import java.util.Properties
import kotlin.concurrent.thread

@Keep
data class YyProject(
        val name:String,
        val version:String,
        val folderPath:String,
        val folderName:String,
        val lastDate:String,
        val downloadUrl:String?
): java.io.Serializable {
    fun start() {
        thread {
            try {
                // 确保工作进程已启动（需要ROOT/SHELL权限）
                EngineClient.ensureEngineRunning()
                PyEngine.startProject(folderName)
            } catch (e: Exception) {
                ExtSystem.printDebugError("启动项目失败: $folderName", e)
            }
        }
    }

    fun delete() {
        ExtSystem.shell("rm -rf $folderPath")
        clearConfig()
    }

    fun hasUiConfig():Boolean {
        return File(getUiYamlPath()).exists()
    }

    fun clearConfig() {
        ExtSystem.shell("rm -rf ${getConfigFilePath()}")
    }

    fun getUiYamlPath():String {
        return "$folderPath/ui.yml"
    }

    fun getConfigFilePath():String {
        val f = PyEngine.getProjectDir() + "/config/"
        if (!File(f).exists()) {
            File(f).mkdirs()
        }
        return "$f$folderName.json"
    }


    companion object {
        const val Key_Config_Project_Name = "PROJECT_NAME"
        const val Key_Config_Project_Version = "PROJECT_VERSION"
        const val Key_Config_Project_Download = "DOWNLOAD_URL"

        private fun tryLoadProperties(configFile:File): Properties? {
            if (!configFile.exists()) return null
            return try {
                val fs = FileInputStream(configFile)
                val fsc = InputStreamReader(fs, "UTF-8")
                val properties = Properties()
                properties.load(fsc)
                fsc.close()
                properties
            } catch (e: Exception) {
                ExtSystem.printDebugError("加载项目配置文件失败", e)
                return null
            }
        }

        @SuppressLint("SimpleDateFormat", "WeekBasedYear")
        fun scanProject():List<YyProject> {
            val allProjectDir = File(PyEngine.getProjectDir()).listFiles { f -> f.isDirectory && f.name != "config" && !f.name.startsWith(".");  } ?: return emptyList<YyProject>()
            return allProjectDir.mapNotNull {
                val projectConfigFile = File(it, "project.config")
                val properties = tryLoadProperties(projectConfigFile) ?: return@mapNotNull null
                val downloadUrl = properties.getProperty(Key_Config_Project_Download, null)

                if (properties.containsKey(Key_Config_Project_Name) &&
                    properties.containsKey(Key_Config_Project_Version)) {
                    return@mapNotNull YyProject(
                        properties.getProperty(Key_Config_Project_Name)
                        .replace("\r", "")
                        .replace("\n", ""),
                        properties.getProperty(Key_Config_Project_Version)
                            .replace("\r", "")
                            .replace("\n", ""),
                        it.absolutePath, it.name,
                        SimpleDateFormat("YYYY-MM-dd hh:mm:ss").format(Timestamp(it.lastModified())),
                        downloadUrl
                    )
                } else {
                    ExtSystem.printDebugLog("# 无法从" + projectConfigFile.absolutePath + "找不到键值， 已有键值" + properties.keys.joinToString(";"))
                    return@mapNotNull null
                }
            }
        }
    }
}