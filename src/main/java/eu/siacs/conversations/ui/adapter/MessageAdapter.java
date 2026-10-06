package eu.siacs.conversations.ui.adapter;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import de.gultsch.common.Linkify;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.crypto.axolotl.FingerprintStatus;
import eu.siacs.conversations.databinding.ItemMessageDateBubbleBinding;
import eu.siacs.conversations.databinding.ItemMessageEndBinding;
import eu.siacs.conversations.databinding.ItemMessageRtpSessionBinding;
import eu.siacs.conversations.databinding.ItemMessageStartBinding;
import eu.siacs.conversations.databinding.ItemMessageStatusBinding;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Conversational;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.Message.FileParams;
import eu.siacs.conversations.entities.RtpSessionStatus;
import eu.siacs.conversations.entities.Transferable;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.services.NotificationService;
import eu.siacs.conversations.ui.Activities;
import eu.siacs.conversations.ui.BindingAdapters;
import eu.siacs.conversations.ui.ConversationFragment;
import eu.siacs.conversations.ui.ConversationsActivity;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.service.AudioPlayer;
import eu.siacs.conversations.ui.text.DividerSpan;
import eu.siacs.conversations.ui.text.FixedURLSpan;
import eu.siacs.conversations.ui.text.QuoteSpan;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.util.Typefaces;
import eu.siacs.conversations.ui.util.QuoteHelper;
import eu.siacs.conversations.ui.util.QuotedMessages;
import eu.siacs.conversations.ui.util.ReactionChips;
import eu.siacs.conversations.ui.util.ReactionDetails;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.ui.widget.ClickableMovementMethod;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.Emoticons;
import eu.siacs.conversations.utils.GeoHelper;
import eu.siacs.conversations.utils.MessageUtils;
import eu.siacs.conversations.utils.StylingHelper;
import eu.siacs.conversations.utils.TimeFrameUtils;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.mam.MamReference;
import eu.siacs.conversations.xmpp.manager.MessageArchiveManager;
import im.conversations.android.xmpp.model.reactions.Restrictions;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageItemViewHolder> {

    public static final String DATE_SEPARATOR_BODY = "DATE_SEPARATOR";
    private static final int END = 0;
    private static final int START = 1;
    private static final int STATUS = 2;
    private static final int DATE_SEPARATOR = 3;
    private static final int RTP_SESSION = 4;
    private final XmppActivity activity;
    private List<Message> messages;
    private final AudioPlayer audioPlayer;
    private List<String> highlightedTerm = null;
    private final DisplayMetrics metrics;
    private OnContactPictureClicked mOnContactPictureClickedListener;
    private OnReplyPreviewClicked mOnReplyPreviewClickedListener;

    /** Reply target lookup for the current snapshot; see {@link #quotedMessages()}. */
    private QuotedMessages quotedMessages;

    /**
     * The message currently flashed after jumping to it from a reply, or null. Held as a uuid
     * rather than a Message so a row rebuilt from the database still matches.
     */
    private String highlightedMessageUuid;
    private OnContactPictureLongClicked mOnContactPictureLongClickedListener;
    private BubbleDesign bubbleDesign = new BubbleDesign(false, false, false, true, true);
    private final boolean mForceNames;

    public MessageAdapter(
            final XmppActivity activity, final List<Message> messages, final boolean forceNames) {
        // Order matters. AudioPlayer's constructor calls back into getContext(), which now reads
        // this.activity; while this was an ArrayAdapter the context had already been handed to
        // super(activity, 0, messages), so constructing the player first was safe. It no longer is,
        // and doing it first left getContext() returning null.
        this.activity = activity;
        // RecyclerView must keep seeing its old item count until the matching DiffUtil updates are
        // dispatched. Holding the caller's mutable list broke that contract during every refill.
        this.messages = new ArrayList<>(messages);
        this.mForceNames = forceNames;
        this.metrics = activity.getResources().getDisplayMetrics();
        this.audioPlayer = new AudioPlayer(this);
        updatePreferences();
    }

    /** Public because {@link AudioPlayer} relies on it, as it did when this was an ArrayAdapter. */
    public Context getContext() {
        return this.activity;
    }

    private Message getItem(final int position) {
        return this.messages.get(position);
    }

    @Override
    public int getItemCount() {
        return this.messages.size();
    }

    /** Replaces the adapter snapshot immediately before its matching notifications are dispatched. */
    public void replaceMessages(final List<Message> messages) {
        this.messages = new ArrayList<>(messages);
        this.quotedMessages = null;
    }

    public MessageAdapter(final XmppActivity activity, final List<Message> messages) {
        this(activity, messages, false);
    }

    private static void resetClickListener(View... views) {
        for (View view : views) {
            view.setOnClickListener(null);
        }
    }

    public void flagScreenOn() {
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void flagScreenOff() {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setVolumeControl(final int stream) {
        activity.setVolumeControlStream(stream);
    }

    public void setOnContactPictureClicked(OnContactPictureClicked listener) {
        this.mOnContactPictureClickedListener = listener;
    }

    public Activity getActivity() {
        return activity;
    }

    public void setOnReplyPreviewClicked(final OnReplyPreviewClicked listener) {
        this.mOnReplyPreviewClickedListener = listener;
    }

    /** Pass null to clear. The caller is responsible for rebinding the affected rows. */
    public void setHighlightedMessage(final String uuid) {
        this.highlightedMessageUuid = uuid;
    }

    public int positionOf(final String uuid) {
        if (uuid == null) {
            return -1;
        }
        for (int i = 0; i < this.messages.size(); ++i) {
            if (uuid.equals(this.messages.get(i).getUuid())) {
                return i;
            }
        }
        return -1;
    }

    public void setOnContactPictureLongClicked(OnContactPictureLongClicked listener) {
        this.mOnContactPictureLongClickedListener = listener;
    }

    private static int getItemViewType(final Message message, final boolean alignStart) {
        if (message.getType() == Message.TYPE_STATUS) {
            if (DATE_SEPARATOR_BODY.equals(message.getBody())) {
                return DATE_SEPARATOR;
            } else {
                return STATUS;
            }
        } else if (message.getType() == Message.TYPE_RTP_SESSION) {
            return RTP_SESSION;
        } else if (message.getStatus() <= Message.STATUS_RECEIVED || alignStart) {
            return START;
        } else {
            return END;
        }
    }

    @Override
    public int getItemViewType(final int position) {
        return getItemViewType(getItem(position), bubbleDesign.alignStart);
    }

    private void displayStatus(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        final int status = message.getStatus();
        final boolean error;
        final Transferable transferable = message.getTransferable();
        final boolean sent = status != Message.STATUS_RECEIVED;
        final boolean showUserNickname =
                message.getConversation().getMode() == Conversation.MODE_MULTI
                        && viewHolder instanceof StartBubbleMessageItemViewHolder;
        final String fileSize;
        if (message.isFileOrImage()
                || transferable != null
                || MessageUtils.unInitiatedButKnownSize(message)) {
            final FileParams params = message.getFileParams();
            fileSize = params.size != null ? UIHelper.filesizeToString(params.size) : null;
            if (message.getStatus() == Message.STATUS_SEND_FAILED
                    || (transferable != null
                            && (transferable.getStatus() == Transferable.STATUS_FAILED
                                    || transferable.getStatus()
                                            == Transferable.STATUS_CANCELLED))) {
                error = true;
            } else {
                error = message.getStatus() == Message.STATUS_SEND_FAILED;
            }
        } else {
            fileSize = null;
            error = message.getStatus() == Message.STATUS_SEND_FAILED;
        }

        if (sent) {
            final @DrawableRes Integer receivedIndicator =
                    getMessageStatusAsDrawable(message, status);
            if (receivedIndicator == null) {
                viewHolder.indicatorReceived().setVisibility(View.INVISIBLE);
            } else {
                viewHolder.indicatorReceived().setImageResource(receivedIndicator);
                if (status == Message.STATUS_SEND_FAILED) {
                    setImageTintError(viewHolder.indicatorReceived());
                } else {
                    setImageTint(viewHolder.indicatorReceived(), bubbleColor);
                }
                viewHolder.indicatorReceived().setVisibility(View.VISIBLE);
            }
        } else {
            viewHolder.indicatorReceived().setVisibility(View.GONE);
        }
        final var additionalStatusInfo = getAdditionalStatusInfo(message, status);

        if (error && sent) {
            viewHolder
                    .time()
                    .setTextColor(
                            MaterialColors.getColor(
                                    viewHolder.time(), androidx.appcompat.R.attr.colorError));
        } else {
            setTextColor(viewHolder.time(), bubbleColor);
        }
        viewHolder.time().setVisibility(View.VISIBLE);
        if (message.getEncryption() == Message.ENCRYPTION_NONE) {
            viewHolder.indicatorSecurity().setVisibility(View.GONE);
        } else {
            boolean verified = false;
            if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL) {
                final FingerprintStatus fingerprintStatus =
                        message.getConversation()
                                .getAccount()
                                .getAxolotlService()
                                .getFingerprintTrust(message.getFingerprint());
                if (fingerprintStatus != null && fingerprintStatus.isVerified()) {
                    verified = true;
                }
            }
            if (verified) {
                viewHolder.indicatorSecurity().setImageResource(R.drawable.ic_verified_user_24dp);
            } else {
                viewHolder.indicatorSecurity().setImageResource(R.drawable.ic_lock_24dp);
            }
            if (error && sent) {
                setImageTintError(viewHolder.indicatorSecurity());
            } else {
                setImageTint(viewHolder.indicatorSecurity(), bubbleColor);
            }
            viewHolder.indicatorSecurity().setVisibility(View.VISIBLE);
        }

        if (message.edited()) {
            viewHolder.indicatorEdit().setVisibility(View.VISIBLE);
            if (error && sent) {
                setImageTintError(viewHolder.indicatorEdit());
            } else {
                setImageTint(viewHolder.indicatorEdit(), bubbleColor);
            }
        } else {
            viewHolder.indicatorEdit().setVisibility(View.GONE);
        }

        final String formattedTime =
                UIHelper.readableTimeDifferenceFull(getContext(), message.getTimeSent());
        final String bodyLanguage = message.getBodyLanguage();
        final ImmutableList.Builder<String> timeInfoBuilder = new ImmutableList.Builder<>();

        if (mForceNames || showUserNickname) {
            final String displayName = UIHelper.getMessageDisplayName(message);
            if (displayName != null) {
                timeInfoBuilder.add(displayName);
            }
        }
        // Disable file size display in Snikket
        // Most other messaging apps don't display this so prominently, it's
        // not of immense value to the user once the file is visible
        if (false && fileSize != null) {
            timeInfoBuilder.add(fileSize);
        }
        if (bodyLanguage != null) {
            timeInfoBuilder.add(bodyLanguage.toUpperCase(Locale.US));
        }
        // for space reasons we display only 'additional status info' (send progress or concrete
        // failure reason) or the time
        if (additionalStatusInfo != null) {
            timeInfoBuilder.add(additionalStatusInfo);
        } else {
            timeInfoBuilder.add(formattedTime);
        }
        final var timeInfo = timeInfoBuilder.build();
        viewHolder.time().setText(Joiner.on(" · ").join(timeInfo));
    }

    public static @DrawableRes Integer getMessageStatusAsDrawable(
            final Message message, final int status) {
        final var transferable = message.getTransferable();
        return switch (status) {
            case Message.STATUS_WAITING -> R.drawable.ic_more_horiz_24dp;
            case Message.STATUS_UNSEND -> transferable == null ? null : R.drawable.ic_upload_24dp;
            case Message.STATUS_SEND -> R.drawable.ic_done_24dp;
            case Message.STATUS_SEND_RECEIVED, Message.STATUS_SEND_DISPLAYED ->
                    R.drawable.ic_done_all_24dp;
            case Message.STATUS_SEND_FAILED -> {
                final String errorMessage = message.getErrorMessage();
                if (Message.ERROR_MESSAGE_CANCELLED.equals(errorMessage)) {
                    yield R.drawable.ic_cancel_24dp;
                } else {
                    yield R.drawable.ic_error_24dp;
                }
            }
            case Message.STATUS_OFFERED -> R.drawable.ic_p2p_24dp;
            default -> null;
        };
    }

    @Nullable
    private String getAdditionalStatusInfo(final Message message, final int mergedStatus) {
        final String additionalStatusInfo;
        if (mergedStatus == Message.STATUS_SEND_FAILED) {
            final String errorMessage = Strings.nullToEmpty(message.getErrorMessage());
            final String[] errorParts = errorMessage.split("\\u001f", 2);
            if (errorParts.length == 2 && errorParts[0].equals("file-too-large")) {
                additionalStatusInfo = getContext().getString(R.string.file_too_large);
            } else {
                additionalStatusInfo = null;
            }
        } else if (mergedStatus == Message.STATUS_UNSEND) {
            final var transferable = message.getTransferable();
            if (transferable == null) {
                return null;
            }
            return getContext().getString(R.string.sending_file, transferable.getProgress());
        } else {
            additionalStatusInfo = null;
        }
        return additionalStatusInfo;
    }

    private void displayInfoMessage(
            BubbleMessageItemViewHolder viewHolder,
            CharSequence text,
            final BubbleColor bubbleColor) {
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        viewHolder.messageBody().setText(text);
        setTextSize(viewHolder.messageBody(), this.bubbleDesign.largeFont);
        Typefaces.apply(viewHolder.messageBody(), Typefaces.regular(activity), true);
        viewHolder
                .messageBody()
                .setTextColor(bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor));
        viewHolder.messageBody().setTextIsSelectable(false);
    }

    private void displayEmojiMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final String body,
            final BubbleColor bubbleColor) {
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.GONE);
        Typefaces.apply(viewHolder.messageBody(), Typefaces.regular(activity), false);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody(), bubbleColor);
        final Spannable span = new SpannableString(body);
        float size = Emoticons.isEmoji(body) ? 3.0f : 2.0f;
        span.setSpan(
                new RelativeSizeSpan(size), 0, body.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        viewHolder.messageBody().setText(span);
    }

    private void applyQuoteSpan(
            final TextView textView,
            SpannableStringBuilder body,
            int start,
            int end,
            final BubbleColor bubbleColor) {
        if (start > 1 && !"\n\n".equals(body.subSequence(start - 2, start).toString())) {
            body.insert(start++, "\n");
            body.setSpan(
                    new DividerSpan(false), start - 2, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            end++;
        }
        if (end < body.length() - 1 && !"\n\n".equals(body.subSequence(end, end + 2).toString())) {
            body.insert(end, "\n");
            body.setSpan(new DividerSpan(false), end, end + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        body.setSpan(
                new QuoteSpan(bubbleToOnSurfaceVariant(textView, bubbleColor), metrics),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Applies QuoteSpan to group of lines which starts with > or » characters. Appends likebreaks
     * and applies DividerSpan to them to show a padding between quote and text.
     */
    private boolean handleTextQuotes(
            final TextView textView,
            final SpannableStringBuilder body,
            final BubbleColor bubbleColor) {
        boolean startsWithQuote = false;
        int quoteDepth = 1;
        while (QuoteHelper.bodyContainsQuoteStart(body) && quoteDepth <= Config.QUOTE_MAX_DEPTH) {
            char previous = '\n';
            int lineStart = -1;
            int lineTextStart = -1;
            int quoteStart = -1;
            for (int i = 0; i <= body.length(); i++) {
                char current = body.length() > i ? body.charAt(i) : '\n';
                if (lineStart == -1) {
                    if (previous == '\n') {
                        if (i < body.length() && QuoteHelper.isPositionQuoteStart(body, i)) {
                            // Line start with quote
                            lineStart = i;
                            if (quoteStart == -1) quoteStart = i;
                            if (i == 0) startsWithQuote = true;
                        } else if (quoteStart >= 0) {
                            // Line start without quote, apply spans there
                            applyQuoteSpan(textView, body, quoteStart, i - 1, bubbleColor);
                            quoteStart = -1;
                        }
                    }
                } else {
                    // Remove extra spaces between > and first character in the line
                    // > character will be removed too
                    if (current != ' ' && lineTextStart == -1) {
                        lineTextStart = i;
                    }
                    if (current == '\n') {
                        body.delete(lineStart, lineTextStart);
                        i -= lineTextStart - lineStart;
                        if (i == lineStart) {
                            // Avoid empty lines because span over empty line can be hidden
                            body.insert(i++, " ");
                        }
                        lineStart = -1;
                        lineTextStart = -1;
                    }
                }
                previous = current;
            }
            if (quoteStart >= 0) {
                // Apply spans to finishing open quote
                applyQuoteSpan(textView, body, quoteStart, body.length(), bubbleColor);
            }
            quoteDepth++;
        }
        return startsWithQuote;
    }

    private void displayTextMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.messageBody().setVisibility(View.VISIBLE);
        setTextColor(viewHolder.messageBody(), bubbleColor);
        setTextSize(viewHolder.messageBody(), this.bubbleDesign.largeFont);
        Typefaces.apply(viewHolder.messageBody(), Typefaces.regular(activity), false);
        final var rawBody = message.getBody();
        if (Strings.isNullOrEmpty(rawBody)) {
            viewHolder.messageBody().setText("");
            viewHolder.messageBody().setTextIsSelectable(false);
            return;
        }
        final String nick = UIHelper.getMessageDisplayName(message);
        final boolean hasMeCommand = message.hasMeCommand();
        final var trimmedBody = rawBody.trim();
        final SpannableStringBuilder body;
        if (trimmedBody.length() > Config.MAX_DISPLAY_MESSAGE_CHARS) {
            body = new SpannableStringBuilder(trimmedBody, 0, Config.MAX_DISPLAY_MESSAGE_CHARS);
            body.append("…");
        } else {
            body = new SpannableStringBuilder(trimmedBody);
        }
        if (hasMeCommand) {
            body.replace(0, Message.ME_COMMAND.length(), String.format("%s ", nick));
        }
        boolean startsWithQuote = handleTextQuotes(viewHolder.messageBody(), body, bubbleColor);
        if (!message.isPrivateMessage()) {
            if (hasMeCommand) {
                body.setSpan(
                        new StyleSpan(Typeface.BOLD_ITALIC),
                        0,
                        nick.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } else {
            String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            body.insert(0, privateMarker);
            int privateMarkerIndex = privateMarker.length();
            if (startsWithQuote) {
                body.insert(privateMarkerIndex, "\n\n");
                body.setSpan(
                        new DividerSpan(false),
                        privateMarkerIndex,
                        privateMarkerIndex + 2,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                body.insert(privateMarkerIndex, " ");
            }
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor)),
                    0,
                    privateMarkerIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarkerIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (hasMeCommand) {
                body.setSpan(
                        new StyleSpan(Typeface.BOLD_ITALIC),
                        privateMarkerIndex + 1,
                        privateMarkerIndex + 1 + nick.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (message.getConversation().getMode() == Conversation.MODE_MULTI
                && message.getStatus() == Message.STATUS_RECEIVED) {
            if (message.getConversation() instanceof Conversation conversation) {
                Pattern pattern =
                        NotificationService.generateNickHighlightPattern(
                                conversation.getMucOptions().getActualNick());
                Matcher matcher = pattern.matcher(body);
                while (matcher.find()) {
                    body.setSpan(
                            new StyleSpan(Typeface.BOLD),
                            matcher.start(),
                            matcher.end(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        final var matcher = Emoticons.getEmojiPattern(body).matcher(body);
        while (matcher.find()) {
            if (matcher.start() < matcher.end()) {
                body.setSpan(
                        new RelativeSizeSpan(1.2f),
                        matcher.start(),
                        matcher.end(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        StylingHelper.format(body, viewHolder.messageBody().getCurrentTextColor());
        Linkify.addLinks(body);
        FixedURLSpan.fix(body);
        if (highlightedTerm != null) {
            StylingHelper.highlight(viewHolder.messageBody(), body, highlightedTerm);
        }
        viewHolder.messageBody().setAutoLinkMask(0);
        viewHolder.messageBody().setText(body);
        viewHolder.messageBody().setMovementMethod(ClickableMovementMethod.getInstance());
    }

    private void displayDownloadableMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final String text,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.downloadButton().setText(text);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.downloadButton().setIconResource(imageResource);
        viewHolder
                .downloadButton()
                .setOnClickListener(v -> ConversationFragment.downloadFile(activity, message));
    }

    private void displayOpenableMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder
                .downloadButton()
                .setText(
                        activity.getString(
                                R.string.open_x_file,
                                UIHelper.getFileDescriptionString(activity, message)));
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.downloadButton().setIconResource(imageResource);
        viewHolder.downloadButton().setOnClickListener(v -> openDownloadable(message));
    }

    private void displayLocationMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.VISIBLE);
        viewHolder.downloadButton().setText(R.string.show_location);
        final var attachment = Attachment.of(message);
        final @DrawableRes int imageResource = MediaAdapter.getImageDrawable(attachment);
        viewHolder.downloadButton().setIconResource(imageResource);
        viewHolder.downloadButton().setOnClickListener(v -> showLocation(message));
    }

    private void displayAudioMessage(
            final BubbleMessageItemViewHolder viewHolder,
            Message message,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.image().setVisibility(View.GONE);
        viewHolder.downloadButton().setVisibility(View.GONE);
        final RelativeLayout audioPlayer = viewHolder.audioPlayer();
        audioPlayer.setVisibility(View.VISIBLE);
        AudioPlayer.ViewHolder.get(audioPlayer).setBubbleColor(bubbleColor);
        this.audioPlayer.init(audioPlayer, message);
    }

    private void displayMediaPreviewMessage(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        toggleWhisperInfo(viewHolder, message, bubbleColor);
        viewHolder.downloadButton().setVisibility(View.GONE);
        viewHolder.audioPlayer().setVisibility(View.GONE);
        viewHolder.image().setVisibility(View.VISIBLE);
        final FileParams params = message.getFileParams();
        final float target = activity.getResources().getDimension(R.dimen.image_preview_width);
        final int scaledW;
        final int scaledH;
        if (Math.max(params.height, params.width) * metrics.density <= target) {
            scaledW = (int) (params.width * metrics.density);
            scaledH = (int) (params.height * metrics.density);
        } else if (Math.max(params.height, params.width) <= target) {
            scaledW = params.width;
            scaledH = params.height;
        } else if (params.width <= params.height) {
            scaledW = (int) (params.width / ((double) params.height / target));
            scaledH = (int) target;
        } else {
            scaledW = (int) target;
            scaledH = (int) (params.height / ((double) params.width / target));
        }
        final LinearLayout.LayoutParams layoutParams =
                new LinearLayout.LayoutParams(scaledW, scaledH);
        viewHolder.image().setLayoutParams(layoutParams);
        activity.loadBitmap(message, viewHolder.image());
        viewHolder.image().setOnClickListener(v -> openDownloadable(message));
    }

    private void toggleWhisperInfo(
            final BubbleMessageItemViewHolder viewHolder,
            final Message message,
            final BubbleColor bubbleColor) {
        if (message.isPrivateMessage()) {
            final String privateMarker;
            if (message.getStatus() <= Message.STATUS_RECEIVED) {
                privateMarker = activity.getString(R.string.private_message);
            } else {
                Jid cp = message.getCounterpart();
                privateMarker =
                        activity.getString(
                                R.string.private_message_to,
                                Strings.nullToEmpty(cp == null ? null : cp.getResource()));
            }
            final SpannableString body = new SpannableString(privateMarker);
            body.setSpan(
                    new ForegroundColorSpan(
                            bubbleToOnSurfaceVariant(viewHolder.messageBody(), bubbleColor)),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            body.setSpan(
                    new StyleSpan(Typeface.BOLD),
                    0,
                    privateMarker.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            viewHolder.messageBody().setText(body);
            Typefaces.apply(viewHolder.messageBody(), Typefaces.regular(activity), false);
            viewHolder.messageBody().setVisibility(View.VISIBLE);
        } else {
            viewHolder.messageBody().setVisibility(View.GONE);
        }
    }

    private void loadMoreMessages(final Conversation conversation) {
        final var connection = conversation.getAccount().getXmppConnection();
        conversation.setLastClearHistory(0, null);
        activity.xmppConnectionService.updateConversation(conversation);
        conversation.setHasMessagesLeftOnServer(true);
        conversation.setFirstMamReference(null);
        long timestamp = conversation.getLastMessageTransmitted().getTimestamp();
        if (timestamp == 0) {
            timestamp = System.currentTimeMillis();
        }
        conversation.messagesLoaded.set(true);
        final var query =
                connection
                        .getManager(MessageArchiveManager.class)
                        .query(conversation, new MamReference(0), timestamp, false);
        if (query != null) {
            Toast.makeText(activity, R.string.fetching_history_from_server, Toast.LENGTH_LONG)
                    .show();
        } else {
            Toast.makeText(
                            activity,
                            R.string.not_fetching_history_retention_period,
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    @NonNull
    @Override
    public MessageItemViewHolder onCreateViewHolder(
            @NonNull final ViewGroup parent, final int viewType) {
        final var inflater = LayoutInflater.from(parent.getContext());
        return switch (viewType) {
            case RTP_SESSION ->
                    new RtpSessionMessageItemViewHolder(
                            DataBindingUtil.inflate(
                                    inflater, R.layout.item_message_rtp_session, parent, false));
            case DATE_SEPARATOR ->
                    new DateSeperatorMessageItemViewHolder(
                            DataBindingUtil.inflate(
                                    inflater, R.layout.item_message_date_bubble, parent, false));
            case STATUS ->
                    new StatusMessageItemViewHolder(
                            DataBindingUtil.inflate(
                                    inflater, R.layout.item_message_status, parent, false));
            case END ->
                    new EndBubbleMessageItemViewHolder(
                            DataBindingUtil.inflate(
                                    inflater, R.layout.item_message_end, parent, false));
            case START ->
                    new StartBubbleMessageItemViewHolder(
                            DataBindingUtil.inflate(
                                    inflater, R.layout.item_message_start, parent, false));
            default -> throw new AssertionError("Unable to create ViewHolder for type " + viewType);
        };
    }

    @Override
    public void onBindViewHolder(
            @NonNull final MessageItemViewHolder viewHolder, final int position) {
        final Message message = getItem(position);
        final int type = getItemViewType(message, bubbleDesign.alignStart);

        if (type == DATE_SEPARATOR
                && viewHolder instanceof DateSeperatorMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
            return;
        }

        if (type == RTP_SESSION
                && viewHolder instanceof RtpSessionMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
            return;
        }

        if (type == STATUS
                && viewHolder instanceof StatusMessageItemViewHolder messageItemViewHolder) {
            render(message, messageItemViewHolder);
            return;
        }

        if ((type == END || type == START)
                && viewHolder instanceof BubbleMessageItemViewHolder messageItemViewHolder) {
            render(position, message, messageItemViewHolder);
            return;
        }

        throw new AssertionError(
                "No ViewHolder of type " + type + " for " + viewHolder.getClass().getSimpleName());
    }

    private void render(
            final int position,
            final Message message,
            final BubbleMessageItemViewHolder viewHolder) {
        final boolean omemoEncryption = message.getEncryption() == Message.ENCRYPTION_AXOLOTL;
        final boolean isInValidSession =
                message.isValidInSession() && (!omemoEncryption || message.isTrusted());
        final Conversational conversation = message.getConversation();
        final Account account = conversation.getAccount();

        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final boolean received = message.getStatus() == Message.STATUS_RECEIVED;
        final BubbleColor baseColor;
        if (received) {
            if (isInValidSession) {
                baseColor = colorfulBackground ? BubbleColor.SURFACE_HIGH : BubbleColor.PRIMARY;
            } else {
                baseColor = BubbleColor.WARNING;
            }
        } else {
            baseColor = colorfulBackground ? BubbleColor.PRIMARY : BubbleColor.SURFACE_HIGH;
        }
        // A row flashed after jumping to it from a reply borrows the tertiary container colour.
        // Overriding here rather than repainting the background afterwards keeps the text, icon
        // and quote-span colours consistent, because all of them are derived from this value.
        final boolean highlighted =
                message.getUuid() != null && message.getUuid().equals(this.highlightedMessageUuid);
        final BubbleColor bubbleColor = highlighted ? BubbleColor.TERTIARY : baseColor;

        final var mergeIntoTop = mergeIntoTop(position, message);
        final var mergeIntoBottom = mergeIntoBottom(position, message);
        final boolean showAvatar;
        if (viewHolder instanceof StartBubbleMessageItemViewHolder) {
            showAvatar =
                    bubbleDesign.showAvatars11
                            || message.getConversation().getMode() == Conversation.MODE_MULTI;
        } else if (viewHolder instanceof EndBubbleMessageItemViewHolder) {
            showAvatar = bubbleDesign.showAvatarsAccounts;
        } else {
            throw new IllegalStateException("Unrecognized BubbleMessageItemViewHolder");
        }
        setBubblePadding(viewHolder.root(), mergeIntoTop, mergeIntoBottom);
        if (showAvatar) {
            final var requiresAvatar =
                    viewHolder instanceof StartBubbleMessageItemViewHolder
                            ? !mergeIntoTop
                            : !mergeIntoBottom;
            setRequiresAvatar(viewHolder, requiresAvatar);
            AvatarWorkerTask.loadAvatar(message, viewHolder.contactPicture(), R.dimen.avatar);
        } else {
            viewHolder.contactPicture().setVisibility(View.GONE);
        }
        setAvatarDistance(viewHolder.messageBox(), viewHolder.getClass(), showAvatar);
        viewHolder.messageBox().setClipToOutline(true);

        resetClickListener(viewHolder.messageBox(), viewHolder.messageBody());

        viewHolder
                .contactPicture()
                .setOnClickListener(
                        v -> {
                            if (MessageAdapter.this.mOnContactPictureClickedListener != null) {
                                MessageAdapter.this.mOnContactPictureClickedListener
                                        .onContactPictureClicked(message);
                            }
                        });
        viewHolder
                .contactPicture()
                .setOnLongClickListener(
                        v -> {
                            if (MessageAdapter.this.mOnContactPictureLongClickedListener != null) {
                                MessageAdapter.this.mOnContactPictureLongClickedListener
                                        .onContactPictureLongClicked(v, message);
                                return true;
                            } else {
                                return false;
                            }
                        });

        final Transferable transferable = message.getTransferable();
        final boolean unInitiatedButKnownSize = MessageUtils.unInitiatedButKnownSize(message);
        if (unInitiatedButKnownSize
                || message.isDeleted()
                || (transferable != null
                        && transferable.getStatus() != Transferable.STATUS_UPLOADING)) {
            if (unInitiatedButKnownSize
                    || transferable != null
                            && transferable.getStatus() == Transferable.STATUS_OFFER) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        activity.getString(
                                R.string.download_x_file,
                                UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else if (transferable != null
                    && transferable.getStatus() == Transferable.STATUS_OFFER_CHECK_FILESIZE) {
                displayDownloadableMessage(
                        viewHolder,
                        message,
                        activity.getString(
                                R.string.check_x_filesize,
                                UIHelper.getFileDescriptionString(activity, message)),
                        bubbleColor);
            } else {
                displayInfoMessage(
                        viewHolder,
                        UIHelper.getMessagePreview(activity, message).first,
                        bubbleColor);
            }
        } else if (message.isFileOrImage()
                && message.getEncryption() != Message.ENCRYPTION_PGP
                && message.getEncryption() != Message.ENCRYPTION_DECRYPTION_FAILED) {
            if (message.getFileParams().width > 0 && message.getFileParams().height > 0) {
                displayMediaPreviewMessage(viewHolder, message, bubbleColor);
            } else if (message.getFileParams().runtime > 0) {
                displayAudioMessage(viewHolder, message, bubbleColor);
            } else {
                displayOpenableMessage(viewHolder, message, bubbleColor);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_PGP) {
            if (account.isPgpDecryptionServiceConnected()) {
                if (conversation instanceof Conversation
                        && !account.hasPendingPgpIntent((Conversation) conversation)) {
                    displayInfoMessage(
                            viewHolder,
                            activity.getString(R.string.message_decrypting),
                            bubbleColor);
                } else {
                    displayInfoMessage(
                            viewHolder, activity.getString(R.string.pgp_message), bubbleColor);
                }
            } else {
                displayInfoMessage(
                        viewHolder, activity.getString(R.string.install_openkeychain), bubbleColor);
                viewHolder.messageBox().setOnClickListener(this::promptOpenKeychainInstall);
                viewHolder.messageBody().setOnClickListener(this::promptOpenKeychainInstall);
            }
        } else if (message.getEncryption() == Message.ENCRYPTION_DECRYPTION_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.decryption_failed), bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE) {
            displayInfoMessage(
                    viewHolder,
                    activity.getString(R.string.not_encrypted_for_this_device),
                    bubbleColor);
        } else if (message.getEncryption() == Message.ENCRYPTION_AXOLOTL_FAILED) {
            displayInfoMessage(
                    viewHolder, activity.getString(R.string.omemo_decryption_failed), bubbleColor);
        } else {
            if (message.isGeoUri()) {
                displayLocationMessage(viewHolder, message, bubbleColor);
            } else if (message.bodyIsOnlyEmojis() && message.getType() != Message.TYPE_PRIVATE) {
                displayEmojiMessage(viewHolder, message.getBody().trim(), bubbleColor);
            } else if (message.treatAsDownloadable()) {
                try {
                    final URI uri = new URI(message.getBody());
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize_on_host,
                                    UIHelper.getFileDescriptionString(activity, message),
                                    uri.getHost()),
                            bubbleColor);
                } catch (Exception e) {
                    displayDownloadableMessage(
                            viewHolder,
                            message,
                            activity.getString(
                                    R.string.check_x_filesize,
                                    UIHelper.getFileDescriptionString(activity, message)),
                            bubbleColor);
                }
            } else {
                displayTextMessage(viewHolder, message, bubbleColor);
            }
        }

        setBubbleBackground(viewHolder, bubbleColor, received, mergeIntoTop, mergeIntoBottom);
        setTextColor(viewHolder.messageBody(), bubbleColor);
        displayReplyPreview(viewHolder, message);

        if (received && viewHolder instanceof StartBubbleMessageItemViewHolder startViewHolder) {
            setTextColor(startViewHolder.encryption(), bubbleColor);
            if (isInValidSession) {
                startViewHolder.encryption().setVisibility(View.GONE);
            } else {
                startViewHolder.encryption().setVisibility(View.VISIBLE);
                if (omemoEncryption && !message.isTrusted()) {
                    startViewHolder.encryption().setText(R.string.not_trusted);
                } else {
                    startViewHolder
                            .encryption()
                            .setText(CryptoHelper.encryptionTypeToText(message.getEncryption()));
                }
            }
            final boolean remaining = Restrictions.reactionsPerUserRemaining(message);
            if (remaining) {
                BindingAdapters.setReactionsOnReceived(
                        viewHolder.reactions(),
                        message.getAggregatedReactions(),
                        reactions -> sendReactions(message, reactions),
                        emoji -> showDetailedReaction(message, emoji),
                        () -> showAllReactions(message),
                        () -> addReaction(message));
            } else {
                BindingAdapters.setReactionsOnSent(
                        viewHolder.reactions(),
                        message.getAggregatedReactions(),
                        reactions -> sendReactions(message, reactions),
                        emoji -> showDetailedReaction(message, emoji),
                        () -> showAllReactions(message));
            }
        } else {
            if (viewHolder instanceof StartBubbleMessageItemViewHolder startViewHolder) {
                startViewHolder.encryption().setVisibility(View.GONE);
            }
            BindingAdapters.setReactionsOnSent(
                    viewHolder.reactions(),
                    message.getAggregatedReactions(),
                    reactions -> sendReactions(message, reactions),
                    emoji -> showDetailedReaction(message, emoji),
                    () -> showAllReactions(message));
        }

        displayStatus(viewHolder, message, bubbleColor);
        if (mergeIntoBottom) {
            hideRepeatedGroupStatus(viewHolder);
        }
    }

    private void render(
            final Message message, final DateSeperatorMessageItemViewHolder viewHolder) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        if (UIHelper.today(message.getTimeSent())) {
            viewHolder.binding.messageBody.setText(R.string.today);
        } else if (UIHelper.yesterday(message.getTimeSent())) {
            viewHolder.binding.messageBody.setText(R.string.yesterday);
        } else {
            viewHolder.binding.messageBody.setText(
                    DateUtils.formatDateTime(
                            activity,
                            message.getTimeSent(),
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR));
        }
        setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.TRANSPARENT);
        setTextColor(viewHolder.binding.messageBody, BubbleColor.TRANSPARENT);
    }

    private void render(final Message message, final RtpSessionMessageItemViewHolder viewHolder) {
        final boolean colorfulBackground = this.bubbleDesign.colorfulChatBubbles;
        final boolean received = message.getStatus() <= Message.STATUS_RECEIVED;
        final RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
        final long duration = rtpSessionStatus.duration;
        if (received) {
            if (duration > 0) {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.incoming_call_duration_timestamp,
                                TimeFrameUtils.resolve(activity, duration),
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent())));
            } else if (rtpSessionStatus.successful) {
                viewHolder.binding.messageBody.setText(R.string.incoming_call);
            } else {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.missed_call_timestamp,
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent())));
            }
        } else {
            if (duration > 0) {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.outgoing_call_duration_timestamp,
                                TimeFrameUtils.resolve(activity, duration),
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent())));
            } else {
                viewHolder.binding.messageBody.setText(
                        activity.getString(
                                R.string.outgoing_call_timestamp,
                                UIHelper.readableTimeDifferenceFull(
                                        activity, message.getTimeSent())));
            }
        }
        if (colorfulBackground) {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.SECONDARY);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.SECONDARY);
            setImageTint(viewHolder.binding.indicatorReceived, BubbleColor.SECONDARY);
        } else {
            setBackgroundTint(viewHolder.binding.messageBox, BubbleColor.SURFACE_HIGH);
            setTextColor(viewHolder.binding.messageBody, BubbleColor.SURFACE_HIGH);
            setImageTint(viewHolder.binding.indicatorReceived, BubbleColor.SURFACE_HIGH);
        }
        viewHolder.binding.indicatorReceived.setImageResource(
                RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful));
    }

    private void render(final Message message, final StatusMessageItemViewHolder viewHolder) {
        final var conversation = message.getConversation();
        if ("LOAD_MORE".equals(message.getBody())) {
            viewHolder.binding.statusMessage.setVisibility(View.GONE);
            viewHolder.binding.messagePhoto.setVisibility(View.GONE);
            viewHolder.binding.loadMoreMessages.setVisibility(View.VISIBLE);
            viewHolder.binding.loadMoreMessages.setOnClickListener(
                    v -> loadMoreMessages((Conversation) message.getConversation()));
        } else {
            viewHolder.binding.statusMessage.setVisibility(View.VISIBLE);
            viewHolder.binding.loadMoreMessages.setVisibility(View.GONE);
            viewHolder.binding.statusMessage.setText(message.getBody());
            boolean showAvatar;
            if (conversation.getMode() == Conversation.MODE_SINGLE) {
                showAvatar = true;
                AvatarWorkerTask.loadAvatar(
                        message, viewHolder.binding.messagePhoto, R.dimen.avatar_on_status_message);
            } else if (message.getCounterpart() != null
                    || message.getTrueCounterpart() != null
                    || (message.getCounterparts() != null
                            && !message.getCounterparts().isEmpty())) {
                showAvatar = true;
                AvatarWorkerTask.loadAvatar(
                        message, viewHolder.binding.messagePhoto, R.dimen.avatar_on_status_message);
            } else {
                showAvatar = false;
            }
            if (showAvatar) {
                viewHolder.binding.messagePhoto.setAlpha(0.5f);
                viewHolder.binding.messagePhoto.setVisibility(View.VISIBLE);
            } else {
                viewHolder.binding.messagePhoto.setVisibility(View.GONE);
            }
        }
    }

    private void setAvatarDistance(
            final LinearLayout messageBox,
            final Class<? extends BubbleMessageItemViewHolder> clazz,
            final boolean showAvatar) {
        final ViewGroup.MarginLayoutParams layoutParams =
                (ViewGroup.MarginLayoutParams) messageBox.getLayoutParams();
        if (showAvatar) {
            final var resources = messageBox.getResources();
            if (clazz == StartBubbleMessageItemViewHolder.class) {
                layoutParams.setMarginStart(
                        resources.getDimensionPixelSize(R.dimen.bubble_avatar_distance));
                layoutParams.setMarginEnd(0);
            } else if (clazz == EndBubbleMessageItemViewHolder.class) {
                layoutParams.setMarginStart(0);
                layoutParams.setMarginEnd(
                        resources.getDimensionPixelSize(R.dimen.bubble_avatar_distance));
            } else {
                throw new AssertionError("Avatar distances are not available on this view type");
            }
        } else {
            layoutParams.setMarginStart(0);
            layoutParams.setMarginEnd(0);
        }
        messageBox.setLayoutParams(layoutParams);
    }

    private void setBubblePadding(
            final ConstraintLayout root,
            final boolean mergeIntoTop,
            final boolean mergeIntoBottom) {
        final var resources = root.getResources();
        final var horizontal = resources.getDimensionPixelSize(R.dimen.bubble_horizontal_padding);
        final int top =
                resources.getDimensionPixelSize(
                        mergeIntoTop
                                ? R.dimen.bubble_vertical_padding_minimum
                                : R.dimen.bubble_vertical_padding);
        final int bottom =
                resources.getDimensionPixelSize(
                        mergeIntoBottom
                                ? R.dimen.bubble_vertical_padding_minimum
                                : R.dimen.bubble_vertical_padding);
        root.setPadding(horizontal, top, horizontal, bottom);
    }

    private static void setBubbleBackground(
            final BubbleMessageItemViewHolder viewHolder,
            final BubbleColor bubbleColor,
            final boolean received,
            final boolean mergeIntoTop,
            final boolean mergeIntoBottom) {
        final var resources = viewHolder.messageBox().getResources();
        final float radius = resources.getDimensionPixelSize(R.dimen.message_bubble_corner_radius);
        final float joined = resources.getDimensionPixelSize(R.dimen.message_bubble_joined_radius);
        final float topStart = mergeIntoTop && received ? joined : radius;
        final float topEnd = mergeIntoTop && !received ? joined : radius;
        final float bottomStart = mergeIntoBottom && received ? joined : radius;
        final float bottomEnd = mergeIntoBottom && !received ? joined : radius;
        final var background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(bubbleToColorStateList(viewHolder.messageBox(), bubbleColor).getDefaultColor());
        background.setCornerRadii(
                new float[] {
                    topStart,
                    topStart,
                    topEnd,
                    topEnd,
                    bottomEnd,
                    bottomEnd,
                    bottomStart,
                    bottomStart
                });
        // The layouts declare android:backgroundTint="?colorSurface" on message_box. That tint
        // belongs to the View, not to the drawable, and View#setBackground re-applies it to
        // whatever is handed over - repainting every bubble in the chat background colour and
        // making it invisible. Clear it so the computed colour survives.
        viewHolder.messageBox().setBackgroundTintList(null);
        viewHolder.messageBox().setBackground(background);
    }

    private static void hideRepeatedGroupStatus(final BubbleMessageItemViewHolder viewHolder) {
        viewHolder.time().setVisibility(View.GONE);
        viewHolder.indicatorSecurity().setVisibility(View.GONE);
        viewHolder.indicatorEdit().setVisibility(View.GONE);
        viewHolder.indicatorReceived().setVisibility(View.GONE);
        if (viewHolder instanceof StartBubbleMessageItemViewHolder startViewHolder) {
            startViewHolder.encryption().setVisibility(View.GONE);
        }
    }

    private void setRequiresAvatar(
            final BubbleMessageItemViewHolder viewHolder, final boolean requiresAvatar) {
        final var layoutParams = viewHolder.contactPicture().getLayoutParams();
        if (requiresAvatar) {
            final var resources = viewHolder.contactPicture().getResources();
            final var avatarSize = resources.getDimensionPixelSize(R.dimen.bubble_avatar_size);
            layoutParams.height = avatarSize;
            viewHolder.contactPicture().setVisibility(View.VISIBLE);
            viewHolder.messageBox().setMinimumHeight(avatarSize);
        } else {
            layoutParams.height = 0;
            viewHolder.contactPicture().setVisibility(View.INVISIBLE);
            viewHolder.messageBox().setMinimumHeight(0);
        }
        viewHolder.contactPicture().setLayoutParams(layoutParams);
    }

    private boolean mergeIntoTop(final int position, final Message message) {
        // Guards position - 1, which is what is actually read. The previous `position < 0` check
        // let position 0 through into getItem(-1); nothing hit it only because the list's first row
        // is normally a date separator or the load-more notice, which never reach this path.
        if (position <= 0) {
            return false;
        }
        final var top = getItem(position - 1);
        return merge(top, message);
    }

    private boolean mergeIntoBottom(final int position, final Message message) {
        final Message bottom;
        try {
            bottom = getItem(position + 1);
        } catch (final IndexOutOfBoundsException e) {
            return false;
        }
        return merge(message, bottom);
    }

    /** Whether two adjacent messages render as one visual bubble group. */
    public static boolean merge(final Message a, final Message b) {
        if (getItemViewType(a, false) != getItemViewType(b, false)) {
            return false;
        }
        final var receivedA = a.getStatus() == Message.STATUS_RECEIVED;
        final var receivedB = b.getStatus() == Message.STATUS_RECEIVED;
        if (receivedA != receivedB) {
            return false;
        }
        if (homogenizedEncryption(a.getEncryption()) != homogenizedEncryption(b.getEncryption())
                || !Objects.equal(a.getFingerprint(), b.getFingerprint())) {
            return false;
        }
        // A private message has a different audience than the public room, so it never shares a
        // bubble group with one; two private messages only do when they are with the same person.
        // The occupant check below cannot stand in for this: it only runs on received messages, so
        // without this two private messages *we* sent to different participants would merge into
        // one group and read as a single thread.
        if (a.isPrivateMessage() != b.isPrivateMessage()) {
            return false;
        }
        if (a.isPrivateMessage() && !Objects.equal(a.getCounterpart(), b.getCounterpart())) {
            return false;
        }
        if (a.getConversation().getMode() == Conversation.MODE_MULTI
                && a.getStatus() == Message.STATUS_RECEIVED) {
            final var occupantIdA = a.getOccupantId();
            final var occupantIdB = b.getOccupantId();
            if (occupantIdA != null && occupantIdB != null) {
                if (!occupantIdA.equals(occupantIdB)) {
                    return false;
                }
            }
            final var counterPartA = a.getCounterpart();
            final var counterPartB = b.getCounterpart();
            if (counterPartA == null || !counterPartA.equals(counterPartB)) {
                return false;
            }
        }
        return b.getTimeSent() - a.getTimeSent() <= Config.MESSAGE_MERGE_WINDOW;
    }

    private static int homogenizedEncryption(final int encryption) {
        return switch (encryption) {
            case Message.ENCRYPTION_AXOLOTL,
                    Message.ENCRYPTION_AXOLOTL_FAILED,
                    Message.ENCRYPTION_AXOLOTL_NOT_FOR_THIS_DEVICE ->
                    Message.ENCRYPTION_AXOLOTL;
            case Message.ENCRYPTION_PGP,
                    Message.ENCRYPTION_DECRYPTED,
                    Message.ENCRYPTION_DECRYPTION_FAILED ->
                    Message.ENCRYPTION_PGP;
            default -> encryption;
        };
    }

    private boolean showDetailedReaction(final Message message, final String emoji) {
        final var names = reactorNames(message, emoji);
        if (names == null) {
            // Nobody to name. Leave the long press unhandled so it still reaches the message
            // context menu, as it did before this had anything to show.
            return false;
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(emoji)
                .setMessage(names)
                .create()
                .show();
        return true;
    }

    /** Everyone who reacted with {@code emoji}, joined for display, or null when nobody did. */
    private String reactorNames(final Message message, final String emoji) {
        final var reactions =
                Collections2.filter(
                        message.getReactions(), r -> r.normalizedReaction().equals(emoji));
        final var c = message.getConversation();
        if (c.getMode() == Conversational.MODE_MULTI) {
            if (c instanceof Conversation conversation) {
                final var users = conversation.getMucOptions().getUsersOrStubs(reactions);
                return users.isEmpty() ? null : UIHelper.concatNames(users);
            }
            // A detached channel row carries no occupant list to resolve reactors against.
            return null;
        }
        // A direct conversation has only two possible reactors, so there is nothing to look up.
        // Before, the long press simply did nothing here and the chip looked inert.
        return ReactionDetails.format(
                ReactionDetails.directNames(
                        reactions,
                        activity.getString(R.string.me),
                        c.getContact() == null ? null : c.getContact().getDisplayName()));
    }

    /**
     * The full reaction list, for the "+n" chip that stands in for the reactions {@link
     * ReactionChips} held back.
     */
    private void showAllReactions(final Message message) {
        final var aggregated = message.getAggregatedReactions();
        final var lines = new StringBuilder();
        for (final var reaction : aggregated.reactions) {
            final var names = reactorNames(message, reaction.getKey());
            if (lines.length() > 0) {
                lines.append('\n');
            }
            lines.append(
                    String.format(
                            Locale.getDefault(),
                            "%s  %d",
                            reaction.getKey(),
                            reaction.getValue()));
            if (names != null) {
                lines.append("  ").append(names);
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.reactions)
                .setMessage(lines)
                .create()
                .show();
    }

    private void sendReactions(final Message message, final Collection<String> reactions) {
        activity.sendReactions(message, reactions);
    }

    private void displayReplyPreview(
            final BubbleMessageItemViewHolder viewHolder, final Message message) {
        final var quoted = findQuotedMessage(message);
        final CharSequence previewText = replyPreviewText(message, quoted);
        final boolean hasPreview = previewText != null && previewText.length() > 0;
        if (message.isReply() && hasPreview) {
            final var senderColor = replySenderColor(message, quoted);
            viewHolder.replyPreview().setVisibility(View.VISIBLE);
            viewHolder.replyPreviewAccent().setBackgroundTintList(
                    ColorStateList.valueOf(senderColor));
            viewHolder.replyPreviewSender().setText(getReplySender(message, quoted));
            viewHolder.replyPreviewSender().setTextColor(senderColor);
            viewHolder.replyPreviewBody().setText(previewText);
            viewHolder.replyPreview().setClickable(true);
            viewHolder.replyPreview().setOnClickListener(
                    v -> {
                        final var listener = MessageAdapter.this.mOnReplyPreviewClickedListener;
                        if (listener != null) {
                            listener.onReplyPreviewClicked(message);
                        }
                    });
        } else {
            viewHolder.replyPreview().setVisibility(View.GONE);
            // Cleared explicitly: this row may be recycled into a message that is not a reply,
            // where a live listener would make dead space tappable.
            viewHolder.replyPreview().setOnClickListener(null);
            viewHolder.replyPreview().setClickable(false);
        }
    }

    /**
     * What the quote card shows as the quoted message's content.
     *
     * <p>Runs the resolved message through the same preview formatter the conversation list uses,
     * so a quoted image reads as "Image" rather than as the raw URL its body happens to hold, and a
     * quoted call, location or undecryptable message gets its own label. Only when the quoted
     * message is outside the loaded range does this fall back to the id-only preview the sender
     * supplied.
     */
    private CharSequence replyPreviewText(final Message message, final Message quoted) {
        if (quoted != null) {
            final var preview = UIHelper.getMessagePreview(activity, quoted, ' ').first;
            // A transfer in a state with no label of its own previews as the empty string, which
            // would hide the card altogether and leave the reply looking like a plain message.
            if (preview != null && preview.length() > 0) {
                return preview;
            }
        }
        return message.getReplyPreview();
    }

    /**
     * Colour identifying the quoted sender, applied to the card's accent bar and name.
     *
     * <p>Derived from the sender rather than fixed so that, in a channel, two quote cards for
     * different people are told apart at a glance - the same cue the avatars already give. Falls
     * back to the theme accent while the quoted message is unresolved, because the reply only
     * carries an address, and colouring from that would disagree with the colour the same person
     * gets once their message loads.
     */
    private int replySenderColor(final Message message, final Message quoted) {
        final var fallback =
                MaterialColors.getColor(
                        this.activity,
                        com.google.android.material.R.attr.colorTertiary,
                        Color.TRANSPARENT);
        if (quoted == null) {
            return fallback;
        }
        if (quoted.getStatus() != Message.STATUS_RECEIVED) {
            return fallback;
        }
        final var counterpart = quoted.getCounterpart();
        if (counterpart == null) {
            return fallback;
        }
        return MaterialColors.harmonizeWithPrimary(
                this.activity, UIHelper.getColorForName(counterpart.toString()));
    }

    /**
     * The message a reply is quoting, or null when it is not in the loaded range.
     *
     * <p>Preferred over the stored {@code replyPreview} for display. That field is only reliable
     * for replies this device sent; for incoming ones MessageParser derives it from the incoming
     * message's own body after stripping "&gt;" fallback markers, so when the sender omits those
     * the "quote" ends up being a copy of the reply itself.
     */
    private Message findQuotedMessage(final Message reply) {
        return quotedMessages().resolve(reply);
    }

    /**
     * The reply index for the current snapshot, built on first use after each replacement. Rebuilt
     * lazily rather than in {@link #replaceMessages(List)} so a snapshot that never renders a reply
     * never pays for one.
     */
    private QuotedMessages quotedMessages() {
        var index = this.quotedMessages;
        if (index == null) {
            index = QuotedMessages.of(this.messages);
            this.quotedMessages = index;
        }
        return index;
    }

    /**
     * The name shown on the quote card.
     *
     * <p>Prefers the resolved message's display name - the channel nick, the contact's name, or our
     * own account name for a reply to ourselves - which is what the row being quoted is labelled
     * with elsewhere in the conversation. The reply's {@code replyTo} address is only a fallback
     * for a quote outside the loaded range, and its local part is not a display name at all.
     */
    private String getReplySender(final Message message, final Message quoted) {
        if (quoted != null) {
            if (quoted.getStatus() != Message.STATUS_RECEIVED) {
                return activity.getString(R.string.me);
            }
            final var displayName = UIHelper.getMessageDisplayName(quoted);
            if (!Strings.isNullOrEmpty(displayName)) {
                return displayName;
            }
        }
        final String replyTo = message.getReplyTo();
        if (!Strings.isNullOrEmpty(replyTo)) {
            try {
                final Jid jid = Jid.of(replyTo);
                final var resource = jid.getResource();
                // In a channel the address is room@host/nick, where the nick is the display name.
                return Strings.isNullOrEmpty(resource) ? jid.getLocal() : resource;
            } catch (final IllegalArgumentException ignored) {
                return replyTo;
            }
        }
        return activity.getString(R.string.reply);
    }

    private void addReaction(final Message message) {
        activity.addReaction(message, reactions -> sendReactions(message, reactions));
    }

    private void promptOpenKeychainInstall(View view) {
        activity.showInstallPgpDialog();
    }

    public FileBackend getFileBackend() {
        return activity.xmppConnectionService.getFileBackend();
    }

    public void stopAudioPlayer() {
        audioPlayer.stop();
    }

    public void unregisterListenerInAudioPlayer() {
        audioPlayer.unregisterListener();
    }

    public void startStopPending() {
        audioPlayer.startStopPending();
    }

    public void openDownloadable(Message message) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(
                                activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ConversationFragment.registerPendingMessage(activity, message);
            ActivityCompat.requestPermissions(
                    activity,
                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    ConversationsActivity.REQUEST_OPEN_MESSAGE);
            return;
        }
        final DownloadableFile file =
                activity.xmppConnectionService.getFileBackend().getFile(message);
        ViewUtil.view(activity, file);
    }

    private void showLocation(Message message) {
        for (Intent intent : GeoHelper.createGeoIntentsFromMessage(activity, message)) {
            if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                getContext().startActivity(intent);
                return;
            }
        }
        Toast.makeText(
                        activity,
                        R.string.no_application_found_to_display_location,
                        Toast.LENGTH_SHORT)
                .show();
    }

    /** Updates bubble presentation and reports whether existing rows need rebinding. */
    public boolean updatePreferences() {
        final AppSettings appSettings = new AppSettings(activity);
        final BubbleDesign updated =
                new BubbleDesign(
                        appSettings.isColorfulChatBubbles(),
                        appSettings.isAlignStart(),
                        appSettings.isLargeFont(),
                        appSettings.isShowAvatars11(),
                        appSettings.isShowAvatarsAccounts());
        final boolean changed = !this.bubbleDesign.sameAs(updated);
        this.bubbleDesign = updated;
        return changed;
    }

    public void setHighlightedTerm(List<String> terms) {
        this.highlightedTerm = terms == null ? null : StylingHelper.filterHighlightedWords(terms);
    }

    /** Raised when the quoted block inside a reply bubble is tapped. */
    public interface OnReplyPreviewClicked {
        void onReplyPreviewClicked(Message reply);
    }

    public interface OnContactPictureClicked {
        void onContactPictureClicked(Message message);
    }

    public interface OnContactPictureLongClicked {
        void onContactPictureLongClicked(View v, Message message);
    }

    private static void setBackgroundTint(final LinearLayout view, final BubbleColor bubbleColor) {
        view.setBackgroundTintList(bubbleToColorStateList(view, bubbleColor));
    }

    private static ColorStateList bubbleToColorStateList(
            final View view, final BubbleColor bubbleColor) {
        if(bubbleColor == BubbleColor.TRANSPARENT) {
                return ColorStateList.valueOf(Color.TRANSPARENT);
        }

        final @AttrRes int colorAttributeResId =
                switch (bubbleColor) {
                    case SURFACE ->
                            Activities.isNightMode(view.getContext())
                                    ? com.google.android.material.R.attr.colorSurfaceContainerHigh
                                    : com.google.android.material.R.attr.colorSurfaceContainerLow;
                    case SURFACE_HIGH ->
                            Activities.isNightMode(view.getContext())
                                    ? com.google.android.material.R.attr
                                            .colorSurfaceContainerHighest
                                    : com.google.android.material.R.attr.colorSurfaceContainerHigh;
                    case PRIMARY -> com.google.android.material.R.attr.colorPrimaryContainer;
                    case SECONDARY -> com.google.android.material.R.attr.colorSecondaryContainer;
                    case TERTIARY -> com.google.android.material.R.attr.colorTertiaryContainer;
                    case WARNING -> com.google.android.material.R.attr.colorErrorContainer;
                    default -> com.google.android.material.R.attr.colorSurfaceContainer;
                };
        return ColorStateList.valueOf(MaterialColors.getColor(view, colorAttributeResId));
    }

    public static void setImageTint(final ImageView imageView, final BubbleColor bubbleColor) {
        ImageViewCompat.setImageTintList(
                imageView, bubbleToOnSurfaceColorStateList(imageView, bubbleColor));
    }

    public static void setImageTintError(final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(imageView, androidx.appcompat.R.attr.colorError)));
    }

    public static void setTextColor(final TextView textView, final BubbleColor bubbleColor) {
        final var color = bubbleToOnSurfaceColor(textView, bubbleColor);
        textView.setTextColor(color);
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            textView.setLinkTextColor(
                    MaterialColors.getColor(textView, androidx.appcompat.R.attr.colorPrimary));
        } else {
            textView.setLinkTextColor(color);
        }
    }

    private static void setTextSize(final TextView textView, final boolean largeFont) {
        if (largeFont) {
            textView.setTextAppearance(
                    R.style.TextAppearance_Snikket_MessageContentLarge);
        } else {
            textView.setTextAppearance(
                    R.style.TextAppearance_Snikket_MessageContentNormal);
        }
    }

    private static @ColorInt int bubbleToOnSurfaceVariant(
            final View view, final BubbleColor bubbleColor) {
        final @AttrRes int colorAttributeResId;
        if (BubbleColor.SURFACES.contains(bubbleColor)) {
            colorAttributeResId = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            colorAttributeResId = bubbleToOnSurface(bubbleColor);
        }
        return MaterialColors.getColor(view, colorAttributeResId);
    }

    private static @ColorInt int bubbleToOnSurfaceColor(
            final View view, final BubbleColor bubbleColor) {
        return MaterialColors.getColor(view, bubbleToOnSurface(bubbleColor));
    }

    public static ColorStateList bubbleToOnSurfaceColorStateList(
            final View view, final BubbleColor bubbleColor) {
        return ColorStateList.valueOf(bubbleToOnSurfaceColor(view, bubbleColor));
    }

    private static @AttrRes int bubbleToOnSurface(final BubbleColor bubbleColor) {
        return switch (bubbleColor) {
            case TRANSPARENT ->  com.google.android.material.R.attr.colorOnBackground;
            case SURFACE, SURFACE_HIGH -> com.google.android.material.R.attr.colorOnSurface;
            case PRIMARY -> com.google.android.material.R.attr.colorOnPrimaryContainer;
            case SECONDARY -> com.google.android.material.R.attr.colorOnSecondaryContainer;
            case TERTIARY -> com.google.android.material.R.attr.colorOnTertiaryContainer;
            case WARNING -> com.google.android.material.R.attr.colorOnErrorContainer;
        };
    }

    public enum BubbleColor {
        TRANSPARENT,
        SURFACE,
        SURFACE_HIGH,
        PRIMARY,
        SECONDARY,
        TERTIARY,
        WARNING;

        private static final Collection<BubbleColor> SURFACES =
                Arrays.asList(BubbleColor.SURFACE, BubbleColor.SURFACE_HIGH);
    }

    private static class BubbleDesign {
        public final boolean colorfulChatBubbles;
        public final boolean alignStart;
        public final boolean largeFont;
        public final boolean showAvatars11;
        public final boolean showAvatarsAccounts;

        private BubbleDesign(
                final boolean colorfulChatBubbles,
                final boolean alignStart,
                final boolean largeFont,
                final boolean showAvatars11,
                final boolean showAvatarsAccounts) {
            this.colorfulChatBubbles = colorfulChatBubbles;
            this.alignStart = alignStart;
            this.largeFont = largeFont;
            this.showAvatars11 = showAvatars11;
            this.showAvatarsAccounts = showAvatarsAccounts;
        }

        private boolean sameAs(final BubbleDesign other) {
            return this.colorfulChatBubbles == other.colorfulChatBubbles
                    && this.alignStart == other.alignStart
                    && this.largeFont == other.largeFont
                    && this.showAvatars11 == other.showAvatars11
                    && this.showAvatarsAccounts == other.showAvatarsAccounts;
        }
    }

    public abstract static class MessageItemViewHolder extends RecyclerView.ViewHolder {

        private MessageItemViewHolder(@NonNull final View itemView) {
            super(itemView);
        }
    }

    private abstract static class BubbleMessageItemViewHolder extends MessageItemViewHolder {

        private BubbleMessageItemViewHolder(@NonNull View itemView) {
            super(itemView);
        }

        public abstract ConstraintLayout root();

        protected abstract ImageView indicatorEdit();

        protected abstract RelativeLayout audioPlayer();

        protected abstract LinearLayout messageBox();

        protected abstract MaterialButton downloadButton();

        protected abstract ImageView image();

        protected abstract ImageView indicatorSecurity();

        protected abstract ImageView indicatorReceived();

        protected abstract TextView time();

        protected abstract TextView messageBody();

        protected abstract ImageView contactPicture();

        protected abstract ChipGroup reactions();

        protected abstract LinearLayout replyPreview();

        protected abstract View replyPreviewAccent();

        protected abstract TextView replyPreviewSender();

        protected abstract TextView replyPreviewBody();
    }

    private static class StartBubbleMessageItemViewHolder extends BubbleMessageItemViewHolder {

        private final ItemMessageStartBinding binding;

        public StartBubbleMessageItemViewHolder(@NonNull ItemMessageStartBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        public ConstraintLayout root() {
            return (ConstraintLayout) this.binding.getRoot();
        }

        @Override
        protected ImageView indicatorEdit() {
            return this.binding.editIndicator;
        }

        @Override
        protected RelativeLayout audioPlayer() {
            return this.binding.messageContent.audioPlayer;
        }

        @Override
        protected LinearLayout messageBox() {
            return this.binding.messageBox;
        }

        @Override
        protected MaterialButton downloadButton() {
            return this.binding.messageContent.downloadButton;
        }

        @Override
        protected ImageView image() {
            return this.binding.messageContent.messageImage;
        }

        protected ImageView indicatorSecurity() {
            return this.binding.securityIndicator;
        }

        @Override
        protected ImageView indicatorReceived() {
            return this.binding.indicatorReceived;
        }

        @Override
        protected TextView time() {
            return this.binding.messageTime;
        }

        @Override
        protected TextView messageBody() {
            return this.binding.messageContent.messageBody;
        }

        protected TextView encryption() {
            return this.binding.messageEncryption;
        }

        @Override
        protected ImageView contactPicture() {
            return this.binding.messagePhoto;
        }

        @Override
        protected ChipGroup reactions() {
            return this.binding.reactions;
        }

        @Override
        protected LinearLayout replyPreview() {
            return this.binding.messageContent.replyPreview;
        }

        @Override
        protected View replyPreviewAccent() {
            return this.binding.messageContent.replyPreviewAccent;
        }

        @Override
        protected TextView replyPreviewSender() {
            return this.binding.messageContent.replyPreviewSender;
        }

        @Override
        protected TextView replyPreviewBody() {
            return this.binding.messageContent.replyPreviewBody;
        }
    }

    private static class EndBubbleMessageItemViewHolder extends BubbleMessageItemViewHolder {

        private final ItemMessageEndBinding binding;

        private EndBubbleMessageItemViewHolder(@NonNull ItemMessageEndBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        @Override
        public ConstraintLayout root() {
            return (ConstraintLayout) this.binding.getRoot();
        }

        @Override
        protected ImageView indicatorEdit() {
            return this.binding.editIndicator;
        }

        @Override
        protected RelativeLayout audioPlayer() {
            return this.binding.messageContent.audioPlayer;
        }

        @Override
        protected LinearLayout messageBox() {
            return this.binding.messageBox;
        }

        @Override
        protected MaterialButton downloadButton() {
            return this.binding.messageContent.downloadButton;
        }

        @Override
        protected ImageView image() {
            return this.binding.messageContent.messageImage;
        }

        @Override
        protected ImageView indicatorSecurity() {
            return this.binding.securityIndicator;
        }

        @Override
        protected ImageView indicatorReceived() {
            return this.binding.indicatorReceived;
        }

        @Override
        protected TextView time() {
            return this.binding.messageTime;
        }

        @Override
        protected TextView messageBody() {
            return this.binding.messageContent.messageBody;
        }

        @Override
        protected ImageView contactPicture() {
            return this.binding.messagePhoto;
        }

        @Override
        protected ChipGroup reactions() {
            return this.binding.reactions;
        }

        @Override
        protected LinearLayout replyPreview() {
            return this.binding.messageContent.replyPreview;
        }

        @Override
        protected View replyPreviewAccent() {
            return this.binding.messageContent.replyPreviewAccent;
        }

        @Override
        protected TextView replyPreviewSender() {
            return this.binding.messageContent.replyPreviewSender;
        }

        @Override
        protected TextView replyPreviewBody() {
            return this.binding.messageContent.replyPreviewBody;
        }
    }

    private static class DateSeperatorMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageDateBubbleBinding binding;

        private DateSeperatorMessageItemViewHolder(@NonNull ItemMessageDateBubbleBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class RtpSessionMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageRtpSessionBinding binding;

        private RtpSessionMessageItemViewHolder(@NonNull ItemMessageRtpSessionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class StatusMessageItemViewHolder extends MessageItemViewHolder {

        private final ItemMessageStatusBinding binding;

        private StatusMessageItemViewHolder(@NonNull ItemMessageStatusBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
