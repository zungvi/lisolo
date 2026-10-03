package eu.siacs.conversations.ui.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemAccountSwitchBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.xmpp.Jid;
import java.util.List;

/**
 * Rows for the account switcher: one synthetic "All accounts" row followed by one row per account.
 *
 * <p>The unified inbox is modelled as a row rather than a separate control so that "which
 * conversations am I looking at" is a single list with exactly one check mark, instead of a toggle
 * whose state has to be read together with a selection.
 */
public class AccountSwitcherAdapter
        extends RecyclerView.Adapter<AccountSwitcherAdapter.AccountViewHolder> {

    private static final int POSITION_ALL_ACCOUNTS = 0;

    public interface OnAccountSelected {
        /** {@code jid} is null for the unified inbox. */
        void onAccountSelected(@Nullable Jid jid);
    }

    private final List<Account> accounts;
    private final OnAccountSelected listener;

    /** Null means the unified inbox is selected. */
    @Nullable private final Jid selected;

    private final int unreadAcrossAllAccounts;

    public AccountSwitcherAdapter(
            final List<Account> accounts,
            @Nullable final Jid selected,
            final int unreadAcrossAllAccounts,
            final OnAccountSelected listener) {
        this.accounts = accounts;
        this.selected = selected;
        this.unreadAcrossAllAccounts = unreadAcrossAllAccounts;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int type) {
        return new AccountViewHolder(
                DataBindingUtil.inflate(
                        LayoutInflater.from(parent.getContext()),
                        R.layout.item_account_switch,
                        parent,
                        false));
    }

    @Override
    public void onBindViewHolder(@NonNull final AccountViewHolder holder, final int position) {
        if (position == POSITION_ALL_ACCOUNTS) {
            bindAllAccounts(holder);
        } else {
            bindAccount(holder, this.accounts.get(position - 1));
        }
    }

    private void bindAllAccounts(final AccountViewHolder holder) {
        final var binding = holder.binding;
        final var context = binding.getRoot().getContext();
        binding.accountJid.setText(R.string.all_accounts);
        if (this.unreadAcrossAllAccounts > 0) {
            binding.accountStatus.setText(
                    context.getResources()
                            .getQuantityString(
                                    R.plurals.x_unread_conversations,
                                    this.unreadAcrossAllAccounts,
                                    this.unreadAcrossAllAccounts));
        } else {
            binding.accountStatus.setText(
                    context.getResources()
                            .getQuantityString(R.plurals.n_accounts, this.accounts.size(), this.accounts.size()));
        }
        binding.accountStatus.setTextColor(
                MaterialColors.getColor(
                        binding.accountStatus,
                        com.google.android.material.R.attr.colorOnSurfaceVariant));
        // No avatar to show for the aggregate, so the slot carries a neutral glyph on a container
        // tint rather than being left blank, which would misalign the two text lines.
        binding.accountImage.setImageResource(R.drawable.ic_forum_24dp);
        binding.accountImage.setBackgroundColor(
                MaterialColors.getColor(
                        binding.accountImage,
                        com.google.android.material.R.attr.colorSurfaceVariant));
        ImageViewCompat.setImageTintList(
                binding.accountImage,
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                binding.accountImage,
                                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        setSelected(holder, this.selected == null);
        binding.getRoot().setOnClickListener(v -> this.listener.onAccountSelected(null));
    }

    private void bindAccount(final AccountViewHolder holder, final Account account) {
        final var binding = holder.binding;
        final var jid = account.getJid().asBareJid();
        binding.accountJid.setText(jid.toString());
        binding.accountStatus.setText(statusLabel(account));
        binding.accountStatus.setTextColor(statusColor(binding.accountStatus, account));

        // Clear what bindAllAccounts may have left on a recycled row.
        binding.accountImage.setBackground(null);
        ImageViewCompat.setImageTintList(binding.accountImage, null);
        AvatarWorkerTask.loadAvatar(account, binding.accountImage, R.dimen.avatar);

        setSelected(holder, jid.equals(this.selected));
        binding.getRoot().setOnClickListener(v -> this.listener.onAccountSelected(jid));
    }

    private static void setSelected(final AccountViewHolder holder, final boolean selected) {
        // INVISIBLE rather than GONE so the two text lines keep the same width whether or not a
        // row is the selected one.
        holder.binding.accountSelected.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        holder.binding.getRoot().setSelected(selected);
    }

    private static int statusLabel(final Account account) {
        if (account.isServiceOutage()) {
            final var outage = account.getServiceOutageStatus();
            return outage != null && outage.isPlanned()
                    ? R.string.account_status_service_outage_scheduled
                    : R.string.account_status_service_outage_known;
        }
        return account.getStatus().getReadableId();
    }

    private static int statusColor(final View view, final Account account) {
        return switch (account.getStatus()) {
            case ONLINE ->
                    MaterialColors.getColor(view, androidx.appcompat.R.attr.colorPrimary);
            case DISABLED, LOGGED_OUT, CONNECTING ->
                    MaterialColors.getColor(
                            view, com.google.android.material.R.attr.colorOnSurfaceVariant);
            default -> MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError);
        };
    }

    @Override
    public int getItemCount() {
        // +1 for the "All accounts" row.
        return this.accounts.size() + 1;
    }

    public static class AccountViewHolder extends RecyclerView.ViewHolder {

        private final ItemAccountSwitchBinding binding;

        private AccountViewHolder(@NonNull final ItemAccountSwitchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
