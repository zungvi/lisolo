package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.XmppProvider;
import java.util.ArrayList;
import java.util.List;

public final class XmppProviderAdapter
        extends RecyclerView.Adapter<XmppProviderAdapter.ViewHolder> {

    public interface OnProviderSelected {
        void onProviderSelected(XmppProvider provider);
    }

    private final OnProviderSelected listener;
    private final List<XmppProvider> providers = new ArrayList<>();

    public XmppProviderAdapter(final OnProviderSelected listener) {
        this.listener = listener;
    }

    public void setProviders(final List<XmppProvider> values) {
        providers.clear();
        providers.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_xmpp_provider, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final XmppProvider provider = providers.get(position);
        holder.domain.setText(provider.domain);
        holder.summary.setText(
                holder.itemView
                        .getResources()
                        .getString(
                                provider.inBandRegistration
                                        ? R.string.provider_list_summary_in_band
                                        : R.string.provider_list_summary_website,
                                provider.category));
        holder.itemView.setOnClickListener(v -> listener.onProviderSelected(provider));
        holder.itemView.setContentDescription(
                holder.itemView.getResources().getString(
                        R.string.provider_list_content_description,
                        provider.domain,
                        holder.summary.getText()));
    }

    @Override
    public int getItemCount() {
        return providers.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView domain;
        private final TextView summary;

        ViewHolder(@NonNull final View itemView) {
            super(itemView);
            domain = itemView.findViewById(R.id.provider_domain);
            summary = itemView.findViewById(R.id.provider_summary);
        }
    }
}
