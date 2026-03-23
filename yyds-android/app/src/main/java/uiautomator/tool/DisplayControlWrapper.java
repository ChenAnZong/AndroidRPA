package uiautomator.tool;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import pyengine.ContextUtil;
import uiautomator.AppProcess;
import uiautomator.ExportApi;
import uiautomator.ExtSystem;

@SuppressLint({"PrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi", "UnsafeDynamicallyLoadedCode"})
public final class DisplayControlWrapper {
    private static final Class<?> CLASS;

    static {
        Class<?> displayControlClass = null;
        try {
            if (Build.VERSION.SDK_INT < 34) {
                Class<?> classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory");
                Method createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                        ClassLoader.class, int.class, boolean.class, String.class);
                ClassLoader classLoader = (ClassLoader) createClassLoaderMethod.invoke(null,
                        "/system/framework/services.jar",
                        null, null,
                        ClassLoader.getSystemClassLoader(), 0, true, null);
                displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");
                Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
                loadMethod.setAccessible(true);
                loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_server");
            } else {
                System.setProperty("java.library.path", AppProcess.pyLibPath);
                Runtime.getRuntime().load("/data/local/tmp/cache/lib/arm64-v8a/libas.so");
                displayControlClass = Class.forName("com.android.server.display.DisplayControl");
            }
        } catch (Throwable e) {
            ExtSystem.printDebugError("Could not initialize DisplayControl", e);
            // Do not throw an exception here, the methods will fail when they are called
        }
        CLASS = displayControlClass;
    }

    private static Method getPhysicalDisplayTokenMethod;
    private static Method getPhysicalDisplayIdsMethod;

    public static void testGetToken() {
        long[] ids = DisplayControlWrapper.getPhysicalDisplayIds();
        Object displayToken = DisplayControlWrapper.getPhysicalDisplayToken(ids[0]);
        ExtSystem.printDebugLog("#GetToken", displayToken);
    }

    private static Method getGetPhysicalDisplayTokenMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayTokenMethod == null) {
            getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
        }
        return getPhysicalDisplayTokenMethod;
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            Method method = getGetPhysicalDisplayTokenMethod();
            return (IBinder) method.invoke(null, physicalDisplayId);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            ExtSystem.printDebugError("Could not invoke method", e);
            return null;
        }
    }

    private static Method getGetPhysicalDisplayIdsMethod() throws NoSuchMethodException {
        if (getPhysicalDisplayIdsMethod == null) {
            getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
        }
        return getPhysicalDisplayIdsMethod;
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            Method method = getGetPhysicalDisplayIdsMethod();
            return (long[]) method.invoke(null);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            ExtSystem.printDebugError("Could not invoke method", e);
            return null;
        }
    }

    public static void setDisplayPowerMode(int mode) {
        try {
            ExtSystem.printDebugLog("loadLibrary......");
            Runtime.getRuntime().load("/data/local/tmp/cache/lib/arm64-v8a/libas.so");
            ExtSystem.printDebugLog("Loaded libandroiod_servers.so");
            Class<?> displayControlClass = Class.forName("com.android.server.display.DisplayControl");
            ExtSystem.printDebugLog("displayControlClass: " + displayControlClass);
            Method getPhysicalDisplayIdsMethod = displayControlClass.getDeclaredMethod("getPhysicalDisplayIds", new Class[0]);
            ExtSystem.printDebugLog("getPhysicalDisplayIdsMethod: " + getPhysicalDisplayIdsMethod);
            Method getPhysicalDisplayTokenMethod = displayControlClass.getDeclaredMethod("getPhysicalDisplayToken", Long.TYPE);
            ExtSystem.printDebugLog("getPhysicalDisplayTokenMethod: " + getPhysicalDisplayTokenMethod);
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
            ExtSystem.printDebugLog("surfaceControlClass: " + surfaceControlClass);
            Method setDisplayPowerModeMethod = surfaceControlClass.getDeclaredMethod("setDisplayPowerMode", IBinder.class, Integer.TYPE);
            ExtSystem.printDebugLog("setDisplayPowerModeMethod: " + setDisplayPowerModeMethod);
            long[] displayIds = (long[]) getPhysicalDisplayIdsMethod.invoke(null, new Object[0]);
            ExtSystem.printDebugLog("displayIds.length: " + displayIds.length);
            int length = displayIds.length;
            int i2 = 0;
            int i = 1;
            while (i2 < length) {
                long displayId = displayIds[i2];
                ExtSystem.printDebugLog("||displayId: " + displayId);
                Object[] objArr = new Object[i];
                objArr[0] = Long.valueOf(displayId);
                ExtSystem.printDebugLog("||Try getPhysicalDisplayToken: " + displayId);
                IBinder token = (IBinder) getPhysicalDisplayTokenMethod.invoke(null, objArr);
                // IBinder token = DisplayControl.getPhysicalDisplayToken(displayId);
                ExtSystem.printDebugLog("token: " + token);
                setDisplayPowerModeMethod.invoke(null, token, Integer.valueOf(mode));
                ExtSystem.printDebugLog("setDisplayPowerMode success");
                i2++;
                i = 1;
            }
        } catch (Throwable e) {
            ExtSystem.printDebugError("SetDisplayMode",e);
        }
    }
}