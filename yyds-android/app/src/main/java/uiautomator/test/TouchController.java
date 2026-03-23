package uiautomator.test;

import android.app.Instrumentation;
import android.app.Service;
import android.app.UiAutomation;
import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.MotionEvent;

import uiautomator.compat.InputManagerWrapper;
import uiautomator.input.InputUtil;


public class TouchController {
    private static final String LOG_TAG = "TouchController";
    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);
    private static final int MOTION_EVENT_INJECTION_DELAY_MILLIS = 5;

    private final KeyCharacterMap mKeyCharacterMap =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private static int touchScreenDeviceId = InputUtil.getDeviceId();
    private long mDownTime;
    /**
    private final Instrumentation mInstrumentation;
     */


    /**
    public TouchController(Instrumentation instrumentation) {
        mInstrumentation = instrumentation;
    }
     */

    /**
    public boolean isScreenOn() {
        PowerManager pm = (PowerManager) getContext().getSystemService(Service.POWER_SERVICE);
        return pm.isScreenOn();
    }
     */

    /**
     * 不用uiautomation注入，使用反射+权限进行注入
     * */
    private static final InputManagerWrapper wrapper = new InputManagerWrapper();

    private boolean injectEventSync(InputEvent event) {
        Log.w(LOG_TAG, "injectEventSync " + event.toString());
        return wrapper.injectInputEvent(event);
        // return getUiAutomation().injectInputEvent(event, true);
    }

    public boolean touchDown(float x, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchDown (" + x + ", " + y + ")");
        }
        mDownTime = SystemClock.uptimeMillis();
        MotionEvent event = getMotionEvent(mDownTime, mDownTime, MotionEvent.ACTION_DOWN, x, y);
        return injectEventSync(event);
    }

    public boolean touchUp(float x, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchUp (" + x + ", " + y + ")");
        }
        final long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = getMotionEvent(mDownTime, eventTime, MotionEvent.ACTION_UP, x, y);
        mDownTime = 0;
        return injectEventSync(event);
    }

    public boolean touchMove(float x, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchMove (" + x + ", " + y + ")");
        }
        final long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = getMotionEvent(mDownTime, eventTime, MotionEvent.ACTION_MOVE, x, y);
        return injectEventSync(event);
    }

    private static MotionEvent getMotionEvent(long downTime, long eventTime, int action,
                                              float x, float y) {

        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.pressure = 1;
        coords.size = TouchAction.randomInt(5, 12);
        coords.x = x;
        coords.y = y;

        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1.0f, 1.0f, touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        return event;
    }

    public boolean performMultiPointerGesture(MotionEvent.PointerCoords[]... touches) {
        boolean ret = true;
        if (touches.length < 2) {
            throw new IllegalArgumentException("Must provide coordinates for at least 2 pointers");
        }

        // Get the pointer with the max steps to inject.
        int maxSteps = 0;
        for (int x = 0; x < touches.length; x++)
            maxSteps = (maxSteps < touches[x].length) ? touches[x].length : maxSteps;

        // specify the properties for each pointer as finger touch
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[touches.length];
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[touches.length];
        for (int x = 0; x < touches.length; x++) {
            MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
            prop.id = x;
            prop.toolType =  MotionEvent.TOOL_TYPE_FINGER;
            properties[x] = prop;

            // for each pointer set the first coordinates for touch down
            pointerCoords[x] = touches[x][0];
        }

        // Touch down all pointers
        long downTime = SystemClock.uptimeMillis();
        MotionEvent event;
        event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 1,
                properties, pointerCoords, 0, 0, 1, 1, touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        ret &= injectEventSync(event);

        for (int x = 1; x < touches.length; x++) {
            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    getPointerAction(MotionEvent.ACTION_POINTER_DOWN, x), x + 1, properties,
                    pointerCoords, 0, 0, 1, 1, touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            ret &= injectEventSync(event);
        }

        // Move all pointers
        for (int i = 1; i < maxSteps - 1; i++) {
            // for each pointer
            for (int x = 0; x < touches.length; x++) {
                // check if it has coordinates to move
                if (touches[x].length > i)
                    pointerCoords[x] = touches[x][i];
                else
                    pointerCoords[x] = touches[x][touches[x].length - 1];
            }

            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, touches.length, properties, pointerCoords, 0, 0, 1, 1,
                    touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);

            ret &= injectEventSync(event);
            SystemClock.sleep(MOTION_EVENT_INJECTION_DELAY_MILLIS);
        }

        // For each pointer get the last coordinates
        for (int x = 0; x < touches.length; x++)
            pointerCoords[x] = touches[x][touches[x].length - 1];

        // touch up
        for (int x = 1; x < touches.length; x++) {
            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    getPointerAction(MotionEvent.ACTION_POINTER_UP, x), x + 1, properties,
                    pointerCoords, 0, 0, 1, 1, touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            ret &= injectEventSync(event);
        }

        Log.i(LOG_TAG, "x " + pointerCoords[0].x);
        // first to touch down is last up
        event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1,
                properties, pointerCoords, 0, 0, 1, 1, touchScreenDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        ret &= injectEventSync(event);
        return ret;
    }

    private int getPointerAction(int motionEnvent, int index) {
        return motionEnvent + (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

}
