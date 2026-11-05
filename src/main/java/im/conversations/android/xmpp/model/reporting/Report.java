package im.conversations.android.xmpp.model.reporting;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;
import java.util.Locale;

@XmlElement(namespace = Namespace.REPORTING)
public class Report extends Extension {
    public Report() {
        super(Report.class);
    }

    public void setReason(final String reason) {
        this.setAttribute("reason", reason);
    }

    public void setText(final String text, final Locale locale) {
        final Element note = this.addChild("text", Namespace.REPORTING);
        note.setAttribute("xml:lang", locale.toLanguageTag());
        note.setContent(text);
    }
}
