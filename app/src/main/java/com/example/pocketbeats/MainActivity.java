package com.example.pocketbeats;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final int SORT_TITLE = 0;
    private static final int SORT_ARTIST = 1;
    private static final int SORT_ALBUM = 2;

    private ArrayList<Song> allSongs = new ArrayList<Song>();
    private ArrayList<Song> filteredSongs = new ArrayList<Song>();
    private SongAdapter adapter;
    private MusicService musicService;
    private boolean serviceBound = false;
    private int currentSort = SORT_TITLE;

    private ListView songListView;
    private TextView songCount;
    private TextView noMusicText;
    private TextView searchLabel;
    private Button searchButton;
    private Button clearButton;
    private Button sortButton;
    private String currentQuery = "";

    private boolean autoPlayPending = false;
    private int autoPlayIndex = -1;
    private final Handler searchHandler = new Handler();
    private Runnable searchRunnable;

    private ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;
            musicService.setSongList(filteredSongs);
            Log.i(TAG, "Service bound, songs in queue: " + filteredSongs.size());

            if (autoPlayPending && autoPlayIndex >= 0 && autoPlayIndex < filteredSongs.size()) {
                Log.i(TAG, "Auto-playing song at index " + autoPlayIndex);
                musicService.playSongAtIndex(autoPlayIndex);
                autoPlayPending = false;
                Intent playerIntent = new Intent(MainActivity.this, PlayerActivity.class);
                startActivity(playerIntent);
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        songListView = (ListView) findViewById(R.id.songList);
        songCount = (TextView) findViewById(R.id.songCount);
        noMusicText = (TextView) findViewById(R.id.noMusicText);
        searchLabel = (TextView) findViewById(R.id.searchLabel);
        searchButton = (Button) findViewById(R.id.searchButton);
        clearButton = (Button) findViewById(R.id.clearButton);
        sortButton = (Button) findViewById(R.id.sortButton);

        adapter = new SongAdapter(this, filteredSongs);
        songListView.setAdapter(adapter);

        songCount.setText("Loading...");
        noMusicText.setVisibility(View.GONE);

        // Load songs on background thread to avoid blocking UI
        new Thread(new Runnable() {
            public void run() {
                loadSongs();
                sortSongs();
                searchHandler.post(new Runnable() {
                    public void run() {
                        updateFilteredList("");
                        updateUI();
                    }
                });
            }
        }).start();

        songListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (serviceBound) {
                    musicService.setSongList(filteredSongs);
                    musicService.playSongAtIndex(position);
                    Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                    startActivity(intent);
                }
            }
        });

        searchButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSearchDialog();
            }
        });

        clearButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentQuery = "";
                searchLabel.setText(R.string.search_hint);
                clearButton.setVisibility(View.GONE);
                updateFilteredList("");
            }
        });

        sortButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentSort = (currentSort + 1) % 3;
                sortSongs();
                updateFilteredList(currentQuery);
                updateSortButtonText();
            }
        });

        updateSortButtonText();

        // Check for auto-play intent
        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.hasExtra("autoplay")) {
            autoPlayIndex = launchIntent.getIntExtra("autoplay", 0);
            autoPlayPending = true;
            Log.i(TAG, "Auto-play requested for index: " + autoPlayIndex);
        }

        // Start and bind to service
        Intent serviceIntent = new Intent(this, MusicService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    protected void onResume() {
        super.onResume();
        if (!serviceBound) {
            Intent serviceIntent = new Intent(this, MusicService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void loadSongs() {
        allSongs.clear();
        ContentResolver resolver = getContentResolver();
        Uri[] uris = {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        };
        String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 OR "
                + MediaStore.Audio.Media.MIME_TYPE + " LIKE 'audio/%'";
        java.util.HashSet<String> seenPaths = new java.util.HashSet<String>();
        for (int u = 0; u < uris.length; u++) {
            Cursor cursor = null;
            try {
                cursor = resolver.query(uris[u], projection, selection, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                    int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                    int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                    int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                    int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                    int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                    int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                    do {
                        String path = cursor.getString(dataCol);
                        if (path != null && !seenPaths.contains(path)) {
                            seenPaths.add(path);
                            long id = cursor.getLong(idCol);
                            String title = cursor.getString(titleCol);
                            String artist = cursor.getString(artistCol);
                            String album = cursor.getString(albumCol);
                            long albumId = cursor.getLong(albumIdCol);
                            long duration = cursor.getLong(durationCol);
                            allSongs.add(new Song(id, title, artist, album, albumId, path, duration));
                        }
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading songs from " + uris[u], e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        Log.i(TAG, "Loaded " + allSongs.size() + " songs from MediaStore");

        // Normalize MediaStore paths for dedup (/sdcard -> /mnt/sdcard)
        java.util.HashSet<String> knownPaths = new java.util.HashSet<String>();
        for (int i = 0; i < allSongs.size(); i++) {
            String p = allSongs.get(i).getPath();
            knownPaths.add(normalizePath(p));
        }

        // Scan filesystem for audio files MediaStore missed
        String[] scanDirs = {
            "/mnt/emmc/Music",
            "/mnt/sdcard/Music"
        };
        int fsCount = 0;
        for (int d = 0; d < scanDirs.length; d++) {
            java.io.File dir = new java.io.File(scanDirs[d]);
            if (dir.exists() && dir.isDirectory()) {
                java.io.File[] files = dir.listFiles();
                if (files != null) {
                    for (int f = 0; f < files.length; f++) {
                        java.io.File file = files[f];
                        if (!file.isFile()) continue;
                        String filePath = file.getAbsolutePath();
                        if (knownPaths.contains(normalizePath(filePath))) continue;
                        String name = file.getName().toLowerCase();
                        if (!name.endsWith(".mp3") && !name.endsWith(".m4a")
                                && !name.endsWith(".ogg") && !name.endsWith(".wav")
                                && !name.endsWith(".flac") && !name.endsWith(".aac")
                                && !name.endsWith(".wma")) {
                            continue;
                        }
                        knownPaths.add(normalizePath(filePath));
                        // Use filename without extension as title
                        String title = file.getName();
                        int dotIdx = title.lastIndexOf('.');
                        if (dotIdx > 0) {
                            title = title.substring(0, dotIdx);
                        }
                        allSongs.add(new Song(filePath.hashCode(), title,
                                "Unknown", "Unknown", 0, filePath, 0));
                        fsCount++;
                    }
                }
            }
        }
        if (fsCount > 0) {
            Log.i(TAG, "Found " + fsCount + " additional songs from filesystem scan");
        }
        Log.i(TAG, "Total songs: " + allSongs.size());
    }

    private static String normalizePath(String path) {
        if (path != null && path.startsWith("/sdcard/")) {
            return "/mnt/sdcard/" + path.substring(8);
        }
        return path;
    }

    private void sortSongs() {
        Comparator<Song> comparator;
        switch (currentSort) {
            case SORT_ARTIST:
                comparator = new Comparator<Song>() {
                    public int compare(Song a, Song b) {
                        return a.getArtist().compareToIgnoreCase(b.getArtist());
                    }
                };
                break;
            case SORT_ALBUM:
                comparator = new Comparator<Song>() {
                    public int compare(Song a, Song b) {
                        return a.getAlbum().compareToIgnoreCase(b.getAlbum());
                    }
                };
                break;
            default:
                comparator = new Comparator<Song>() {
                    public int compare(Song a, Song b) {
                        return a.getTitle().compareToIgnoreCase(b.getTitle());
                    }
                };
                break;
        }
        Collections.sort(allSongs, comparator);
    }

    private void updateFilteredList(String query) {
        filteredSongs.clear();
        if (query == null || query.length() == 0) {
            filteredSongs.addAll(allSongs);
        } else {
            String lowerQuery = query.toLowerCase();
            for (int i = 0; i < allSongs.size(); i++) {
                Song song = allSongs.get(i);
                if (song.getTitle().toLowerCase().contains(lowerQuery)
                        || song.getArtist().toLowerCase().contains(lowerQuery)
                        || song.getAlbum().toLowerCase().contains(lowerQuery)) {
                    filteredSongs.add(song);
                }
            }
        }
        adapter.notifyDataSetChanged();
        updateUI();
    }

    @SuppressWarnings("deprecation")
    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | android.text.InputType.TYPE_TEXT_VARIATION_FILTER);
        input.setText(currentQuery);
        input.setHint(R.string.search_hint);
        input.setSingleLine(true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Search Songs")
                .setView(input)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        currentQuery = input.getText().toString().trim();
                        if (currentQuery.length() > 0) {
                            searchLabel.setText(currentQuery);
                            clearButton.setVisibility(View.VISIBLE);
                        } else {
                            searchLabel.setText(R.string.search_hint);
                            clearButton.setVisibility(View.GONE);
                        }
                        searchHandler.postDelayed(new Runnable() {
                            public void run() {
                                updateFilteredList(currentQuery);
                            }
                        }, 300);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
    }

    private void updateUI() {
        int count = filteredSongs.size();
        songCount.setText(count + " songs");
        if (count == 0) {
            noMusicText.setVisibility(View.VISIBLE);
            songListView.setVisibility(View.GONE);
        } else {
            noMusicText.setVisibility(View.GONE);
            songListView.setVisibility(View.VISIBLE);
        }
    }

    private void updateSortButtonText() {
        switch (currentSort) {
            case SORT_TITLE:
                sortButton.setText(R.string.sort_title);
                break;
            case SORT_ARTIST:
                sortButton.setText(R.string.sort_artist);
                break;
            case SORT_ALBUM:
                sortButton.setText(R.string.sort_album);
                break;
        }
    }
}
