package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Outline;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated adapter for horizontal Channel rows.
 * Placed in ConcatAdapter either above or below the AppAdapter.
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelRowHolder> {

    public enum Position {
        ABOVE,
        BELOW,
        ALL
    }

    public interface ChannelListener {
        void onChannelsChanged();
    }

    private final Context context;
    private final List<Channel> channels = new ArrayList<>();
    private final ChannelListener listener;
    private final Position positionFilter;

    public ChannelAdapter(Context context, ChannelListener listener) {
        this(context, Position.ALL, listener);
    }

    public ChannelAdapter(Context context, Position positionFilter, ChannelListener listener) {
        this.context = context;
        this.positionFilter = positionFilter;
        this.listener = listener;
        reload();
    }

    public void reload() {
        channels.clear();
        if (positionFilter == Position.ABOVE) {
            channels.addAll(ChannelManager.getActiveChannelsAboveDock(context));
        } else if (positionFilter == Position.BELOW) {
            channels.addAll(ChannelManager.getActiveChannelsBelowDock(context));
        } else {
            channels.addAll(ChannelManager.getOrderedActiveChannels(context));
        }
        notifyDataSetChanged();
    }

    public boolean hasChannels() {
        return !channels.isEmpty();
    }

    @NonNull
    @Override
    public ChannelRowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_channel, parent, false);
        return new ChannelRowHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelRowHolder holder, int position) {
        Channel channel = channels.get(position);
        holder.title.setText(channel.title);
        holder.appIcon.setImageDrawable(channel.appIcon);
        if (holder.rowTitle != null) {
            holder.rowTitle.setText(channel.title);
        }
        if (holder.rowIcon != null) {
            holder.rowIcon.setImageDrawable(channel.appIcon);
        }
        holder.header.setVisibility(View.GONE);

        holder.header.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_BACK) {
                    v.setVisibility(View.GONE);
                    View first = holder.recycler.getLayoutManager() != null
                            ? holder.recycler.getLayoutManager().findViewByPosition(0) : null;
                    if (first != null) first.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    return true; // stay on header
                }
            }
            return false;
        });

        final boolean[] isDialogOpen = new boolean[]{false};

        holder.header.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                v.post(() -> {
                    if (!v.hasFocus() && !isDialogOpen[0]) {
                        v.setVisibility(View.GONE);
                    }
                });
            }
        });

        holder.recycler.setAdapter(new ChannelCardAdapter(channel.items, holder.header, channel.isAboveDock, position, channels.size()));

        holder.header.setOnClickListener(v -> showChannelOptions(channel, holder, isDialogOpen, position));
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    private void showChannelOptions(Channel channel, ChannelRowHolder holder, boolean[] isDialogOpen, int position) {
        isDialogOpen[0] = true;
        if (context instanceof MainActivity) {
            ((MainActivity) context).showChannelOptionsPopup(holder.header, channel, position, () -> {
                isDialogOpen[0] = false;
                if (holder.header != null) {
                    holder.header.setVisibility(View.GONE);
                }
                View first = holder.recycler.getLayoutManager() != null
                        ? holder.recycler.getLayoutManager().findViewByPosition(0) : null;
                if (first != null) first.requestFocus();
            });
        }
    }

    public void showManageAllDialog() {
        if (context instanceof MainActivity) {
            ((MainActivity) context).showChannelsManagePopup(null);
        }
    }

    static class ChannelRowHolder extends RecyclerView.ViewHolder {
        final View titleBar;
        final ImageView rowIcon;
        final TextView rowTitle;
        final View header;
        final ImageView appIcon;
        final TextView title;
        final ImageView optionsIcon;
        final RecyclerView recycler;

        ChannelRowHolder(View v) {
            super(v);
            titleBar = v.findViewById(R.id.channelTitleBar);
            rowIcon = v.findViewById(R.id.channelRowIcon);
            rowTitle = v.findViewById(R.id.channelRowTitle);
            header = v.findViewById(R.id.channelHeader);
            appIcon = v.findViewById(R.id.channelAppIcon);
            title = v.findViewById(R.id.channelTitle);
            optionsIcon = v.findViewById(R.id.channelOptionsIcon);
            recycler = v.findViewById(R.id.channelRecycler);
            recycler.setLayoutManager(new LinearLayoutManager(v.getContext(), LinearLayoutManager.HORIZONTAL, false));

            float density = v.getResources().getDisplayMetrics().density;
            final int radiusPx = (int) (10 * density);
            ViewOutlineProvider roundOutline = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            };
            if (header != null) {
                header.setOutlineProvider(roundOutline);
                header.setClipToOutline(true);
            }
        }
    }
}
