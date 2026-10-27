package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard data model representing a horizontal display channel row.
 * Universal across Kodi playlists and system streaming providers (TvContractCompat).
 */
public class Channel {
    public final String id;
    public final String title;
    public final String appName;
    public final Drawable appIcon;
    public final List<ChannelItem> items;
    public boolean enabled;
    public int order;
    public boolean isAboveDock;

    public Channel(String id, String title, String appName, Drawable appIcon, List<ChannelItem> items, boolean enabled, int order) {
        this.id = id;
        this.title = title;
        this.appName = appName;
        this.appIcon = appIcon;
        this.items = items != null ? items : new ArrayList<>();
        this.enabled = enabled;
        this.order = order;
        this.isAboveDock = false;
    }
}
