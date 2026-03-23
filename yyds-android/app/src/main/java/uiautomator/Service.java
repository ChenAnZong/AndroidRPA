package uiautomator;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;



import java.util.ArrayList;
import java.util.List;

public class Service extends android.app.Service {
    public static final String ACTION_START = "com.github.uiautomator.ACTION_START";
    public static final String ACTION_STOP = "com.github.uiautomator.ACTION_STOP";

    private static final String TAG = "UIAService";
    private static final int NOTIFICATION_ID = 0x1;
//
//    private NotificationCompat.Builder builder;
//    private List<AbstractMonitor> monitors = new ArrayList<>();
//
//
    @Override
    public IBinder onBind(Intent intent) {
        // We don't support binding to this service
        return null;
    }

//    @RequiresApi(Build.VERSION_CODES.O)
//    private String createNotificationChannel(String channelId, String channelName) {
//        NotificationChannel chan = new NotificationChannel(channelId,
//                channelName, NotificationManager.IMPORTANCE_NONE);
//        chan.setLightColor(Color.BLUE);
//        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
//        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//        service.createNotificationChannel(chan);
//        return channelId;
//    }
//
//    @Override
//    public void onCreate() {
//        super.onCreate();
//
//        Intent notificationIntent = new Intent(this, MainActivity.class);
//        String channelId = "";
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            channelId = createNotificationChannel("monitor service", "Monitor Service");
//        } else {
//            // If earlier version channel ID is not used
//            // https://developer.android.com/reference/android/support/v4/app/NotificationCompat.Builder.html#NotificationCompat.Builder(android.content.Context)
//        }
//        builder = new NotificationCompat.Builder(this, channelId)
//                .setSmallIcon(R.drawable.ic_notification)
//                .setTicker(getString(R.string.monitor_service_ticker))
//                .setContentTitle(getString(R.string.monitor_service_title))
//                .setContentText(getString(R.string.monitor_service_text))
//                .setContentIntent(PendingIntent.getActivity(this, NOTIFICATION_ID, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT))
//                .setWhen(System.currentTimeMillis());
//        Notification notification = builder.build();
//        startForeground(NOTIFICATION_ID, notification);
//        stopForeground(true);
//        HttpPostNotifier notifier = new HttpPostNotifier("http://127.0.0.1:7912");
//        Context context = getApplicationContext();
//        addMonitor(new BatteryMonitor(context, notifier));
//        addMonitor(new WifiMonitor(this, notifier));
//    }
//
//    public void setNotificationContentText(String text) {
//        builder.setContentText(text);
//        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//        nm.notify(NOTIFICATION_ID, builder.build());
//    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Stopping service");
        Log.i(TAG, "unregister all watchers");
        stopForeground(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        Log.i(TAG, "On StartCommand");
        String action = intent.getAction();

        if (ACTION_START.equals(action)) {
            Log.i(TAG, "Receive start-service action, but ignore it");
//            startActivity(new Intent(this, MainActivity.class));
        } else if (ACTION_STOP.equals(action)) {
            stopSelf();
        }

        return START_NOT_STICKY; // not start again, when killed by system
    }

    @Override
    public void onLowMemory() {
        Log.w(TAG, "Low memory");
    }

}
