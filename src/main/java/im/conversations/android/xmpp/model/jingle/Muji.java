package im.conversations.android.xmpp.model.jingle;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

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

    @XmlElement(namespace = Namespace.JINGLE_MUJI)
    public static class Preparing extends Extension {

        public Preparing() {
            super(Preparing.class);
        }
    }
}
