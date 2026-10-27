package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView wallpaperSourceChoice;
    private TextView wallpaperIntervalChoice;
    private TextView screensaverChoice;
    private TextView channelGapChoice;
    private String wallpaperSource;
    private int wallpaperInterval;
    private int screensaverIdle;
    private static final int[] INTERVAL_SECONDS = {5, 10, 15, 30, 60, 180, 300};
    private static final int[] SCREENSAVER_MINUTES = {0, 1, 3, 5, 10, 15, 30, 60};
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        wallpaperSourceChoice = findViewById(R.id.wallpaperSourceChoice);
        wallpaperSourceChoice.setOnClickListener(v -> showWallpaperSourcePicker());
        wallpaperIntervalChoice = findViewById(R.id.wallpaperIntervalChoice);
        wallpaperIntervalChoice.setOnClickListener(v -> showWallpaperIntervalPicker());
        screensaverChoice = findViewById(R.id.screensaverChoice);
        screensaverChoice.setOnClickListener(v -> showScreensaverPicker());
        channelGapChoice = findViewById(R.id.channelGapChoice);
        channelGapChoice.setOnClickListener(v -> showChannelGapPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CHANNEL_HEADER_GAP_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        wallpaperSource = choices.getString(Config.WALLPAPER_SOURCE_KEY, null);
        if (wallpaperSource == null) {
            Config activeCfg = Config.load(this);
            wallpaperSource = activeCfg.wallpaperSource;
        }
        boolean isKodi = Config.WALLPAPER_SOURCE_KODI.equalsIgnoreCase(wallpaperSource);
        wallpaperChoice.setVisibility(isKodi ? View.GONE : View.VISIBLE);
        String sourceLabel = isKodi
                ? getString(R.string.wallpaper_source_kodi) : getString(R.string.wallpaper_source_theme);
        wallpaperSourceChoice.setText(getString(R.string.wallpaper_source_label, sourceLabel));

        wallpaperInterval = choices.getInt(Config.WALLPAPER_INTERVAL_KEY, 15);
        String intervalStr = formatIntervalSeconds(wallpaperInterval);
        wallpaperIntervalChoice.setText(getString(R.string.wallpaper_interval_label, intervalStr));

        screensaverIdle = choices.getInt(Config.SCREENSAVER_IDLE_KEY, 5);
        String idleStr = screensaverIdle <= 0 ? getString(R.string.screensaver_off)
                : (screensaverIdle == 1 ? getString(R.string.minute_single) : getString(R.string.minutes_format, screensaverIdle));
        screensaverChoice.setText(getString(R.string.screensaver_label, idleStr));

        String gap = choices.getString(Config.CHANNEL_HEADER_GAP_KEY, Config.GAP_MEDIUM);
        String gapLabel = Config.GAP_SMALL.equalsIgnoreCase(gap) ? getString(R.string.gap_small)
                : Config.GAP_LARGE.equalsIgnoreCase(gap) ? getString(R.string.gap_large)
                : getString(R.string.gap_medium);
        channelGapChoice.setText(getString(R.string.channel_header_gap_state, gapLabel));

        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private String formatIntervalSeconds(int sec) {
        if (sec < 60) {
            return sec == 1 ? getString(R.string.seconds_single) : getString(R.string.seconds_format, sec);
        }
        int m = sec / 60;
        return m == 1 ? getString(R.string.minute_single) : getString(R.string.minutes_format, m);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CHANNEL_HEADER_GAP_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
    private void showWallpaperSourcePicker() {
        String[] options = {getString(R.string.wallpaper_source_theme), getString(R.string.wallpaper_source_kodi)};
        int checked = Config.WALLPAPER_SOURCE_KODI.equalsIgnoreCase(wallpaperSource) ? 1 : 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_source_title)
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putString(Config.WALLPAPER_SOURCE_KEY,
                            which == 1 ? Config.WALLPAPER_SOURCE_KODI : Config.WALLPAPER_SOURCE_THEME).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showWallpaperIntervalPicker() {
        String[] labels = new String[INTERVAL_SECONDS.length];
        int checked = -1;
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) {
            int s = INTERVAL_SECONDS[i];
            if (s < 60) {
                labels[i] = getString(R.string.seconds_format, s);
            } else {
                int m = s / 60;
                labels[i] = (m == 1 ? getString(R.string.minute_single) : getString(R.string.minutes_format, m))
                        + " (" + s + "s)";
            }
            if (s == wallpaperInterval) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_interval_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putInt(Config.WALLPAPER_INTERVAL_KEY, INTERVAL_SECONDS[which]).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showScreensaverPicker() {
        String[] labels = new String[SCREENSAVER_MINUTES.length];
        int checked = 0;
        for (int i = 0; i < SCREENSAVER_MINUTES.length; i++) {
            int m = SCREENSAVER_MINUTES[i];
            labels[i] = m <= 0 ? getString(R.string.screensaver_off)
                    : (m == 1 ? getString(R.string.minute_single) : getString(R.string.minutes_format, m));
            if (m == screensaverIdle) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.screensaver_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    choices.edit().putInt(Config.SCREENSAVER_IDLE_KEY, SCREENSAVER_MINUTES[which]).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showChannelGapPicker() {
        String[] options = {getString(R.string.gap_small), getString(R.string.gap_medium), getString(R.string.gap_large)};
        String gap = choices.getString(Config.CHANNEL_HEADER_GAP_KEY, Config.GAP_MEDIUM);
        int checked = Config.GAP_SMALL.equalsIgnoreCase(gap) ? 0 : Config.GAP_LARGE.equalsIgnoreCase(gap) ? 2 : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.channel_header_gap_label)
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    dialog.dismiss();
                    String chosen = which == 0 ? Config.GAP_SMALL : which == 2 ? Config.GAP_LARGE : Config.GAP_MEDIUM;
                    choices.edit().putString(Config.CHANNEL_HEADER_GAP_KEY, chosen).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
