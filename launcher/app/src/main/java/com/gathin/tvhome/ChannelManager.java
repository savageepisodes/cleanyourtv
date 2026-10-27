package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages enabled channels, custom user ordering, and placement relative to the dock.
 */
public final class ChannelManager {
    private static final String TAG = "TvHome.Channels";
    private static final String PREF_NAME = "tvhome_channels_pref";
    private static final String KEY_CHANNEL_ORDER = "channel_order";
    private static final String KEY_DISABLED_CHANNELS = "disabled_channels";
    private static final String KEY_ABOVE_DOCK = "channels_above_dock";
    private static final String KEY_CHANNEL_ABOVE_PREFIX = "channel_above_";

    private ChannelManager() {}

    public static boolean isAboveDock(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ABOVE_DOCK, false);
    }

    public static void setAboveDock(Context context, boolean above) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ABOVE_DOCK, above)
                .apply();
    }

    public static boolean isChannelAboveDock(Context context, String channelId) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean globalDefault = sp.getBoolean(KEY_ABOVE_DOCK, false);
        return sp.getBoolean(KEY_CHANNEL_ABOVE_PREFIX + channelId, globalDefault);
    }

    public static void setChannelAboveDock(Context context, String channelId, boolean above) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CHANNEL_ABOVE_PREFIX + channelId, above)
                .apply();
    }

    public static List<Channel> getOrderedActiveChannels(Context context) {
        List<Channel> channels = KodiChannelProvider.loadChannels(context);
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        Set<String> disabled = sp.getStringSet(KEY_DISABLED_CHANNELS, Collections.emptySet());
        String orderCsv = sp.getString(KEY_CHANNEL_ORDER, "");
        List<String> savedOrder = new ArrayList<>();
        if (!orderCsv.isEmpty()) {
            for (String id : orderCsv.split(",")) {
                if (!id.trim().isEmpty()) savedOrder.add(id.trim());
            }
        }

        List<Channel> active = new ArrayList<>();
        for (Channel ch : channels) {
            ch.enabled = !disabled.contains(ch.id);
            ch.isAboveDock = isChannelAboveDock(context, ch.id);
            if (ch.enabled && ch.items != null && !ch.items.isEmpty()) {
                int idx = savedOrder.indexOf(ch.id);
                ch.order = idx != -1 ? idx : 999;
                active.add(ch);
            }
        }

        Collections.sort(active, Comparator.comparingInt(c -> c.order));
        return active;
    }

    public static List<Channel> getActiveChannelsAboveDock(Context context) {
        List<Channel> all = getOrderedActiveChannels(context);
        List<Channel> above = new ArrayList<>();
        for (Channel ch : all) {
            if (ch.isAboveDock) above.add(ch);
        }
        return above;
    }

    public static List<Channel> getActiveChannelsBelowDock(Context context) {
        List<Channel> all = getOrderedActiveChannels(context);
        List<Channel> below = new ArrayList<>();
        for (Channel ch : all) {
            if (!ch.isAboveDock) below.add(ch);
        }
        return below;
    }

    public static List<Channel> getAllChannels(Context context) {
        List<Channel> channels = KodiChannelProvider.loadChannels(context);
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        Set<String> disabled = sp.getStringSet(KEY_DISABLED_CHANNELS, Collections.emptySet());
        String orderCsv = sp.getString(KEY_CHANNEL_ORDER, "");
        List<String> savedOrder = new ArrayList<>();
        if (!orderCsv.isEmpty()) {
            for (String id : orderCsv.split(",")) {
                if (!id.trim().isEmpty()) savedOrder.add(id.trim());
            }
        }

        for (Channel ch : channels) {
            ch.enabled = !disabled.contains(ch.id);
            ch.isAboveDock = isChannelAboveDock(context, ch.id);
            int idx = savedOrder.indexOf(ch.id);
            ch.order = idx != -1 ? idx : 999;
        }

        Collections.sort(channels, Comparator.comparingInt(c -> c.order));
        return channels;
    }

    public static void setChannelEnabled(Context context, String channelId, boolean enabled) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> disabled = new HashSet<>(sp.getStringSet(KEY_DISABLED_CHANNELS, Collections.emptySet()));
        if (enabled) {
            disabled.remove(channelId);
        } else {
            disabled.add(channelId);
        }
        sp.edit().putStringSet(KEY_DISABLED_CHANNELS, disabled).apply();
    }

    public static void moveChannel(Context context, String channelId, boolean moveUp) {
        List<Channel> active = getOrderedActiveChannels(context);
        int index = -1;
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).id.equals(channelId)) {
                index = i;
                break;
            }
        }

        if (index == -1) return;

        if (moveUp && index > 0) {
            Collections.swap(active, index, index - 1);
        } else if (!moveUp && index < active.size() - 1) {
            Collections.swap(active, index, index + 1);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < active.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(active.get(i).id);
        }

        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CHANNEL_ORDER, sb.toString())
                .apply();
    }
}
