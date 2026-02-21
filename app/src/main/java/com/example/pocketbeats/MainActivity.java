package com.example.pocketbeats;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";

    private static final int SORT_TITLE = 0;
    private static final int SORT_ARTIST = 1;
    private static final int SORT_ALBUM = 2;

    private static final int TAB_SONGS = 0;
    private static final int TAB_ARTISTS = 1;
    private static final int TAB_ALBUMS = 2;
    private static final int TAB_PLAYLISTS = 3;

    // Context menu IDs
    private static final int CONTEXT_ADD_TO_PLAYLIST = 1;
    private static final int CONTEXT_PLAY_NEXT = 2;
    private static final int CONTEXT_RENAME_PLAYLIST = 3;
    private static final int CONTEXT_DELETE_PLAYLIST = 4;
    private static final int CONTEXT_REMOVE_FROM_PLAYLIST = 5;

    private ArrayList<Song> allSongs = new ArrayList<Song>();
    private ArrayList<Song> filteredSongs = new ArrayList<Song>();
    private SongAdapter songAdapter;
    private MusicService musicService;
    private boolean serviceBound = false;
    private int currentSort = SORT_TITLE;

    // Tab state
    private int currentTab = TAB_SONGS;
    private boolean inSubView = false;
    private String subViewKey = "";

    // Tab views
    private TextView tabSongs;
    private TextView tabArtists;
    private TextView tabAlbums;
    private TextView tabPlaylists;
    private View tabIndicator;
    private View tabIndicatorSpacer;

    // Category data
    private ArrayList<String> categoryNames = new ArrayList<String>();
    private ArrayList<Integer> categoryCounts = new ArrayList<Integer>();
    private CategoryAdapter categoryAdapter;

    // Sub-view song list
    private ArrayList<Song> subViewSongs = new ArrayList<Song>();

    // Song path lookup for playlists
    private HashMap<String, Song> songsByPath = new HashMap<String, Song>();

    // Playlist DB
    private PlaylistDbHelper playlistDb;

    // Views
    private ListView songListView;
    private TextView noMusicText;
    private TextView searchLabel;
    private ImageButton searchButton;
    private ImageButton clearButton;
    private Button sortButton;
    private LinearLayout toolbar;
    private LinearLayout subViewHeader;
    private TextView subViewTitle;
    private Button btnPlayAll;
    private Button btnShuffleAll;
    private String currentQuery = "";

    // Mini-player views
    private LinearLayout miniPlayerBar;
    private ImageView miniAlbumArt;
    private TextView miniSongTitle;
    private ImageButton miniPlayPause;

    private boolean autoPlayPending = false;
    private int autoPlayIndex = -1;
    private final Handler mainHandler = new Handler();

    private ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;

            if (currentTab == TAB_SONGS && !inSubView) {
                musicService.setSongList(filteredSongs);
            }
            Log.i(TAG, "Service bound, songs: " + filteredSongs.size());

            if (autoPlayPending && autoPlayIndex >= 0 && autoPlayIndex < filteredSongs.size()) {
                Log.i(TAG, "Auto-playing song at index " + autoPlayIndex);
                musicService.playSongAtIndex(autoPlayIndex);
                autoPlayPending = false;
                Intent playerIntent = new Intent(MainActivity.this, PlayerActivity.class);
                startActivity(playerIntent);
            }

            // Set mini-player listener
            musicService.setOnMiniPlayerUpdateListener(new MusicService.OnMiniPlayerUpdateListener() {
                public void onMiniPlayerUpdate() {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            updateMiniPlayer();
                        }
                    });
                }
            });

            updateMiniPlayer();
            updateNowPlayingIndicator();
        }

        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        try {
            playlistDb = new PlaylistDbHelper(this);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open playlist database", e);
            playlistDb = null;
        }

        // Tab views
        tabSongs = (TextView) findViewById(R.id.tabSongs);
        tabArtists = (TextView) findViewById(R.id.tabArtists);
        tabAlbums = (TextView) findViewById(R.id.tabAlbums);
        tabPlaylists = (TextView) findViewById(R.id.tabPlaylists);
        tabIndicator = findViewById(R.id.tabIndicator);
        tabIndicatorSpacer = findViewById(R.id.tabIndicatorSpacer);

        // Main views
        songListView = (ListView) findViewById(R.id.songList);
        noMusicText = (TextView) findViewById(R.id.noMusicText);
        searchLabel = (TextView) findViewById(R.id.searchLabel);
        searchButton = (ImageButton) findViewById(R.id.searchButton);
        clearButton = (ImageButton) findViewById(R.id.clearButton);
        sortButton = (Button) findViewById(R.id.sortButton);
        toolbar = (LinearLayout) findViewById(R.id.toolbar);
        subViewHeader = (LinearLayout) findViewById(R.id.subViewHeader);
        subViewTitle = (TextView) findViewById(R.id.subViewTitle);
        btnPlayAll = (Button) findViewById(R.id.btnPlayAll);
        btnShuffleAll = (Button) findViewById(R.id.btnShuffleAll);

        // Mini-player views
        miniPlayerBar = (LinearLayout) findViewById(R.id.miniPlayerBar);
        miniAlbumArt = (ImageView) findViewById(R.id.miniAlbumArt);
        miniSongTitle = (TextView) findViewById(R.id.miniSongTitle);
        miniPlayPause = (ImageButton) findViewById(R.id.miniPlayPause);

        songAdapter = new SongAdapter(this, filteredSongs);
        categoryAdapter = new CategoryAdapter(this, categoryNames, categoryCounts);
        songListView.setAdapter(songAdapter);

        searchLabel.setText("Loading...");
        noMusicText.setVisibility(View.GONE);

        registerForContextMenu(songListView);

        // Load songs on background thread
        new Thread(new Runnable() {
            public void run() {
                loadSongs();
                sortSongs();
                mainHandler.post(new Runnable() {
                    public void run() {
                        buildSongsByPath();
                        updateFilteredList("");
                        updateUI();
                        // Handle auto-play if service is already bound
                        if (autoPlayPending && serviceBound && autoPlayIndex >= 0
                                && autoPlayIndex < filteredSongs.size()) {
                            musicService.setSongList(filteredSongs);
                            musicService.playSongAtIndex(autoPlayIndex);
                            autoPlayPending = false;
                            Intent playerIntent = new Intent(MainActivity.this, PlayerActivity.class);
                            startActivity(playerIntent);
                        } else if (serviceBound) {
                            musicService.setSongList(filteredSongs);
                        }
                    }
                });
            }
        }).start();

        songListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onListItemClick(position);
            }
        });

        // Tab click listeners
        View.OnClickListener tabClickListener = new View.OnClickListener() {
            public void onClick(View v) {
                int id = v.getId();
                if (id == R.id.tabSongs) {
                    switchTab(TAB_SONGS);
                } else if (id == R.id.tabArtists) {
                    switchTab(TAB_ARTISTS);
                } else if (id == R.id.tabAlbums) {
                    switchTab(TAB_ALBUMS);
                } else if (id == R.id.tabPlaylists) {
                    switchTab(TAB_PLAYLISTS);
                }
            }
        };
        tabSongs.setOnClickListener(tabClickListener);
        tabArtists.setOnClickListener(tabClickListener);
        tabAlbums.setOnClickListener(tabClickListener);
        tabPlaylists.setOnClickListener(tabClickListener);

        searchButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showSearchDialog();
            }
        });

        clearButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentQuery = "";
                clearButton.setVisibility(View.GONE);
                refreshCurrentView();
                updateToolbarLabel();
            }
        });

        sortButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                currentSort = (currentSort + 1) % 3;
                sortSongs();
                refreshCurrentView();
                updateSortButtonText();
            }
        });

        updateSortButtonText();

        // Sub-view buttons
        btnPlayAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playSubViewSongs(false);
            }
        });

        btnShuffleAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                playSubViewSongs(true);
            }
        });

        // Mini-player controls
        miniPlayPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.togglePlayPause();
                    updateMiniPlayer();
                }
            }
        });

        miniPlayerBar.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PlayerActivity.class);
                startActivity(intent);
            }
        });

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
        if (currentTab == TAB_PLAYLISTS && !inSubView) {
            loadPlaylistsTab();
        }
        updateMiniPlayer();
        updateNowPlayingIndicator();
    }

    protected void onDestroy() {
        if (serviceBound) {
            if (musicService != null) {
                musicService.setOnMiniPlayerUpdateListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        if (playlistDb != null) {
            try {
                playlistDb.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing playlist db", e);
            }
        }
        super.onDestroy();
    }

    public void onBackPressed() {
        if (inSubView) {
            exitSubView();
        } else {
            super.onBackPressed();
        }
    }

    // --- Context Menu ---

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;

        if (currentTab == TAB_PLAYLISTS && !inSubView) {
            // On playlist list: rename, delete
            if (info.position < categoryNames.size()) {
                String name = categoryNames.get(info.position);
                if (!name.equals(getString(R.string.new_playlist))) {
                    menu.setHeaderTitle(name);
                    menu.add(0, CONTEXT_RENAME_PLAYLIST, 0, R.string.rename_playlist);
                    menu.add(0, CONTEXT_DELETE_PLAYLIST, 1, R.string.delete_playlist);
                }
            }
        } else if (inSubView && currentTab == TAB_PLAYLISTS) {
            // In playlist sub-view: remove from playlist, add to playlist, play next
            if (info.position < subViewSongs.size()) {
                Song song = subViewSongs.get(info.position);
                menu.setHeaderTitle(song.getTitle());
                menu.add(0, CONTEXT_REMOVE_FROM_PLAYLIST, 0, R.string.remove_from_playlist);
                menu.add(0, CONTEXT_ADD_TO_PLAYLIST, 1, R.string.add_to_playlist);
                menu.add(0, CONTEXT_PLAY_NEXT, 2, R.string.play_next);
            }
        } else if (isShowingSongList()) {
            // On any song list: add to playlist, play next
            int pos = info.position;
            ArrayList<Song> list = getActiveSongList();
            if (pos < list.size()) {
                Song song = list.get(pos);
                menu.setHeaderTitle(song.getTitle());
                menu.add(0, CONTEXT_ADD_TO_PLAYLIST, 0, R.string.add_to_playlist);
                menu.add(0, CONTEXT_PLAY_NEXT, 1, R.string.play_next);
            }
        }
    }

    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info =
                (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        int pos = info.position;

        switch (item.getItemId()) {
            case CONTEXT_ADD_TO_PLAYLIST: {
                ArrayList<Song> list = getActiveSongList();
                if (pos < list.size()) {
                    PlaylistHelper.showAddToPlaylistDialog(this, list.get(pos).getPath());
                }
                return true;
            }
            case CONTEXT_PLAY_NEXT: {
                ArrayList<Song> list = getActiveSongList();
                if (pos < list.size() && serviceBound) {
                    musicService.playNextInQueue(list.get(pos));
                    Toast.makeText(this, "Playing next: " + list.get(pos).getTitle(), Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            case CONTEXT_RENAME_PLAYLIST: {
                if (pos < categoryNames.size()) {
                    final String oldName = categoryNames.get(pos);
                    showRenamePlaylistDialog(oldName);
                }
                return true;
            }
            case CONTEXT_DELETE_PLAYLIST: {
                if (pos < categoryNames.size()) {
                    final String name = categoryNames.get(pos);
                    showDeletePlaylistDialog(name);
                }
                return true;
            }
            case CONTEXT_REMOVE_FROM_PLAYLIST: {
                if (pos < subViewSongs.size() && inSubView && currentTab == TAB_PLAYLISTS && playlistDb != null) {
                    Song song = subViewSongs.get(pos);
                    playlistDb.removeSongFromPlaylist(subViewKey, song.getPath());
                    Toast.makeText(this, R.string.removed_from_playlist, Toast.LENGTH_SHORT).show();
                    enterSubView(subViewKey);
                }
                return true;
            }
        }
        return super.onContextItemSelected(item);
    }

    // --- Tab Switching ---

    private void switchTab(int tab) {
        if (tab == currentTab && !inSubView) return;
        currentTab = tab;
        inSubView = false;
        subViewKey = "";
        currentQuery = "";
        clearButton.setVisibility(View.GONE);

        updateTabColors();
        updateTabIndicator();

        switch (tab) {
            case TAB_SONGS:
                showSongsTab();
                break;
            case TAB_ARTISTS:
                loadArtistsTab();
                break;
            case TAB_ALBUMS:
                loadAlbumsTab();
                break;
            case TAB_PLAYLISTS:
                loadPlaylistsTab();
                break;
        }
    }

    private void updateTabColors() {
        tabSongs.setTextColor(currentTab == TAB_SONGS ? 0xFF4CAF50 : 0xFFAAAAAA);
        tabArtists.setTextColor(currentTab == TAB_ARTISTS ? 0xFF4CAF50 : 0xFFAAAAAA);
        tabAlbums.setTextColor(currentTab == TAB_ALBUMS ? 0xFF4CAF50 : 0xFFAAAAAA);
        tabPlaylists.setTextColor(currentTab == TAB_PLAYLISTS ? 0xFF4CAF50 : 0xFFAAAAAA);
    }

    private void updateTabIndicator() {
        // Adjust indicator weight: indicator takes 1 part, spacer takes 3 parts
        // Position determined by which tab is active
        LinearLayout.LayoutParams indicatorParams =
                (LinearLayout.LayoutParams) tabIndicator.getLayoutParams();
        LinearLayout.LayoutParams spacerParams =
                (LinearLayout.LayoutParams) tabIndicatorSpacer.getLayoutParams();

        // We need left spacer + indicator + right spacer
        // Remove old views and rebuild
        LinearLayout container = (LinearLayout) tabIndicator.getParent();
        container.removeAllViews();

        if (currentTab > 0) {
            View leftSpacer = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, currentTab);
            leftSpacer.setLayoutParams(lp);
            container.addView(leftSpacer);
        }

        tabIndicator.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        container.addView(tabIndicator);

        int rightWeight = 3 - currentTab;
        if (rightWeight > 0) {
            View rightSpacer = new View(this);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, rightWeight);
            rightSpacer.setLayoutParams(rp);
            container.addView(rightSpacer);
        }
    }

    // --- Tab Content ---

    private void showSongsTab() {
        subViewHeader.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        sortButton.setVisibility(View.VISIBLE);
        songListView.setAdapter(songAdapter);
        updateFilteredList(currentQuery);
        updateUI();
        updateToolbarLabel();
    }

    private void loadArtistsTab() {
        subViewHeader.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        sortButton.setVisibility(View.GONE);

        // Build unique artist list with counts
        LinkedHashMap<String, Integer> artistMap = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < allSongs.size(); i++) {
            String artist = allSongs.get(i).getArtist();
            Integer count = artistMap.get(artist);
            artistMap.put(artist, count == null ? 1 : count + 1);
        }

        categoryNames.clear();
        categoryCounts.clear();
        for (Map.Entry<String, Integer> entry : artistMap.entrySet()) {
            String name = entry.getKey();
            if (currentQuery.length() > 0 && !name.toLowerCase().contains(currentQuery.toLowerCase())) {
                continue;
            }
            categoryNames.add(name);
            categoryCounts.add(entry.getValue());
        }

        categoryAdapter = new CategoryAdapter(this, categoryNames, categoryCounts);
        songListView.setAdapter(categoryAdapter);

        noMusicText.setVisibility(categoryNames.isEmpty() ? View.VISIBLE : View.GONE);
        songListView.setVisibility(categoryNames.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadAlbumsTab() {
        subViewHeader.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        sortButton.setVisibility(View.GONE);

        LinkedHashMap<String, Integer> albumMap = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < allSongs.size(); i++) {
            String album = allSongs.get(i).getAlbum();
            Integer count = albumMap.get(album);
            albumMap.put(album, count == null ? 1 : count + 1);
        }

        categoryNames.clear();
        categoryCounts.clear();
        for (Map.Entry<String, Integer> entry : albumMap.entrySet()) {
            String name = entry.getKey();
            if (currentQuery.length() > 0 && !name.toLowerCase().contains(currentQuery.toLowerCase())) {
                continue;
            }
            categoryNames.add(name);
            categoryCounts.add(entry.getValue());
        }

        categoryAdapter = new CategoryAdapter(this, categoryNames, categoryCounts);
        songListView.setAdapter(categoryAdapter);

        noMusicText.setVisibility(categoryNames.isEmpty() ? View.VISIBLE : View.GONE);
        songListView.setVisibility(categoryNames.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadPlaylistsTab() {
        subViewHeader.setVisibility(View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        sortButton.setVisibility(View.GONE);

        ArrayList<String> playlists = new ArrayList<String>();
        if (playlistDb != null) {
            playlists = playlistDb.getAllPlaylistNames();
        }

        categoryNames.clear();
        categoryCounts.clear();

        // Add "+ New Playlist" at top
        categoryNames.add(getString(R.string.new_playlist));
        categoryCounts.add(-1); // sentinel: no count shown

        for (int i = 0; i < playlists.size(); i++) {
            String name = playlists.get(i);
            if (currentQuery.length() > 0 && !name.toLowerCase().contains(currentQuery.toLowerCase())) {
                continue;
            }
            categoryNames.add(name);
            if (playlistDb != null) {
                ArrayList<String> paths = playlistDb.getPlaylistSongPaths(name);
                categoryCounts.add(paths.size());
            } else {
                categoryCounts.add(0);
            }
        }

        categoryAdapter = new CategoryAdapter(this, categoryNames, categoryCounts);
        songListView.setAdapter(categoryAdapter);

        noMusicText.setVisibility(View.GONE);
        songListView.setVisibility(View.VISIBLE);
    }

    // --- Sub-View ---

    private void enterSubView(String key) {
        inSubView = true;
        subViewKey = key;

        subViewSongs.clear();

        if (currentTab == TAB_ARTISTS) {
            for (int i = 0; i < allSongs.size(); i++) {
                if (allSongs.get(i).getArtist().equals(key)) {
                    subViewSongs.add(allSongs.get(i));
                }
            }
        } else if (currentTab == TAB_ALBUMS) {
            for (int i = 0; i < allSongs.size(); i++) {
                if (allSongs.get(i).getAlbum().equals(key)) {
                    subViewSongs.add(allSongs.get(i));
                }
            }
        } else if (currentTab == TAB_PLAYLISTS && playlistDb != null) {
            ArrayList<String> paths = playlistDb.getPlaylistSongPaths(key);
            for (int i = 0; i < paths.size(); i++) {
                Song song = songsByPath.get(paths.get(i));
                if (song != null) {
                    subViewSongs.add(song);
                }
            }
        }

        // Apply search filter within sub-view
        if (currentQuery.length() > 0) {
            ArrayList<Song> filtered = new ArrayList<Song>();
            String lowerQuery = currentQuery.toLowerCase();
            for (int i = 0; i < subViewSongs.size(); i++) {
                Song s = subViewSongs.get(i);
                if (s.getTitle().toLowerCase().contains(lowerQuery)
                        || s.getArtist().toLowerCase().contains(lowerQuery)) {
                    filtered.add(s);
                }
            }
            subViewSongs.clear();
            subViewSongs.addAll(filtered);
        }

        subViewTitle.setText(key);
        subViewHeader.setVisibility(View.VISIBLE);
        toolbar.setVisibility(View.VISIBLE);
        sortButton.setVisibility(View.VISIBLE);

        // Create a new adapter for sub-view songs
        SongAdapter subAdapter = new SongAdapter(this, subViewSongs);
        if (serviceBound) {
            Song current = musicService.getCurrentSong();
            if (current != null) {
                subAdapter.setNowPlayingPath(current.getPath());
            }
        }
        songListView.setAdapter(subAdapter);

        updateToolbarLabel();
        noMusicText.setVisibility(subViewSongs.isEmpty() ? View.VISIBLE : View.GONE);
        songListView.setVisibility(subViewSongs.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void exitSubView() {
        inSubView = false;
        subViewKey = "";
        currentQuery = "";
        clearButton.setVisibility(View.GONE);
        subViewHeader.setVisibility(View.GONE);

        switch (currentTab) {
            case TAB_SONGS:
                showSongsTab();
                break;
            case TAB_ARTISTS:
                loadArtistsTab();
                break;
            case TAB_ALBUMS:
                loadAlbumsTab();
                break;
            case TAB_PLAYLISTS:
                loadPlaylistsTab();
                break;
        }
    }

    // --- List Item Clicks ---

    private void onListItemClick(int position) {
        if (inSubView) {
            // Playing from sub-view song list
            if (position < subViewSongs.size() && serviceBound) {
                musicService.setSongList(subViewSongs);
                musicService.playSongAtIndex(position);
                Intent intent = new Intent(this, PlayerActivity.class);
                startActivity(intent);
            }
        } else if (currentTab == TAB_SONGS) {
            if (position < filteredSongs.size() && serviceBound) {
                musicService.setSongList(filteredSongs);
                musicService.playSongAtIndex(position);
                Intent intent = new Intent(this, PlayerActivity.class);
                startActivity(intent);
            }
        } else if (currentTab == TAB_ARTISTS) {
            if (position < categoryNames.size()) {
                enterSubView(categoryNames.get(position));
            }
        } else if (currentTab == TAB_ALBUMS) {
            if (position < categoryNames.size()) {
                enterSubView(categoryNames.get(position));
            }
        } else if (currentTab == TAB_PLAYLISTS) {
            if (position < categoryNames.size()) {
                String name = categoryNames.get(position);
                if (name.equals(getString(R.string.new_playlist))) {
                    showCreatePlaylistDialog();
                } else {
                    enterSubView(name);
                }
            }
        }
    }

    // --- Play Sub-View Songs ---

    private void playSubViewSongs(boolean shuffle) {
        if (subViewSongs.isEmpty() || !serviceBound) return;
        if (shuffle) {
            if (!musicService.isShuffleOn()) {
                musicService.toggleShuffle();
            }
        } else {
            if (musicService.isShuffleOn()) {
                musicService.toggleShuffle();
            }
        }
        musicService.setSongList(subViewSongs);
        musicService.playSongAtIndex(0);
        Intent intent = new Intent(this, PlayerActivity.class);
        startActivity(intent);
    }

    // --- Song Loading ---

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

        java.util.HashSet<String> knownPaths = new java.util.HashSet<String>();
        for (int i = 0; i < allSongs.size(); i++) {
            String p = allSongs.get(i).getPath();
            knownPaths.add(normalizePath(p));
        }

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

    private void buildSongsByPath() {
        songsByPath.clear();
        for (int i = 0; i < allSongs.size(); i++) {
            Song song = allSongs.get(i);
            songsByPath.put(song.getPath(), song);
        }
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
        songAdapter.notifyDataSetChanged();
        updateUI();
    }

    private void refreshCurrentView() {
        if (inSubView) {
            enterSubView(subViewKey);
        } else {
            switch (currentTab) {
                case TAB_SONGS:
                    updateFilteredList(currentQuery);
                    break;
                case TAB_ARTISTS:
                    loadArtistsTab();
                    break;
                case TAB_ALBUMS:
                    loadAlbumsTab();
                    break;
                case TAB_PLAYLISTS:
                    loadPlaylistsTab();
                    break;
            }
        }
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
                .setTitle("Search")
                .setView(input)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        currentQuery = input.getText().toString().trim();
                        if (currentQuery.length() > 0) {
                            clearButton.setVisibility(View.VISIBLE);
                        } else {
                            clearButton.setVisibility(View.GONE);
                        }
                        mainHandler.postDelayed(new Runnable() {
                            public void run() {
                                refreshCurrentView();
                                updateToolbarLabel();
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
        updateToolbarLabel();
        if (count == 0 && currentTab == TAB_SONGS) {
            noMusicText.setVisibility(View.VISIBLE);
            songListView.setVisibility(View.GONE);
        } else {
            noMusicText.setVisibility(View.GONE);
            songListView.setVisibility(View.VISIBLE);
        }
    }

    private void updateToolbarLabel() {
        if (currentQuery.length() > 0) {
            searchLabel.setText(currentQuery);
        } else if (inSubView) {
            searchLabel.setText(subViewSongs.size() + " songs");
        } else if (currentTab == TAB_SONGS) {
            searchLabel.setText(filteredSongs.size() + " songs");
        } else {
            searchLabel.setText(categoryNames.size() + " items");
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

    // --- Mini-Player ---

    private void updateMiniPlayer() {
        if (!serviceBound || musicService == null) {
            miniPlayerBar.setVisibility(View.GONE);
            return;
        }

        Song current = musicService.getCurrentSong();
        if (current == null) {
            miniPlayerBar.setVisibility(View.GONE);
            return;
        }

        miniPlayerBar.setVisibility(View.VISIBLE);
        miniSongTitle.setText(current.getTitle() + " - " + current.getArtist());

        if (musicService.isPlaying()) {
            miniPlayPause.setImageResource(R.drawable.ic_pause);
        } else {
            miniPlayPause.setImageResource(R.drawable.ic_play);
        }

        // Load mini album art from cache
        AlbumArtCache cache = AlbumArtCache.getInstance();
        long albumId = current.getAlbumId();
        if (cache.contains(albumId)) {
            Bitmap bmp = cache.get(albumId);
            if (bmp != null) {
                miniAlbumArt.setImageBitmap(bmp);
            } else {
                miniAlbumArt.setImageResource(R.drawable.ic_default_album);
            }
        } else {
            miniAlbumArt.setImageResource(R.drawable.ic_default_album);
        }
    }

    // --- Now-Playing Indicator ---

    private void updateNowPlayingIndicator() {
        if (serviceBound && musicService != null) {
            Song current = musicService.getCurrentSong();
            if (current != null) {
                songAdapter.setNowPlayingPath(current.getPath());
            }
        }
    }

    // --- Playlist Dialogs ---

    private void showCreatePlaylistDialog() {
        if (playlistDb == null) {
            Toast.makeText(this, "Cannot create playlists (storage full?)", Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(R.string.playlist_name_hint);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.create_playlist)
                .setView(input)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() > 0) {
                            playlistDb.createPlaylist(name);
                            Toast.makeText(MainActivity.this, R.string.playlist_created, Toast.LENGTH_SHORT).show();
                            loadPlaylistsTab();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenamePlaylistDialog(final String oldName) {
        if (playlistDb == null) return;
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(oldName);
        input.setSingleLine(true);

        new AlertDialog.Builder(this)
                .setTitle(R.string.rename_playlist)
                .setView(input)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String newName = input.getText().toString().trim();
                        if (newName.length() > 0 && !newName.equals(oldName)) {
                            playlistDb.renamePlaylist(oldName, newName);
                            Toast.makeText(MainActivity.this, R.string.playlist_renamed, Toast.LENGTH_SHORT).show();
                            loadPlaylistsTab();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeletePlaylistDialog(final String name) {
        if (playlistDb == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_playlist)
                .setMessage("Delete playlist \"" + name + "\"?")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        playlistDb.deletePlaylist(name);
                        Toast.makeText(MainActivity.this, R.string.playlist_deleted, Toast.LENGTH_SHORT).show();
                        loadPlaylistsTab();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Helpers ---

    private boolean isShowingSongList() {
        if (inSubView) return true;
        return currentTab == TAB_SONGS;
    }

    private ArrayList<Song> getActiveSongList() {
        if (inSubView) return subViewSongs;
        return filteredSongs;
    }
}
