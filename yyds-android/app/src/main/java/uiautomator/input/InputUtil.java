package uiautomator.input;

import android.view.InputDevice;

import java.io.File;

import uiautomator.ExtSystem;

public class InputUtil {

    // Protocol constants (must match native side)
    public static final int PROTOCOL_UNKNOWN = 0;
    public static final int PROTOCOL_A = 1;
    public static final int PROTOCOL_B = 2;

    private static boolean supportSource(InputDevice device, int source) {
        return (device.getSources() & source) == source;
    }

    /**
     * Find the touch input device path (/dev/input/eventX).
     * Improved: always returns a /dev/input/eventX path, never a device name.
     * Uses EVIOCGBIT capability check via getevent as primary detection.
     */
    public static String getDevice() {
        String touchDeviceName = null;

        // Step 1: Find touch device name via Android InputDevice API
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;
            if (supportSource(device, InputDevice.SOURCE_TOUCHSCREEN)
                    || supportSource(device, InputDevice.SOURCE_TOUCHPAD)) {
                touchDeviceName = device.getName();
                ExtSystem.printDebugLog("# Touch device name: " + touchDeviceName);
            }
        }

        final String devInput = "/dev/input";

        // Step 2: Match device name to /dev/input/eventX path
        try {
            String[] events = new File(devInput).list();
            if (events != null) {
                // If only one input device exists (common on cloud VMs), use it directly
                if (events.length == 1) {
                    String path = devInput + "/" + events[0];
                    ExtSystem.printDebugLog("Single input device: " + path);
                    return path;
                }

                // Match by device name from getevent -p
                if (touchDeviceName != null) {
                    for (String event : events) {
                        String path = devInput + "/" + event;
                        try {
                            String info = ExtSystem.shell("getevent -p " + path);
                            if (info.contains(touchDeviceName)) {
                                ExtSystem.printDebugLog("Matched touch device: " + path);
                                return path;
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // Fallback: find device that supports ABS_MT_POSITION_X (0x35)
                // This is a reliable indicator of a touch device
                for (String event : events) {
                    String path = devInput + "/" + event;
                    try {
                        String info = ExtSystem.shell("getevent -p " + path);
                        // Check for ABS_MT_POSITION_X (0035) in ABS section
                        if (info.contains("0035") && info.contains("0036")) {
                            ExtSystem.printDebugLog("Fallback touch device (has MT_POSITION): " + path);
                            return path;
                        }
                    } catch (Exception ignored) {}
                }

                // Last resort: first event device that is not gpio_keys/fingerprint
                for (String event : events) {
                    String path = devInput + "/" + event;
                    try {
                        String info = ExtSystem.shell("getevent -p " + path);
                        if (!info.contains("gpio_keys") && !info.contains("fingerprint")
                                && !info.contains("power") && !info.contains("volume")) {
                            ExtSystem.printDebugLog("Last resort device: " + path);
                            return path;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            ExtSystem.printDebugError("getDevice scan failed", e);
        }

        throw new RuntimeException("Not found touch device in " + devInput);
    }

    public static int getDeviceId() {
        try {
            if (InputDevice.getDeviceIds().length == 1) {
                ExtSystem.printInfo("Unique Input Device");
                return 0;
            }

            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;
                if (supportSource(device, InputDevice.SOURCE_TOUCHSCREEN)
                        || supportSource(device, InputDevice.SOURCE_TOUCHPAD)) {
                    ExtSystem.printDebugLog("# Input device id: " + id + " name: " + device.getName());
                    return id;
                }
            }
        } catch (Exception exception) {
            ExtSystem.printDebugError("getDeviceId", exception);
        }
        return 0;
    }

    // --- Native methods: real device ---
    public static native boolean init(int width, int height, String devicePath);
    public static synchronized native boolean sendEvent(int type, int code, int value);
    public static native int getDetectedProtocol();
    public static native int getMaxSlots();
    public static native boolean hasAbsAxis(int axis);
    public static native void closeDevice();

    // --- Native methods: uinput virtual device ---
    public static native boolean createUinput(int width, int height, String deviceName);
    public static native void destroyUinput();
    public static synchronized native boolean uinputSendEvent(int type, int code, int value);
    public static native boolean isUinputReady();

    // --- Native methods: uinput clone (clones real device identity) ---
    public static native boolean createUinputClone(int width, int height);
    public static native String getDeviceName();
    public static native int getRealPressureMax();
    public static native int getRealTouchMajorMax();
    public static native int getRealAbsMax(int axis);
}
