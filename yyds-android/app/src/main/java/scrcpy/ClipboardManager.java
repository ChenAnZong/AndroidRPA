package scrcpy;


import android.content.ClipData;
import android.os.Build;
import android.os.IInterface;
import android.os.Looper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import uiautomator.ExtSystem;

public class ClipboardManager {
    private final IInterface manager;
    private Method getPrimaryClipMethod;
    private Method setPrimaryClipMethod;
    private boolean alternativeGetMethod;
    private boolean alternativeSetMethod;

    public ClipboardManager(IInterface manager) {
        this.manager = manager;
    }

    private int getMethodVersion;
    private int setMethodVersion;


    private Method getGetPrimaryClipMethod() throws NoSuchMethodException {
        if (getPrimaryClipMethod == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                getPrimaryClipMethod = manager.getClass().getMethod("getPrimaryClip", String.class);
            } else {
                try {
                    getPrimaryClipMethod = manager.getClass().getMethod("getPrimaryClip", String.class, int.class);
                    getMethodVersion = 0;
                } catch (NoSuchMethodException e1) {
                    try {
                        getPrimaryClipMethod = manager.getClass().getMethod("getPrimaryClip", String.class, String.class, int.class);
                        getMethodVersion = 1;
                    } catch (NoSuchMethodException e2) {
                        try {
                            getPrimaryClipMethod = manager.getClass().getMethod("getPrimaryClip", String.class, String.class, int.class, int.class);
                            getMethodVersion = 2;
                        } catch (NoSuchMethodException e3) {
                            getPrimaryClipMethod = manager.getClass().getMethod("getPrimaryClip", String.class, int.class, String.class);
                            getMethodVersion = 3;
                        }
                    }
                }
            }
        }
        return getPrimaryClipMethod;
    }

    private Method getSetPrimaryClipMethod() throws NoSuchMethodException {
        if (setPrimaryClipMethod == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                setPrimaryClipMethod = manager.getClass().getMethod("setPrimaryClip", ClipData.class, String.class);
            } else {
                try {
                    setPrimaryClipMethod = manager.getClass().getMethod("setPrimaryClip", ClipData.class, String.class, int.class);
                    setMethodVersion = 0;
                } catch (NoSuchMethodException e1) {
                    try {
                        setPrimaryClipMethod = manager.getClass().getMethod("setPrimaryClip", ClipData.class, String.class, String.class, int.class);
                        setMethodVersion = 1;
                    } catch (NoSuchMethodException e2) {
                        setPrimaryClipMethod = manager.getClass()
                                .getMethod("setPrimaryClip", ClipData.class, String.class, String.class, int.class, int.class);
                        setMethodVersion = 2;
                    }
                }
            }
        }
        return setPrimaryClipMethod;
    }

    private static ClipData getPrimaryClip(Method method, int methodVersion, IInterface manager)
            throws InvocationTargetException, IllegalAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return (ClipData) method.invoke(manager, FakeContext.PACKAGE_NAME);
        }

        switch (methodVersion) {
            case 0:
                return (ClipData) method.invoke(manager, FakeContext.PACKAGE_NAME, FakeContext.ROOT_UID);
            case 1:
                return (ClipData) method.invoke(manager, FakeContext.PACKAGE_NAME, null, FakeContext.ROOT_UID);
            case 2:
                return (ClipData) method.invoke(manager, FakeContext.PACKAGE_NAME, null, FakeContext.ROOT_UID, 0);
            default:
                return (ClipData) method.invoke(manager, FakeContext.PACKAGE_NAME, FakeContext.ROOT_UID, null);
        }
    }

    private static void setPrimaryClip(Method method, int methodVersion, IInterface manager, ClipData clipData)
            throws InvocationTargetException, IllegalAccessException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            method.invoke(manager, clipData, FakeContext.PACKAGE_NAME);
            return;
        }

        switch (methodVersion) {
            case 0:
                method.invoke(manager, clipData, FakeContext.PACKAGE_NAME, FakeContext.ROOT_UID);
                break;
            case 1:
                method.invoke(manager, clipData, FakeContext.PACKAGE_NAME, null, FakeContext.ROOT_UID);
                break;
            default:
                method.invoke(manager, clipData, FakeContext.PACKAGE_NAME, null, FakeContext.ROOT_UID, 0);
                break;
        }
    }

    public CharSequence getText() {
        try {
            Method method = getGetPrimaryClipMethod();
            ClipData clipData = getPrimaryClip(method, getMethodVersion, manager);
            if (clipData == null) {
                ExtSystem.printInfo("clipData == null");
                return null;
            }
            if (clipData.getItemCount() == 0) {
                ExtSystem.printInfo("clipData.getItemCount() == 0");
                return null;
            }
            return clipData.getItemAt(0).getText();
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            ExtSystem.printDebugError("Could not invoke method", e);
            return null;
        }
    }

    public boolean setText(CharSequence text) {
        try {
            Method method = getSetPrimaryClipMethod();
            ClipData clipData = ClipData.newPlainText(null, text);
            setPrimaryClip(method, setMethodVersion, manager, clipData);
            return true;
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            ExtSystem.printDebugError("Could not invoke method", e);
            return false;
        }
    }
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        System.out.println("--------@@@@");
        ClipboardManager clipboardManager = new ServiceManager().getClipboardManager();
        System.out.println("前：" + clipboardManager.getText() + "  " +   clipboardManager.setText("你好坏，我好喜欢"));
        System.out.println("前：" + clipboardManager.getText());
        ClipboardManagerWrapper.setClipboardText("真是坑爹");
        System.exit(5);
    }
}
