package com.example.pocketbeats;

import android.graphics.Bitmap;

import java.util.LinkedHashMap;
import java.util.Map;

public class AlbumArtCache {

    private static final int MAX_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    private static AlbumArtCache instance;

    // Sentinel bitmap to mark album IDs with no art (prevents repeated failed loads)
    private static final Bitmap NO_ART_SENTINEL = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8);

    private final LinkedHashMap<Long, Bitmap> cache;
    private int currentSizeBytes = 0;

    private AlbumArtCache() {
        cache = new LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                if (currentSizeBytes > MAX_SIZE_BYTES) {
                    Bitmap evicted = eldest.getValue();
                    if (evicted != null && evicted != NO_ART_SENTINEL) {
                        currentSizeBytes -= getBitmapSize(evicted);
                        evicted.recycle();
                    }
                    return true;
                }
                return false;
            }
        };
    }

    public static synchronized AlbumArtCache getInstance() {
        if (instance == null) {
            instance = new AlbumArtCache();
        }
        return instance;
    }

    public synchronized Bitmap get(long albumId) {
        Bitmap bmp = cache.get(albumId);
        if (bmp == NO_ART_SENTINEL) {
            return null;
        }
        return bmp;
    }

    public synchronized boolean contains(long albumId) {
        return cache.containsKey(albumId);
    }

    public synchronized void put(long albumId, Bitmap bitmap) {
        if (bitmap == null) return;
        // Remove old entry if exists
        Bitmap old = cache.remove(albumId);
        if (old != null && old != NO_ART_SENTINEL) {
            currentSizeBytes -= getBitmapSize(old);
            old.recycle();
        }
        currentSizeBytes += getBitmapSize(bitmap);
        cache.put(albumId, bitmap);
    }

    public synchronized void putNoArt(long albumId) {
        Bitmap old = cache.remove(albumId);
        if (old != null && old != NO_ART_SENTINEL) {
            currentSizeBytes -= getBitmapSize(old);
            old.recycle();
        }
        cache.put(albumId, NO_ART_SENTINEL);
    }

    public synchronized boolean isNoArt(long albumId) {
        return cache.get(albumId) == NO_ART_SENTINEL;
    }

    private static int getBitmapSize(Bitmap bmp) {
        if (bmp == null || bmp == NO_ART_SENTINEL) return 0;
        return bmp.getRowBytes() * bmp.getHeight();
    }
}
