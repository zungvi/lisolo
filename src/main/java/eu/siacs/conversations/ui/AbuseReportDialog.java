package eu.siacs.conversations.ui;

import android.text.TextUtils;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.DialogAbuseReportBinding;
import eu.siacs.conversations.entities.Blockable;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.manager.BlockingManager;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import im.conversations.android.xmpp.model.stanza.Iq;

public final class AbuseReportDialog {

    private AbuseReportDialog() {}

    public static void showDirect(
            final XmppActivity activity,
            final Blockable blockable,
            @Nullable final String serverMsgId) {
        final var connection = blockable.getAccount().getXmppConnection();
        final var blockingManager = connection.getManager(BlockingManager.class);
        if (!blockingManager.hasSpamReporting()) {
            final var builder =
                    new MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.report_abuse)
                            .setMessage(R.string.direct_report_not_supported)
                            .setPositiveButton(R.string.got_it, null);
            if (blockingManager.hasFeature() && !blockable.isBlocked()) {
                builder.setNeutralButton(
                        R.string.action_block_contact,
                        (dialog, which) ->
                                BlockContactDialog.show(activity, blockable));
            }
            builder.show();
            return;
        }
        showForm(
                activity,
                R.string.report_abuse,
                R.string.direct_report_privacy_note,
                R.string.report_sent_and_contact_blocked,
                (reason, details) ->
                        blockingManager.reportAndBlock(
                                blockable,
                                reason,
                                details,
                                serverMsgId));
    }

    public static void showGroup(
            final XmppActivity activity, final Conversation conversation, final Message message) {
        final var mucOptions = conversation.getMucOptions();
        if (!mucOptions.hasFeature(Namespace.GROUP_CHAT_REPORTING)) {
            showUnsupported(activity, R.string.group_report_not_supported);
            return;
        }
        final var occupantId = message.getOccupantId();
        if (!mucOptions.occupantId() || TextUtils.isEmpty(occupantId)) {
            showUnsupported(activity, R.string.group_report_occupant_id_unavailable);
            return;
        }
        final var manager =
                conversation
                        .getAccount()
                        .getXmppConnection()
                        .getManager(MultiUserChatManager.class);
        showForm(
                activity,
                R.string.report_group_participant,
                R.string.group_report_privacy_note,
                R.string.group_report_sent,
                (reason, details) ->
                        manager.reportParticipant(
                                conversation,
                                occupantId,
                                reason,
                                details,
                                message.getServerMsgId()));
    }

    private static void showUnsupported(
            final XmppActivity activity, final int explanation) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.report_abuse)
                .setMessage(explanation)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private static void showForm(
            final XmppActivity activity,
            final int title,
            final int privacyNote,
            final int successMessage,
            final ReportRequest reportRequest) {
        final DialogAbuseReportBinding binding =
                DataBindingUtil.inflate(
                        activity.getLayoutInflater(), R.layout.dialog_abuse_report, null, false);
        binding.privacyNote.setText(privacyNote);
        final var dialog =
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(title)
                        .setView(binding.getRoot())
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.send_report, null)
                        .create();
        dialog.setOnShowListener(
                ignored ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(
                                        view ->
                                                submit(
                                                        activity,
                                                        dialog,
                                                        view,
                                                        binding,
                                                        successMessage,
                                                        reportRequest)));
        dialog.show();
    }

    private static void submit(
            final XmppActivity activity,
            final AlertDialog dialog,
            final View button,
            final DialogAbuseReportBinding binding,
            final int successMessage,
            final ReportRequest reportRequest) {
        final String reason =
                binding.reasonGroup.getCheckedRadioButtonId() == R.id.reason_abuse
                        ? Namespace.REPORTING_REASON_ABUSE
                        : Namespace.REPORTING_REASON_SPAM;
        final String text = binding.details.getText().toString().trim();
        button.setEnabled(false);
        final ListenableFuture<Iq> future =
                reportRequest.send(reason, text.isEmpty() ? null : text);
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final Iq result) {
                        if (activity.isFinishing()) {
                            return;
                        }
                        dialog.dismiss();
                        new MaterialAlertDialogBuilder(activity)
                                .setMessage(successMessage)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }

                    @Override
                    public void onFailure(final Throwable throwable) {
                        if (activity.isFinishing()) {
                            return;
                        }
                        button.setEnabled(true);
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.report_failed)
                                .setMessage(R.string.report_failed_details)
                                .setPositiveButton(R.string.ok, null)
                                .show();
                    }
                },
                ContextCompat.getMainExecutor(activity));
    }

    private interface ReportRequest {
        ListenableFuture<Iq> send(String reason, String details);
    }
}
