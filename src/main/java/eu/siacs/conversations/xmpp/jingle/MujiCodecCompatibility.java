package eu.siacs.conversations.xmpp.jingle;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import eu.siacs.conversations.xmpp.jingle.stanzas.RtpDescription;
import im.conversations.android.xmpp.model.jingle.Muji;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Validates the payload-type mappings advertised by MUJI participants for shared content. */
public final class MujiCodecCompatibility {

    private MujiCodecCompatibility() {}

    public static Result inspect(final Collection<Muji> announcements) {
        final List<String> issues = new ArrayList<>();
        final List<Map<String, Map<Integer, Codec>>> participantCodecs = new ArrayList<>();
        if (announcements.isEmpty()) {
            return new Result(
                    false,
                    Collections.emptyMap(),
                    ImmutableList.of("No MUJI media announcements are available"));
        }

        int participant = 0;
        for (final Muji muji : announcements) {
            participant++;
            if (muji == null) {
                issues.add("Participant " + participant + " has no MUJI announcement");
                participantCodecs.add(Collections.emptyMap());
                continue;
            }
            if (muji.isPreparing()) {
                issues.add("Participant " + participant + " is still preparing");
            }
            if (muji.getContents().isEmpty() && !muji.isPreparing()) {
                issues.add("Participant " + participant + " has not advertised media content");
            }
            final Map<String, Map<Integer, Codec>> contents = new LinkedHashMap<>();
            for (final Muji.Content content : muji.getContents()) {
                final String name = content.getName();
                if (name == null) {
                    issues.add("Participant " + participant + " advertised content without a name");
                    continue;
                }
                if (contents.containsKey(name)) {
                    issues.add(
                            "Participant " + participant + " advertised duplicate content " + name);
                    continue;
                }
                final RtpDescription description = content.getRtpDescription();
                if (description == null) {
                    issues.add(
                            "Participant " + participant + " has no RTP description for " + name);
                    contents.put(name, Collections.emptyMap());
                    continue;
                }
                if (description.getMedia() == Media.UNKNOWN) {
                    issues.add(
                            "Participant "
                                    + participant
                                    + " advertised unsupported media for "
                                    + name);
                    contents.put(name, Collections.emptyMap());
                    continue;
                }
                final Map<Integer, Codec> payloadTypes = new HashMap<>();
                for (final RtpDescription.PayloadType payloadType : description.getPayloadTypes()) {
                    final Integer id = parsePayloadTypeId(payloadType.getId());
                    final Codec codec = codec(payloadType);
                    if (id == null || codec == null) {
                        issues.add(
                                "Participant "
                                        + participant
                                        + " has an invalid payload type for "
                                        + name);
                        continue;
                    }
                    final Codec previous = payloadTypes.putIfAbsent(id, codec);
                    if (previous != null && !previous.equals(codec)) {
                        issues.add(
                                "Participant "
                                        + participant
                                        + " maps payload type "
                                        + id
                                        + " to more than one codec in "
                                        + name);
                    }
                }
                if (payloadTypes.isEmpty()) {
                    issues.add(
                            "Participant "
                                    + participant
                                    + " advertised no valid payload types for "
                                    + name);
                }
                contents.put(name, payloadTypes);
            }
            participantCodecs.add(contents);
        }

        final Set<String> contentNames = new TreeSet<>();
        for (final Map<String, Map<Integer, Codec>> contents : participantCodecs) {
            contentNames.addAll(contents.keySet());
        }
        final Map<String, Set<Integer>> sharedPayloadTypes = new LinkedHashMap<>();
        for (final String contentName : contentNames) {
            final List<Map<Integer, Codec>> mapsForContent = new ArrayList<>();
            for (final Map<String, Map<Integer, Codec>> contents : participantCodecs) {
                final Map<Integer, Codec> codecs = contents.get(contentName);
                if (codecs != null) {
                    mapsForContent.add(codecs);
                }
            }
            // A content can be omitted from one or more participant announcements. It is not
            // part of the room-wide profile in that case, even if the local participant supports
            // it. Other common contents (for example audio without video) can still be used.
            if (mapsForContent.size() != participantCodecs.size()) {
                continue;
            }

            final Map<Integer, Codec> seenMappings = new HashMap<>();
            final Set<Integer> commonIds = new TreeSet<>(mapsForContent.get(0).keySet());
            for (final Map<Integer, Codec> codecs : mapsForContent) {
                for (final Map.Entry<Integer, Codec> entry : codecs.entrySet()) {
                    final Codec previous = seenMappings.putIfAbsent(entry.getKey(), entry.getValue());
                    if (previous != null && !previous.equals(entry.getValue())) {
                        issues.add(
                                "Payload type "
                                        + entry.getKey()
                                        + " maps to different codecs for "
                                        + contentName);
                    }
                }
                commonIds.retainAll(codecs.keySet());
            }
            if (commonIds.isEmpty()) {
                continue;
            } else {
                sharedPayloadTypes.put(contentName, ImmutableSet.copyOf(commonIds));
            }
        }
        if (sharedPayloadTypes.isEmpty()) {
            issues.add("Participants have no shared media content");
        }
        return new Result(issues.isEmpty(), sharedPayloadTypes, issues);
    }

    private static Integer parsePayloadTypeId(final String id) {
        if (id == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(id);
            return value >= 0 && value <= 127 ? value : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Codec codec(final RtpDescription.PayloadType payloadType) {
        final String name = payloadType.getPayloadTypeName();
        if (name == null || payloadType.getClockRate() <= 0 || payloadType.getChannels() <= 0) {
            return null;
        }
        final List<String> parameters = new ArrayList<>();
        for (final RtpDescription.Parameter parameter : payloadType.getParameters()) {
            final String parameterName = parameter.getParameterName();
            if (parameterName == null) {
                return null;
            }
            parameters.add(
                    parameterName.toLowerCase(Locale.ROOT)
                            + "="
                            + (parameter.getParameterValue() == null
                                    ? ""
                                    : parameter.getParameterValue()));
        }
        Collections.sort(parameters);
        return new Codec(
                name.toLowerCase(Locale.ROOT),
                payloadType.getClockRate(),
                payloadType.getChannels(),
                ImmutableList.copyOf(parameters));
    }

    private record Codec(String name, int clockRate, int channels, List<String> parameters) {}

    public record Result(
            boolean compatible,
            Map<String, Set<Integer>> sharedPayloadTypes,
            List<String> issues) {
        public Result {
            sharedPayloadTypes = ImmutableMap.copyOf(sharedPayloadTypes);
            issues = ImmutableList.copyOf(issues);
        }

        public RtpContentMap retainSharedPayloadTypes(final RtpContentMap contentMap) {
            if (!compatible) {
                throw new IllegalStateException(
                        "Cannot publish MUJI media with incompatible payload mappings");
            }
            return contentMap.retainPayloadTypes(sharedPayloadTypes);
        }
    }
}
