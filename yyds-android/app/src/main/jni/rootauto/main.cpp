//
// Created by Administrator on 2022/9/23.
// Rewritten: Full MT Protocol A/B support, uinput virtual device, fd lifecycle management
//

#include "string"
#include <unistd.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <sys/time.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include "jni.h"
#include "../log.h"
#include <sys/file.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <pthread.h>
#include <errno.h>

#define VERSION 2

#define ERROR_IO 2

// --- Protocol detection ---
#define PROTOCOL_UNKNOWN 0
#define PROTOCOL_A       1
#define PROTOCOL_B       2

// UI_SET_PHYS added in Linux 5.4; define for older kernels
#ifndef UI_SET_PHYS
#define UI_SET_PHYS _IOW('U', 108, char[UINPUT_MAX_NAME_SIZE])
#endif
#ifndef INPUT_PROP_MAX
#define INPUT_PROP_MAX 0x1f
#endif

static int deviceFd = -1;
static int detectedProtocol = PROTOCOL_UNKNOWN;
static int maxSlots = 10;  // default max slots for Protocol B
static pthread_mutex_t fdMutex = PTHREAD_MUTEX_INITIALIZER;

// ---- uinput support ----
static int uinputFd = -1;
static int screenWidth = 0;
static int screenHeight = 0;

// ---- Real device properties for uinput cloning ----
struct RealDeviceInfo {
    bool valid;
    char name[256];
    char phys[256];
    struct input_id id;
    struct {
        bool exists;
        struct input_absinfo info;
    } abs[ABS_MAX + 1];
    unsigned long keyBits[(KEY_MAX + 1) / (sizeof(unsigned long) * 8) + 1];
    unsigned long propBits[(INPUT_PROP_MAX + 1) / (sizeof(unsigned long) * 8) + 1];
};

static RealDeviceInfo realDev = {};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

static void fillTimeval(struct input_event *ev) {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    ev->time = tv;
}

static bool writeEvent(int fd, int type, int code, int value) {
    struct input_event event {};
    fillTimeval(&event);
    event.type = type;
    event.code = code;
    event.value = value;
    ssize_t written = write(fd, &event, sizeof(struct input_event));
    return written == sizeof(struct input_event);
}

// ---------------------------------------------------------------------------
// Protocol detection via EVIOCGBIT
// ---------------------------------------------------------------------------

static int detectProtocol(int fd) {
    // Check if device supports ABS_MT_SLOT (Protocol B indicator)
    unsigned long absBits[(ABS_MAX + 1) / (sizeof(unsigned long) * 8) + 1];
    memset(absBits, 0, sizeof(absBits));

    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) {
        LOGE("detectProtocol: EVIOCGBIT(EV_ABS) failed: %s", strerror(errno));
        return PROTOCOL_A; // fallback
    }

    // Check ABS_MT_SLOT bit
    bool hasSlot = (absBits[ABS_MT_SLOT / (sizeof(unsigned long) * 8)]
                    >> (ABS_MT_SLOT % (sizeof(unsigned long) * 8))) & 1;

    // Check ABS_MT_TRACKING_ID bit
    bool hasTrackingId = (absBits[ABS_MT_TRACKING_ID / (sizeof(unsigned long) * 8)]
                          >> (ABS_MT_TRACKING_ID % (sizeof(unsigned long) * 8))) & 1;

    if (hasSlot) {
        // Query max slots
        struct input_absinfo slotInfo {};
        if (ioctl(fd, EVIOCGABS(ABS_MT_SLOT), &slotInfo) == 0) {
            maxSlots = slotInfo.maximum + 1;
            if (maxSlots <= 0) maxSlots = 10;
        }
        LOGI("detectProtocol: Protocol B detected, maxSlots=%d", maxSlots);
        return PROTOCOL_B;
    }

    if (hasTrackingId) {
        LOGI("detectProtocol: Protocol A with tracking_id");
    } else {
        LOGI("detectProtocol: Protocol A (basic)");
    }
    return PROTOCOL_A;
}

// ---------------------------------------------------------------------------
// Check device capabilities for ABS axes
// ---------------------------------------------------------------------------

static bool deviceHasAbsAxis(int fd, int axis) {
    unsigned long absBits[(ABS_MAX + 1) / (sizeof(unsigned long) * 8) + 1];
    memset(absBits, 0, sizeof(absBits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) < 0) return false;
    return (absBits[axis / (sizeof(unsigned long) * 8)]
            >> (axis % (sizeof(unsigned long) * 8))) & 1;
}

// ---------------------------------------------------------------------------
// Real device init / close
// ---------------------------------------------------------------------------

static void closeDevice() {
    pthread_mutex_lock(&fdMutex);
    if (deviceFd >= 0) {
        ::close(deviceFd);
        deviceFd = -1;
    }
    detectedProtocol = PROTOCOL_UNKNOWN;
    pthread_mutex_unlock(&fdMutex);
}

static int initDevice(int w, int h, const char *devicePath) {
    pthread_mutex_lock(&fdMutex);

    // Close old fd if any (prevent leak)
    if (deviceFd >= 0) {
        ::close(deviceFd);
        deviceFd = -1;
    }

    screenWidth = w;
    screenHeight = h;

    deviceFd = open(devicePath, O_RDWR | O_SYNC);
    if (deviceFd < 0) {
        LOGE("initDevice: cannot open '%s': %s", devicePath, strerror(errno));
        pthread_mutex_unlock(&fdMutex);
        return -ERROR_IO;
    }

    detectedProtocol = detectProtocol(deviceFd);

    // Read real device properties for uinput cloning & coordinate mapping
    memset(&realDev, 0, sizeof(realDev));
    if (ioctl(deviceFd, EVIOCGNAME(sizeof(realDev.name)), realDev.name) >= 0) {
        LOGI("initDevice: real device name='%s'", realDev.name);
    }
    ioctl(deviceFd, EVIOCGPHYS(sizeof(realDev.phys)), realDev.phys);
    ioctl(deviceFd, EVIOCGID, &realDev.id);
    ioctl(deviceFd, EVIOCGPROP(sizeof(realDev.propBits)), realDev.propBits);
    ioctl(deviceFd, EVIOCGBIT(EV_KEY, sizeof(realDev.keyBits)), realDev.keyBits);

    // Read absinfo for all supported ABS axes
    unsigned long absBitsAll[(ABS_MAX + 1) / (sizeof(unsigned long) * 8) + 1];
    memset(absBitsAll, 0, sizeof(absBitsAll));
    if (ioctl(deviceFd, EVIOCGBIT(EV_ABS, sizeof(absBitsAll)), absBitsAll) >= 0) {
        for (int axis = 0; axis <= ABS_MAX; axis++) {
            bool has = (absBitsAll[axis / (sizeof(unsigned long) * 8)]
                        >> (axis % (sizeof(unsigned long) * 8))) & 1;
            realDev.abs[axis].exists = has;
            if (has) {
                ioctl(deviceFd, EVIOCGABS(axis), &realDev.abs[axis].info);
            }
        }
    }
    realDev.valid = true;

    LOGI("initDevice: opened '%s' fd=%d protocol=%s w=%d h=%d bus=%d vendor=0x%04x product=0x%04x phys='%s'",
         devicePath, deviceFd,
         detectedProtocol == PROTOCOL_B ? "B" : "A",
         w, h,
         realDev.id.bustype, realDev.id.vendor, realDev.id.product, realDev.phys);

    pthread_mutex_unlock(&fdMutex);
    return deviceFd;
}

// ---------------------------------------------------------------------------
// sendEvent — writes a single input_event to the real device
// ---------------------------------------------------------------------------

static bool sendEvent(int type, int code, int value) {
    pthread_mutex_lock(&fdMutex);
    if (deviceFd < 0) {
        pthread_mutex_unlock(&fdMutex);
        return false;
    }
    bool ok = writeEvent(deviceFd, type, code, value);
    pthread_mutex_unlock(&fdMutex);
    return ok;
}

// ---------------------------------------------------------------------------
// uinput virtual touchscreen
// ---------------------------------------------------------------------------

static void setupAbsAxis(struct uinput_user_dev *dev, int axis, int min, int max) {
    dev->absmin[axis] = min;
    dev->absmax[axis] = max;
    dev->absfuzz[axis] = 0;
    dev->absflat[axis] = 0;
}

static int createUinputDevice(int w, int h, const char *deviceName) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("createUinputDevice: cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    // Enable event types
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    // Enable touch keys
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    // Enable ABS axes
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MAJOR);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TOUCH_MINOR);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_WIDTH_MAJOR);

    // Enable INPUT_PROP_DIRECT (direct touch device)
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);

    struct uinput_user_dev udev {};
    memset(&udev, 0, sizeof(udev));
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "%s",
             deviceName ? deviceName : "Goodix Capacitive TouchScreen");
    udev.id.bustype = BUS_USB;
    udev.id.vendor  = 0x1234;
    udev.id.product = 0x5678;
    udev.id.version = 1;

    setupAbsAxis(&udev, ABS_MT_SLOT, 0, 9);
    setupAbsAxis(&udev, ABS_MT_TRACKING_ID, 0, 65535);
    setupAbsAxis(&udev, ABS_MT_POSITION_X, 0, w > 0 ? w - 1 : 1079);
    setupAbsAxis(&udev, ABS_MT_POSITION_Y, 0, h > 0 ? h - 1 : 1919);
    setupAbsAxis(&udev, ABS_MT_TOUCH_MAJOR, 0, 255);
    setupAbsAxis(&udev, ABS_MT_TOUCH_MINOR, 0, 255);
    setupAbsAxis(&udev, ABS_MT_PRESSURE, 0, 255);
    setupAbsAxis(&udev, ABS_MT_WIDTH_MAJOR, 0, 255);

    if (write(fd, &udev, sizeof(udev)) != sizeof(udev)) {
        LOGE("createUinputDevice: write udev failed: %s", strerror(errno));
        ::close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("createUinputDevice: UI_DEV_CREATE failed: %s", strerror(errno));
        ::close(fd);
        return -1;
    }

    // Give the system time to register the device
    usleep(200000); // 200ms

    LOGI("createUinputDevice: created '%s' fd=%d w=%d h=%d",
         udev.name, fd, w, h);
    return fd;
}

// ---------------------------------------------------------------------------
// uinput clone — creates a uinput device that perfectly mimics the real one
// ---------------------------------------------------------------------------

static int createUinputCloneDevice(int w, int h) {
    if (!realDev.valid) {
        LOGE("createUinputCloneDevice: no real device info, falling back to default");
        return createUinputDevice(w, h, nullptr);
    }

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("createUinputCloneDevice: cannot open /dev/uinput: %s", strerror(errno));
        return -1;
    }

    // Enable event types
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    // Clone key capabilities from real device
    for (int key = 0; key <= KEY_MAX; key++) {
        if ((realDev.keyBits[key / (sizeof(unsigned long) * 8)]
             >> (key % (sizeof(unsigned long) * 8))) & 1) {
            ioctl(fd, UI_SET_KEYBIT, key);
        }
    }
    // Ensure minimum touch keys
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOOL_FINGER);

    // Clone ABS axes from real device
    for (int axis = 0; axis <= ABS_MAX; axis++) {
        if (realDev.abs[axis].exists) {
            ioctl(fd, UI_SET_ABSBIT, axis);
        }
    }

    // Clone property bits (INPUT_PROP_DIRECT etc.)
    for (int prop = 0; prop <= INPUT_PROP_MAX; prop++) {
        if ((realDev.propBits[prop / (sizeof(unsigned long) * 8)]
             >> (prop % (sizeof(unsigned long) * 8))) & 1) {
            ioctl(fd, UI_SET_PROPBIT, prop);
        }
    }

    // Set physical path to match real device (Linux 5.4+, ignored on older)
    if (realDev.phys[0] != '\0') {
        ioctl(fd, UI_SET_PHYS, realDev.phys);  // ignore ENOTTY on old kernels
    }

    // Build uinput_user_dev with cloned identity
    struct uinput_user_dev udev {};
    memset(&udev, 0, sizeof(udev));
    snprintf(udev.name, UINPUT_MAX_NAME_SIZE, "%s", realDev.name);
    udev.id = realDev.id;  // clone bustype, vendor, product, version

    // Clone absinfo for all supported axes
    for (int axis = 0; axis <= ABS_MAX; axis++) {
        if (realDev.abs[axis].exists) {
            udev.absmin[axis]  = realDev.abs[axis].info.minimum;
            udev.absmax[axis]  = realDev.abs[axis].info.maximum;
            udev.absfuzz[axis] = realDev.abs[axis].info.fuzz;
            udev.absflat[axis] = realDev.abs[axis].info.flat;
        }
    }

    if (write(fd, &udev, sizeof(udev)) != sizeof(udev)) {
        LOGE("createUinputCloneDevice: write udev failed: %s", strerror(errno));
        ::close(fd);
        return -1;
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        LOGE("createUinputCloneDevice: UI_DEV_CREATE failed: %s", strerror(errno));
        ::close(fd);
        return -1;
    }

    usleep(200000); // 200ms for system to register

    LOGI("createUinputCloneDevice: created clone '%s' bus=%d vendor=0x%04x product=0x%04x phys='%s' fd=%d",
         udev.name, udev.id.bustype, udev.id.vendor, udev.id.product, realDev.phys, fd);
    return fd;
}

static void destroyUinputDevice() {
    if (uinputFd >= 0) {
        ioctl(uinputFd, UI_DEV_DESTROY);
        ::close(uinputFd);
        uinputFd = -1;
        LOGI("destroyUinputDevice: destroyed");
    }
}

static bool uinputSendEvent(int type, int code, int value) {
    if (uinputFd < 0) return false;
    return writeEvent(uinputFd, type, code, value);
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_init(JNIEnv *env, jclass thiz, jint w, jint h, jstring devicePath) {
    const char *path = env->GetStringUTFChars(devicePath, nullptr);
    jboolean result = initDevice(w, h, path) > 0;
    env->ReleaseStringUTFChars(devicePath, path);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_sendEvent(JNIEnv *env, jclass thiz, jint type, jint code, jint value) {
    return sendEvent(type, code, value);
}

JNIEXPORT jint JNICALL
Java_uiautomator_input_InputUtil_getDetectedProtocol(JNIEnv *env, jclass thiz) {
    return detectedProtocol;
}

JNIEXPORT jint JNICALL
Java_uiautomator_input_InputUtil_getMaxSlots(JNIEnv *env, jclass thiz) {
    return maxSlots;
}

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_hasAbsAxis(JNIEnv *env, jclass thiz, jint axis) {
    pthread_mutex_lock(&fdMutex);
    bool result = deviceFd >= 0 && deviceHasAbsAxis(deviceFd, axis);
    pthread_mutex_unlock(&fdMutex);
    return result;
}

JNIEXPORT void JNICALL
Java_uiautomator_input_InputUtil_closeDevice(JNIEnv *env, jclass thiz) {
    closeDevice();
}

// --- uinput JNI ---

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_createUinput(JNIEnv *env, jclass thiz, jint w, jint h, jstring name) {
    const char *devName = nullptr;
    if (name != nullptr) {
        devName = env->GetStringUTFChars(name, nullptr);
    }
    destroyUinputDevice(); // cleanup old
    uinputFd = createUinputDevice(w, h, devName);
    if (name != nullptr && devName != nullptr) {
        env->ReleaseStringUTFChars(name, devName);
    }
    return uinputFd >= 0;
}

JNIEXPORT void JNICALL
Java_uiautomator_input_InputUtil_destroyUinput(JNIEnv *env, jclass thiz) {
    destroyUinputDevice();
}

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_uinputSendEvent(JNIEnv *env, jclass thiz, jint type, jint code, jint value) {
    return uinputSendEvent(type, code, value);
}

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_isUinputReady(JNIEnv *env, jclass thiz) {
    return uinputFd >= 0;
}

// --- uinput clone JNI (clones real device identity) ---

JNIEXPORT jboolean JNICALL
Java_uiautomator_input_InputUtil_createUinputClone(JNIEnv *env, jclass thiz, jint w, jint h) {
    destroyUinputDevice();
    uinputFd = createUinputCloneDevice(w, h);
    return uinputFd >= 0;
}

JNIEXPORT jstring JNICALL
Java_uiautomator_input_InputUtil_getDeviceName(JNIEnv *env, jclass thiz) {
    if (realDev.valid && realDev.name[0] != '\0') {
        return env->NewStringUTF(realDev.name);
    }
    return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL
Java_uiautomator_input_InputUtil_getRealPressureMax(JNIEnv *env, jclass thiz) {
    if (realDev.valid && realDev.abs[ABS_MT_PRESSURE].exists) {
        return realDev.abs[ABS_MT_PRESSURE].info.maximum;
    }
    return 255;
}

JNIEXPORT jint JNICALL
Java_uiautomator_input_InputUtil_getRealTouchMajorMax(JNIEnv *env, jclass thiz) {
    if (realDev.valid && realDev.abs[ABS_MT_TOUCH_MAJOR].exists) {
        return realDev.abs[ABS_MT_TOUCH_MAJOR].info.maximum;
    }
    return 255;
}

JNIEXPORT jint JNICALL
Java_uiautomator_input_InputUtil_getRealAbsMax(JNIEnv *env, jclass thiz, jint axis) {
    if (realDev.valid && axis >= 0 && axis <= ABS_MAX && realDev.abs[axis].exists) {
        return realDev.abs[axis].info.maximum;
    }
    return -1;
}

} // extern "C"