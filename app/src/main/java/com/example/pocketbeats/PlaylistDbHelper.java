package com.example.pocketbeats;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;

public class PlaylistDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "PlaylistDbHelper";
    private static final String DB_NAME = "pocketbeats_playlists.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_PLAYLISTS = "playlists";
    private static final String TABLE_PLAYLIST_SONGS = "playlist_songs";

    private static final String COL_ID = "_id";
    private static final String COL_NAME = "name";
    private static final String COL_CREATED_AT = "created_at";
    private static final String COL_PLAYLIST_ID = "playlist_id";
    private static final String COL_SONG_PATH = "song_path";
    private static final String COL_POSITION = "position";

    public PlaylistDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_PLAYLISTS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_NAME + " TEXT NOT NULL UNIQUE, "
                + COL_CREATED_AT + " INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE " + TABLE_PLAYLIST_SONGS + " ("
                + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                + COL_PLAYLIST_ID + " INTEGER NOT NULL, "
                + COL_SONG_PATH + " TEXT NOT NULL, "
                + COL_POSITION + " INTEGER NOT NULL, "
                + "FOREIGN KEY(" + COL_PLAYLIST_ID + ") REFERENCES "
                + TABLE_PLAYLISTS + "(" + COL_ID + ") ON DELETE CASCADE)");
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAYLIST_SONGS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PLAYLISTS);
        onCreate(db);
    }

    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        try {
            db.execSQL("PRAGMA foreign_keys = ON");
        } catch (Exception e) {
            Log.e(TAG, "Error setting foreign keys pragma", e);
        }
    }

    public long createPlaylist(String name) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_NAME, name);
            values.put(COL_CREATED_AT, System.currentTimeMillis());
            return db.insert(TABLE_PLAYLISTS, null, values);
        } catch (Exception e) {
            Log.e(TAG, "Error creating playlist", e);
            return -1;
        }
    }

    public void deletePlaylist(String name) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            long playlistId = getPlaylistId(name);
            if (playlistId >= 0) {
                db.delete(TABLE_PLAYLIST_SONGS, COL_PLAYLIST_ID + "=?",
                        new String[]{String.valueOf(playlistId)});
                db.delete(TABLE_PLAYLISTS, COL_ID + "=?",
                        new String[]{String.valueOf(playlistId)});
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting playlist", e);
        }
    }

    public void renamePlaylist(String oldName, String newName) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_NAME, newName);
            db.update(TABLE_PLAYLISTS, values, COL_NAME + "=?", new String[]{oldName});
        } catch (Exception e) {
            Log.e(TAG, "Error renaming playlist", e);
        }
    }

    public void addSongToPlaylist(String playlistName, String songPath) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            long playlistId = getPlaylistId(playlistName);
            if (playlistId < 0) return;

            int nextPos = 0;
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT MAX(" + COL_POSITION + ") FROM " + TABLE_PLAYLIST_SONGS
                        + " WHERE " + COL_PLAYLIST_ID + "=?", new String[]{String.valueOf(playlistId)});
                if (cursor != null && cursor.moveToFirst()) {
                    nextPos = cursor.getInt(0) + 1;
                }
            } finally {
                if (cursor != null) cursor.close();
            }

            ContentValues values = new ContentValues();
            values.put(COL_PLAYLIST_ID, playlistId);
            values.put(COL_SONG_PATH, songPath);
            values.put(COL_POSITION, nextPos);
            db.insert(TABLE_PLAYLIST_SONGS, null, values);
        } catch (Exception e) {
            Log.e(TAG, "Error adding song to playlist", e);
        }
    }

    public void removeSongFromPlaylist(String playlistName, String songPath) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            long playlistId = getPlaylistId(playlistName);
            if (playlistId < 0) return;
            db.delete(TABLE_PLAYLIST_SONGS,
                    COL_PLAYLIST_ID + "=? AND " + COL_SONG_PATH + "=?",
                    new String[]{String.valueOf(playlistId), songPath});
        } catch (Exception e) {
            Log.e(TAG, "Error removing song from playlist", e);
        }
    }

    public ArrayList<String> getPlaylistSongPaths(String playlistName) {
        ArrayList<String> paths = new ArrayList<String>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            long playlistId = getPlaylistId(playlistName);
            if (playlistId < 0) return paths;

            Cursor cursor = null;
            try {
                cursor = db.query(TABLE_PLAYLIST_SONGS,
                        new String[]{COL_SONG_PATH},
                        COL_PLAYLIST_ID + "=?",
                        new String[]{String.valueOf(playlistId)},
                        null, null, COL_POSITION + " ASC");
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        paths.add(cursor.getString(0));
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting playlist songs", e);
        }
        return paths;
    }

    public ArrayList<String> getAllPlaylistNames() {
        ArrayList<String> names = new ArrayList<String>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            Cursor cursor = null;
            try {
                cursor = db.query(TABLE_PLAYLISTS, new String[]{COL_NAME},
                        null, null, null, null, COL_CREATED_AT + " ASC");
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        names.add(cursor.getString(0));
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting playlist names", e);
        }
        return names;
    }

    private long getPlaylistId(String name) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_PLAYLISTS, new String[]{COL_ID},
                    COL_NAME + "=?", new String[]{name},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }
}
