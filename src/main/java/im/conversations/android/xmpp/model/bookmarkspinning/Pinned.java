package im.conversations.android.xmpp.model.bookmarkspinning;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

/** Pin marker from XEP-0469, stored inside a native bookmark's extensions element. */
@XmlElement(name = "pinned")
public class Pinned extends Extension {

    public static final String ELEMENT = "pinned";

    public Pinned() {
        super(Pinned.class);
    }
}
