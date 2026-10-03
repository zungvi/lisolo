/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui;

import static eu.siacs.conversations.ui.ConversationFragment.REQUEST_DECRYPT_PGP;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.graphics.Bitmap;
import android.graphics.drawable.InsetDrawable;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.collect.Iterables;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.OmemoSetting;
import eu.siacs.conversations.databinding.ActivityConversationsBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.interfaces.OnBackendConnected;
import eu.siacs.conversations.ui.interfaces.OnConversationArchived;
import eu.siacs.conversations.ui.interfaces.OnConversationRead;
import eu.siacs.conversations.ui.interfaces.OnConversationSelected;
import eu.siacs.conversations.ui.interfaces.OnConversationsListItemUpdated;
import eu.siacs.conversations.ui.util.ActivityResult;
import eu.siacs.conversations.ui.util.ConversationMenuConfigurator;
import eu.siacs.conversations.ui.util.MenuDoubleTabUtil;
import eu.siacs.conversations.ui.util.PendingItem;
import eu.siacs.conversations.ui.util.ToolbarUtils;
import eu.siacs.conversations.utils.ExceptionHelper;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.OnUpdateBlocklist;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.openintents.openpgp.util.OpenPgpApi;

public class ConversationsActivity extends XmppActivity
        implements OnConversationSelected,
                OnConversationArchived,
                OnConversationsListItemUpdated,
                OnConversationRead,
                XmppConnectionService.OnAccountUpdate,
                XmppConnectionService.OnConversationUpdate,
                XmppConnectionService.OnRosterUpdate,
                OnUpdateBlocklist,
                XmppConnectionService.OnShowErrorToast,
                XmppConnectionService.OnAffiliationChanged,
                AccountSwitcherSheet.OnAccountSelectionChanged {

    /** Generates the toolbar avatar off the main thread when it is not already cached. */
    private static final ExecutorService AVATAR_EXECUTOR = Executors.newSingleThreadExecutor();

    /** The chat whose avatar the toolbar is currently showing or loading. */
    private Conversation toolbarAvatarConversation;

    public static final String ACTION_VIEW_CONVERSATION = "eu.siacs.conversations.action.VIEW";
    public static final String EXTRA_CONVERSATION = "conversationUuid";
    public static final String EXTRA_DOWNLOAD_UUID = "eu.siacs.conversations.download_uuid";
    public static final String EXTRA_AS_QUOTE = "eu.siacs.conversations.as_quote";
    public static final String EXTRA_NICK = "nick";
    public static final String EXTRA_IS_PRIVATE_MESSAGE = "pm";
    public static final String EXTRA_DO_NOT_APPEND = "do_not_append";
    public static final String EXTRA_POST_INIT_ACTION = "post_init_action";
    public static final String POST_ACTION_RECORD_VOICE = "record_voice";
    public static final String EXTRA_TYPE = "type";

    private static final List<String> VIEW_AND_SHARE_ACTIONS =
            Arrays.asList(
                    ACTION_VIEW_CONVERSATION, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE);

    public static final int REQUEST_OPEN_MESSAGE = 0x9876;
    public static final int REQUEST_PLAY_PAUSE = 0x5432;

    // secondary fragment (when holding the conversation, must be initialized before refreshing the
    // overview fragment
    private static final @IdRes int[] FRAGMENT_ID_NOTIFICATION_ORDER = {
        R.id.secondary_fragment, R.id.main_fragment
    };
    private final PendingItem<Intent> pendingViewIntent = new PendingItem<>();
    private final PendingItem<ActivityResult> postponedActivityResult = new PendingItem<>();
    private ActivityConversationsBinding binding;
    private boolean mActivityPaused = true;

    private static boolean isViewOrShareIntent(Intent i) {
        Log.d(Config.LOGTAG, "action: " + (i == null ? null : i.getAction()));
        return i != null
                && VIEW_AND_SHARE_ACTIONS.contains(i.getAction())
                && i.hasExtra(EXTRA_CONVERSATION);
    }

    private static Intent createLauncherIntent(Context context) {
        final Intent intent = new Intent(context, ConversationsActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        return intent;
    }

    @Override
    protected void refreshUiReal() {
        invalidateOptionsMenu();
        invalidateActionBarTitle();
        refreshAccountSwitcher();
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            refreshFragment(id);
        }
    }

    @Override
    protected void onBackendConnected() {
        xmppConnectionService.getNotificationService().setIsInForeground(true);
        final Intent intent = pendingViewIntent.pop();
        if (intent != null) {
            if (processViewIntent(intent)) {
                if (binding.secondaryFragment != null) {
                    notifyFragmentOfBackendConnected(R.id.main_fragment);
                }
                invalidateActionBarTitle();
                return;
            }
        }
        for (@IdRes int id : FRAGMENT_ID_NOTIFICATION_ORDER) {
            notifyFragmentOfBackendConnected(id);
        }

        final ActivityResult activityResult = postponedActivityResult.pop();
        if (activityResult != null) {
            handleActivityResult(activityResult);
        }

        invalidateActionBarTitle();
        if (binding.secondaryFragment != null
                && ConversationFragment.getConversation(this) == null) {
            final var conversation = ConversationsOverviewFragment.getSuggestion(this);
            openConversation(conversation, null);
        }
        showDialogsIfMainIsOverview();
    }

    private void showDialogsIfMainIsOverview() {
        if (xmppConnectionService == null) {
            return;
        }
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment) {
            if (ExceptionHelper.checkForCrash(this)) {
                return;
            }
            if (openBatteryOptimizationDialogIfNeeded()) {
                return;
            }
            requestNotificationPermissionIfNeeded();
        }
    }

    private String getBatteryOptimizationPreferenceKey() {
        @SuppressLint("HardwareIds")
        String device = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        return "show_battery_optimization" + (device == null ? "" : device);
    }

    private void setNeverAskForBatteryOptimizationsAgain() {
        getPreferences().edit().putBoolean(getBatteryOptimizationPreferenceKey(), false).apply();
    }

    private boolean openBatteryOptimizationDialogIfNeeded() {
        if (isOptimizingBattery()
                && getPreferences().getBoolean(getBatteryOptimizationPreferenceKey(), true)) {
            final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
            builder.setTitle(R.string.battery_optimizations_enabled);
            builder.setMessage(
                    getString(
                            R.string.battery_optimizations_enabled_dialog,
                            getString(R.string.app_name)));
            builder.setPositiveButton(
                    R.string.next,
                    (dialog, which) -> {
                        final Intent intent =
                                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        final Uri uri = Uri.parse("package:" + getPackageName());
                        intent.setData(uri);
                        try {
                            startActivityForResult(intent, REQUEST_BATTERY_OP);
                        } catch (final ActivityNotFoundException e) {
                            Toast.makeText(
                                            this,
                                            R.string.device_does_not_support_battery_op,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });
            builder.setOnDismissListener(dialog -> setNeverAskForBatteryOptimizationsAgain());
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
            return true;
        }
        return false;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATION);
        }
    }

    private void notifyFragmentOfBackendConnected(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof OnBackendConnected callback) {
            callback.onBackendConnected();
        }
    }

    private void refreshFragment(@IdRes int id) {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(id);
        if (fragment instanceof XmppFragment xmppFragment) {
            xmppFragment.refresh();
        }
    }

    private boolean processViewIntent(final Intent intent) {
        final String uuid = intent.getStringExtra(EXTRA_CONVERSATION);
        final Conversation conversation =
                uuid != null ? xmppConnectionService.findConversationByUuidReliable(uuid) : null;
        if (conversation == null) {
            Log.d(Config.LOGTAG, "unable to view conversation with uuid:" + uuid);
            return false;
        }
        openConversation(conversation, intent.getExtras());
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        UriHandlerActivity.onRequestPermissionResult(this, requestCode, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                switch (requestCode) {
                    case REQUEST_OPEN_MESSAGE:
                        refreshUiReal();
                        ConversationFragment.openPendingMessage(this);
                        break;
                    case REQUEST_PLAY_PAUSE:
                        ConversationFragment.startStopPending(this);
                        break;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        ActivityResult activityResult = ActivityResult.of(requestCode, resultCode, data);
        if (xmppConnectionService != null) {
            handleActivityResult(activityResult);
        } else {
            this.postponedActivityResult.push(activityResult);
        }
    }

    private void handleActivityResult(final ActivityResult activityResult) {
        if (activityResult.resultCode == AppCompatActivity.RESULT_OK) {
            handlePositiveActivityResult(activityResult.requestCode, activityResult.data);
        } else {
            handleNegativeActivityResult(activityResult.requestCode);
        }
        if (activityResult.requestCode == REQUEST_BATTERY_OP) {
            // the result code is always 0 even when battery permission were granted
            requestNotificationPermissionIfNeeded();
            XmppConnectionService.toggleForegroundService(xmppConnectionService);
        }
    }

    private void handleNegativeActivityResult(int requestCode) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                if (conversation == null) {
                    break;
                }
                conversation.getAccount().getPgpDecryptionService().giveUpCurrentDecryption();
                break;
            case REQUEST_BATTERY_OP:
                setNeverAskForBatteryOptimizationsAgain();
                break;
        }
    }

    private void handlePositiveActivityResult(int requestCode, final Intent data) {
        Conversation conversation = ConversationFragment.getConversationReliable(this);
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found");
            return;
        }
        switch (requestCode) {
            case REQUEST_DECRYPT_PGP:
                conversation.getAccount().getPgpDecryptionService().continueDecryption(data);
                break;
            case REQUEST_CHOOSE_PGP_ID:
                long id = data.getLongExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, 0);
                if (id != 0) {
                    conversation.getAccount().setPgpSignId(id);
                    announcePgp(conversation.getAccount(), null, null, onOpenPGPKeyPublished);
                } else {
                    choosePgpSignId(conversation.getAccount());
                }
                break;
            case REQUEST_ANNOUNCE_PGP:
                announcePgp(conversation.getAccount(), conversation, data, onOpenPGPKeyPublished);
                break;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConversationMenuConfigurator.reloadFeatures(this);
        OmemoSetting.load(this);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_conversations);
        // The hosted fragments route their own bottom and keyboard insets, so this activity only
        // claims the status bar inset for the app bar.
        Activities.applyWindowChrome(this);
        Activities.applyTopInset(binding.appBarLayout);
        binding.accountSwitcher.setOnClickListener(v -> showAccountSwitcher());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.getSupportFragmentManager()
                .addOnBackStackChangedListener(this::invalidateActionBarTitle);
        this.getSupportFragmentManager()
                .addOnBackStackChangedListener(this::showDialogsIfMainIsOverview);
        this.initializeFragments();
        this.invalidateActionBarTitle();
        final Intent intent;
        if (savedInstanceState == null) {
            intent = getIntent();
        } else {
            intent = savedInstanceState.getParcelable("intent");
        }
        if (isViewOrShareIntent(intent)) {
            pendingViewIntent.push(intent);
            setIntent(createLauncherIntent(this));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_conversations, menu);
        final MenuItem qrCodeScanMenuItem = menu.findItem(R.id.action_scan_qr_code);
        if (qrCodeScanMenuItem != null) {
            if (isCameraFeatureAvailable()) {
                final var fragment =
                        getSupportFragmentManager().findFragmentById(R.id.main_fragment);
                boolean visible =
                        getResources().getBoolean(R.bool.show_qr_code_scan)
                                && fragment instanceof ConversationsOverviewFragment;
                qrCodeScanMenuItem.setVisible(visible);
            } else {
                qrCodeScanMenuItem.setVisible(false);
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onConversationSelected(Conversation conversation) {
        clearPendingViewIntent();
        if (ConversationFragment.getConversation(this) == conversation) {
            Log.d(
                    Config.LOGTAG,
                    "ignore onConversationSelected() because conversation is already open");
            return;
        }
        openConversation(conversation, null);
    }

    public void clearPendingViewIntent() {
        if (pendingViewIntent.clear()) {
            Log.e(Config.LOGTAG, "cleared pending view intent");
        }
    }

    private void displayToast(final String msg) {
        runOnUiThread(
                () -> Toast.makeText(ConversationsActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onAffiliationChangedSuccessful(Jid jid) {}

    @Override
    public void onAffiliationChangeFailed(Jid jid, int resId) {
        displayToast(getString(resId, jid.asBareJid().toString()));
    }

    private void openConversation(@Nullable final Conversation conversation, final Bundle extras) {
        // Deliberately opening a chat clears any local unread marker. Done here rather than in
        // ConversationFragment#reInit: on a phone that fragment has only just been committed and is
        // not yet attached to an activity, so it cannot reach the service itself.
        if (conversation != null && xmppConnectionService != null) {
            xmppConnectionService.clearConversationUnreadMarker(conversation);
        }
        final var fragmentManager = getSupportFragmentManager();
        executePendingTransactions(fragmentManager);
        final boolean mainNeedsRefresh;
        if (binding.secondaryFragment != null) {
            final var secondaryFragment = fragmentManager.findFragmentById(R.id.secondary_fragment);
            if (conversation == null) {
                if (secondaryFragment != null) {
                    Log.d(Config.LOGTAG, "not loading conversation. removing secondary");
                    final var transaction = fragmentManager.beginTransaction();
                    transaction.remove(secondaryFragment);
                    transaction.runOnCommit(this::invalidateActionBarTitle);
                    transaction.commitAllowingStateLoss();
                } else {
                    Log.d(Config.LOGTAG, "not loading conversation and secondary is already empty");
                }
            } else {
                final ConversationFragment conversationFragment;
                if (secondaryFragment instanceof ConversationFragment cf) {
                    conversationFragment = cf;
                    cf.reInit(conversation, extras == null ? new Bundle() : extras);
                } else {
                    final var cf = new ConversationFragment();
                    FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                    fragmentTransaction.replace(R.id.secondary_fragment, cf);
                    fragmentTransaction.runOnCommit(
                            () -> {
                                invalidateActionBarTitle();
                                refreshFragment(R.id.main_fragment);
                            });
                    fragmentTransaction.commitAllowingStateLoss();
                    conversationFragment = cf;
                }
                conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
            }
            mainNeedsRefresh = true;
        } else if (conversation != null) {
            mainNeedsRefresh = false;
            final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
            final ConversationFragment conversationFragment;
            if (mainFragment instanceof ConversationFragment cf) {
                conversationFragment = cf;
            } else {
                conversationFragment = new ConversationFragment();
                FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
                fragmentTransaction.replace(R.id.main_fragment, conversationFragment);
                fragmentTransaction.addToBackStack(null);
                fragmentTransaction.commitAllowingStateLoss();
            }
            conversationFragment.reInit(conversation, extras == null ? new Bundle() : extras);
        } else {
            mainNeedsRefresh = false;
        }
        if (mainNeedsRefresh) {
            refreshFragment(R.id.main_fragment);
        }
        invalidateActionBarTitle();
    }

    private static void executePendingTransactions(final FragmentManager fragmentManager) {
        try {
            fragmentManager.executePendingTransactions();
        } catch (final Exception e) {
            Log.e(Config.LOGTAG, "unable to execute pending fragment transactions");
        }
    }

    public boolean onXmppUriClicked(Uri uri) {
        XmppUri xmppUri = new XmppUri(uri);
        if (xmppUri.isValidJid() && !xmppUri.hasFingerprints()) {
            final Conversation conversation =
                    xmppConnectionService.findUniqueConversationByJid(xmppUri);
            if (conversation != null) {
                openConversation(conversation, null);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (MenuDoubleTabUtil.shouldIgnoreTap()) {
            return false;
        }
        switch (item.getItemId()) {
            case android.R.id.home:
                FragmentManager fm = getSupportFragmentManager();
                if (fm.getBackStackEntryCount() > 0) {
                    try {
                        fm.popBackStack();
                    } catch (IllegalStateException e) {
                        Log.w(Config.LOGTAG, "Unable to pop back stack after pressing home button");
                    }
                    return true;
                }
                break;
            case R.id.action_scan_qr_code:
                UriHandlerActivity.scan(this);
                return true;
            case R.id.action_search_all_conversations:
                startActivity(new Intent(this, SearchActivity.class));
                return true;
            case R.id.action_search_this_conversation:
                final Conversation conversation = ConversationFragment.getConversation(this);
                if (conversation == null) {
                    return true;
                }
                final Intent intent = new Intent(this, SearchActivity.class);
                intent.putExtra(SearchActivity.EXTRA_CONVERSATION_UUID, conversation.getUuid());
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && keyEvent.isCtrlPressed()) {
            final ConversationFragment conversationFragment = ConversationFragment.get(this);
            if (conversationFragment != null && conversationFragment.onArrowUpCtrlPressed()) {
                return true;
            }
        }
        return super.onKeyDown(keyCode, keyEvent);
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        final Intent pendingIntent = pendingViewIntent.peek();
        savedInstanceState.putParcelable(
                "intent", pendingIntent != null ? pendingIntent : getIntent());
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    protected void onNewIntent(@NonNull final Intent intent) {
        super.onNewIntent(intent);
        if (isViewOrShareIntent(intent)) {
            if (xmppConnectionService != null) {
                clearPendingViewIntent();
                processViewIntent(intent);
            } else {
                pendingViewIntent.push(intent);
            }
        }
        setIntent(createLauncherIntent(this));
    }

    @Override
    public void onPause() {
        this.mActivityPaused = true;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        this.mActivityPaused = false;
    }

    private void initializeFragments() {
        final var fragmentManager = getSupportFragmentManager();
        final var existing = fragmentManager.findFragmentById(R.id.main_fragment);
        if (existing != null) {
            return;
        }
        final var transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.main_fragment, new ConversationsOverviewFragment());
        transaction.commitAllowingStateLoss();
    }

    private void showAccountSwitcher() {
        final var existing = getSupportFragmentManager().findFragmentByTag(AccountSwitcherSheet.TAG);
        if (existing != null) {
            return;
        }
        new AccountSwitcherSheet().show(getSupportFragmentManager(), AccountSwitcherSheet.TAG);
    }

    @Override
    public void onAccountSelectionChanged() {
        refreshAccountSwitcher();
        refreshFragment(R.id.main_fragment);
        invalidateActionBarTitle();
    }

    /**
     * Shows the switcher only once there is something to switch between, and paints it with the
     * avatar of whatever the inbox is currently filtered to.
     */
    public void refreshAccountSwitcher() {
        if (this.binding == null) {
            return;
        }
        final var switcher = this.binding.accountSwitcher;
        final var service = this.xmppConnectionService;
        if (service == null) {
            switcher.setVisibility(View.GONE);
            return;
        }
        final var accounts = service.getAccounts();
        if (accounts.size() < 2) {
            // A single-account install gains nothing from a switcher, so it gets no extra chrome.
            switcher.setVisibility(View.GONE);
            return;
        }
        switcher.setVisibility(View.VISIBLE);
        final var selected = new AppSettings(this).getSelectedAccount();
        final Account account =
                selected.isPresent()
                        ? Iterables.tryFind(
                                        accounts,
                                        a -> selected.get().equals(a.getJid().asBareJid()))
                                .orNull()
                        : null;
        if (account == null) {
            // Unified inbox, or a selection that no longer resolves: a neutral glyph rather than
            // one account's avatar, which would imply the list is filtered to it.
            switcher.setImageResource(R.drawable.ic_forum_24dp);
            switcher.setBackgroundColor(
                    MaterialColors.getColor(
                            switcher, com.google.android.material.R.attr.colorSurfaceVariant));
            ImageViewCompat.setImageTintList(
                    switcher,
                    ColorStateList.valueOf(
                            MaterialColors.getColor(
                                    switcher,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant)));
            switcher.setContentDescription(getString(R.string.switch_account));
        } else {
            switcher.setBackground(null);
            ImageViewCompat.setImageTintList(switcher, null);
            AvatarWorkerTask.loadAvatar(account, switcher, R.dimen.avatar);
            switcher.setContentDescription(
                    getString(R.string.switch_account)
                            + ", "
                            + account.getJid().asBareJid());
        }
    }

    private void invalidateActionBarTitle() {
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        final var fragmentManager = getSupportFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment conversationFragment) {
            final Conversation conversation = conversationFragment.getConversation();
            if (conversation != null) {
                setTitleAndSubtitle(actionBar, conversation, true);
                // A chat fills the whole pane, so the inbox the switcher acts on is not on screen.
                // Leaving it up put your own face next to the other party's and squeezed the name
                // down to an ellipsis.
                this.binding.accountSwitcher.setVisibility(View.GONE);
                return;
            }
        }
        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment conversationFragment) {
            final Conversation conversation = conversationFragment.getConversation();
            if (conversation != null) {
                setTitleAndSubtitle(actionBar, conversation, false);
            } else {
                actionBar.setTitle(R.string.app_name);
                this.toolbarAvatarConversation = null;
                actionBar.setDisplayUseLogoEnabled(false);
                actionBar.setLogo(null);
            }
        } else {
            actionBar.setTitle(R.string.app_name);
            // While the inbox is narrowed to one account, say which one. Without this the list
            // silently hides conversations and looks like messages have gone missing.
            actionBar.setSubtitle(filteredAccountSubtitle());
        }
        this.toolbarAvatarConversation = null;
        actionBar.setDisplayUseLogoEnabled(false);
        actionBar.setLogo(null);
        actionBar.setDisplayHomeAsUpEnabled(false);
        // Either the inbox is in the main pane, or this is a tablet whose chat sits beside it; the
        // switcher is meaningful in both, subject to its own "more than one account" rule.
        refreshAccountSwitcher();
        ToolbarUtils.resetActionBarOnClickListeners(binding.toolbar);
    }

    /** The selected account's address, or null when showing the unified inbox. */
    private CharSequence filteredAccountSubtitle() {
        final var service = this.xmppConnectionService;
        if (service == null || service.getAccounts().size() < 2) {
            return null;
        }
        final var selected = new AppSettings(this).getSelectedAccount();
        return selected.map(jid -> (CharSequence) jid.toString()).orElse(null);
    }

    private void setTitleAndSubtitle(
            final ActionBar actionBar, final Conversation conversation, final boolean clickable) {
        actionBar.setTitle(conversation.getName());
        if (conversation.getMode() == Conversational.MODE_SINGLE && this.mShowLastUserInteraction) {
            final var contact = conversation.getContact();
            actionBar.setSubtitle(
                    UIHelper.lastUserInteraction(this, contact.getLastUserInteraction()));
        } else if (conversation.getMode() == Conversation.MODE_MULTI) {
            final var mucOptions = conversation.getMucOptions();
            final var userCount = mucOptions.getUserCount();
            if (mucOptions.isPrivateAndNonAnonymous() || userCount < 1) {
                actionBar.setSubtitle(null);
            } else {
                actionBar.setSubtitle(
                        getResources()
                                .getQuantityString(R.plurals.x_participants, userCount, userCount));
            }
        } else {
            actionBar.setSubtitle(null);
        }
        actionBar.setDisplayHomeAsUpEnabled(true);
        bindConversationAvatar(actionBar, conversation);
        if (clickable) {
            ToolbarUtils.setActionBarOnClickListener(
                    binding.toolbar, (v) -> openConversationDetails(conversation));
        }
    }

    /**
     * Shows the other party's avatar beside the title in a 1:1 chat. Messages no longer carry an
     * avatar there, so this is the only place the face appears; group chats keep theirs per
     * message, where it tells you who wrote what, and get nothing here.
     *
     * <p>This uses the toolbar's logo slot rather than a child view. A child with {@code
     * layout_gravity="start"} is laid out after the title, which pushed the name into an ellipsis.
     * The logo sits between the navigation icon and the title, which is where this belongs.
     */
    private void bindConversationAvatar(final ActionBar actionBar, final Conversation conversation) {
        if (conversation.getMode() != Conversational.MODE_SINGLE) {
            this.toolbarAvatarConversation = null;
            actionBar.setDisplayUseLogoEnabled(false);
            actionBar.setLogo(null);
            return;
        }
        this.toolbarAvatarConversation = conversation;
        final var contact = conversation.getContact();
        final int size = getResources().getDimensionPixelSize(R.dimen.avatar_on_toolbar);
        final var cached = avatarService().get(contact, size, true);
        if (cached != null) {
            applyToolbarLogo(cached, conversation);
            return;
        }
        // Not cached yet: clear the previous chat's face rather than leave it on the wrong name,
        // and generate off the main thread.
        actionBar.setLogo(null);
        AVATAR_EXECUTOR.execute(
                () -> {
                    final var bitmap = avatarService().get(contact, size, false);
                    if (bitmap == null) {
                        return;
                    }
                    runOnUiThread(() -> applyToolbarLogo(bitmap, conversation));
                });
    }

    /** Applies {@code bitmap} only while {@code conversation} is still the one on screen. */
    private void applyToolbarLogo(final Bitmap bitmap, final Conversation conversation) {
        // Compared against our own field, not the fragment: when this lands from the executor the
        // fragment transaction that hosts the chat has often not been executed yet, so
        // ConversationFragment#getConversation still answers null and every avatar was discarded.
        if (this.toolbarAvatarConversation != conversation) {
            return;
        }
        final var actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        final var circle = RoundedBitmapDrawableFactory.create(getResources(), bitmap);
        circle.setCircular(true);
        // The toolbar draws the logo hard against the title, so the gap has to come from the
        // drawable itself. contentInsetStartWithNavigation is zeroed in the layout to pay for it.
        final int gap = getResources().getDimensionPixelSize(R.dimen.toolbar_avatar_gap);
        final var logo =
                new InsetDrawable(circle, 0, 0, gap, 0) {
                    @Override
                    public int getIntrinsicWidth() {
                        return circle.getIntrinsicWidth() + gap;
                    }

                    @Override
                    public int getIntrinsicHeight() {
                        return circle.getIntrinsicHeight();
                    }
                };
        // ToolbarWidgetWrapper rewrites the toolbar's logo from its own display options whenever
        // they change, so the logo has to go through the ActionBar and these two flags have to be
        // on, or setDisplayHomeAsUpEnabled silently clears it again.
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setDisplayUseLogoEnabled(true);
        actionBar.setLogo(logo);
        binding.toolbar.setLogoDescription(conversation.getName());
    }

    private void openConversationDetails(final Conversation conversation) {
        if (conversation.getMode() == Conversational.MODE_MULTI) {
            ConferenceDetailsActivity.open(this, conversation);
        } else {
            final Contact contact = conversation.getContact();
            if (contact.isSelf()) {
                switchToAccount(conversation.getAccount());
            } else {
                switchToContactDetails(contact);
            }
        }
    }

    @Override
    public void onConversationArchived(final Conversation conversation) {
        final var fragmentManager = getSupportFragmentManager();
        final Fragment mainFragment = fragmentManager.findFragmentById(R.id.main_fragment);
        if (mainFragment instanceof ConversationFragment) {
            try {
                fragmentManager.popBackStack();
            } catch (final IllegalStateException e) {
                Log.w(
                        Config.LOGTAG,
                        "state loss while popping back state after archiving conversation",
                        e);
                // this usually means activity is no longer active; meaning on the next open we will
                // run through this again
            }
            return;
        }
        final Fragment secondaryFragment =
                fragmentManager.findFragmentById(R.id.secondary_fragment);
        if (secondaryFragment instanceof ConversationFragment conversationFragment) {
            if (conversationFragment.getConversation() == conversation) {
                final var suggestion =
                        ConversationsOverviewFragment.getSuggestion(this, conversation);
                openConversation(suggestion, null);
            }
        }
    }

    @Override
    public void onConversationsListItemUpdated() {
        final var fragment = getSupportFragmentManager().findFragmentById(R.id.main_fragment);
        if (fragment instanceof ConversationsOverviewFragment overviewFragment) {
            overviewFragment.refresh();
        }
    }

    @Override
    public void switchToConversation(Conversation conversation) {
        Log.d(Config.LOGTAG, "override");
        openConversation(conversation, null);
    }

    @Override
    public void onConversationRead(Conversation conversation, String upToUuid) {
        if (!mActivityPaused && pendingViewIntent.peek() == null) {
            xmppConnectionService.sendReadMarker(conversation, upToUuid);
        } else {
            Log.d(Config.LOGTAG, "ignoring read callback. mActivityPaused=" + mActivityPaused);
        }
    }

    @Override
    public void onAccountUpdate() {
        this.refreshUi();
    }

    @Override
    public void onConversationUpdate() {
        this.refreshUi();
    }

    @Override
    public void onRosterUpdate() {
        this.refreshUi();
    }

    @Override
    public void OnUpdateBlocklist(OnUpdateBlocklist.Status status) {
        this.refreshUi();
    }

    @Override
    public void onShowErrorToast(int resId) {
        runOnUiThread(() -> Toast.makeText(this, resId, Toast.LENGTH_SHORT).show());
    }
}
