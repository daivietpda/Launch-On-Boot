package news.androidtv.launchonboot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Non-system injector for tests and the future UI preview mode. It never sends
 * a real Android input event; it only records requests in order.
 */
public final class FakeKeyInjector implements KeyInjector {
    private final List<Integer> requestedKeyCodes = new ArrayList<>();
    private final List<String> requestedTexts = new ArrayList<>();
    private volatile boolean available = true;
    private volatile boolean sendSucceeds = true;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean sendKey(int keyCode) {
        if (!available) {
            return false;
        }
        synchronized (requestedKeyCodes) {
            requestedKeyCodes.add(keyCode);
        }
        return sendSucceeds;
    }

    @Override
    public boolean sendText(String text) {
        if (!available) {
            return false;
        }
        synchronized (requestedTexts) {
            requestedTexts.add(text);
        }
        return sendSucceeds;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setSendSucceeds(boolean sendSucceeds) {
        this.sendSucceeds = sendSucceeds;
    }

    public List<Integer> getRequestedKeyCodes() {
        synchronized (requestedKeyCodes) {
            return Collections.unmodifiableList(new ArrayList<>(requestedKeyCodes));
        }
    }

    public List<String> getRequestedTexts() {
        synchronized (requestedTexts) {
            return Collections.unmodifiableList(new ArrayList<>(requestedTexts));
        }
    }
}
