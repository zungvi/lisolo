package eu.siacs.conversations.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.IndividualMessage;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.model.Bookmark;
import im.conversations.android.xmpp.model.muc.Affiliation;
import im.conversations.android.xmpp.model.muc.Role;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Exercises the real fragment diff snapshot without requiring an XMPP connection or a view. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 24)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ConversationMessageSnapshotTest {

    @Test
    public void openingGroupWithDateSeparatorDoesNotLookUpSeparatorSender() throws Exception {
        final Fixture fixture = fixture();
        final Message message = receivedMessage(fixture.conversation);
        fixture.messages.add(IndividualMessage.createDateSeparator(message));
        fixture.messages.add(message);

        assertEquals(2, snapshot(fixture.fragment).size());
    }

    @Test
    public void groupLoadMoreRowDoesNotRequireParticipant() throws Exception {
        final Fixture fixture = fixture();
        fixture.messages.add(Message.createLoadMoreMessage(fixture.conversation));

        assertEquals(1, snapshot(fixture.fragment).size());
    }

    @Test
    public void groupStatusWithoutParticipantDoesNotRequireSender() throws Exception {
        final Fixture fixture = fixture();
        fixture.messages.add(Message.createStatusMessage(fixture.conversation, "Room notice"));

        assertEquals(1, snapshot(fixture.fragment).size());
    }

    @Test
    public void singleReaderStatusTracksVisibleParticipantAvatar() throws Exception {
        final Fixture fixture = fixture();
        final MucOptions.User alice = participant(fixture.conversation, "Alice");
        fixture.conversation.getMucOptions().updateUser(alice);
        final Message status = Message.createStatusMessage(fixture.conversation, "Alice has read");
        status.setCounterpart(alice.getFullJid());
        fixture.messages.add(status);
        final String before = content(snapshot(fixture.fragment).get(0));

        alice.setAvatar("alice-new-avatar");

        assertNotEquals(before, content(snapshot(fixture.fragment).get(0)));
    }

    @Test
    public void multipleReaderStatusTracksAvatarsWithoutInventingSingleSender() throws Exception {
        final Fixture fixture = fixture();
        final MucOptions.User alice = participant(fixture.conversation, "Alice");
        final MucOptions.User bob = participant(fixture.conversation, "Bob");
        final Message status =
                Message.createStatusMessage(fixture.conversation, "Alice and Bob have read");
        status.setCounterparts(List.of(alice, bob));
        fixture.messages.add(status);
        final String before = content(snapshot(fixture.fragment).get(0));

        bob.setAvatar("bob-new-avatar");

        assertNotEquals(before, content(snapshot(fixture.fragment).get(0)));
    }

    @Test
    public void realReceivedGroupMessageStillTracksSenderAvatar() throws Exception {
        final Fixture fixture = fixture();
        final MucOptions.User alice = participant(fixture.conversation, "Alice");
        fixture.conversation.getMucOptions().updateUser(alice);
        fixture.messages.add(receivedMessage(fixture.conversation));
        final String before = content(snapshot(fixture.fragment).get(0));
        assertEquals(before, content(snapshot(fixture.fragment).get(0)));

        alice.setAvatar("alice-new-avatar");

        assertNotEquals(before, content(snapshot(fixture.fragment).get(0)));
    }

    private static Message receivedMessage(final Conversation conversation) {
        final Message message =
                new Message(
                        conversation,
                        "A group message",
                        Message.ENCRYPTION_NONE,
                        Message.STATUS_RECEIVED);
        message.setCounterpart(conversation.getAddress().withResource("Alice"));
        return message;
    }

    private static MucOptions.User participant(
            final Conversation conversation, final String nickname) {
        return new MucOptions.User(
                conversation.getMucOptions(),
                conversation.getAddress().withResource(nickname),
                null,
                null,
                Role.PARTICIPANT,
                Affiliation.MEMBER);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() throws Exception {
        final ConversationFragment fragment = new ConversationFragment();
        final TestConversation conversation = new TestConversation(new TestAccount());
        final Field conversationField = ConversationFragment.class.getDeclaredField("conversation");
        conversationField.setAccessible(true);
        conversationField.set(fragment, conversation);
        final Field messagesField = ConversationFragment.class.getDeclaredField("messageList");
        messagesField.setAccessible(true);
        return new Fixture(
                fragment, conversation, (List<Message>) messagesField.get(fragment));
    }

    private static List<?> snapshot(final ConversationFragment fragment) throws Exception {
        final Method method = ConversationFragment.class.getDeclaredMethod("snapshotMessageRows");
        method.setAccessible(true);
        try {
            return (List<?>) method.invoke(fragment);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static String content(final Object row) throws Exception {
        final Field field = row.getClass().getDeclaredField("content");
        field.setAccessible(true);
        return (String) field.get(row);
    }

    private record Fixture(
            ConversationFragment fragment, TestConversation conversation, List<Message> messages) {}

    private static final class TestAccount extends Account {
        private TestAccount() {
            super(Jid.of("tester@example.net"), "unused");
        }

        @Override
        public AxolotlService getAxolotlService() {
            return null;
        }

        @Override
        public boolean isPgpDecryptionServiceConnected() {
            return false;
        }

        @Override
        public boolean hasPendingPgpIntent(final Conversation conversation) {
            return false;
        }
    }

    private static final class TestConversation extends Conversation {
        private MucOptions options;

        private TestConversation(final Account account) {
            super("Test group", account, Jid.of("room@conference.example.net"), MODE_MULTI);
        }

        @Override
        public synchronized MucOptions getMucOptions() {
            if (options == null) {
                options = new MucOptions(this);
            }
            return options;
        }

        @Override
        public Bookmark getBookmark() {
            return null;
        }

        @Override
        public int getNextEncryption() {
            return Message.ENCRYPTION_NONE;
        }
    }
}
