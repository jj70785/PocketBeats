package com.example.pocketbeats;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;

public class PlaylistHelper {

    private static final String TAG = "PlaylistHelper";

    public static void showAddToPlaylistDialog(final Activity activity, final String songPath) {
        final PlaylistDbHelper db;
        final ArrayList<String> playlists;
        try {
            db = new PlaylistDbHelper(activity);
            playlists = db.getAllPlaylistNames();
        } catch (Exception e) {
            Log.e(TAG, "Cannot open playlist database", e);
            Toast.makeText(activity, "Cannot open playlists (storage full?)", Toast.LENGTH_LONG).show();
            return;
        }

        if (playlists.isEmpty()) {
            showCreateAndAddDialog(activity, songPath, db);
            return;
        }

        String[] items = new String[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            items[i] = playlists.get(i);
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.add_to_playlist)
                .setItems(items, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            String playlistName = playlists.get(which);
                            db.addSongToPlaylist(playlistName, songPath);
                            Toast.makeText(activity, R.string.added_to_playlist, Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error adding to playlist", e);
                            Toast.makeText(activity, "Error adding to playlist", Toast.LENGTH_SHORT).show();
                        }
                        db.close();
                    }
                })
                .setNeutralButton(R.string.create_playlist, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        showCreateAndAddDialog(activity, songPath, db);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        db.close();
                    }
                })
                .show();
    }

    private static void showCreateAndAddDialog(final Activity activity, final String songPath,
                                                final PlaylistDbHelper db) {
        final EditText input = new EditText(activity);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_FILTER);
        input.setHint(R.string.playlist_name_hint);
        input.setSingleLine(true);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.create_playlist)
                .setView(input)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            String name = input.getText().toString().trim();
                            if (name.length() > 0) {
                                db.createPlaylist(name);
                                db.addSongToPlaylist(name, songPath);
                                Toast.makeText(activity, R.string.added_to_playlist, Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error creating playlist", e);
                            Toast.makeText(activity, "Error creating playlist (storage full?)", Toast.LENGTH_LONG).show();
                        }
                        db.close();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        db.close();
                    }
                })
                .show();
    }
}
