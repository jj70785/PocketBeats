package com.example.pocketbeats;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.ref.WeakReference;

public class PlayerActivity extends Activity {

    private static final String TAG = "PlayerActivity";
    private static final int UPDATE_SEEKBAR = 1;
    private static final int UPDATE_INTERVAL = 500;

    private MusicService musicService;
    private boolean serviceBound = false;

    private ImageView albumArt;
    private TextView playerTitle;
    private TextView playerArtist;
    private SeekBar seekBar;
    private TextView timeElapsed;
    private TextView timeTotal;
    private ImageButton btnPrev;
    private ImageButton btnPlayPause;
    private ImageButton btnNext;

    private boolean userDragging = false;
    private Bitmap currentAlbumBitmap = null;

    private final SeekBarHandler handler = new SeekBarHandler(this);

    private static class SeekBarHandler extends Handler {
        private final WeakReference<PlayerActivity> activityRef;

        SeekBarHandler(PlayerActivity activity) {
            activityRef = new WeakReference<PlayerActivity>(activity);
        }

        public void handleMessage(Message msg) {
            PlayerActivity activity = activityRef.get();
            if (activity == null) return;
            if (msg.what == UPDATE_SEEKBAR) {
                activity.updateSeekBarProgress();
                sendEmptyMessageDelayed(UPDATE_SEEKBAR, UPDATE_INTERVAL);
            }
        }
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;
            musicService.setOnPlaybackChangedListener(new MusicService.OnPlaybackChangedListener() {
                public void onSongChanged(Song song) {
                    updateSongInfo(song);
                }
                public void onPlayStateChanged(boolean isPlaying) {
                    updatePlayPauseButton(isPlaying);
                }
            });
            Song current = musicService.getCurrentSong();
            if (current != null) {
                updateSongInfo(current);
            }
            updatePlayPauseButton(musicService.isPlaying());
            startSeekBarUpdates();
        }

        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        albumArt = (ImageView) findViewById(R.id.albumArt);
        playerTitle = (TextView) findViewById(R.id.playerTitle);
        playerArtist = (TextView) findViewById(R.id.playerArtist);
        seekBar = (SeekBar) findViewById(R.id.seekBar);
        timeElapsed = (TextView) findViewById(R.id.timeElapsed);
        timeTotal = (TextView) findViewById(R.id.timeTotal);
        btnPrev = (ImageButton) findViewById(R.id.btnPrev);
        btnPlayPause = (ImageButton) findViewById(R.id.btnPlayPause);
        btnNext = (ImageButton) findViewById(R.id.btnNext);

        // Enable marquee scrolling on title
        playerTitle.setSelected(true);

        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.togglePlayPause();
                }
            }
        });

        btnNext.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.playNext();
                }
            }
        });

        btnPrev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.playPrev();
                }
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && serviceBound) {
                    timeElapsed.setText(formatTime(progress));
                }
            }

            public void onStartTrackingTouch(SeekBar sb) {
                userDragging = true;
            }

            public void onStopTrackingTouch(SeekBar sb) {
                if (serviceBound) {
                    musicService.seekTo(sb.getProgress());
                }
                userDragging = false;
            }
        });

        Intent serviceIntent = new Intent(this, MusicService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_player, menu);
        return true;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        if (serviceBound) {
            MenuItem shuffleItem = menu.findItem(R.id.menu_shuffle);
            if (musicService.isShuffleOn()) {
                shuffleItem.setTitle("Shuffle: On");
            } else {
                shuffleItem.setTitle("Shuffle: Off");
            }

            MenuItem repeatItem = menu.findItem(R.id.menu_repeat);
            int mode = musicService.getRepeatMode();
            switch (mode) {
                case MusicService.REPEAT_OFF:
                    repeatItem.setTitle("Repeat: Off");
                    break;
                case MusicService.REPEAT_ALL:
                    repeatItem.setTitle("Repeat: All");
                    break;
                case MusicService.REPEAT_ONE:
                    repeatItem.setTitle("Repeat: One");
                    break;
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_shuffle) {
            if (serviceBound) {
                musicService.toggleShuffle();
            }
            return true;
        } else if (id == R.id.menu_repeat) {
            if (serviceBound) {
                musicService.cycleRepeatMode();
            }
            return true;
        } else if (id == R.id.menu_add_to_playlist) {
            if (serviceBound) {
                Song current = musicService.getCurrentSong();
                if (current != null) {
                    PlaylistHelper.showAddToPlaylistDialog(this, current.getPath());
                }
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    protected void onResume() {
        super.onResume();
        if (serviceBound) {
            Song current = musicService.getCurrentSong();
            if (current != null) {
                updateSongInfo(current);
            }
            updatePlayPauseButton(musicService.isPlaying());
            startSeekBarUpdates();
        }
    }

    protected void onPause() {
        super.onPause();
        stopSeekBarUpdates();
    }

    protected void onDestroy() {
        stopSeekBarUpdates();
        if (serviceBound) {
            if (musicService != null) {
                musicService.setOnPlaybackChangedListener(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
        recycleAlbumBitmap();
        super.onDestroy();
    }

    private void updateSongInfo(final Song song) {
        if (song == null) return;
        runOnUiThread(new Runnable() {
            public void run() {
                playerTitle.setText(song.getTitle());
                playerArtist.setText(song.getArtist());
                loadAlbumArt(song.getAlbumId());
                if (serviceBound) {
                    int duration = musicService.getDuration();
                    seekBar.setMax(duration);
                    timeTotal.setText(formatTime(duration));
                }
            }
        });
    }

    private void updatePlayPauseButton(final boolean isPlaying) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (isPlaying) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause);
                } else {
                    btnPlayPause.setImageResource(R.drawable.ic_play);
                }
            }
        });
    }

    private void loadAlbumArt(long albumId) {
        recycleAlbumBitmap();

        try {
            Uri albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId);
            InputStream is = getContentResolver().openInputStream(albumArtUri);
            if (is != null) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();

                int size = 480;
                int inSampleSize = 1;
                if (opts.outHeight > size || opts.outWidth > size) {
                    int halfH = opts.outHeight / 2;
                    int halfW = opts.outWidth / 2;
                    while ((halfH / inSampleSize) >= size && (halfW / inSampleSize) >= size) {
                        inSampleSize *= 2;
                    }
                }

                is = getContentResolver().openInputStream(albumArtUri);
                if (is != null) {
                    BitmapFactory.Options opts2 = new BitmapFactory.Options();
                    opts2.inSampleSize = inSampleSize;
                    currentAlbumBitmap = BitmapFactory.decodeStream(is, null, opts2);
                    is.close();
                }

                if (currentAlbumBitmap != null) {
                    albumArt.setImageBitmap(currentAlbumBitmap);
                    return;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "No album art for albumId=" + albumId);
        }

        albumArt.setImageResource(R.drawable.ic_default_album);
    }

    private void recycleAlbumBitmap() {
        if (currentAlbumBitmap != null) {
            albumArt.setImageResource(R.drawable.ic_default_album);
            currentAlbumBitmap.recycle();
            currentAlbumBitmap = null;
        }
    }

    private void startSeekBarUpdates() {
        handler.removeMessages(UPDATE_SEEKBAR);
        handler.sendEmptyMessage(UPDATE_SEEKBAR);
    }

    private void stopSeekBarUpdates() {
        handler.removeMessages(UPDATE_SEEKBAR);
    }

    private void updateSeekBarProgress() {
        if (serviceBound && !userDragging) {
            int pos = musicService.getCurrentPosition();
            int dur = musicService.getDuration();
            seekBar.setMax(dur);
            seekBar.setProgress(pos);
            timeElapsed.setText(formatTime(pos));
            timeTotal.setText(formatTime(dur));
        }
    }

    private String formatTime(int millis) {
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }
}
