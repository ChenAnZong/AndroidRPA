package scrcpy;

import android.os.IInterface;
import androidx.annotation.Keep;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import uiautomator.ExtSystem;

public final class WindowManager {
    private final IInterface manager;
    private Method getRotationMethod;
    private Method freezeRotationMethod;
    private Method isRotationFrozenMethod;
    private Method thawRotationMethod;

    public WindowManager(IInterface manager) {
        this.manager = manager;
    }

    private Method getGetRotationMethod() throws NoSuchMethodException {
        if (getRotationMethod == null) {
            Class<?> cls = manager.getClass();
            try {
                // method changed since this commit:
                // https://android.googlesource.com/platform/frameworks/base/+/8ee7285128c3843401d4c4d0412cd66e86ba49e3%5E%21/#F2
                getRotationMethod = cls.getMethod("getDefaultDisplayRotation");
            } catch (NoSuchMethodException e) {
                // old version
                getRotationMethod = cls.getMethod("getRotation");
            }
        }
        return getRotationMethod;
    }

    private Method getFreezeRotationMethod() throws NoSuchMethodException {
        if (freezeRotationMethod == null) {
            freezeRotationMethod = manager.getClass().getMethod("freezeRotation", int.class);
        }
        return freezeRotationMethod;
    }

    private Method getIsRotationFrozenMethod() throws NoSuchMethodException {
        if (isRotationFrozenMethod == null) {
            isRotationFrozenMethod = manager.getClass().getMethod("isRotationFrozen");
        }
        return isRotationFrozenMethod;
    }

    private Method getThawRotationMethod() throws NoSuchMethodException {
        if (thawRotationMethod == null) {
            thawRotationMethod = manager.getClass().getMethod("thawRotation");
        }
        return thawRotationMethod;
    }

    public int getRotation() {
        try {
            Method method = getGetRotationMethod();
            return (int) method.invoke(manager);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            ExtSystem.printDebugError("Could not invoke method", e);
            return 0;
        }
    }

    public void freezeRotation(int rotation) {
        try {
            Method method = getFreezeRotationMethod();
            method.invoke(manager, rotation);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
             ExtSystem.printDebugError("Could not invoke method", e);
        }
    }

    public boolean isRotationFrozen() {
        try {
            Method method = getIsRotationFrozenMethod();
            return (boolean) method.invoke(manager);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
             ExtSystem.printDebugError("Could not invoke method", e);
            return false;
        }
    }

    public void thawRotation() {
        try {
            Method method = getThawRotationMethod();
            method.invoke(manager);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
             ExtSystem.printDebugError("Could not invoke method", e);
        }
    }

    public static String prettyClassMethod(Class<?> cls) {
        StringBuilder builder = new StringBuilder().append("<||| ClassName: ").append(cls.toString()).append("\n");
        LinkedHashSet<Method> methods = new LinkedHashSet<>();
        methods.addAll(Arrays.asList(cls.getMethods()));
        methods.addAll(Arrays.asList(cls.getDeclaredMethods()));

        Set<String> filter = new HashSet<>();
        filter.addAll(Arrays.asList("notify","notifyAll", "wait", "toString", "hashCode", "getClass", "equals"));
        for (Method method : methods) {
            if (filter.contains(method.getName())) continue;
            Class<?>[] param = method.getParameterTypes();
            builder.append(method.getName()).append("(");
            int count = 0;
            for (Class<?> aClass : param) {
                builder.append(aClass.getName());
                if (++count < param.length) {
                    builder.append(", ");
                }
            }
            builder.append(");\n");
        }
        return builder.toString();
    }

    public void registerRotationWatcher(android.view.IRotationWatcher rotationWatcher, int displayId) {
        Class<?> cls = manager.getClass();

        try {
            Class<?> c = Class.forName("android.view.IRotationWatche-".replace("-", "r"));
            try {
                // display parameter added since this commit:
                // https://android.googlesource.com/platform/frameworks/base/+/35fa3c26adcb5f6577849fd0df5228b1f67cf2c6%5E%21/#F1

                cls.getMethod("watchRotation",c, int.class).invoke(manager, rotationWatcher, displayId);
            } catch (NoSuchMethodException e) {
                // old version
                cls.getMethod("watchRotation",c).invoke(manager, rotationWatcher);
            }
            ExtSystem.printInfo(">>>>3registerRotationWatcher：" + rotationWatcher.toString());
        } catch (Exception e) {
            ExtSystem.printInfo("找不到方法", prettyClassMethod(cls));
            throw new AssertionError(e);
        }
    }
}
