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
import android.os.IBinder;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
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
    private EditText searchField;
    private Button sortButton;

    private boolean autoPlayPending = false;
    private int autoPlayIndex = -1;

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
        searchField = (EditText) findViewById(R.id.searchField);
        sortButton = (Button) findViewById(R.id.sortButton);

        adapter = new SongAdapter(this, filteredSongs);
        songListView.setAdapter(adapter);

        loadSongs();
        sortSongs();
        updateFilteredList("");
        updateUI();

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

        searchField.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateFilteredList(s.toString());
            }
            public void afterTextChanged(Editable s) {}
        });

        sortButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentSort = (currentSort + 1) % 3;
                sortSongs();
                updateFilteredList(searchField.getText().toString());
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
        Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        Cursor cursor = null;
        try {
            cursor = resolver.query(musicUri, projection, selection, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
                int durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION);

                do {
                    long id = cursor.getLong(idCol);
                    String title = cursor.getString(titleCol);
                    String artist = cursor.getString(artistCol);
                    String album = cursor.getString(albumCol);
                    long albumId = cursor.getLong(albumIdCol);
                    String path = cursor.getString(dataCol);
                    long duration = cursor.getLong(durationCol);
                    allSongs.add(new Song(id, title, artist, album, albumId, path, duration));
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading songs", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        Log.i(TAG, "Loaded " + allSongs.size() + " songs from MediaStore");
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
        if (serviceBound) {
            musicService.setSongList(filteredSongs);
        }
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
