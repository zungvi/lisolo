package eu.siacs.conversations.ui;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityPickServerBinding;
import eu.siacs.conversations.entities.XmppProvider;
import eu.siacs.conversations.ui.adapter.XmppProviderAdapter;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;

public class PickServerActivity extends XmppActivity {

    private static final String PROVIDERS_URL =
            "https://data.xmpp.net/providers/v2/providers-B.json";
    private static final String PROVIDER_CACHE_KEY = "providers_b_json";
    private static final String PROVIDER_CACHE_PREFERENCES = "provider_directory";

    private final OkHttpClient httpClient = new OkHttpClient();
    private final List<XmppProvider> providers = new ArrayList<>();

    private ActivityPickServerBinding binding;
    private XmppProviderAdapter adapter;
    @Nullable private Call providerCall;
    private boolean loadingProviders;
    @Nullable private String directoryError;

    @Override
    protected void refreshUiReal() {}

    @Override
    protected void onBackendConnected() {}

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (getResources().getBoolean(R.bool.portrait_only)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_pick_server);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());

        adapter = new XmppProviderAdapter(this::showProviderDetails);
        binding.providerList.setLayoutManager(new LinearLayoutManager(this));
        binding.providerList.setAdapter(adapter);
        binding.providerSearch.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            final CharSequence s,
                            final int start,
                            final int count,
                            final int after) {}

                    @Override
                    public void onTextChanged(
                            final CharSequence s,
                            final int start,
                            final int before,
                            final int count) {
                        filterProviders(s.toString());
                    }

                    @Override
                    public void afterTextChanged(final Editable s) {}
                });
        binding.useCustomDomain.setOnClickListener(v -> useCustomProvider());
        binding.createZungviAccount.setOnClickListener(v -> createZungviAccount());
        loadProviderDirectory();
    }

    @Override
    protected void onDestroy() {
        if (providerCall != null) {
            providerCall.cancel();
        }
        super.onDestroy();
    }

    private void loadProviderDirectory() {
        final String cached =
                getSharedPreferences(PROVIDER_CACHE_PREFERENCES, MODE_PRIVATE)
                        .getString(PROVIDER_CACHE_KEY, null);
        if (cached != null) {
            try {
                providers.clear();
                providers.addAll(validateProviders(XmppProvider.parseList(cached)));
                filterProviders(binding.providerSearch.getText().toString());
            } catch (final JSONException ignored) {
                getSharedPreferences(PROVIDER_CACHE_PREFERENCES, MODE_PRIVATE)
                        .edit()
                        .remove(PROVIDER_CACHE_KEY)
                        .apply();
            }
        }

        loadingProviders = true;
        directoryError = null;
        updateDirectoryStatus();
        final Request request =
                new Request.Builder()
                        .url(PROVIDERS_URL)
                        .header("Accept", "application/json")
                        .build();
        providerCall = httpClient.newCall(request);
        providerCall.enqueue(
                new Callback() {
                    @Override
                    public void onFailure(@NonNull final Call call, @NonNull final IOException e) {
                        runOnUiThread(
                                () -> {
                                    if (isFinishing() || isDestroyed()) {
                                        return;
                                    }
                                    loadingProviders = false;
                                    directoryError =
                                            getString(R.string.provider_directory_unavailable);
                                    updateDirectoryStatus();
                                });
                    }

                    @Override
                    public void onResponse(
                            @NonNull final Call call, @NonNull final Response response) {
                        try (response) {
                            if (!response.isSuccessful() || response.body() == null) {
                                throw new IOException("Provider directory response was not successful");
                            }
                            final String body = response.body().string();
                            final List<XmppProvider> fetched =
                                    validateProviders(XmppProvider.parseList(body));
                            if (fetched.isEmpty()) {
                                throw new IOException("Provider directory had no supported entries");
                            }
                            getSharedPreferences(PROVIDER_CACHE_PREFERENCES, MODE_PRIVATE)
                                    .edit()
                                    .putString(PROVIDER_CACHE_KEY, body)
                                    .apply();
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing() || isDestroyed()) {
                                            return;
                                        }
                                        providers.clear();
                                        providers.addAll(fetched);
                                        loadingProviders = false;
                                        directoryError = null;
                                        filterProviders(
                                                binding.providerSearch.getText().toString());
                                    });
                        } catch (final IOException | JSONException e) {
                            runOnUiThread(
                                    () -> {
                                        if (isFinishing() || isDestroyed()) {
                                            return;
                                        }
                                        loadingProviders = false;
                                        directoryError =
                                                getString(R.string.provider_directory_unavailable);
                                        updateDirectoryStatus();
                                    });
                        }
                    }
                });
    }

    private static List<XmppProvider> validateProviders(final List<XmppProvider> values) {
        final List<XmppProvider> valid = new ArrayList<>();
        for (final XmppProvider provider : values) {
            try {
                Jid.ofDomain(provider.domain);
                valid.add(provider);
            } catch (final IllegalArgumentException ignored) {
                // A malformed directory entry must not become an account domain.
            }
        }
        return valid;
    }

    private void filterProviders(final String query) {
        final String normalized =
                query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final List<XmppProvider> filtered = new ArrayList<>();
        for (final XmppProvider provider : providers) {
            if (normalized.isEmpty()
                    || provider.domain.toLowerCase(Locale.ROOT).contains(normalized)
                    || provider.category.toLowerCase(Locale.ROOT).contains(normalized)) {
                filtered.add(provider);
            }
        }
        adapter.setProviders(filtered);
        updateDirectoryStatus(filtered.size(), normalized.isEmpty());
    }

    private void updateDirectoryStatus() {
        updateDirectoryStatus(adapter == null ? 0 : adapter.getItemCount(), true);
    }

    private void updateDirectoryStatus(final int visibleCount, final boolean queryIsEmpty) {
        binding.providerProgress.setVisibility(loadingProviders ? View.VISIBLE : View.GONE);
        if (!queryIsEmpty && visibleCount == 0) {
            binding.providerStatus.setText(R.string.provider_no_search_results);
        } else if (loadingProviders && providers.isEmpty()) {
            binding.providerStatus.setText(R.string.provider_directory_loading);
        } else if (loadingProviders) {
            binding.providerStatus.setText(R.string.provider_directory_refreshing);
        } else if (directoryError != null && providers.isEmpty()) {
            binding.providerStatus.setText(directoryError);
        } else if (directoryError != null) {
            binding.providerStatus.setText(R.string.provider_directory_using_saved_list);
        } else {
            binding.providerStatus.setText(
                    getString(R.string.provider_directory_count, visibleCount));
        }
    }

    private void showProviderDetails(final XmppProvider provider) {
        final String details = buildProviderDetails(provider);
        final MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(provider.domain)
                        .setMessage(details)
                        .setNegativeButton(
                                R.string.provider_use_existing_account,
                                (dialog, which) -> signInWithProvider(provider.domain));
        if (provider.inBandRegistration) {
            builder.setPositiveButton(
                    R.string.provider_create_in_app,
                    (dialog, which) -> createProviderAccount(provider.domain));
        } else if (provider.registrationWebsite != null) {
            builder.setPositiveButton(
                    R.string.provider_open_registration,
                    (dialog, which) -> openWebsite(provider.registrationWebsite));
        }
        if (provider.website != null) {
            builder.setNeutralButton(
                    R.string.provider_open_website,
                    (dialog, which) -> openWebsite(provider.website));
        }
        builder.show();
    }

    private String buildProviderDetails(final XmppProvider provider) {
        final StringBuilder details = new StringBuilder();
        details.append(
                getString(
                        provider.category.equals("A")
                                ? R.string.provider_category_a
                                : R.string.provider_category_b));
        details.append("\n");
        if (provider.inBandRegistration) {
            details.append(getString(R.string.provider_registration_in_app));
        } else if (provider.registrationWebsite != null) {
            details.append(getString(R.string.provider_registration_website));
        } else {
            details.append(getString(R.string.provider_registration_not_listed));
        }
        if (provider.freeOfChargeKnown) {
            details.append("\n")
                    .append(
                            getString(
                                    provider.freeOfCharge
                                            ? R.string.provider_free_of_charge
                                            : R.string.provider_not_free_of_charge));
        }
        if (provider.complianceScore >= 0) {
            details.append("\n")
                    .append(
                            getString(
                                    R.string.provider_compliance_score,
                                    provider.complianceScore));
        }
        if (provider.maximumFileSizeBytes >= 0) {
            details.append("\n")
                    .append(
                            getString(
                                    R.string.provider_max_file_size,
                                    UIHelper.filesizeToString(provider.maximumFileSizeBytes)));
        }
        if (provider.messageArchiveDays == -1) {
            details.append("\n").append(getString(R.string.provider_history_less_than_day));
        } else if (provider.messageArchiveDays >= 0) {
            details.append("\n");
            if (provider.messageArchiveDays == 0) {
                details.append(getString(R.string.provider_history_unlimited));
            } else {
                details.append(
                        getString(
                                R.string.provider_history_days,
                                provider.messageArchiveDays));
            }
        }
        if (!provider.serverLocations.isEmpty()) {
            details.append("\n")
                    .append(
                            getString(
                                    R.string.provider_server_locations,
                                    TextUtils.join(", ", provider.serverLocations)));
        }
        details.append("\n\n")
                .append(getString(R.string.provider_capabilities_checked_after_sign_in));
        return details.toString();
    }

    private void createProviderAccount(final String domain) {
        final Intent intent = new Intent(this, MagicCreateActivity.class);
        intent.putExtra(MagicCreateActivity.EXTRA_DOMAIN, domain);
        addInviteUri(intent);
        startActivity(intent);
    }

    private void createZungviAccount() {
        final Intent intent = new Intent(this, MagicCreateActivity.class);
        addInviteUri(intent);
        startActivity(intent);
    }

    private void signInWithProvider(final String domain) {
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, false);
        intent.putExtra(EditAccountActivity.EXTRA_PROVIDER_DOMAIN, domain);
        intent.putExtra("init", true);
        addInviteUri(intent);
        startActivity(intent);
    }

    private void useCustomProvider() {
        final String input = binding.customDomain.getText() == null
                ? ""
                : binding.customDomain.getText().toString().trim();
        final Jid provider;
        try {
            provider = Jid.ofDomain(input);
            if (provider.getDomain() == null) {
                throw new IllegalArgumentException("Provider domain is missing");
            }
        } catch (final IllegalArgumentException e) {
            binding.customDomainLayout.setError(getString(R.string.provider_invalid_domain));
            binding.customDomain.requestFocus();
            return;
        }
        binding.customDomainLayout.setError(null);
        final Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra(EditAccountActivity.EXTRA_FORCE_REGISTER, true);
        intent.putExtra(EditAccountActivity.EXTRA_PROVIDER_DOMAIN, provider.getDomain().toString());
        intent.putExtra("init", true);
        addInviteUri(intent);
        startActivity(intent);
    }

    private void openWebsite(final String url) {
        final Uri uri = Uri.parse(url);
        final String scheme = uri.getScheme();
        if (!uri.isHierarchical()
                || uri.getHost() == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.provider_invalid_website)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (final ActivityNotFoundException e) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.provider_no_browser)
                    .setPositiveButton(R.string.ok, null)
                    .show();
        }
    }

    public void addInviteUri(final Intent intent) {
        StartConversationActivity.addInviteUri(intent, getIntent());
    }

    public static void launch(final AppCompatActivity activity) {
        final Intent intent = new Intent(activity, PickServerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }
}
