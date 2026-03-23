package uiautomator.compat;

import android.app.NotificationManager;
import android.content.Context;

public class NotificationWrapper {
    public static void cancel(Context context) {
        NotificationManager manager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancelAll();
    }
}
