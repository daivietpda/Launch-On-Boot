package news.androidtv.launchonboot;

/** Builds one safe, quoted argument for Android's {@code input text} command.
 * Android's input command is not Unicode-safe on all TV firmware, so this backend
 * intentionally accepts printable ASCII only. The original Unicode text remains
 * stored unchanged in the action JSON. */
public final class AdbTextEncoder {
    private AdbTextEncoder() { }

    public static String buildShellCommand(String text) {
        ActionItem.validateText(text);
        StringBuilder encoded = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character < 0x20 || character > 0x7e || character == '%') {
                throw new IllegalArgumentException(
                        "ADB text supports printable ASCII except percent (%)");
            }
            if (character == ' ') {
                encoded.append("%s");
            } else if (character == '\'') {
                // Close, escape, and reopen a POSIX single-quoted argument.
                encoded.append("'\\\"'\\\"'");
            } else {
                encoded.append(character);
            }
        }
        return "input text '" + encoded + "'; echo "
                + AdbConnectionManager.TEXT_COMMAND_RESULT_PREFIX + "$?";
    }
}
