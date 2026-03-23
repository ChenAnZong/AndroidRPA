# 设置混淆的压缩比率 0 ~ 7
-optimizationpasses 3
-obfuscationdictionary dictionary_rules.txt #外部字典
-classobfuscationdictionary dictionary_rules.txt  #类字典
-packageobfuscationdictionary dictionary_rules.txt  #包字典


# 不优化输入的类文件
-dontoptimize

# 变态效应 ））））））））））））））））
-repackageclasses "YydsXX.com"
# Multiple fields and methods can then get the same names,
# -overloadaggressively

# Kotlin
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}


# 安卓组件
-keep public class * extends android.app.Fragment
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# CPython 嵌入式桥接
-keepclasseswithmembernames class pyengine.**
# Native 反射
-keepclasseswithmembernames class com.tencent.yyds.**
-keepclasseswithmembernames class pyengine.RpcDataModel

# 入口类
-keepclasseswithmembers class pyengine.Main { public static void main(...);  }
-keepclasseswithmembers class pyengine.PyProcess { public static void main(...);  }
-keepclasseswithmembers class uiautomator.ExportApi { public static void main(...);  }
-keepclasseswithmembers class android.view.IRotationWatcher
# kotlin 序列化
# Kotlin serialization looks up the generated serializer classes through a function on companion
# objects. The companions are looked up reflectively so we need to explicitly keep these functions.
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
# If a companion has the serializer function, keep the companion field on the original type so that
# the reflective lookup succeeds.
-if class **.*$Companion {
  kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class <1>.<2> {
  <1>.<2>$Companion Companion;
}

# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}

-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**

# SnakeYAML - Android 不包含 java.beans 包
-dontwarn java.beans.**

# Ktor debug detector - Android 不包含 java.lang.management 包
-dontwarn java.lang.management.**

# ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.reflect.jvm.internal.** {*;}
-keep class kotlin.text.RegexOption {*;}
-keep class io.netty.** {*;}
-keep class com.squareup.** {*;}
-keep class org.apache.** {*;}
-keep class org.eclipse.** {*;}
-keep class org.slf4j.** {*;}
-keep class android.view.** {*;}

# AIDL方法
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

-keep class * implements android.os.IInterface { *;}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
#    public static *** e(...);
#    public static *** i(...);
#    public static *** v(...);
#    public static *** w(...);
}

-assumenosideeffects class uiautomator.ExtSystem {
    public static *** printDebugLog(...);
}