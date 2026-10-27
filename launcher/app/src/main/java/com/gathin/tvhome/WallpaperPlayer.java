package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's or Kodi's local wallpaper images while Home is visible, with smooth crossfade and subtle slow zoom. */
final class WallpaperPlayer {
    private final Context context;
    private final Config config;
    private final FrameLayout root;
    private final ImageView imageA;
    private final ImageView imageB;
    private ImageView activeImage;
    private ImageView idleImage;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final int width;
    private final Bitmap.Config bitmapConfig;
    private final int height;
    private final int dimColor;
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    private TextView caption;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Set<String>> moods;
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i("TvHome.Wall", "rotateRunnable fired! active=" + active + " closed=" + closed);
            if (!active || closed) return;
            update();
            scheduleNextRotation();
        }
    };

    private final FrameLayout wallpaperContainer;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.context = context;
        this.config = config;
        this.root = root;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationSeconds > 0
                ? config.wallpaperRotationSeconds * 1000L
                : Math.max(5000L, config.wallpaperRotationMinutes * 60_000L);
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = Config.WALLPAPER_SOURCE_KODI.equalsIgnoreCase(config.wallpaperSource)
                ? WallpaperRotation.ORDER_SEQUENTIAL
                : config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);

        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;

        wallpaperContainer = new FrameLayout(context);
        wallpaperContainer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        imageA = new ImageView(context);
        imageA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageA.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        imageB = new ImageView(context);
        imageB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageB.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        imageB.setVisibility(View.INVISIBLE);
        imageB.setAlpha(0f);

        wallpaperContainer.addView(imageB, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallpaperContainer.addView(imageA, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        activeImage = imageA;
        idleImage = imageB;

        Map<String, String> captionNames = Collections.emptyMap();
        if (config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()) {
            if (Config.WALLPAPER_SOURCE_KODI.equalsIgnoreCase(config.wallpaperSource)) {
                captionNames = KodiArtwork.getTitles();
            } else {
                captionNames = WallpaperCaptions.read(files.get(0).getParentFile());
            }
        }
        names.putAll(captionNames);
        caption = names.isEmpty() ? null : makeCaption(context, root);

        Log.i("TvHome.Wall", "init: enabled=" + config.wallpaperEnabled + " files=" + files.size() + " interval=" + interval);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(wallpaperContainer, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            showFirst();
        } else {
            Log.w("TvHome.Wall", "init: wallpaper not enabled or files empty! enabled=" + config.wallpaperEnabled + " files=" + files.size());
        }
    }

    void refreshKodiArtwork() {
        if (closed || config == null || !Config.WALLPAPER_SOURCE_KODI.equalsIgnoreCase(config.wallpaperSource)) return;
        decoder.execute(() -> {
            KodiArtwork.invalidateCache();
            List<File> freshKodi = KodiArtwork.getLandscapeArtwork();
            Map<String, String> titles = KodiArtwork.getTitles();
            if (freshKodi != null && !freshKodi.isEmpty()) {
                handler.post(() -> refreshFiles(freshKodi, titles));
            }
        });
    }

    void refreshFiles(List<File> newFiles, Map<String, String> newCaptions) {
        if (newFiles == null || newFiles.isEmpty() || closed) return;
        boolean wasEmpty = this.files.isEmpty();
        if (!wasEmpty && this.files.size() == newFiles.size() && this.files.equals(newFiles)) {
            if (newCaptions != null) {
                this.names.clear();
                this.names.putAll(newCaptions);
            }
            return;
        }

        this.files.clear();
        this.files.addAll(newFiles);
        if (newCaptions != null) {
            this.names.clear();
            this.names.putAll(newCaptions);
        }
        if (caption == null && !this.names.isEmpty() && config != null && config.wallpaperCaptions) {
            caption = makeCaption(context, root);
        }
        if (wasEmpty && active && !closed) {
            if (wallpaperContainer.getParent() == null) {
                root.addView(wallpaperContainer, 0, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showFirst();
            scheduleNextRotation();
        } else if (active && !closed) {
            scheduleNextRotation();
        }
    }

    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = null;
        if (WallpaperRotation.ORDER_SEQUENTIAL.equals(order) && !playable.isEmpty()) {
            first = playable.get(0);
        } else {
            first = WallpaperRotation.find(playable, previous);
            if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        }
        Log.i("TvHome.Wall", "showFirst: first=" + (first != null ? first.getPath() : "null") + " playable=" + playable.size());
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                current = bitmap;
                activeImage.setImageBitmap(bitmap);
                activeImage.setAlpha(1f);
                activeImage.setScaleX(1.0f);
                activeImage.setScaleY(1.0f);
                activeImage.setVisibility(View.VISIBLE);
                activeImage.bringToFront();
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                startKenBurns(activeImage);
                Log.i("TvHome.Wall", "showFirst: SUCCESS! Displayed " + displayed + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            Log.w("TvHome.Wall", "showFirst: failed to decode " + first.getPath() + ", trying next");
            files.remove(first);
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
        Log.w("TvHome.Wall", "showFirst: NO valid wallpaper decoded among all files!");
    }

    void start() {
        Log.i("TvHome.Wall", "start called! files=" + files.size() + " interval=" + interval);
        active = true;
        scheduleNextRotation();
        startKenBurns(activeImage);
    }

    void stop() {
        Log.i("TvHome.Wall", "stop called!");
        active = false;
        handler.removeCallbacks(rotateRunnable);
        cancelKenBurns(activeImage);
        cancelKenBurns(idleImage);
    }

    private void scheduleNextRotation() {
        handler.removeCallbacks(rotateRunnable);
        Log.i("TvHome.Wall", "scheduleNextRotation: active=" + active + " closed=" + closed + " interval=" + interval + " files=" + files.size());
        if (active && !closed && interval > 0 && files.size() > 1) {
            handler.postDelayed(rotateRunnable, interval);
        }
    }

    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        handler.removeCallbacks(rotateRunnable);
        cancelKenBurns(activeImage);
        cancelKenBurns(idleImage);
        decoder.shutdownNow();
    }

    void update() {
        Log.i("TvHome.Wall", "update: active=" + active + " loading=" + loading);
        if (!active || closed || loading || files.isEmpty()) return;
        String prev = !displayed.isEmpty() ? displayed : preferences.getString("image", "");
        List<File> playable = playable();
        if (playable.isEmpty()) return;
        File selected = WallpaperRotation.ORDER_SEQUENTIAL.equals(order)
                ? WallpaperRotation.next(playable, prev)
                : WallpaperRotation.pick(playable, prev, order, random);
        if (selected == null) return;
        if (!WallpaperRotation.ORDER_SEQUENTIAL.equals(order) && selected.getName().equals(displayed) && playable.size() > 1) {
            for (int i = 0; i < 3 && selected.getName().equals(displayed); i++) {
                selected = WallpaperRotation.pick(playable, prev, order, random);
            }
        }
        File next = selected;
        int nextIdx = playable.indexOf(next);
        Log.i("TvHome.Wall", "update: picked next [" + (nextIdx >= 0 ? (nextIdx + 1) : 0) + "/" + playable.size() + "] = " + next.getName() + " (order=" + order + ")");
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wall", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap);
                displayed = next.getName();
                showCaption(true);
                preferences.edit().putString("image", displayed)
                        .putLong("changed", System.currentTimeMillis()).apply();
            });
        });
    }

    private void startKenBurns(ImageView view) {
        if (view == null) return;
        cancelKenBurns(view);
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
        long animDuration = Math.max(7000L, interval + 2000L);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.0f, 1.06f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.0f, 1.06f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY);
        set.setDuration(animDuration);
        set.setInterpolator(new LinearInterpolator());
        set.start();
        view.setTag(set);
    }

    private void cancelKenBurns(ImageView view) {
        if (view == null) return;
        Object tag = view.getTag();
        if (tag instanceof Animator) {
            ((Animator) tag).cancel();
            view.setTag(null);
        }
    }

    private void show(Bitmap bitmap) {
        final Bitmap old = current;
        current = bitmap;

        final ImageView incoming = idleImage;
        final ImageView outgoing = activeImage;

        incoming.setImageBitmap(bitmap);
        incoming.setVisibility(View.VISIBLE);
        incoming.setAlpha(0f);
        incoming.bringToFront();

        // 1. Continuous slow zoom-in Ken Burns animation on incoming view
        startKenBurns(incoming);

        // 2. Smooth crossfade: fade incoming in to 1.0f over 1200ms
        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(incoming, View.ALPHA, 0f, 1f);
        fadeIn.setDuration(1200);
        fadeIn.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                cancelKenBurns(outgoing);
                outgoing.setVisibility(View.INVISIBLE);
                outgoing.setImageBitmap(null);
                if (old != null && old != bitmap) old.recycle();
            }
        });
        fadeIn.start();

        activeImage = incoming;
        idleImage = outgoing;
    }

    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    private Bitmap decode(File file) {
        try {
            if (!file.exists() || !file.canRead()) {
                Log.e("TvHome.Wall", "decode: file cannot be read: " + file.getPath() + " exists=" + file.exists() + " canRead=" + file.canRead());
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) {
                Log.w("TvHome.Wall", "decode: invalid bounds " + width + "x" + height + " for " + file.getName());
                return null;
            }
            if (width < height) {
                Log.w("TvHome.Wall", "decode: skipping portrait file: " + file.getName() + " (" + width + "x" + height + ")");
                return null;
            }
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) {
                Log.w("TvHome.Wall", "decode: size not accepted: " + width + "x" + height + " sample=" + sample);
                return null;
            }
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) {
                Log.w("TvHome.Wall", "decode: BitmapFactory returned null for " + file.getName());
                return null;
            }
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            return decoded;
        } catch (Exception | OutOfMemoryError failure) {
            Log.e("TvHome.Wall", "decode error for " + file.getName(), failure);
            return null;
        }
    }
}
