package uiautomator.util

import java.io.File
import java.nio.charset.Charset

object FileUtil {
    @JvmStatic
    fun writeText(path:String, content:String):Boolean {
        val f = File(path)
        if (f.parentFile != null) {
            if (!f.parentFile!!.exists()) f.parentFile!!.mkdirs()
        }
        if (f.exists()) {
            f.delete()
        }
        f.writeText(content)
        return f.readText() == content
    }

    @JvmStatic
    fun appendText(path:String, content:String) {
        val f = File(path)
        if (f.parentFile != null) {
            if (!f.parentFile!!.exists()) f.parentFile!!.mkdirs()
        }
        f.appendText(content)
    }

    @JvmStatic
    fun getText(path: String):String {
        return File(path).readText(Charset.defaultCharset())
    }

    @JvmStatic
    fun getTextNotExistsEmpty(path: String):String {
        val f = File(path)
        if (!f.exists()) {
            return ""
        } else {
            return f.readText(Charset.defaultCharset())
        }
    }
}