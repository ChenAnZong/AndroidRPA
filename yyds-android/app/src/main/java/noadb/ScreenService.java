package noadb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import com.tencent.yyds.MainActivity;
import com.tencent.yyds.R;

public class ScreenService extends Service {

    private MediaProjectionManager mMediaProjectionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mMediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int resultCode = intent.getIntExtra("code", -1);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resultData = intent.getParcelableExtra("data", Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra("data");
        }
        startProject(resultCode, resultData);
        return super.onStartCommand(intent, flags, startId);
    }

    // 录屏开始后进行编码推流
    private void startProject(int resultCode, Intent data) {
        MediaProjection c = mMediaProjectionManager.getMediaProjection(resultCode, data);
    }

    private void createNotificationChannel() {
        String channelId = "notification_id";

        // API 26+ requires NotificationChannel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel =
                    new NotificationChannel(
                            channelId, "notification_name", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this.getApplicationContext(), channelId);
        } else {
            builder = new Notification.Builder(this.getApplicationContext());
        }

        int pendingFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_IMMUTABLE : 0;

        Intent nfIntent = new Intent(this, MainActivity.class);
        builder
                .setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, pendingFlags))
                .setLargeIcon(
                        BitmapFactory.decodeResource(
                                this.getResources(), R.mipmap.ic_launcher))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentText("is running......")
                .setWhen(System.currentTimeMillis());

        Notification notification = builder.build();
        startForeground(110, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
