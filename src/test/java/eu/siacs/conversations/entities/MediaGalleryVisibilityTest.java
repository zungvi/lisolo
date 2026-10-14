package eu.siacs.conversations.entities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.xmpp.Jid;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Whether a received file is handed to the system media scanner is decided from the message's
 * conversation, which {@link Message#getConversation()} hands back as a {@link Conversational}.
 * The setting therefore has to be readable through the interface, not just off {@link
 * Conversation} — asking the concrete class is what broke the build in the first place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 24)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MediaGalleryVisibilityTest {

    @Test
    public void mediaIsVisibleByDefault() {
        assertTrue(conversation().isMediaGalleryVisible());
    }

    @Test
    public void hidingTheGalleryIsReadBack() {
        final Conversation conversation = conversation();
        conversation.setAttribute(Conversation.ATTRIBUTE_MEDIA_GALLERY_VISIBLE, false);

        assertFalse(conversation.isMediaGalleryVisible());
    }

    @Test
    public void theSettingIsReachableThroughTheInterface() {
        final Conversation conversation = conversation();
        conversation.setAttribute(Conversation.ATTRIBUTE_MEDIA_GALLERY_VISIBLE, false);
        final Conversational conversational =
                new Message(conversation, "hello", Message.ENCRYPTION_NONE, Message.STATUS_RECEIVED)
                        .getConversation();

        assertFalse(conversational.isMediaGalleryVisible());
    }

    /** A search result carries a stub, which has no attributes to read the setting from. */
    @Test
    public void aStubConversationFallsBackToVisible() {
        final Account account = new Account(Jid.of("me@example.test"), "unused");
        final Conversational stub =
                new StubConversation(
                        account, "uuid", Jid.of("you@example.test"), Conversational.MODE_SINGLE);

        assertTrue(stub.isMediaGalleryVisible());
    }

    private static Conversation conversation() {
        final Account account = new Account(Jid.of("me@example.test"), "unused");
        return new Conversation(
                "Test", account, Jid.of("you@example.test"), Conversation.MODE_SINGLE);
    }
}
