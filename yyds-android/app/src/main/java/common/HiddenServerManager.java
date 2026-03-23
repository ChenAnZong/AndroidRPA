package common;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

import uiautomator.ExtSystem;

public class HiddenServerManager {
    static String TAG = "HiddenServerManager";
    static final Method getService;
    static final Method addService;

    static {
        try {
            @SuppressLint("PrivateApi")
            Class<?> sm = Class.forName("android.os.ServiceManager");
            getService = sm.getDeclaredMethod("getService", String.class);
            addService = sm.getDeclaredMethod("addService", String.class, IBinder.class);
        } catch (Exception e) {
            // Shall not happen!
            throw new RuntimeException(e);
        }
    }

    public static IBinder getService(String name) {
        try {
            return (IBinder) getService.invoke(null, name);
        } catch (Exception e) {
            ExtSystem.printDebugLog(TAG,"getService", Log.getStackTraceString(e));
            return null;
        }
    }

    public static void addService(String name, IBinder service) {
        try {
            addService.invoke(null, name, service);
        } catch (Exception e) {
            ExtSystem.printDebugLog(TAG,"addService", Log.getStackTraceString(e));
        }
    }
}
