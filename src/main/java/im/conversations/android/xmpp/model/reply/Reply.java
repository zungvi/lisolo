package im.conversations.android.xmpp.model.reply;

import com.google.common.base.Strings;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Reply extends Extension {

    public Reply() {
        super(Reply.class);
    }

    public String getId() {
        return Strings.emptyToNull(this.getAttribute("id"));
    }

    public void setId(final String id) {
        this.setAttribute("id", id);
    }

    public Jid getTo() {
        return this.getAttributeAsJid("to");
    }

    public void setTo(final Jid to) {
        this.setAttribute("to", to);
    }
}
