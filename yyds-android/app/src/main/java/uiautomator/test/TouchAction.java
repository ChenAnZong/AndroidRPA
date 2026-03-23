package uiautomator.test;

import android.os.SystemClock;

import java.util.Random;


public class TouchAction {

    public static TouchController touchController = new TouchController();
    private static final Random rand = new Random();

    public static int randomInt(int min, int max) {
        return rand.nextInt((max - min) + 1) + min;
    }

    /**
     * Perform a click at arbitrary coordinates specified by the user.
     *
     * @param x coordinate
     * @param y coordinate
     * @return true if the click succeeded else false
     */
    public static boolean click(int x, int y) {
        // The original implementation got bug here.
        // when y >= getDiaplayHeight() return false, but getDisplayHeight() is not right in infinity display
        //  return device.click(x, y);
        if (x < 0 || y < 0) {
            return false;
        }
        touchController.touchDown(x, y);
        int lastX = x;
        int lastY = y;
        // click 随机产生有Move
//        for (int i = 0; i < randomInt(0, 2); i++) {
//            lastX = x + randomInt(-2, 4);
//            lastY = y + randomInt(-2, 4);
//            touchController.touchMove(lastX, lastY);
//            SystemClock.sleep(randomInt(25, 50)); // normally 100ms for click
//        }
        SystemClock.sleep(randomInt(40, 101));
        // 在最后位置弹起
        return touchController.touchUp(lastX, lastY);
    }

    public static boolean click(int x, int y, long milliseconds) {
        if (x < 0 || y < 0) {
            return false;
        }
        touchController.touchDown(x, y);
        SystemClock.sleep(milliseconds);
        return touchController.touchUp(x, y);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int duration, boolean isRandom) {
        long now = SystemClock.uptimeMillis();
        touchController.touchDown(x1, y1);
        long startTime = now;
        long endTime = startTime + duration;
        if (isRandom) {
            float newX = x1;
            float newY = y1;
            while (now < endTime) {
                long elapsedTime = now - startTime;
                float alpha = (float) elapsedTime / duration;
                float x = lerp(x1, x2, alpha) + randomInt(-10, 10);
                float y = lerp(y1, y2, alpha) + randomInt(-10, 10);
                touchController.touchMove(x, y);
                newX = x;
                newY = y;
                now = SystemClock.uptimeMillis();
            }
            return touchController.touchUp(newX, newY);
        } else {
            while (now < endTime) {
                long elapsedTime = now - startTime;
                float alpha = (float) elapsedTime / duration;
                touchController.touchMove((int) lerp(x1, x2, alpha), (int) lerp(y1, y2, alpha));
                now = SystemClock.uptimeMillis();
            }
            return touchController.touchUp(x2, y2);
        }
    }

    private static float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }
}
