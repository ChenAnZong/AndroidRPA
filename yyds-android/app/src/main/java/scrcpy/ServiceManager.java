package scrcpy;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import uiautomator.ExtSystem;

@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class ServiceManager {
     /*
      *
      * 2022-10-06 10:52:56.305  5059-5093  YYDS_EXT                wjzy.yyds                            D  Could not invoke method
      *                                                                                                     java.lang.reflect.InvocationTargetException
      *                                                                                                     	at java.lang.reflect.Method.invoke(Native Method)
      *                                                                                                     	at scrcpy.ClipboardManager.getPrimaryClip(ClipboardManager.java:48)
      *                                                                                                     	at scrcpy.ClipboardManager.getText(ClipboardManager.java:63)
      *                                                                                                     	at scrcpy.ClipboardManagerWrapper.setClipboardText(ClipboardManagerWrapper.java:43)
      *                                                                                                     	at uiautomator.ExportApi.handle(ExportApi.java:156)
      *                                                                                                     	at uiautomator.ExportApi.exportHandle(ExportApi.java:70)
      *                                                                                                     	at uiautomator.ExportHandle$HandlerBinder.http(ExportHandle.java:16)
      *                                                                                                     	at com.tencent.yyds.IHandle$Stub.onTransact(IHandle.java:73)
      *                                                                                                     	at android.os.Binder.execTransactInternal(Binder.java:1021)
      *                                                                                                     	at android.os.Binder.execTransact(Binder.java:994)
      *                                                                                                     Caused by: java.lang.SecurityException: Calling uid 0 does not own package com.android.shell
      *                                                                                                     	at android.os.Parcel.createException(Parcel.java:2074)
      *                                                                                                     	at android.os.Parcel.readException(Parcel.java:2042)
      *                                                                                                     	at android.os.Parcel.readException(Parcel.java:1990)
      *                                                                                                     	at android.content.IClipboard$Stub$Proxy.getPrimaryClip(IClipboard.java:333)
      *                                                                                                     	at java.lang.reflect.Method.invoke(Native Method) 
      *                                                                                                     	at scrcpy.ClipboardManager.getPrimaryClip(ClipboardManager.java:48) 
      *                                                                                                     	at scrcpy.ClipboardManager.getText(ClipboardManager.java:63) 
      *                                                                                                     	at scrcpy.ClipboardManagerWrapper.setClipboardText(ClipboardManagerWrapper.java:43) 
      *                                                                                                     	at uiautomator.ExportApi.handle(ExportApi.java:156) 
      *                                                                                                     	at uiautomator.ExportApi.exportHandle(ExportApi.java:70) 
      *                                                                                                     	at uiautomator.ExportHandle$HandlerBinder.http(ExportHandle.java:16) 
      *                                                                                                     	at com.tencent.yyds.IHandle$Stub.onTransact(IHandle.java:73) 
      *                                                                                                     	at android.os.Binder.execTransactInternal(Binder.java:1021) 
      *                                                                                                     	at android.os.Binder.execTransact(Binder.java:994) 
      *                                                                                                     Caused by: android.os.RemoteException: Remote stack trace:
      *                                                                                                     	at com.android.server.clipboard.ClipboardService.addActiveOwnerLocked(ClipboardService.java:692)
      *                                                                                                     	at com.android.server.clipboard.ClipboardService.access$700(ClipboardService.java:157)
      *                                                                                                     	at com.android.server.clipboard.ClipboardService$ClipboardImpl.getPrimaryClip(ClipboardService.java:391)
      *                                                                                                     	at android.content.IClipboard$Stub.onTransact(IClipboard.java:173)
      *                                                                                                     	at com.android.server.clipboard.ClipboardService$ClipboardImpl.onTransact(ClipboardService.java:341)
      * */
    public static final String PACKAGE_NAME = "android";
    public static final int USER_ID = ExtSystem.uid();

    private final Method getServiceMethod;
    private InputManager inputManager;
    private ClipboardManager clipboardManager;

    public static ServiceManager INSTANCE = new ServiceManager();

    public ServiceManager() {
        try {
            getServiceMethod = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
    public InputManager getInputManager() {
        if (inputManager == null) {
            try {
                Method getInstanceMethod = android.hardware.input.InputManager.class.getDeclaredMethod("getInstance");
                android.hardware.input.InputManager im = (android.hardware.input.InputManager) getInstanceMethod.invoke(null);
                inputManager = new InputManager(im);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }
        return inputManager;
    }

    private WindowManager windowManager;
    public WindowManager getWindowManager() {
        if (windowManager == null) {
            windowManager = new WindowManager(getService("window", "android.view.IWindowManager"));
        }
        return windowManager;
    }

    private IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) getServiceMethod.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public ClipboardManager getClipboardManager() {
        if (clipboardManager == null) {
            IInterface clipboard = getService("clipboard", "android.content.IClipboard");
            if (clipboard == null) {
                // Some devices have no clipboard manager
                // <https://github.com/Genymobile/scrcpy/issues/1440>
                // <https://github.com/Genymobile/scrcpy/issues/1556>
                return null;
            }
            clipboardManager = new ClipboardManager(clipboard);
        }
        return clipboardManager;
    }


}
