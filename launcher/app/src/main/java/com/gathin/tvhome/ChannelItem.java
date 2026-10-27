package com.gathin.tvhome;

import android.content.Intent;

/**
 * Standard data model representing an individual card/poster inside a Channel row.
 */
public class ChannelItem {
    public final String id;
    public final String title;
    public final String subtitle;
    public final String posterPath;
    public final Intent intent;

    public ChannelItem(String id, String title, String subtitle, String posterPath, Intent intent) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.posterPath = posterPath;
        this.intent = intent;
    }
}
