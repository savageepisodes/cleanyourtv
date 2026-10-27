package com.gathin.tvhome;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers cached fanarts and backdrops for the 50 newest movies and TV shows
 * from local Kodi databases. Completely local, zero dependencies, uses native Android SQLite.
 */
final class KodiArtwork {
    private static final String TAG = "TvHome.Kodi";
    private static final String KODI_DATA =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/Android/data/org.xbmc.kodi/files/.kodi/userdata";

    private static final String KODI_DATA_DIRECT =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/KodiData";

    private static final String KODI_WALLPAPERS =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/KodiWallpapers";

    private static List<File> cachedList = null;
    private static final Map<String, String> TITLES = new HashMap<>();
    private static long lastScanTime = 0;
    private static final long SCAN_CACHE_MS = 60 * 1000L;

    static synchronized Map<String, String> getTitles() {
        return Collections.unmodifiableMap(TITLES);
    }

    static synchronized void invalidateCache() {
        cachedList = null;
        lastScanTime = 0;
    }

    static synchronized List<File> getLandscapeArtwork() {
        return getFanartArtwork();
    }

    static synchronized List<File> getFanartArtwork() {
        if (cachedList != null && !cachedList.isEmpty() && (System.currentTimeMillis() - lastScanTime < SCAN_CACHE_MS)) {
            return cachedList;
        }

        List<File> result = new ArrayList<>();

        // Locate Kodi database directory and thumbnail directories
        File directDb = new File(KODI_DATA_DIRECT);
        File directDir = new File(KODI_WALLPAPERS);
        File kodiDataThumbDir = new File(KODI_DATA_DIRECT, "Thumbnails");

        File dbDir = null;
        if (new File(directDb, "Textures13.db").exists()) {
            dbDir = directDb;
        } else {
            File kodiDb = new File(KODI_DATA, "Database");
            if (kodiDb.exists()) {
                dbDir = kodiDb;
            }
        }

        File thumbDir = kodiDataThumbDir.exists() ? kodiDataThumbDir : new File(KODI_DATA, "Thumbnails");

        File videoDb = null;
        int maxVer = -1;
        if (dbDir.exists()) {
            File[] dbFiles = dbDir.listFiles((dir, name) -> name.startsWith("MyVideos") && name.endsWith(".db"));
            if (dbFiles != null) {
                for (File f : dbFiles) {
                    String num = f.getName().replace("MyVideos", "").replace(".db", "");
                    try {
                        int ver = Integer.parseInt(num);
                        if (ver > maxVer) {
                            maxVer = ver;
                            videoDb = f;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        File texturesDb = dbDir.exists() ? new File(dbDir, "Textures13.db") : null;

        if (videoDb != null && videoDb.exists() && texturesDb != null && texturesDb.exists()) {
            try {
                SQLiteDatabase vDb = SQLiteDatabase.openDatabase(
                        videoDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                SQLiteDatabase tDb = SQLiteDatabase.openDatabase(
                        texturesDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

                try {
                    // Query the newest movies and TV shows with fanart, ordered by dateAdded DESC
                    String movieQuery = "SELECT m.c00 AS title, f.dateAdded AS date_added, a.url AS fanart_url, 'movie' AS type "
                            + "FROM movie m "
                            + "JOIN files f ON m.idFile = f.idFile "
                            + "JOIN art a ON (a.media_id = m.idMovie AND a.media_type = 'movie' AND a.type = 'fanart') "
                            + "WHERE a.url IS NOT NULL AND a.url != '' ";

                    String tvQuery = "SELECT s.c00 AS title, "
                            + "COALESCE((SELECT MAX(ef.dateAdded) FROM episode e JOIN files ef ON e.idFile = ef.idFile WHERE e.idShow = s.idShow), s.c05) AS date_added, "
                            + "a.url AS fanart_url, 'tvshow' AS type "
                            + "FROM tvshow s "
                            + "JOIN art a ON (a.media_id = s.idShow AND a.media_type = 'tvshow' AND a.type = 'fanart') "
                            + "WHERE a.url IS NOT NULL AND a.url != '' ";

                    String combinedSql = "SELECT title, date_added, fanart_url, type FROM ("
                            + movieQuery + "UNION ALL " + tvQuery + ") "
                            + "ORDER BY date_added DESC LIMIT 80";

                    List<String> fanartUrls = new ArrayList<>();
                    Map<String, String> urlToTitle = new HashMap<>();
                    Cursor c = vDb.rawQuery(combinedSql, null);
                    try {
                        while (c.moveToNext()) {
                            String title = c.getString(0);
                            String url = c.getString(2);
                            if (url != null && !url.isEmpty()) {
                                fanartUrls.add(url);
                                if (title != null) urlToTitle.put(url, title);
                            }
                        }
                    } finally {
                        c.close();
                    }

                    TITLES.clear();
                    Set<String> seenPaths = new HashSet<>();
                    for (String artUrl : fanartUrls) {
                        if (result.size() >= 50) break;
                        Cursor tc = tDb.rawQuery("SELECT cachedurl FROM texture WHERE url = ? LIMIT 1", new String[]{artUrl});
                        try {
                            if (tc.moveToFirst()) {
                                String cached = tc.getString(0);
                                if (cached != null && !cached.isEmpty()) {
                                    File img = resolveImageFile(cached, thumbDir, directDir, kodiDataThumbDir);
                                    if (img != null && seenPaths.add(img.getAbsolutePath())) {
                                        result.add(img);
                                        String title = urlToTitle.get(artUrl);
                                        if (title != null) {
                                            TITLES.put(img.getName(), title);
                                        }
                                    }
                                }
                            }
                        } finally {
                            tc.close();
                        }
                    }
                    Log.i(TAG, "Loaded " + result.size() + " fanarts from 50 newest movies & tv shows (ordered, not random).");
                } finally {
                    try { vDb.close(); } catch (Exception ignored) {}
                    try { tDb.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "Error querying newest Kodi media: " + e.getMessage(), e);
            }
        }

        // Fallback 1: KodiWallpapers directory sorted by newest first
        if (result.isEmpty() && directDir.exists()) {
            File[] files = directDir.listFiles((dir, name) -> name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".webp"));
            if (files != null && files.length > 0) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) {
                    if (f.length() > 50000) {
                        result.add(f);
                        if (result.size() >= 50) break;
                    }
                }
            }
        }

        // Fallback 2: Thumbnail scan sorted by newest first
        if (result.isEmpty() && thumbDir.exists()) {
            List<File> allThumbs = new ArrayList<>();
            File[] subdirs = thumbDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                for (File sub : subdirs) {
                    File[] imgs = sub.listFiles((d, name) -> name.endsWith(".jpg") || name.endsWith(".png"));
                    if (imgs != null) {
                        for (File img : imgs) {
                            if (img.length() > 50000) allThumbs.add(img);
                        }
                    }
                }
            }
            allThumbs.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : allThumbs) {
                result.add(f);
                if (result.size() >= 50) break;
            }
        }

        cachedList = result;
        lastScanTime = System.currentTimeMillis();
        return result;
    }

    private static File resolveImageFile(String cached, File thumbDir, File directDir, File kodiDataThumbDir) {
        if (cached == null || cached.isEmpty()) return null;

        String simpleName = new File(cached).getName();
        File fDirect = new File(directDir, simpleName);
        if (fDirect.exists() && fDirect.length() > 1024) return fDirect;

        File f1 = new File(thumbDir, cached);
        if (f1.exists() && f1.length() > 1024) return f1;

        File f3 = new File(kodiDataThumbDir, cached);
        if (f3.exists() && f3.length() > 1024) return f3;

        return null;
    }
}
