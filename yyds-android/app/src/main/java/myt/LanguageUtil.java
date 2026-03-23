package myt;

import android.app.backup.BackupManager;
import android.content.res.Configuration;

import java.util.Locale;

import uiautomator.ExtSystem;

public class LanguageUtil {
    public static boolean updateLanguage(Locale locale) {
        try {
            Class<?> cls = Class.forName("android.app.IActivityManager");
            Class<?> cls2 = Class.forName("android.app.ActivityManagerNative");
            Object invoke = cls2.getDeclaredMethod("getDefault", new Class[0]).invoke(cls2, new Object[0]);
            Configuration configuration = (Configuration) cls.getDeclaredMethod("getConfiguration", new Class[0]).invoke(invoke, new Object[0]);
            configuration.setLocale(locale);
            Class.forName("android.content.res.Configuration").getField("userSetLocale").set(configuration, true);
            cls.getDeclaredMethod("updateConfiguration", Configuration.class).invoke(invoke, configuration);
            BackupManager.dataChanged("com.android.providers.settings");
            return true;
        } catch (Exception e) {
            ExtSystem.printDebugError("updateLanguage err:", e);
            return false;
        }
    }

    public static boolean updateLanguage(String language, String country) {
        return updateLanguage(new Locale(language, country.toUpperCase()));
    }

    public static boolean updateLanguage(String code) {
        String[] s = code.split("-");
        // zh-cn
        // en-us
        return updateLanguage(s[0], s[1]);
    }
}
