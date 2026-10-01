package im.conversations.android.xmpp.model.fallback;

import com.google.common.base.Optional;
import eu.siacs.conversations.xml.LocalizedContent;
import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.ExtensionFactory;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.stanza.Message;

@XmlElement
public class Fallback extends Extension {
    public Fallback() {
        super(Fallback.class);
    }

    public String getFor() {
        return this.getAttribute("for");
    }

    public void setFor(final String namespace) {
        this.setAttribute("for", namespace);
    }

    public static Optional<Range> get(
            final Message message,
            final Class<? extends Extension> extension,
            final Class<? extends Element> element) {
        final var id = ExtensionFactory.id(extension);
        if (id == null) {
            throw new IllegalArgumentException(
                    String.format("%s is not a registered extension", extension.getName()));
        }
        for (final var fallback : message.getExtensions(Fallback.class)) {
            if (id.namespace().equals(fallback.getFor())) {
                if (fallback.isNoChildren()) {
                    return Optional.of(new FullRange());
                }
                final var e = fallback.getExtension(element);
                if (e != null) {
                    return Optional.of(e.getRange());
                }
            }
        }
        return Optional.absent();
    }

    public static LocalizedContent strip(
            final Message message,
            final Class<? extends Extension> extension,
            final Class<? extends Element> element,
            final LocalizedContent content) {
        final var range = get(message, extension, element);
        if (content == null || !range.isPresent()) {
            return content;
        }
        return range.get().strip(content);
    }

    private boolean isNoChildren() {
        return this.getExtensions(Body.class).isEmpty()
                && this.getExtensions(Subject.class).isEmpty();
    }

    public sealed interface Range permits StartEndRange, FullRange {
        boolean isEntire(final LocalizedContent content);

        LocalizedContent strip(final LocalizedContent content);
    }

    private record StartEndRange(int start, int end) implements Range {
        @Override
        public boolean isEntire(final LocalizedContent content) {
            return start == 0 && end >= content.content.length() - 1;
        }

        @Override
        public LocalizedContent strip(final LocalizedContent content) {
            final int safeStart = Math.max(0, Math.min(start, content.content.length()));
            final int safeEnd = Math.max(safeStart, Math.min(end, content.content.length()));
            final String stripped =
                    (content.content.substring(0, safeStart)
                                    + content.content.substring(safeEnd))
                            .trim();
            return LocalizedContent.of(stripped, content.language, content.count);
        }
    }

    private record FullRange() implements Range {

        @Override
        public boolean isEntire(LocalizedContent content) {
            return true;
        }

        @Override
        public LocalizedContent strip(final LocalizedContent content) {
            return LocalizedContent.of("", content.language, content.count);
        }
    }
    ;

    public abstract static sealed class Element extends Extension permits Body, Subject {

        public Element(Class<? extends Extension> clazz) {
            super(clazz);
        }

        public Range getRange() {
            final var start = this.getOptionalIntAttribute("start");
            final var end = this.getOptionalIntAttribute("end");
            if (start.isPresent() && end.isPresent()) {
                return new StartEndRange(start.get(), end.get());
            } else {
                return new FullRange();
            }
        }

        public void setRange(final int start, final int end) {
            this.setAttribute("start", start);
            this.setAttribute("end", end);
        }
    }
}
