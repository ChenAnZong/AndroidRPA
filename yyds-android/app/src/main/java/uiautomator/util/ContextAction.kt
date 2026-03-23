package me.caz.xp.ui

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tencent.yyds.BuildConfig
import com.tencent.yyds.MainActivity
import com.tencent.yyds.R
import uiautomator.ExtSystem
import java.lang.ref.WeakReference
import java.lang.reflect.Method


object ContextAction {
    private var thisContext: WeakReference<Context> = WeakReference(null)

    @JvmStatic
    public fun getApplicationContext(): Context? {
        return if (thisContext.get() != null) {
            thisContext.get()
        } else {
            try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val method: Method = activityThreadClass.getMethod("currentApplication")
                thisContext = WeakReference(method.invoke(null) as Context)
                thisContext.get()
            } catch (e: Exception) {
                null
            }
        }
    }

    fun toast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show()
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showNotification(ctx: Context) {
        val channelId = BuildConfig.APPLICATION_ID


//        val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        val notificationManager = NotificationManagerCompat.from(ctx)

        val channelName: CharSequence = "My Channel"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val notificationChannel = NotificationChannel(channelId, channelName, importance)
        notificationChannel.enableLights(true)
        notificationChannel.lightColor = Color.CYAN
        notificationChannel.enableVibration(true)
        notificationChannel.vibrationPattern = longArrayOf(1000, 2000)
        notificationManager.createNotificationChannel(notificationChannel)
//        val remoteViews = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.noti_script)
//        remoteViews.setTextViewText(R.id.text_cur_project, "当前工程: test")
//        remoteViews.setTextViewText(R.id.text_run_status, "运行状态: 正在运行中")
//        remoteViews.setOnClickPendingIntent(R.id.image_icon_con, PendingIntent.getService(ctx,
//                1,
//                Intent(ctx, BootService::class.java), PendingIntent.FLAG_IMMUTABLE))
//        val pendingIntent = PendingIntent.getService(ctx,
//                1,
//                Intent(ctx, BootService::class.java), PendingIntent.FLAG_IMMUTABLE)

        val pendingIntent = PendingIntent.getActivity(ctx,
                0, Intent(ctx, MainActivity::class.java),
                /* flags */ PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(ctx, channelId)
                .setContentTitle("Yyds.Auto")
                 .setContentText("脚本正在运行中")
                .setSmallIcon(R.drawable.ic_engine)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
//                .setCustomContentView(remoteViews)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        // ctx.startForeground(channelId.hashCode(), notification)
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    fun uiThread(uiFunc:()->Unit) {
        Handler(Looper.getMainLooper()).post { uiFunc() }
    }
}