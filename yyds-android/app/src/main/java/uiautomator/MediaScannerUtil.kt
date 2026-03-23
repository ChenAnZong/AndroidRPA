package uiautomator

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri

object MediaScannerUtil {
    public fun scan(ctx: Context, paths: Array<String>) {
        MediaScannerConnection.scanFile(/* context = */ ctx, /* paths = */
            paths, /* mimeTypes = */
            null
        ) { path, uri -> ExtSystem.printDebugLog("MediaScannerConnection.scanFile ${path} ${uri}") }
    }
}