package im.conversations.android.xmpp.model.reporting;

import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement(namespace = Namespace.GROUP_CHAT_REPORTING)
public class ReportParticipant extends Extension {

    public ReportParticipant() {
        super(ReportParticipant.class);
    }
}
