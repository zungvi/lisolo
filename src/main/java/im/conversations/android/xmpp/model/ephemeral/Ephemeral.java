package im.conversations.android.xmpp.model.ephemeral;

import com.google.common.primitives.Longs;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Ephemeral extends Extension {

    public Ephemeral() {
        super(Ephemeral.class);
    }

    public Ephemeral(final long timerSeconds) {
        this();
        setTimer(timerSeconds);
    }

    public Long getTimer() {
        return Longs.tryParse(getAttribute("timer"));
    }

    public void setTimer(final long timerSeconds) {
        if (timerSeconds <= 0) {
            throw new IllegalArgumentException("ephemeral timer must be positive");
        }
        setAttribute("timer", timerSeconds);
    }
}
