package news.androidtv.launchonboot;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists post-launch action sequences in SharedPreferences as a JSON array. */
public final class ActionSequenceStore {
    public static final int MAX_ACTION_COUNT = 1_000;
    public static final boolean DEFAULT_ADVANCED_ACTIONS_ENABLED = false;
    public static final boolean DEFAULT_ACTION_TRIGGER_BOOT = true;
    public static final boolean DEFAULT_ACTION_TRIGGER_WAKE = true;
    public static final long DEFAULT_APP_LAUNCH_DELAY_MS = 5000L;
    public static final long DEFAULT_POST_LAUNCH_DELAY_MS = 10_000L;
    public static final long DEFAULT_ACTION_DELAY_MS = 300L;
    /** Example shown by the advanced-settings editor before the user saves a sequence. */
    public static final String DEFAULT_DEMO_ACTIONS_JSON =
            "[{\"type\":\"KEY\",\"keyCode\":\"KEYCODE_1\",\"delayAfterMs\":500,\"repeat\":1}]";
    public static final String DEFAULT_KEY_INJECTION_METHOD = "NONE";
    public static final String DEFAULT_ADB_HOST = "127.0.0.1";
    public static final int DEFAULT_ADB_PORT = 5555;
    public static final int DEFAULT_ADB_RETRY_COUNT = 3;
    public static final long DEFAULT_ADB_RETRY_DELAY_MS = 1000L;
    public static final long DEFAULT_ACTION_DEBOUNCE_MS = 20_000L;

    private final SettingsManager settingsManager;

    public ActionSequenceStore(Context context) {
        this(new SettingsManager(context));
    }

    ActionSequenceStore(SettingsManager settingsManager) {
        if (settingsManager == null) {
            throw new IllegalArgumentException("settingsManager must not be null");
        }
        this.settingsManager = settingsManager;
    }

    public List<ActionItem> getActionSequence() {
        String json;
        try {
            json = settingsManager.getString(SettingsManagerConstants.ADVANCED_ACTIONS_JSON, "");
        } catch (ClassCastException e) {
            return Collections.emptyList();
        }
        if (json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return deserializeSafely(json);
    }

    /**
     * Serializes and persists the supplied actions. Returning the canonical JSON
     * lets callers display or log exactly what was committed.
     */
    public String save(List<ActionItem> actions) throws JSONException {
        String json = serialize(actions);
        settingsManager.setString(SettingsManagerConstants.ADVANCED_ACTIONS_JSON, json);
        return json;
    }

    /** @deprecated Use {@link #save(List)} to make persistence explicit. */
    @Deprecated
    public void setActionSequence(List<ActionItem> actions) throws JSONException {
        save(actions);
    }

    public void clearActionSequence() {
        settingsManager.setString(SettingsManagerConstants.ADVANCED_ACTIONS_JSON, "");
    }

    /** Restores all advanced-action settings to their safe defaults. */
    public void restoreDefaultConfiguration() {
        settingsManager.setBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED,
                DEFAULT_ADVANCED_ACTIONS_ENABLED);
        settingsManager.setBoolean(SettingsManagerConstants.ACTION_TRIGGER_BOOT,
                DEFAULT_ACTION_TRIGGER_BOOT);
        settingsManager.setBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE,
                DEFAULT_ACTION_TRIGGER_WAKE);
        settingsManager.setLong(SettingsManagerConstants.APP_LAUNCH_DELAY_MS,
                DEFAULT_APP_LAUNCH_DELAY_MS);
        settingsManager.setLong(SettingsManagerConstants.POST_LAUNCH_DELAY_MS,
                DEFAULT_POST_LAUNCH_DELAY_MS);
        settingsManager.setLong(SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS,
                DEFAULT_ACTION_DELAY_MS);
        settingsManager.setString(SettingsManagerConstants.KEY_INJECTION_METHOD,
                DEFAULT_KEY_INJECTION_METHOD);
        settingsManager.setString(SettingsManagerConstants.ADB_HOST, DEFAULT_ADB_HOST);
        settingsManager.setInt(SettingsManagerConstants.ADB_PORT, DEFAULT_ADB_PORT);
        settingsManager.setInt(SettingsManagerConstants.ADB_RETRY_COUNT, DEFAULT_ADB_RETRY_COUNT);
        settingsManager.setLong(SettingsManagerConstants.ADB_RETRY_DELAY_MS,
                DEFAULT_ADB_RETRY_DELAY_MS);
        settingsManager.setLong(SettingsManagerConstants.ACTION_DEBOUNCE_MS,
                DEFAULT_ACTION_DEBOUNCE_MS);
        settingsManager.setString(SettingsManagerConstants.ADVANCED_ACTIONS_JSON, "[]");
    }

    public static String serialize(List<ActionItem> actions) throws JSONException {
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        validateActionCount(actions.size());

        JSONArray json = new JSONArray();
        for (ActionItem action : actions) {
            if (action == null) {
                throw new IllegalArgumentException("actions must not contain null items");
            }
            json.put(action.toJson());
        }
        return json.toString();
    }

    public static List<ActionItem> deserialize(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }

        JSONArray array = new JSONArray(json);
        validateActionCount(array.length());
        List<ActionItem> actions = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            if (array.isNull(i)) {
                throw new JSONException("Action item at index " + i + " must not be null");
            }
            actions.add(ActionItem.fromJson(array.getJSONObject(i)));
        }
        return Collections.unmodifiableList(actions);
    }

    /**
     * Parses an existing preference defensively. Invalid or obsolete JSON must
     * never prevent the app from starting.
     */
    public static List<ActionItem> deserializeSafely(String json) {
        try {
            return deserialize(json);
        } catch (JSONException | IllegalArgumentException e) {
            return Collections.emptyList();
        }
    }

    private static void validateActionCount(int actionCount) {
        if (actionCount > MAX_ACTION_COUNT) {
            throw new IllegalArgumentException(
                    "Action sequence must not exceed " + MAX_ACTION_COUNT + " items");
        }
    }
}
