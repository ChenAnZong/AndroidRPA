package uiautomator.input;

import static uiautomator.input.InputEventCodes.*;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.os.SystemClock;
import android.util.SparseIntArray;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import uiautomator.ExtSystem;
import uiautomator.compat.WindowManagerWrapper;
import uiautomator.test.TouchAction;

/**
 * RootAutomator — kernel-level touch injection via /dev/input/eventX
 *
 * Supports both Linux Multi-Touch Protocol A and Protocol B (slot-based).
 * Protocol is auto-detected at init time via EVIOCGBIT.
 *
 * Also supports uinput virtual touchscreen as an alternative backend.
 */
public class RootAutomator {

    private static final String LOG_TAG = "RootAutomator";
    private static final Random rand = new Random();

    // Swipe throttle: ~120Hz = 8.3ms per frame
    private static final long SWIPE_FRAME_INTERVAL_US = 8333;

    // Protocol constants
    private int protocol = InputUtil.PROTOCOL_UNKNOWN;
    private int maxSlots = 10;
    private boolean hasPressure = false;
    private boolean hasWidthMajor = false;

    // Real device ranges for realistic touch simulation
    private int realPressureMax = 255;
    private int realTouchMajorMax = 255;

    // Slot allocation for Protocol B
    private final SparseIntArray mIdToSlot = new SparseIntArray();   // touchId -> slot
    private final boolean[] mSlotInUse = new boolean[16];            // slot -> in use
    private final AtomicInteger mTrackingId = new AtomicInteger(1);

    // Whether to use uinput backend instead of real device
    private boolean useUinput = false;

    public RootAutomator() {
        this(false);
    }

    /**
     * @param preferUinput if true, try to create a uinput virtual touchscreen first
     */
    public RootAutomator(boolean preferUinput) {
        WindowManagerWrapper wrapper = new WindowManagerWrapper();
        Point point = wrapper.getDisplaySize();
        int w = point.x;
        int h = point.y;

        if (preferUinput) {
            useUinput = tryInitUinput(w, h);
            if (useUinput) {
                // uinput always uses Protocol B
                protocol = InputUtil.PROTOCOL_B;
                maxSlots = 10;
                hasPressure = true;
                hasWidthMajor = true;
                readRealDeviceRanges();
                ExtSystem.printDebugLog("@RA init=> uinput clone mode, w=" + w + " h=" + h);
                return;
            }
            ExtSystem.printDebugLog("@RA uinput failed, falling back to real device");
        }

        String devicePath = InputUtil.getDevice();
        boolean isSuccess = InputUtil.init(w, h, devicePath);
        if (isSuccess) {
            protocol = InputUtil.getDetectedProtocol();
            maxSlots = InputUtil.getMaxSlots();
            hasPressure = InputUtil.hasAbsAxis(ABS_MT_PRESSURE);
            hasWidthMajor = InputUtil.hasAbsAxis(ABS_MT_WIDTH_MAJOR);
            readRealDeviceRanges();
        }
        ExtSystem.printDebugLog("@RA init=> device:" + devicePath + " success:" + isSuccess
                + " protocol:" + (protocol == InputUtil.PROTOCOL_B ? "B" : "A")
                + " maxSlots:" + maxSlots
                + " hasPressure:" + hasPressure
                + " pressureMax:" + realPressureMax
                + " touchMajorMax:" + realTouchMajorMax
                + " w=" + w + " h=" + h);
    }

    private void readRealDeviceRanges() {
        try {
            int pMax = InputUtil.getRealPressureMax();
            if (pMax > 0) realPressureMax = pMax;
            int tMax = InputUtil.getRealTouchMajorMax();
            if (tMax > 0) realTouchMajorMax = tMax;
        } catch (Throwable ignored) {}
    }

    private boolean tryInitUinput(int w, int h) {
        // First init real device to read its properties, then create uinput clone
        try {
            String devicePath = InputUtil.getDevice();
            InputUtil.init(w, h, devicePath);
            // Create uinput clone with real device's identity (name, bus, vendor, phys)
            boolean ok = InputUtil.createUinputClone(w, h);
            InputUtil.closeDevice(); // close real device fd, uinput clone takes over
            if (ok) {
                ExtSystem.printDebugLog("@RA uinput clone created with real device identity");
                return true;
            }
        } catch (Throwable e) {
            ExtSystem.printDebugLog("createUinputClone failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Fallback: standard uinput with generic name
        try {
            return InputUtil.createUinput(w, h, null);
        } catch (Throwable e) {
            ExtSystem.printDebugLog("createUinput failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    public boolean isUinputMode() {
        return useUinput;
    }

    public int getProtocol() {
        return protocol;
    }

    // -----------------------------------------------------------------------
    // Low-level event sending (dispatches to real device or uinput)
    // -----------------------------------------------------------------------

    private synchronized boolean emit(int type, int code, int value) {
        if (useUinput) {
            return InputUtil.uinputSendEvent(type, code, value);
        }
        return InputUtil.sendEvent(type, code, value);
    }

    public void sendEvent(int type, int code, int value) {
        emit(type, code, value);
    }

    public void sendSync() {
        emit(EV_SYN, SYN_REPORT, 0);
    }

    public void sendMtSync() {
        emit(EV_SYN, SYN_MT_REPORT, 0);
    }

    // -----------------------------------------------------------------------
    // Slot management (Protocol B)
    // -----------------------------------------------------------------------

    private int allocateSlot(int touchId) {
        // Check if already allocated
        int existing = mIdToSlot.get(touchId, -1);
        if (existing >= 0) return existing;

        // Find free slot
        for (int i = 0; i < Math.min(maxSlots, mSlotInUse.length); i++) {
            if (!mSlotInUse[i]) {
                mSlotInUse[i] = true;
                mIdToSlot.put(touchId, i);
                return i;
            }
        }
        // Fallback: use slot 0
        ExtSystem.printDebugLog("@RA WARNING: no free slot, using 0");
        return 0;
    }

    private int getSlot(int touchId) {
        return mIdToSlot.get(touchId, 0);
    }

    private void releaseSlot(int touchId) {
        int idx = mIdToSlot.indexOfKey(touchId);
        if (idx >= 0) {
            int slot = mIdToSlot.valueAt(idx);
            if (slot >= 0 && slot < mSlotInUse.length) {
                mSlotInUse[slot] = false;
            }
            mIdToSlot.removeAt(idx);
        }
    }

    private int activeTouchCount() {
        int count = 0;
        for (boolean inUse : mSlotInUse) {
            if (inUse) count++;
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Pressure / touch area simulation helpers
    // -----------------------------------------------------------------------

    // Scale pressure/touch values to real device ranges for anti-detection
    private int randomPressureDown() {
        // Real finger down: ~30-60% of device max
        int lo = realPressureMax * 30 / 100;
        int hi = realPressureMax * 60 / 100;
        return rand.nextInt(Math.max(hi - lo, 1)) + lo;
    }

    private int randomPressureMove() {
        // Real finger moving: ~20-50% of device max
        int lo = realPressureMax * 20 / 100;
        int hi = realPressureMax * 50 / 100;
        return rand.nextInt(Math.max(hi - lo, 1)) + lo;
    }

    private int randomTouchMajor() {
        // Real finger contact area: ~3-8% of device max
        int lo = Math.max(realTouchMajorMax * 3 / 100, 3);
        int hi = Math.max(realTouchMajorMax * 8 / 100, 10);
        return rand.nextInt(Math.max(hi - lo, 1)) + lo;
    }

    private int randomTouchMinor() {
        // Slightly smaller than major
        int lo = Math.max(realTouchMajorMax * 2 / 100, 2);
        int hi = Math.max(realTouchMajorMax * 6 / 100, 8);
        return rand.nextInt(Math.max(hi - lo, 1)) + lo;
    }

    private int randomWidthMajor() {
        int lo = Math.max(realTouchMajorMax * 3 / 100, 3);
        int hi = Math.max(realTouchMajorMax * 7 / 100, 9);
        return rand.nextInt(Math.max(hi - lo, 1)) + lo;
    }

    // -----------------------------------------------------------------------
    // Touch gestures — Protocol-aware
    // -----------------------------------------------------------------------

    @SuppressLint("DefaultLocale")
    public synchronized void touchDown(int x, int y, int id) {
        if (protocol == InputUtil.PROTOCOL_B) {
            touchDownProtocolB(x, y, id);
        } else {
            touchDownProtocolA(x, y, id);
        }
    }

    private void touchDownProtocolB(int x, int y, int id) {
        int slot = allocateSlot(id);
        int trackId = mTrackingId.getAndIncrement();

        emit(EV_ABS, ABS_MT_SLOT, slot);
        emit(EV_ABS, ABS_MT_TRACKING_ID, trackId);

        // BTN_TOUCH and BTN_TOOL_FINGER only on first finger
        if (activeTouchCount() == 1) {
            emit(EV_KEY, BTN_TOUCH, DOWN);
            emit(EV_KEY, BTN_TOOL_FINGER, DOWN);
        }

        emit(EV_ABS, ABS_MT_POSITION_X, x);
        emit(EV_ABS, ABS_MT_POSITION_Y, y);
        emit(EV_ABS, ABS_MT_TOUCH_MAJOR, randomTouchMajor());
        emit(EV_ABS, ABS_MT_TOUCH_MINOR, randomTouchMinor());
        if (hasPressure) {
            emit(EV_ABS, ABS_MT_PRESSURE, randomPressureDown());
        }
        if (hasWidthMajor) {
            emit(EV_ABS, ABS_MT_WIDTH_MAJOR, randomWidthMajor());
        }
        emit(EV_SYN, SYN_REPORT, 0);
    }

    private void touchDownProtocolA(int x, int y, int id) {
        // Protocol A: no slot, use SYN_MT_REPORT to separate contacts
        emit(EV_KEY, BTN_TOUCH, DOWN);
        emit(EV_KEY, BTN_TOOL_FINGER, DOWN);
        emit(EV_ABS, ABS_MT_TRACKING_ID, mTrackingId.getAndIncrement());
        emit(EV_ABS, ABS_MT_POSITION_X, x);
        emit(EV_ABS, ABS_MT_POSITION_Y, y);
        emit(EV_ABS, ABS_MT_TOUCH_MAJOR, randomTouchMajor());
        emit(EV_ABS, ABS_MT_TOUCH_MINOR, randomTouchMinor());
        if (hasPressure) {
            emit(EV_ABS, ABS_MT_PRESSURE, randomPressureDown());
        }
        emit(EV_SYN, SYN_MT_REPORT, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    public void touchDown(int x, int y) {
        touchDown(x, y, 0);
    }

    @SuppressLint("DefaultLocale")
    public synchronized void touchUp(int id) {
        if (protocol == InputUtil.PROTOCOL_B) {
            touchUpProtocolB(id);
        } else {
            touchUpProtocolA(id);
        }
    }

    private void touchUpProtocolB(int id) {
        int slot = getSlot(id);
        releaseSlot(id);

        emit(EV_ABS, ABS_MT_SLOT, slot);
        emit(EV_ABS, ABS_MT_TRACKING_ID, -1); // release contact

        // BTN_TOUCH/BTN_TOOL_FINGER up only when last finger lifts
        if (activeTouchCount() == 0) {
            emit(EV_KEY, BTN_TOUCH, UP);
            emit(EV_KEY, BTN_TOOL_FINGER, UP);
        }
        emit(EV_SYN, SYN_REPORT, 0);
    }

    private void touchUpProtocolA(int id) {
        // Protocol A: empty SYN_MT_REPORT + SYN_REPORT = lift all
        emit(EV_KEY, BTN_TOUCH, UP);
        emit(EV_KEY, BTN_TOOL_FINGER, UP);
        emit(EV_SYN, SYN_MT_REPORT, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    public void touchUp() {
        touchUp(0);
    }

    public synchronized void touchMove(int x, int y, int id) {
        if (protocol == InputUtil.PROTOCOL_B) {
            touchMoveProtocolB(x, y, id);
        } else {
            touchMoveProtocolA(x, y, id);
        }
    }

    private void touchMoveProtocolB(int x, int y, int id) {
        int slot = getSlot(id);
        emit(EV_ABS, ABS_MT_SLOT, slot);
        emit(EV_ABS, ABS_MT_POSITION_X, x);
        emit(EV_ABS, ABS_MT_POSITION_Y, y);
        emit(EV_ABS, ABS_MT_TOUCH_MAJOR, randomTouchMajor());
        if (hasPressure) {
            emit(EV_ABS, ABS_MT_PRESSURE, randomPressureMove());
        }
        emit(EV_SYN, SYN_REPORT, 0);
    }

    private void touchMoveProtocolA(int x, int y, int id) {
        emit(EV_ABS, ABS_MT_POSITION_X, x);
        emit(EV_ABS, ABS_MT_POSITION_Y, y);
        emit(EV_ABS, ABS_MT_TOUCH_MAJOR, randomTouchMajor());
        if (hasPressure) {
            emit(EV_ABS, ABS_MT_PRESSURE, randomPressureMove());
        }
        emit(EV_SYN, SYN_MT_REPORT, 0);
        emit(EV_SYN, SYN_REPORT, 0);
    }

    public void touchMove(int x, int y) {
        touchMove(x, y, 0);
    }

    // -----------------------------------------------------------------------
    // High-level gestures
    // -----------------------------------------------------------------------

    public void tap(int x, int y, int id) {
        touchDown(x, y, id);
        SystemClock.sleep(TouchAction.randomInt(40, 100));
        touchUp(id);
    }

    public void tap(int x, int y) {
        tap(x, y, 0);
    }

    public void touch(int x, int y) {
        touchDown(x, y, 0);
        SystemClock.sleep(TouchAction.randomInt(40, 100));
        touchUp(0);
    }

    public void press(int x, int y, int duration, int id) {
        touchDown(x, y, id);
        SystemClock.sleep(duration);
        touchUp(id);
    }

    /**
     * Swipe with throttled move events (~120Hz).
     */
    public void swipe(int x1, int y1, int x2, int y2, int duration, int id) {
        touchDown(x1, y1, id);

        long startTime = SystemClock.uptimeMillis();
        long endTime = startTime + duration;
        long now = startTime;

        while (now < endTime) {
            float alpha = (float) (now - startTime) / duration;
            int cx = (int) lerp(x1, x2, alpha);
            int cy = (int) lerp(y1, y2, alpha);
            touchMove(cx, cy, id);

            // Throttle to ~120Hz
            try {
                Thread.sleep(8);
            } catch (InterruptedException ignored) {}
            now = SystemClock.uptimeMillis();
        }

        // Final position
        touchMove(x2, y2, id);
        touchUp(id);
    }

    public void swipe(int x1, int y1, int x2, int y2, int duration) {
        swipe(x1, y1, x2, y2, duration, 0);
    }

    public void swipe(int x1, int y1, int x2, int y2) {
        swipe(x1, y1, x2, y2, 300, 0);
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    public void close() {
        if (useUinput) {
            try { InputUtil.destroyUinput(); } catch (Exception ignored) {}
        } else {
            try { InputUtil.closeDevice(); } catch (Exception ignored) {}
        }
    }

    // -----------------------------------------------------------------------
    // Util
    // -----------------------------------------------------------------------

    private static float lerp(float a, float b, float alpha) {
        return (b - a) * alpha + a;
    }
}
