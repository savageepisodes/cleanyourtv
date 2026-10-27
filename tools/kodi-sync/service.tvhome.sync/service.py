import os
import shutil
import time
import xbmc
import xbmcvfs

TAG = "TvHomeSync"

def log(msg):
    try:
        xbmc.log(f"[{TAG}] {msg}", xbmc.LOGINFO)
    except Exception:
        pass

def get_userdata_path():
    try:
        return xbmcvfs.translatePath('special://userdata/')
    except Exception:
        return os.path.expanduser('~/.kodi/userdata/')

def find_latest_db(db_dir, prefix):
    if not os.path.exists(db_dir):
        return None
    try:
        candidates = [f for f in os.listdir(db_dir) if f.startswith(prefix) and f.endswith('.db')]
        if not candidates:
            return None
        candidates.sort(reverse=True)
        return os.path.join(db_dir, candidates[0])
    except Exception:
        return None

def do_sync():
    try:
        userdata = get_userdata_path()
        db_dir = os.path.join(userdata, 'Database')
        thumb_dir = os.path.join(userdata, 'Thumbnails')
        playlist_dir = os.path.join(userdata, 'playlists', 'video')

        dest_dir = '/sdcard/KodiData'
        dest_playlists = os.path.join(dest_dir, 'playlists', 'video')
        dest_thumbs = os.path.join(dest_dir, 'Thumbnails')

        os.makedirs(dest_dir, exist_ok=True)
        os.makedirs(dest_playlists, exist_ok=True)
        os.makedirs(dest_thumbs, exist_ok=True)

        # 1. Sync MyVideos and Textures databases
        for prefix in ['MyVideos', 'Textures']:
            latest = find_latest_db(db_dir, prefix)
            if latest and os.path.exists(latest):
                dest_file = os.path.join(dest_dir, os.path.basename(latest))
                try:
                    src_mtime = os.path.getmtime(latest)
                    src_size = os.path.getsize(latest)
                    dst_mtime = os.path.getmtime(dest_file) if os.path.exists(dest_file) else 0
                    dst_size = os.path.getsize(dest_file) if os.path.exists(dest_file) else 0
                    if src_size != dst_size or abs(src_mtime - dst_mtime) > 1.0:
                        shutil.copy2(latest, dest_file)
                        log(f"Synced {os.path.basename(latest)}")
                except Exception as e:
                    log(f"Error copying {latest}: {e}")

        # 2. Sync Playlists (.xsp)
        if os.path.exists(playlist_dir):
            for f in os.listdir(playlist_dir):
                if f.lower().endswith('.xsp'):
                    src_p = os.path.join(playlist_dir, f)
                    dst_p = os.path.join(dest_playlists, f)
                    try:
                        if not os.path.exists(dst_p) or os.path.getmtime(src_p) != os.path.getmtime(dst_p):
                            shutil.copy2(src_p, dst_p)
                            log(f"Synced playlist {f}")
                    except Exception as e:
                        log(f"Error copying playlist {f}: {e}")

        # 3. Sync New Thumbnails
        if os.path.exists(thumb_dir):
            copied_count = 0
            for sub in os.listdir(thumb_dir):
                sub_src = os.path.join(thumb_dir, sub)
                if os.path.isdir(sub_src):
                    sub_dst = os.path.join(dest_thumbs, sub)
                    os.makedirs(sub_dst, exist_ok=True)
                    for tf in os.listdir(sub_src):
                        src_tf = os.path.join(sub_src, tf)
                        dst_tf = os.path.join(sub_dst, tf)
                        if not os.path.exists(dst_tf):
                            try:
                                shutil.copy2(src_tf, dst_tf)
                                copied_count += 1
                            except Exception:
                                pass
            if copied_count > 0:
                log(f"Synced {copied_count} new thumbnails")

    except Exception as e:
        log(f"do_sync error: {e}")

class TvHomeSyncMonitor(xbmc.Monitor):
    def __init__(self):
        super().__init__()

    def onScanFinished(self, library):
        if library == 'video':
            log("Video scan finished, running sync")
            do_sync()

    def onCleanFinished(self, library):
        if library == 'video':
            log("Video clean finished, running sync")
            do_sync()

    def onScreensaverActivated(self):
        log("Screensaver activated, running sync")
        do_sync()

class TvHomeSyncPlayer(xbmc.Player):
    def __init__(self):
        super().__init__()

    def onPlayBackStopped(self):
        log("Playback stopped, running sync")
        do_sync()

if __name__ == '__main__':
    log("TvHome Sync service started")
    monitor = TvHomeSyncMonitor()
    player = TvHomeSyncPlayer()

    # Initial sync
    do_sync()

    userdata = get_userdata_path()
    db_dir = os.path.join(userdata, 'Database')

    def get_mv_mtime():
        latest = find_latest_db(db_dir, 'MyVideos')
        if latest and os.path.exists(latest):
            try:
                return os.path.getmtime(latest)
            except Exception:
                pass
        return 0

    last_mtime = get_mv_mtime()

    # Loop with check every 30 seconds
    while not monitor.abortRequested():
        if monitor.waitForAbort(30):
            break
        cur_mtime = get_mv_mtime()
        if cur_mtime != last_mtime:
            log("MyVideos mtime changed, syncing")
            last_mtime = cur_mtime
            do_sync()

    log("TvHome Sync service stopped")
