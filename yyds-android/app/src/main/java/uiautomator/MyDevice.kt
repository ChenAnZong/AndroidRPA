package uiautomator

import android.graphics.Point
import scrcpy.ServiceManager
import uiautomator.compat.WindowManagerWrapper
import java.util.regex.Pattern

object MyDevice {
    private val screenPoint: Point? by lazy {
        val wrapper = WindowManagerWrapper()
        wrapper.displaySize
    }

    private val sizeString: String by lazy {
        ExtSystem.shell("wm size")
    }
    fun getScreenSizeCurrent():Point {
        val rotation = ServiceManager.INSTANCE.windowManager.rotation
        val r = rotation and 1 xor 1
        val point = screenPoint
        if (point == null) {
            // 定义正则表达式模式
            val pattern = Pattern.compile("\\d+")

            // 匹配模式
            val matcher = pattern.matcher(sizeString)

            // 提取数字
            val numbers = mutableListOf<Int>()
            while (matcher.find()) {
                val number = matcher.group().toInt()
                numbers.add(number)
            }
            return if (r == 1) {
                Point(numbers[0], numbers[1])
            } else {
                Point(numbers[0], numbers[1])
            }

        } else {
            return if (r == 1) {
                Point(point.x, point.y)
            } else {
                Point(point.y, point.x)
            }
        }

    }
}