package news.androidtv.launchonboot;

import org.json.JSONException;
import org.json.JSONObject;
import android.view.KeyEvent;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A single action in a post-launch action sequence.
 *
 * <p>The persisted JSON format intentionally follows the format documented in
 * {@code IMPLEMENTATION_PLAN_ADVANCED_ACTIONS.md}:</p>
 *
 * <ul>
 *     <li>{@code {"type":"WAIT", "durationMs":1000}}</li>
 *     <li>{@code {"type":"KEY", "keyCode":"KEYCODE_DPAD_DOWN",
 *     "delayAfterMs":500, "repeat":1}}</li>
 * </ul>
 */
public final class ActionItem {
    public enum Type {
        WAIT,
        KEY,
        TEXT
    }

    public static final long DEFAULT_DELAY_AFTER_MS = 0L;
    public static final int DEFAULT_REPEAT = 1;
    public static final long MAX_ACTION_TIME_MS = 86_400_000L;
    public static final int MAX_REPEAT = 1_000;
    public static final int MAX_TEXT_LENGTH = 200;

    /**
     * Key codes supported by the first version of the action sequence.
     * JSON stores Android's canonical KeyEvent name, while the shorter enum
     * names (for example {@code DPAD_UP}) are also accepted when reading input.
     */
    public enum KeyCode {
        DPAD_UP("KEYCODE_DPAD_UP", KeyEvent.KEYCODE_DPAD_UP),
        DPAD_DOWN("KEYCODE_DPAD_DOWN", KeyEvent.KEYCODE_DPAD_DOWN),
        DPAD_LEFT("KEYCODE_DPAD_LEFT", KeyEvent.KEYCODE_DPAD_LEFT),
        DPAD_RIGHT("KEYCODE_DPAD_RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT),
        DPAD_CENTER("KEYCODE_DPAD_CENTER", KeyEvent.KEYCODE_DPAD_CENTER),
        KEYCODE_0("KEYCODE_0", KeyEvent.KEYCODE_0),
        KEYCODE_1("KEYCODE_1", KeyEvent.KEYCODE_1),
        KEYCODE_2("KEYCODE_2", KeyEvent.KEYCODE_2),
        KEYCODE_3("KEYCODE_3", KeyEvent.KEYCODE_3),
        KEYCODE_4("KEYCODE_4", KeyEvent.KEYCODE_4),
        KEYCODE_5("KEYCODE_5", KeyEvent.KEYCODE_5),
        KEYCODE_6("KEYCODE_6", KeyEvent.KEYCODE_6),
        KEYCODE_7("KEYCODE_7", KeyEvent.KEYCODE_7),
        KEYCODE_8("KEYCODE_8", KeyEvent.KEYCODE_8),
        KEYCODE_9("KEYCODE_9", KeyEvent.KEYCODE_9),
        ENTER("KEYCODE_ENTER", KeyEvent.KEYCODE_ENTER),
        BACK("KEYCODE_BACK", KeyEvent.KEYCODE_BACK),
        HOME("KEYCODE_HOME", KeyEvent.KEYCODE_HOME),
        MENU("KEYCODE_MENU", KeyEvent.KEYCODE_MENU);

        private final String androidKeyCode;
        private final int keyEventCode;

        KeyCode(String androidKeyCode, int keyEventCode) {
            this.androidKeyCode = androidKeyCode;
            this.keyEventCode = keyEventCode;
        }

        public String getAndroidKeyCode() {
            return androidKeyCode;
        }

        public int getKeyEventCode() {
            return keyEventCode;
        }

        public static KeyCode fromValue(String value) {
            if (value == null) {
                throw new IllegalArgumentException("keyCode must not be empty");
            }
            String normalized = value.trim();
            for (KeyCode keyCode : values()) {
                if (keyCode.name().equals(normalized) || keyCode.androidKeyCode.equals(normalized)) {
                    return keyCode;
                }
            }
            throw new IllegalArgumentException("Unsupported keyCode: " + value);
        }
    }

    private static final String JSON_TYPE = "type";
    private static final String JSON_KEY_CODE = "keyCode";
    private static final String JSON_DURATION_MS = "durationMs";
    private static final String JSON_DELAY_AFTER_MS = "delayAfterMs";
    private static final String JSON_REPEAT = "repeat";
    private static final String JSON_TEXT = "text";

    private final Type type;
    private final String keyCode;
    private final long durationMs;
    private final long delayAfterMs;
    private final boolean delayAfterMsSet;
    private final int repeat;
    private final String text;

    private ActionItem(Type type, String keyCode, long durationMs, long delayAfterMs,
                       boolean delayAfterMsSet, int repeat) {
        this(type, keyCode, durationMs, delayAfterMs, delayAfterMsSet, repeat, null);
    }

    private ActionItem(Type type, String keyCode, long durationMs, long delayAfterMs,
                       boolean delayAfterMsSet, int repeat, String text) {
        this.type = type;
        this.keyCode = keyCode;
        this.durationMs = durationMs;
        this.delayAfterMs = delayAfterMs;
        this.delayAfterMsSet = delayAfterMsSet;
        this.repeat = repeat;
        this.text = text;
    }

    public static ActionItem waitFor(long durationMs) {
        validateActionTime(durationMs, "durationMs");
        return new ActionItem(Type.WAIT, null, durationMs, DEFAULT_DELAY_AFTER_MS, false,
                DEFAULT_REPEAT);
    }

    public static ActionItem key(String keyCode, long delayAfterMs, int repeat) {
        validateActionTime(delayAfterMs, "delayAfterMs");
        validateRepeat(repeat);
        KeyCode allowedKeyCode = KeyCode.fromValue(keyCode);
        return new ActionItem(Type.KEY, allowedKeyCode.getAndroidKeyCode(), 0L, delayAfterMs,
                true, repeat);
    }

    public static ActionItem key(KeyCode keyCode, long delayAfterMs, int repeat) {
        if (keyCode == null) {
            throw new IllegalArgumentException("keyCode must not be null");
        }
        return key(keyCode.getAndroidKeyCode(), delayAfterMs, repeat);
    }

    public static ActionItem text(String text, long delayAfterMs, int repeat) {
        validateText(text);
        validateActionTime(delayAfterMs, "delayAfterMs");
        validateRepeat(repeat);
        return new ActionItem(Type.TEXT, null, 0L, delayAfterMs, true, repeat, text);
    }

    public static Set<String> getAllowedKeyCodes() {
        Set<String> keyCodes = new LinkedHashSet<>();
        for (KeyCode keyCode : KeyCode.values()) {
            keyCodes.add(keyCode.getAndroidKeyCode());
        }
        return Collections.unmodifiableSet(keyCodes);
    }

    public Type getType() {
        return type;
    }

    public String getKeyCode() {
        return keyCode;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getDelayAfterMs() {
        return delayAfterMs;
    }

    /** True when the JSON/action explicitly supplied a delay, including zero. */
    public boolean isDelayAfterMsSet() {
        return delayAfterMsSet;
    }

    public int getRepeat() {
        return repeat;
    }

    public String getText() {
        return text;
    }

    public static void validateText(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("text must not be empty");
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("text must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
    }

    private static void validateRepeat(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("repeat must be at least 1");
        }
        if (value > MAX_REPEAT) {
            throw new IllegalArgumentException("repeat must not exceed " + MAX_REPEAT);
        }
    }

    static void validateActionTime(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (value > MAX_ACTION_TIME_MS) {
            throw new IllegalArgumentException(
                    name + " must not exceed " + MAX_ACTION_TIME_MS + " ms");
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put(JSON_TYPE, type.name());
        if (type == Type.WAIT) {
            json.put(JSON_DURATION_MS, durationMs);
        } else if (type == Type.KEY) {
            json.put(JSON_KEY_CODE, keyCode);
            if (delayAfterMsSet) {
                json.put(JSON_DELAY_AFTER_MS, delayAfterMs);
            }
            json.put(JSON_REPEAT, repeat);
        } else {
            json.put(JSON_TEXT, text);
            json.put(JSON_DELAY_AFTER_MS, delayAfterMs);
            json.put(JSON_REPEAT, repeat);
        }
        return json;
    }

    public static ActionItem fromJson(JSONObject json) throws JSONException {
        if (json == null) {
            throw new JSONException("Action item must not be null");
        }

        if (!json.has(JSON_TYPE) || !(json.opt(JSON_TYPE) instanceof String)) {
            throw new JSONException("Action type is required");
        }
        String typeName = (String) json.opt(JSON_TYPE);
        Type parsedType;
        try {
            parsedType = Type.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw new JSONException("Unsupported action type: " + typeName);
        }

        try {
            if (parsedType == Type.WAIT) {
                if (!json.has(JSON_DURATION_MS)) {
                    throw new JSONException("WAIT action requires durationMs");
                }
                return waitFor(requireInteger(json, JSON_DURATION_MS));
            }

            boolean delayAfterMsSet = json.has(JSON_DELAY_AFTER_MS);
            long delayAfterMs = delayAfterMsSet
                    ? requireInteger(json, JSON_DELAY_AFTER_MS) : DEFAULT_DELAY_AFTER_MS;
            long repeatValue = json.has(JSON_REPEAT)
                    ? requireInteger(json, JSON_REPEAT) : DEFAULT_REPEAT;
            if (repeatValue > Integer.MAX_VALUE) {
                throw new JSONException("repeat must be an integer");
            }
            int repeat = (int) repeatValue;
            if (parsedType == Type.TEXT) {
                if (!json.has(JSON_TEXT)) {
                    throw new JSONException("TEXT action requires text");
                }
                return text(requireString(json, JSON_TEXT), delayAfterMs, repeat);
            }
            if (!json.has(JSON_KEY_CODE)) {
                throw new JSONException("KEY action requires keyCode");
            }
            ActionItem action = key(requireString(json, JSON_KEY_CODE), delayAfterMs, repeat);
            return delayAfterMsSet ? action : new ActionItem(action.type, action.keyCode,
                    action.durationMs, action.delayAfterMs, false, action.repeat);
        } catch (IllegalArgumentException e) {
            throw new JSONException(e.getMessage());
        }
    }

    private static long requireInteger(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException(name + " must be an integer");
        }
        return ((Number) value).longValue();
    }

    private static String requireString(JSONObject json, String name) throws JSONException {
        Object value = json.get(name);
        if (!(value instanceof String)) {
            throw new JSONException(name + " must be a string");
        }
        return (String) value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionItem)) {
            return false;
        }
        ActionItem that = (ActionItem) other;
        return durationMs == that.durationMs
                && delayAfterMs == that.delayAfterMs
                && delayAfterMsSet == that.delayAfterMsSet
                && repeat == that.repeat
                && type == that.type
                && Objects.equals(keyCode, that.keyCode)
                && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, keyCode, durationMs, delayAfterMs, delayAfterMsSet, repeat, text);
    }
}
