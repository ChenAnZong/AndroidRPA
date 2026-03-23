package scrcpy;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import uiautomator.ExtSystem;

public class ClipboardManagerWrapper {
    private static final ClipboardManager clipboardManager = new ServiceManager().getClipboardManager();
    public static final InputManager inputManager = new ServiceManager().getInputManager();

    private static int displayId = 0;

    public static boolean injectEvent(InputEvent inputEvent, int displayId, int injectMode) {
        return inputManager.injectInputEvent(inputEvent, injectMode);
    }


    public static boolean injectKeyEvent(int action, int keyCode, int repeat, int metaState, int displayId, int injectMode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        return injectEvent(event, displayId, injectMode);
    }

    public static boolean pressReleaseKeycode(int keyCode, int displayId, int injectMode) {
        return injectKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, 0, displayId, injectMode)
                && injectKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, 0, displayId, injectMode);
    }
    public static boolean pressReleaseKeycode(int keyCode, int injectMode) {
        return pressReleaseKeycode(keyCode, displayId, injectMode);
    }
    public static boolean pressReleaseKeycodeSync(int keyCode) {
        return pressReleaseKeycode(keyCode, displayId, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
    }

    public static boolean injectKeyConfirm() {
        return pressReleaseKeycode(KeyEvent.KEYCODE_SEARCH, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC) &&
                pressReleaseKeycode(KeyEvent.KEYCODE_ENTER, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC) &&
                pressReleaseKeycode(KeyEvent.KEYCODE_NUMPAD_ENTER, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    public static class ErrorClipBoardException extends Exception {}

    public static String getClipboardText() throws ErrorClipBoardException {
        if (clipboardManager == null) {
            throw new ErrorClipBoardException();
        }
        CharSequence text =  clipboardManager.getText();
        if (text == null) {
            throw new ErrorClipBoardException();
        } else {
            return text.toString();
        }
    }

    public static boolean setClipboardText(String text) {
        if (clipboardManager == null) {
            return false;
        }
        CharSequence s = clipboardManager.getText();
        ExtSystem.printDebugLog("设置前：" + s);
        if (s != null) {
            String currentClipboard = s.toString();
            if (!currentClipboard.equals(text)) {
                clipboardManager.setText(text);
            }
        } else {
            clipboardManager.setText(text);
        }
        ExtSystem.printDebugLog("设置后：" + clipboardManager.getText());

        // On Android >= 7, also press the PASTE key if requested
        return pressReleaseKeycode(KeyEvent.KEYCODE_PASTE, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
