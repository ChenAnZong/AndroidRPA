package uiautomator.util

import java.text.SimpleDateFormat
import java.util.*

object DateUtil {
    fun getCommonDate():String {
        val pattern = "MM-dd HH:mm:ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        return simpleDateFormat.format(Date())
    }

    fun getCommonDateYear():String {
        val pattern = "YYYY-MM-dd HH:mm:ss"
        val simpleDateFormat = SimpleDateFormat(pattern)
        return simpleDateFormat.format(Date())
    }
}