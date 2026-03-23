package yyapp;


import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.UUID;

import uiautomator.ExtSystem;


public class ReflectUtil {
    public static void setFinalStatic(Field field, Object newValue) throws Exception {
        field.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("accessFlags");
        modifiersField.setAccessible(true);
        int originModifier = field.getModifiers();
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        field.set(null, newValue);
        modifiersField.setInt(field, originModifier);
    }

    public static void dropFinalStaticFlag(Field field) throws Exception {
        field.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("accessFlags");
        modifiersField.setAccessible(true);
        modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
    }

    public static void changeDebugLog() {
        try {
            Class logClass = Class.forName("android.util.Log");
            ReflectUtil.setFinalStatic(logClass.getField("DEBUG"), 6);
            // ExtSystem.printDebugLog("g1111", logClass.getField("DEBUG").get(null));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void changeDefaultSec() {
        try {
            SecureRandom randomSecureRandom = SecureRandom.getInstanceStrong();
            ExtSystem.printDebugLog("ccc===========");
            Field filed = Class.forName("java.util.UUID$Holder").getField("numberGenerator");
            dropFinalStaticFlag(filed);
            filed.set(null, randomSecureRandom);
        } catch (Exception e) {
            ExtSystem.printDebugError("changeDefaultSec", e);
        }
    }
}
