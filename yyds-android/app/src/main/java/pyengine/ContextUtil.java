package pyengine;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import uiautomator.ExtSystem;

public class ContextUtil {
    public static Context getSystemContext() throws Exception {
        Class<?> ActivityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = ActivityThread.getDeclaredMethod("systemMain");
        Object object = systemMain.invoke(null);

        Class<?> ContextImpl = Class.forName("android.app.ContextImpl");
        java.lang.reflect.Method createSystemContext = ContextImpl.getDeclaredMethod("createSystemContext", ActivityThread);
        createSystemContext.setAccessible(true);
        Context contextInstace = (Context) createSystemContext.invoke(null, object);
        return contextInstace;
    }

    private static final class V23 {
        private static void install(ClassLoader classLoader, File folder)  throws Throwable {
            final Field pathListField = ShareReflectUtil.findField(classLoader, "pathList");
            final Object dexPathList = pathListField.get(classLoader);

            final Field nativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "nativeLibraryDirectories");

            List<File> origLibDirs = (List<File>) nativeLibraryDirectories.get(dexPathList);
            if (origLibDirs == null) {
                origLibDirs = new ArrayList<>(2);
            }
            final Iterator<File> libDirIt = origLibDirs.iterator();
            while (libDirIt.hasNext()) {
                final File libDir = libDirIt.next();
                if (folder.equals(libDir)) {
                    libDirIt.remove();
                    break;
                }
            }
            origLibDirs.add(0, folder);

            final Field systemNativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "systemNativeLibraryDirectories");
            List<File> origSystemLibDirs = (List<File>) systemNativeLibraryDirectories.get(dexPathList);
            if (origSystemLibDirs == null) {
                origSystemLibDirs = new ArrayList<>(2);
            }

            final List<File> newLibDirs = new ArrayList<>(origLibDirs.size() + origSystemLibDirs.size() + 1);
            newLibDirs.addAll(origLibDirs);
            newLibDirs.addAll(origSystemLibDirs);

            final Method makeElements = ShareReflectUtil.findMethod(dexPathList,
                    "makePathElements", List.class, File.class, List.class);
            final ArrayList<IOException> suppressedExceptions = new ArrayList<>();

            final Object[] elements = (Object[]) makeElements.invoke(dexPathList, newLibDirs, null, suppressedExceptions);

            final Field nativeLibraryPathElements = ShareReflectUtil.findField(dexPathList, "nativeLibraryPathElements");
            nativeLibraryPathElements.set(dexPathList, elements);
        }
    }
    private static final class V25 {
        public static void install(ClassLoader classLoader, File folder)  throws Throwable {
            final Field pathListField = ShareReflectUtil.findField(classLoader, "pathList");
            final Object dexPathList = pathListField.get(classLoader);

            final Field nativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "nativeLibraryDirectories");

            List<File> origLibDirs = (List<File>) nativeLibraryDirectories.get(dexPathList);
            if (origLibDirs == null) {
                origLibDirs = new ArrayList<>(2);
            }
            final Iterator<File> libDirIt = origLibDirs.iterator();
            while (libDirIt.hasNext()) {
                final File libDir = libDirIt.next();
                if (folder.equals(libDir)) {
                    libDirIt.remove();
                    break;
                }
            }
            origLibDirs.add(0, folder);

            final Field systemNativeLibraryDirectories = ShareReflectUtil.findField(dexPathList, "systemNativeLibraryDirectories");
            List<File> origSystemLibDirs = (List<File>) systemNativeLibraryDirectories.get(dexPathList);
            if (origSystemLibDirs == null) {
                origSystemLibDirs = new ArrayList<>(2);
            }

            final List<File> newLibDirs = new ArrayList<>(origLibDirs.size() + origSystemLibDirs.size() + 1);
            newLibDirs.addAll(origLibDirs);
            newLibDirs.addAll(origSystemLibDirs);

            final Method makeElements = ShareReflectUtil.findMethod(dexPathList, "makePathElements", List.class);

            final Object[] elements = (Object[]) makeElements.invoke(dexPathList, newLibDirs);

            for (File file: newLibDirs) {
                ExtSystem.printInfo("===> NewLibPath:" + file.getAbsolutePath());
            }

            final Field nativeLibraryPathElements = ShareReflectUtil.findField(dexPathList, "nativeLibraryPathElements");
            nativeLibraryPathElements.set(dexPathList, elements);
        }
    }
    public static final class InstallLoader {
        public static void install(ClassLoader loader, File folder) throws Throwable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                V25.install(loader, folder);
            } else {
                V23.install(loader, folder);
            }
        }
    }

}
