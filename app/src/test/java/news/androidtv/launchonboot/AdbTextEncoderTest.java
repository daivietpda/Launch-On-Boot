package news.androidtv.launchonboot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AdbTextEncoderTest {
    @Test public void encodesSpacesInsideOneQuotedArgument() {
        assertEquals("input text 'TV%s105'; echo __LOB_TEXT_EXIT__$?",
                AdbTextEncoder.buildShellCommand("TV 105"));
    }

    @Test public void rejectsUnicodeInsteadOfSilentlyChangingIt() {
        try { AdbTextEncoder.buildShellCommand("Tiếng Việt"); fail("Expected rejection"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void quotesShellMetacharacters() {
        String command = AdbTextEncoder.buildShellCommand("a;rm -rf /");
        assertEquals("input text 'a;rm%s-rf%s/'; echo __LOB_TEXT_EXIT__$?", command);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPercentBecauseInputTextUsesPercentEscapes() {
        AdbTextEncoder.buildShellCommand("100%");
    }
}
