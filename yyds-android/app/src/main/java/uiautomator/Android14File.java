package uiautomator;

import android.os.Build;

import java.io.File;

public class Android14File {
    public static void doCopy() {
        if (Build.VERSION.SDK_INT >= 34 || !new File("/data/local/tmp/cache/lib/arm64-v8a/libas.so").exists()) {
            ExtSystem.shell(
                     "cp -rf /apex/com.android.adbd/lib64/libadb_pairing_auth.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.adbd/lib64/libadb_pairing_server.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.adbd/lib64/libadb_pairing_connection.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.os.statsd/lib64/libstatspull.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.os.statsd/lib64/libstatssocket.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.runtime/lib64/bionic/libdl_android.so /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /apex/com.android.i18n/lib64/libandroidicu.so  /data/local/tmp/cache/lib/arm64-v8a/\n" +
                            "cp -rf /system/lib64/libandroid_servers.so /data/local/tmp/cache/lib/arm64-v8a/libas.so"
            );
        }
    }
}
