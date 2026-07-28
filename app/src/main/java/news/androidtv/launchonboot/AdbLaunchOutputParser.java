package news.androidtv.launchonboot;

/** Parses only the documented output of the fixed `am start -W` command. */
final class AdbLaunchOutputParser {
    private AdbLaunchOutputParser() { }
    static boolean isConfirmed(String output) {
        if (output == null) return false;
        String value = output.toLowerCase(java.util.Locale.US);
        return value.contains("status: ok")
                && !value.contains("error:")
                && !value.contains("securityexception")
                && !value.contains("permission denial");
    }
}
