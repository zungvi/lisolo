package eu.siacs.conversations.entities;

import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Public provider information from the XMPP Providers project. */
public final class XmppProvider {

    public final String domain;
    public final String category;
    @Nullable public final String website;
    @Nullable public final String registrationWebsite;
    public final boolean inBandRegistration;
    public final int complianceScore;
    public final long maximumFileSizeBytes;
    public final int messageArchiveDays;
    public final boolean freeOfCharge;
    public final boolean freeOfChargeKnown;
    public final List<String> serverLocations;

    private XmppProvider(
            final String domain,
            final String category,
            @Nullable final String website,
            @Nullable final String registrationWebsite,
            final boolean inBandRegistration,
            final int complianceScore,
            final long maximumFileSizeBytes,
            final int messageArchiveDays,
            final boolean freeOfCharge,
            final boolean freeOfChargeKnown,
            final List<String> serverLocations) {
        this.domain = domain;
        this.category = category;
        this.website = website;
        this.registrationWebsite = registrationWebsite;
        this.inBandRegistration = inBandRegistration;
        this.complianceScore = complianceScore;
        this.maximumFileSizeBytes = maximumFileSizeBytes;
        this.messageArchiveDays = messageArchiveDays;
        this.freeOfCharge = freeOfCharge;
        this.freeOfChargeKnown = freeOfChargeKnown;
        this.serverLocations = serverLocations;
    }

    public static List<XmppProvider> parseList(final String json) throws JSONException {
        final JSONArray array = new JSONArray(json);
        final List<XmppProvider> providers = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final String domain = item.optString("jid", "").trim();
            final String category = item.optString("category", "").trim();
            if (domain.isEmpty() || !("A".equals(category) || "B".equals(category))) {
                continue;
            }
            providers.add(
                    new XmppProvider(
                            domain,
                            category,
                            localizedValue(item.optJSONObject("website")),
                            localizedValue(item.optJSONObject("registrationWebPage")),
                            item.optBoolean("inBandRegistration", false),
                            item.optInt("ratingXmppComplianceTester", -1),
                            item.optLong("maximumHttpFileUploadFileSize", -1),
                            item.optInt("maximumMessageArchiveManagementStorageTime", -2),
                            item.optBoolean("freeOfCharge", false),
                            item.has("freeOfCharge") && !item.isNull("freeOfCharge"),
                            getLocations(item.optJSONArray("serverLocations"))));
        }
        providers.sort(
                (left, right) -> {
                    final int categoryOrder = left.category.compareTo(right.category);
                    return categoryOrder != 0
                            ? categoryOrder
                            : left.domain.compareToIgnoreCase(right.domain);
                });
        return providers;
    }

    @Nullable
    private static String localizedValue(@Nullable final JSONObject translations) {
        if (translations == null) {
            return null;
        }
        final String language = Locale.getDefault().getLanguage();
        final String localized = translations.optString(language, "").trim();
        if (!localized.isEmpty()) {
            return localized;
        }
        final String english = translations.optString("en", "").trim();
        if (!english.isEmpty()) {
            return english;
        }
        final Iterator<String> keys = translations.keys();
        while (keys.hasNext()) {
            final String value = translations.optString(keys.next(), "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> getLocations(@Nullable final JSONArray array) {
        final List<String> locations = new ArrayList<>();
        if (array == null) {
            return locations;
        }
        for (int i = 0; i < array.length(); i++) {
            final String location = array.optString(i, "").trim();
            if (!location.isEmpty()) {
                locations.add(location.toUpperCase(Locale.ROOT));
            }
        }
        return locations;
    }
}
