package news.androidtv.launchonboot;

/**
 * Sends a validated Android key code to the active input target.
 * Implementations must report failure instead of silently claiming a key was sent.
 */
public interface KeyInjector {
    boolean isAvailable();

    boolean sendKey(int keyCode);

    /** Sends text to the focused input field when this backend supports it. */
    default boolean sendText(String text) {
        return false;
    }

    /**
     * Releases transient resources after a sequence. Implementations may
     * reconnect when a later sequence starts.
     */
    default void release() {
    }
}
