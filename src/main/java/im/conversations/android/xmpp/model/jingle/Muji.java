package im.conversations.android.xmpp.model.jingle;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import java.util.List;

@XmlElement(namespace = Namespace.JINGLE_MUJI)
public class Muji extends Extension {

    public Muji() {
        super(Muji.class);
    }

    public static Muji preparing() {
        final var muji = new Muji();
        muji.addExtension(new Preparing());
        return muji;
    }

    public static Muji forRoom(final Jid room) {
        final var muji = new Muji();
        muji.setRoom(Preconditions.checkNotNull(room));
        return muji;
    }

    public Jid getRoom() {
        final String room = Strings.emptyToNull(getAttribute("room"));
        if (room == null) {
            return null;
        }
        try {
            return Jid.of(room).asBareJid();
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    public void setRoom(final Jid room) {
        if (room == null) {
            removeAttribute("room");
        } else {
            setAttribute("room", room.asBareJid());
        }
    }

    public boolean isPreparing() {
        return hasExtension(Preparing.class);
    }

    public List<Content> getContents() {
        return ImmutableList.copyOf(getExtensions(Content.class));
    }

    public Content addContent(
            final String creator, final String name, final RtpDescription description) {
        final var content = new Content(creator, name);
        content.addChild(Preconditions.checkNotNull(description));
        return addExtension(content);
    }

    @XmlElement(namespace = Namespace.JINGLE_MUJI)
    public static class Preparing extends Extension {

        public Preparing() {
            super(Preparing.class);
        }
    }

    @XmlElement(namespace = Namespace.JINGLE_MUJI)
    public static class Content extends Extension {

        public Content() {
            super(Content.class);
        }

        public Content(final String creator, final String name) {
            this();
            setAttribute("creator", Preconditions.checkNotNull(creator));
            setAttribute("name", Preconditions.checkNotNull(name));
        }

        public String getCreator() {
            return Strings.emptyToNull(getAttribute("creator"));
        }

        public String getName() {
            return Strings.emptyToNull(getAttribute("name"));
        }

        public RtpDescription getRtpDescription() {
            final Element description =
                    findChildEnsureSingle("description", Namespace.JINGLE_APPS_RTP);
            return description == null ? null : RtpDescription.upgrade(description);
        }
    }
}
