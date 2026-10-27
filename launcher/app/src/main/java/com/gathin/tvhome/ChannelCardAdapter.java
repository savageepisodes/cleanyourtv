package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter for horizontal carousel of cards inside a Channel row.
 * Handles D-pad navigation, smooth focus zoom, and lightweight downsampled poster loading.
 */
public class ChannelCardAdapter extends RecyclerView.Adapter<ChannelCardAdapter.CardHolder> {

    private static final int POSTER_TARGET_W = 150;
    private static final int POSTER_TARGET_H = 225;

    // Small shared in-memory LRU cache (capped at ~3 MB)
    private static final int CACHE_SIZE = 3 * 1024 * 1024;
    private static final LruCache<String, Bitmap> BITMAP_CACHE = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final List<ChannelItem> items;
    private final View leftHeaderView;
    private final boolean isAboveDock;
    private final int channelRowIndex;
    private final int totalChannelRows;

    public ChannelCardAdapter(List<ChannelItem> items, View leftHeaderView) {
        this(items, leftHeaderView, false, 0, 1);
    }

    public ChannelCardAdapter(List<ChannelItem> items, View leftHeaderView, boolean isAboveDock) {
        this(items, leftHeaderView, isAboveDock, 0, 1);
    }

    public ChannelCardAdapter(List<ChannelItem> items, View leftHeaderView, boolean isAboveDock, int channelRowIndex, int totalChannelRows) {
        this.items = items;
        this.leftHeaderView = leftHeaderView;
        this.isAboveDock = isAboveDock;
        this.channelRowIndex = channelRowIndex;
        this.totalChannelRows = totalChannelRows;
    }

    @NonNull
    @Override
    public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel_card, parent, false);
        return new CardHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull CardHolder holder, int position) {
        ChannelItem item = items.get(position);
        holder.title.setText(item.title);
        holder.subtitle.setText(item.subtitle != null ? item.subtitle : "");

        holder.poster.setImageBitmap(null);
        holder.poster.setTag(item.posterPath);

        if (item.posterPath != null && !item.posterPath.isEmpty()) {
            loadPoster(item.posterPath, holder.poster);
        }

        holder.itemView.setOnClickListener(v -> {
            if (item.intent != null) {
                try {
                    v.getContext().startActivity(item.intent);
                } catch (Exception e) {
                    // Fallback to launching package
                    try {
                        String pkg = item.intent.getPackage();
                        if (pkg == null && item.intent.getComponent() != null) {
                            pkg = item.intent.getComponent().getPackageName();
                        }
                        if (pkg != null) {
                            Intent launch = v.getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                            if (launch != null) v.getContext().startActivity(launch);
                        }
                    } catch (Exception ignored) {}
                }
            }
        });

        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && holder.getAdapterPosition() == 0) {
                    if (leftHeaderView != null) {
                        leftHeaderView.setVisibility(View.VISIBLE);
                        leftHeaderView.setAlpha(0f);
                        leftHeaderView.setTranslationX(-25f);
                        leftHeaderView.animate().alpha(1f).translationX(0f).setDuration(120).start();
                        leftHeaderView.requestFocus();
                        return true;
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (v.getContext() instanceof MainActivity) {
                        MainActivity act = (MainActivity) v.getContext();
                        int cardPos = holder.getAdapterPosition();
                        if (channelRowIndex < totalChannelRows - 1) {
                            act.focusChannelRowCard(isAboveDock, channelRowIndex + 1, cardPos);
                            return true;
                        }
                        if (isAboveDock) {
                            act.focusTileFromChannel(cardPos);
                            return true;
                        }
                    }
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (v.getContext() instanceof MainActivity) {
                        MainActivity act = (MainActivity) v.getContext();
                        int cardPos = holder.getAdapterPosition();
                        if (channelRowIndex > 0) {
                            act.focusChannelRowCard(isAboveDock, channelRowIndex - 1, cardPos);
                            return true;
                        }
                        if (isAboveDock) {
                            act.focusTopBar();
                            return true;
                        } else {
                            act.focusTileFromBelowChannel(cardPos);
                            return true;
                        }
                    }
                }
            }
            return false;
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            holder.focusBorder.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
            holder.title.setTextColor(hasFocus ? 0xFFFFFFFF : 0xE6FFFFFF);
            v.animate()
             .scaleX(hasFocus ? 1.08f : 1.0f)
             .scaleY(hasFocus ? 1.08f : 1.0f)
             .setDuration(150)
             .start();
        });
    }

    @Override
    public int getItemCount() {
        return items != null ? items.size() : 0;
    }

    private static void loadPoster(String path, ImageView target) {
        Bitmap cached = BITMAP_CACHE.get(path);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                File f = new File(path);
                if (!f.exists()) return;

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, opts);

                int sample = 1;
                while ((opts.outWidth / (sample * 2) >= POSTER_TARGET_W)
                        && (opts.outHeight / (sample * 2) >= POSTER_TARGET_H)) {
                    sample *= 2;
                }

                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.RGB_565; // half memory of ARGB_8888

                Bitmap decoded = BitmapFactory.decodeFile(path, opts);
                if (decoded != null) {
                    BITMAP_CACHE.put(path, decoded);
                    MAIN_HANDLER.post(() -> {
                        if (path.equals(target.getTag())) {
                            target.setImageBitmap(decoded);
                        }
                    });
                }
            } catch (Throwable ignored) {}
        });
    }

    static class CardHolder extends RecyclerView.ViewHolder {
        final View posterFrame;
        final ImageView poster;
        final View focusBorder;
        final TextView title;
        final TextView subtitle;

        CardHolder(View itemView) {
            super(itemView);
            posterFrame = itemView.findViewById(R.id.cardPosterFrame);
            poster = itemView.findViewById(R.id.cardPoster);
            focusBorder = itemView.findViewById(R.id.cardFocusBorder);
            title = itemView.findViewById(R.id.cardTitle);
            subtitle = itemView.findViewById(R.id.cardSubtitle);

            float density = itemView.getResources().getDisplayMetrics().density;
            final int radiusPx = (int) (10 * density);
            ViewOutlineProvider roundOutline = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            };
            if (posterFrame != null) {
                posterFrame.setOutlineProvider(roundOutline);
                posterFrame.setClipToOutline(true);
            }
            if (poster != null) {
                poster.setOutlineProvider(roundOutline);
                poster.setClipToOutline(true);
            }
        }
    }
}
