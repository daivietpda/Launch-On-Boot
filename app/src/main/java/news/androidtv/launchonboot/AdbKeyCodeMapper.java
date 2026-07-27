package news.androidtv.launchonboot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts only the application's supported Android key codes to fixed ADB
 * tokens. No user-provided text is ever appended to a shell command.
 */
public final class AdbKeyCodeMapper {
    private static final Map<Integer, String> KEY_CODES;

    static {
        Map<Integer, String> keyCodes = new LinkedHashMap<>();
        for (ActionItem.KeyCode keyCode : ActionItem.KeyCode.values()) {
            keyCodes.put(keyCode.getKeyEventCode(), keyCode.getAndroidKeyCode());
        }
        KEY_CODES = Collections.unmodifiableMap(keyCodes);
    }

    private AdbKeyCodeMapper() {
    }

    public static boolean isAllowed(int keyCode) {
        return KEY_CODES.containsKey(keyCode);
    }

    public static String toAdbKeyCode(int keyCode) {
        String adbKeyCode = KEY_CODES.get(keyCode);
        if (adbKeyCode == null) {
            throw new IllegalArgumentException("Unsupported keyCode: " + keyCode);
        }
        return adbKeyCode;
    }

    static String buildShellCommand(int keyCode) {
        return "input keyevent " + toAdbKeyCode(keyCode)
                + "; echo " + AdbConnectionManager.KEY_COMMAND_RESULT_PREFIX + "$?";
    }
}
