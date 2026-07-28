package news.androidtv.launchonboot;

import org.json.JSONException;

import java.util.List;

/** JSON-only import/export helpers. URI I/O remains in the Activity via ContentResolver. */
public final class ActionSequenceFileManager {
    public static final int MAX_FILE_SIZE_BYTES = 1024 * 1024;
    public static final int MAX_FILE_NAME_LENGTH = 100;

    private ActionSequenceFileManager() {
    }

    public static String normalizeFileName(String requestedName) {
        if (requestedName == null) {
            throw new IllegalArgumentException("File name is required");
        }
        String value = requestedName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.toLowerCase(java.util.Locale.US).endsWith(".json")) {
            value = value.substring(0, value.length() - 5).trim();
        }
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException("File name must not be empty");
        }
        if (value.length() > MAX_FILE_NAME_LENGTH) {
            value = value.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return value + ".json";
    }

    public static String serializeForExport(List<ActionItem> actions) throws JSONException {
        return new org.json.JSONArray(ActionSequenceStore.serialize(actions)).toString(2);
    }

    public static List<ActionItem> parseImportJson(String json) throws JSONException {
        if (json == null) {
            throw new JSONException("File cannot be read");
        }
        String withoutBom = json.startsWith("\ufeff") ? json.substring(1) : json;
        return ActionSequenceStore.deserialize(withoutBom);
    }
}
