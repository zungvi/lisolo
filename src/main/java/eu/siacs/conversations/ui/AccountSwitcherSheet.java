package eu.siacs.conversations.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.SheetAccountSwitcherBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.ui.adapter.AccountSwitcherAdapter;
import eu.siacs.conversations.utils.AccountUtils;
import eu.siacs.conversations.xmpp.Jid;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets the user point the conversation list at one account, or back at all of them.
 *
 * <p>Only the filter changes: every enabled account stays connected and keeps delivering
 * notifications. Narrowing the inbox must not look like signing out of the others.
 */
public class AccountSwitcherSheet extends BottomSheetDialogFragment {

    public static final String TAG = "account_switcher";

    /** Implemented by the hosting activity so it can re-filter and repaint after a choice. */
    public interface OnAccountSelectionChanged {
        void onAccountSelectionChanged();
    }

    private SheetAccountSwitcherBinding binding;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull final LayoutInflater inflater,
            @Nullable final ViewGroup container,
            @Nullable final Bundle savedInstanceState) {
        this.binding =
                DataBindingUtil.inflate(
                        inflater, R.layout.sheet_account_switcher, container, false);
        return this.binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final var activity = getActivity();
        if (!(activity instanceof XmppActivity xmppActivity)
                || xmppActivity.xmppConnectionService == null) {
            // The service can be gone if the sheet is restored before it rebinds; there is nothing
            // meaningful to show without it.
            dismissAllowingStateLoss();
            return;
        }

        final List<Account> accounts =
                new ArrayList<>(xmppActivity.xmppConnectionService.getAccounts());
        final var appSettings = new AppSettings(requireContext());
        final Jid selected = appSettings.getSelectedAccount().orElse(null);

        this.binding.accountList.setAdapter(
                new AccountSwitcherAdapter(
                        accounts,
                        selected,
                        countUnread(xmppActivity, null),
                        this::select));

        this.binding.actionAddAccount.setOnClickListener(
                v -> {
                    startActivity(new Intent(requireContext(), EditAccountActivity.class));
                    dismiss();
                });

        this.binding.actionManageAccounts.setOnClickListener(
                v -> {
                    final var clazz = AccountUtils.MANAGE_ACCOUNT_ACTIVITY;
                    if (clazz != null) {
                        startActivity(new Intent(requireContext(), clazz));
                    }
                    dismiss();
                });
    }

    private void select(@Nullable final Jid jid) {
        new AppSettings(requireContext()).setSelectedAccount(jid);
        if (getActivity() instanceof OnAccountSelectionChanged callback) {
            callback.onAccountSelectionChanged();
        }
        dismiss();
    }

    /** Unread conversations, across every account when {@code account} is null. */
    private static int countUnread(final XmppActivity activity, @Nullable final Jid account) {
        final var service = activity.xmppConnectionService;
        if (service == null) {
            return 0;
        }
        int unread = 0;
        for (final Conversation conversation : service.getConversations()) {
            if (account != null
                    && !account.equals(conversation.getAccount().getJid().asBareJid())) {
                continue;
            }
            if (!conversation.isRead()) {
                ++unread;
            }
        }
        return unread;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        this.binding = null;
    }
}
