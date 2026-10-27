package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers and populates Channel rows from Kodi user playlists (.xsp) and in-progress movies.
 * Lightweight, reads Kodi SQLite databases directly, zero external dependencies.
 */
public final class KodiChannelProvider {
    private static final String TAG = "TvHome.KodiChannel";

    private static final String KODI_DATA_DIRECT =
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/KodiData";

    private static final String KODI_USERDATA =
            Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/Android/data/org.xbmc.kodi/files/.kodi/userdata";

    private static final String KODI_PACKAGE = "org.xbmc.kodi";

    private KodiChannelProvider() {}

    public static List<Channel> loadChannels(Context context) {
        List<Channel> channels = new ArrayList<>();

        File[] candidateDirs = new File[] {
            new File(KODI_DATA_DIRECT),
            new File(KODI_USERDATA, "Database"),
            new File(KODI_USERDATA)
        };
        File dbDir = new File(KODI_DATA_DIRECT);
        File thumbnailsDir = new File(KODI_DATA_DIRECT, "Thumbnails");
        File playlistDir = new File(KODI_DATA_DIRECT, "playlists/video");

        SQLiteDatabase vDb = null;
        SQLiteDatabase tDb = null;

        for (File dir : candidateDirs) {
            File found = findLatestDb(dir, "MyVideos");
            if (found != null && found.exists()) {
                try {
                    vDb = SQLiteDatabase.openDatabase(found.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                    dbDir = dir;
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "Cannot open " + found.getAbsolutePath() + ": " + e.getMessage());
                }
            }
        }

        if (vDb == null) {
            Log.w(TAG, "MyVideos DB not found or cannot be opened");
            return channels;
        }

        File texturesDb = findLatestDb(dbDir, "Textures");
        if (texturesDb == null || !texturesDb.exists()) {
            texturesDb = findLatestDb(new File(KODI_DATA_DIRECT), "Textures");
        }
        if (texturesDb != null && texturesDb.exists()) {
            try {
                tDb = SQLiteDatabase.openDatabase(texturesDb.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            } catch (Exception ignored) {}
        }

        // Check for playlist dirs
        File[] candidatePlaylistDirs = new File[] {
            new File(KODI_DATA_DIRECT, "playlists/video"),
            new File(KODI_USERDATA, "playlists/video")
        };
        for (File pDir : candidatePlaylistDirs) {
            if (pDir.exists()) {
                File[] found = pDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xsp"));
                if (found != null && found.length > 0) {
                    playlistDir = pDir;
                    break;
                }
            }
        }

        Drawable kodiIcon = null;
        try {
            kodiIcon = context.getPackageManager().getApplicationIcon(KODI_PACKAGE);
        } catch (PackageManager.NameNotFoundException ignored) {}

        try {
            int order = 0;

            // 1. "Continue Watching" Channel
            List<ChannelItem> inProgressItems = queryInProgressMovies(context, vDb, tDb, thumbnailsDir);
            if (!inProgressItems.isEmpty()) {
                channels.add(new Channel(
                        "kodi_continue_watching",
                        "Continue Watching",
                        "Kodi",
                        kodiIcon,
                        inProgressItems,
                        true,
                        order++
                ));
            }

            // 2. Discover user Smart Playlists (.xsp)
            List<File> xspFiles = new ArrayList<>();
            if (playlistDir.exists()) {
                File[] found = playlistDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xsp"));
                if (found != null) {
                    Collections.addAll(xspFiles, found);
                }
            }
            File directPlaylists = new File(KODI_DATA_DIRECT, "playlists/video");
            if (!directPlaylists.equals(playlistDir) && directPlaylists.exists()) {
                File[] found = directPlaylists.listFiles((dir, name) -> name.toLowerCase().endsWith(".xsp"));
                if (found != null) {
                    for (File f : found) {
                        boolean already = false;
                        for (File existing : xspFiles) {
                            if (existing.getName().equalsIgnoreCase(f.getName())) {
                                already = true;
                                break;
                            }
                        }
                        if (!already) xspFiles.add(f);
                    }
                }
            }

            for (File xsp : xspFiles) {
                PlaylistInfo info = parseXsp(xsp);
                if (info != null) {
                    List<ChannelItem> items = queryPlaylistMovies(context, vDb, tDb, thumbnailsDir, info);
                    channels.add(new Channel(
                            "kodi_playlist_" + xsp.getName().replace(".xsp", "").toLowerCase(),
                            info.name,
                            "Kodi",
                            kodiIcon,
                            items,
                            true,
                            order++
                    ));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading Kodi channels: " + e.getMessage(), e);
        } finally {
            if (vDb != null) {
                try { vDb.close(); } catch (Exception ignored) {}
            }
            if (tDb != null) {
                try { tDb.close(); } catch (Exception ignored) {}
            }
        }

        return channels;
    }

    private static List<ChannelItem> queryInProgressMovies(
            Context context, SQLiteDatabase vDb, SQLiteDatabase tDb, File thumbnailsDir) {
        List<ChannelItem> items = new ArrayList<>();
        String sql = "SELECT m.idMovie, m.c00, m.premiered, p.strPath, f.strFilename, "
                + "COALESCE("
                + "(SELECT url FROM art WHERE media_id = m.idMovie AND media_type = 'movie' AND type = 'poster' LIMIT 1), "
                + "(SELECT url FROM art WHERE media_id = m.idMovie AND media_type = 'movie' AND type = 'thumb' LIMIT 1)"
                + ") AS art_url "
                + "FROM bookmark b "
                + "JOIN files f ON b.idFile = f.idFile "
                + "JOIN movie m ON m.idFile = f.idFile "
                + "JOIN path p ON f.idPath = p.idPath "
                + "WHERE b.timeInSeconds > 0 "
                + "ORDER BY f.dateAdded DESC LIMIT 20";

        Cursor cursor = null;
        try {
            cursor = vDb.rawQuery(sql, null);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String title = cursor.getString(1);
                String premiered = cursor.getString(2);
                String strPath = cursor.getString(3);
                String strFilename = cursor.getString(4);
                String artUrl = cursor.getString(5);

                String year = extractYear(premiered);
                String posterPath = resolvePosterPath(artUrl, tDb, thumbnailsDir);
                Intent launchIntent = createMovieIntent(id, strPath, strFilename);

                items.add(new ChannelItem(String.valueOf(id), title, year, posterPath, launchIntent));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error querying in-progress movies: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    private static List<ChannelItem> queryPlaylistMovies(
            Context context, SQLiteDatabase vDb, SQLiteDatabase tDb, File thumbnailsDir, PlaylistInfo info) {
        List<ChannelItem> items = new ArrayList<>();

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT m.idMovie, m.c00, m.premiered, p.strPath, f.strFilename, ")
           .append("COALESCE(")
           .append("(SELECT url FROM art WHERE media_id = m.idMovie AND media_type = 'movie' AND type = 'poster' LIMIT 1), ")
           .append("(SELECT url FROM art WHERE media_id = m.idMovie AND media_type = 'movie' AND type = 'thumb' LIMIT 1)")
           .append(") AS art_url ")
           .append("FROM movie m ")
           .append("JOIN files f ON m.idFile = f.idFile ")
           .append("JOIN path p ON f.idPath = p.idPath ");

        List<String> whereClauses = new ArrayList<>();
        List<String> args = new ArrayList<>();

        if (info.genreIs != null && !info.genreIs.isEmpty()) {
            whereClauses.add("m.c14 LIKE ?");
            args.add("%" + info.genreIs + "%");
        }
        if (info.genreIsNot != null && !info.genreIsNot.isEmpty()) {
            whereClauses.add("(m.c14 NOT LIKE ? OR m.c14 IS NULL)");
            args.add("%" + info.genreIsNot + "%");
        }

        if (!whereClauses.isEmpty()) {
            sql.append("WHERE ").append(join(" AND ", whereClauses)).append(" ");
        }

        sql.append("ORDER BY f.dateAdded DESC LIMIT 20");

        Cursor cursor = null;
        try {
            cursor = vDb.rawQuery(sql.toString(), args.toArray(new String[0]));
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String title = cursor.getString(1);
                String premiered = cursor.getString(2);
                String strPath = cursor.getString(3);
                String strFilename = cursor.getString(4);
                String artUrl = cursor.getString(5);

                String year = extractYear(premiered);
                String posterPath = resolvePosterPath(artUrl, tDb, thumbnailsDir);
                Intent launchIntent = createMovieIntent(id, strPath, strFilename);

                items.add(new ChannelItem(String.valueOf(id), title, year, posterPath, launchIntent));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error querying playlist movies for " + info.name + ": " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return items;
    }

    private static String resolvePosterPath(String artUrl, SQLiteDatabase tDb, File thumbnailsDir) {
        if (artUrl == null || artUrl.isEmpty()) return null;

        // 1. Direct local file on disk
        if (artUrl.startsWith("/")) {
            File f = new File(artUrl);
            if (f.exists() && f.length() > 0) return f.getAbsolutePath();
        }

        // 2. Lookup in Textures DB
        if (tDb != null) {
            Cursor c = null;
            try {
                c = tDb.rawQuery("SELECT cachedurl FROM texture WHERE url = ? LIMIT 1", new String[]{artUrl});
                if (c.moveToFirst()) {
                    String cached = c.getString(0);
                    if (cached != null && !cached.isEmpty()) {
                        File[] candidates = new File[] {
                            thumbnailsDir,
                            new File(KODI_USERDATA, "Thumbnails"),
                            new File(KODI_DATA_DIRECT, "Thumbnails")
                        };
                        for (File dir : candidates) {
                            if (dir != null && dir.exists()) {
                                File thumb = new File(dir, cached);
                                if (thumb.exists() && thumb.length() > 0) {
                                    return thumb.getAbsolutePath();
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
        }

        return null;
    }

    private static Intent createMovieIntent(long idMovie, String path, String filename) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage(KODI_PACKAGE);
        intent.setClassName(KODI_PACKAGE, "org.xbmc.kodi.Splash");
        if (idMovie > 0) {
            intent.setData(Uri.parse("videodb://movies/titles/" + idMovie + "?showinfo=true"));
        } else {
            String fullPath = "";
            if (path != null) fullPath += path;
            if (filename != null) fullPath += filename;
            if (!fullPath.isEmpty()) {
                intent.setDataAndType(Uri.parse("file://" + fullPath), "video/*");
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private static String extractYear(String premiered) {
        if (premiered != null && premiered.length() >= 4) {
            return premiered.substring(0, 4);
        }
        return "";
    }

    private static File findLatestDb(File dir, String prefix) {
        if (!dir.exists()) return null;
        File direct131 = new File(dir, prefix + "131.db");
        if (direct131.exists()) return direct131;
        File direct13 = new File(dir, prefix + "13.db");
        if (direct13.exists()) return direct13;
        File[] dbs = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".db"));
        if (dbs == null || dbs.length == 0) return null;
        File best = dbs[0];
        for (File db : dbs) {
            if (db.getName().compareTo(best.getName()) > 0) {
                best = db;
            }
        }
        return best;
    }

    private static class PlaylistInfo {
        String name;
        String genreIs;
        String genreIsNot;
    }

    private static PlaylistInfo parseXsp(File xspFile) {
        PlaylistInfo info = new PlaylistInfo();
        info.name = xspFile.getName().replace(".xsp", "");

        InputStream in = null;
        try {
            in = new FileInputStream(xspFile);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "UTF-8");

            int event = parser.getEventType();
            String currentTag = "";
            String ruleField = "";
            String ruleOp = "";

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    currentTag = parser.getName();
                    if ("rule".equalsIgnoreCase(currentTag)) {
                        ruleField = parser.getAttributeValue(null, "field");
                        ruleOp = parser.getAttributeValue(null, "operator");
                    }
                } else if (event == XmlPullParser.TEXT) {
                    String text = parser.getText() != null ? parser.getText().trim() : "";
                    if (!text.isEmpty()) {
                        if ("name".equalsIgnoreCase(currentTag)) {
                            info.name = text;
                        } else if ("value".equalsIgnoreCase(currentTag)) {
                            if ("genre".equalsIgnoreCase(ruleField)) {
                                if ("is".equalsIgnoreCase(ruleOp)) {
                                    info.genreIs = text;
                                } else if ("isnot".equalsIgnoreCase(ruleOp)) {
                                    info.genreIsNot = text;
                                }
                            }
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    currentTag = "";
                }
                event = parser.next();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error parsing XSP " + xspFile.getName() + ": " + e.getMessage());
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }

        // Capitalize default name if needed
        if (info.name == null || info.name.isEmpty()) {
            info.name = xspFile.getName().replace(".xsp", "");
        }
        return info;
    }

    private static String join(String delim, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(delim);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
