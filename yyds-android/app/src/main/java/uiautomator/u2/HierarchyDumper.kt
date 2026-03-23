package uiautomator.u2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.os.Build
import android.util.SparseArray
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dalvik.system.DexClassLoader
import uiautomator.ExportApi
import uiautomator.ExtSystem
import uiautomator.tool.ScreenCapture
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

@SuppressLint("PrivateApi")
object HierarchyDumper {
//    private val reflectMethodObject: Pair<Method, Any>? by lazy {
//        try {
//            val loader = DexClassLoader("/system/framework/uiautomator.jar", ".", null, ExportApi::class.java.classLoader)
//            val cls = loader.loadClass("com.android.commands.uiautomator.DumpCommand")
//            val dumper = cls.newInstance()
//            val method = cls.getMethod("run", Array<String>::class.java)
//            method to dumper
//        } catch (e:Exception) {
//            ExtSystem.printDebugError("反射DumpCommand失败", e)
//            null
//        }
//    }

//    fun dumpWithBin(path:String, isAllWindow:Boolean = false) {
//
//        if (reflectMethodObject != null) {
//            reflectMethodObject!!.first.invoke(reflectMethodObject!!.second, runArgs)
//        } else {
//            ExtSystem.shell("uiautomator ${runArgs.joinToString(" ")}")
//        }
//    }

    private val uiAutomation by lazy {
        val loader = DexClassLoader("/system/framework/uiautomator.jar", "/data/local/tmp/cache", null, ExportApi::class.java.classLoader)
        val cls = loader.loadClass("com.android.uiautomator.core.UiAutomationShellWrapper")
        val automationWrapper = cls.newInstance()
        automationWrapper::class.java.getMethod("connect").invoke(automationWrapper)
        val auto = automationWrapper::class.java.getMethod("getUiAutomation").invoke(automationWrapper)
        try {
            val info = auto::class.java.getMethod("getServiceInfo").invoke(auto) as AccessibilityServiceInfo
            info.eventTypes = info.eventTypes and AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED.inv()
            val newCapabilities = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS.inv()
            info::class.java.getMethod("setCapabilities", 0::class.java).invoke(info, newCapabilities)
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.flags = info.flags and AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME.inv()
            }
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
            auto::class.java.getMethod("setServiceInfo", AccessibilityServiceInfo::class.java).invoke(auto, info)
            ExtSystem.printDebugLog("===> 创建UIA服务", info)
        } catch (e:Exception) {
            ExtSystem.printDebugError("UIA标识", e)
        }
        return@lazy auto
    }

    fun dump(path:String, isAllWindow:Boolean = false):Long {
        val ms = System.currentTimeMillis()
        try {
            ExtSystem.printDebugLog("# 开始执行获取")
            val info = uiAutomation::class.java.getMethod("getRootInActiveWindow").invoke(uiAutomation) as AccessibilityNodeInfo?
                ?: throw NullPointerException("getRootInActiveWindow null")
            ExtSystem.printDebugLog("# 获取到根节点:" + info.className + " " + info)
            val os = ByteArrayOutputStream()
            ExtSystem.printDebugLog("# 开始dump")
            if (isAllWindow) {
                AccessibilityNodeInfoDumper.dumpAllWindowHierarchy(getAllWindowRoot(), os, ScreenCapture.curRotation)
            } else {
                AccessibilityNodeInfoDumper.dumpWindowHierarchy(info, os, ScreenCapture.curRotation)
            }
            ExtSystem.printDebugLog("# dump结束")
            val xml = os.toString("Utf-8")
            ExtSystem.printDebugLog("打印结果并写出($path)")
            File(path).writeText(xml)
        } catch(e:Throwable) {
            ExtSystem.printDebugError("反射dump节点失败!", e)
            val runArgs = if (isAllWindow) arrayOf<String>("--windows", path) else arrayOf(path)
            ExtSystem.shell("uiautomator dump ${runArgs.joinToString(" ")}")
        }
        return ms
    }

    fun getAllWindowRoot():List<AccessibilityWindowInfo> {
        try {
            val list =  uiAutomation::class.java.getMethod("getWindows")
                .invoke(uiAutomation) as List<AccessibilityWindowInfo>
                ?: throw NullPointerException("getWindows null")
            ExtSystem.printDebugLog("长度:", list.size)
            return list
        } catch (e: Throwable) {
            ExtSystem.printDebugError(e)
        }
        return listOf()
    }
}