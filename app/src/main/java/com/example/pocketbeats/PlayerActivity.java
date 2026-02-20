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
import android.view.View;
import android.widget.Button;
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
    private Button btnPrev;
    private Button btnPlayPause;
    private Button btnNext;
    private Button btnShuffle;
    private Button btnRepeat;

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
            // Update UI with current state
            Song current = musicService.getCurrentSong();
            if (current != null) {
                updateSongInfo(current);
            }
            updatePlayPauseButton(musicService.isPlaying());
            updateShuffleButton();
            updateRepeatButton();
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
        btnPrev = (Button) findViewById(R.id.btnPrev);
        btnPlayPause = (Button) findViewById(R.id.btnPlayPause);
        btnNext = (Button) findViewById(R.id.btnNext);
        btnShuffle = (Button) findViewById(R.id.btnShuffle);
        btnRepeat = (Button) findViewById(R.id.btnRepeat);

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

        btnShuffle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.toggleShuffle();
                    updateShuffleButton();
                }
            }
        });

        btnRepeat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (serviceBound) {
                    musicService.cycleRepeatMode();
                    updateRepeatButton();
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

        // Bind to service
        Intent serviceIntent = new Intent(this, MusicService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    protected void onResume() {
        super.onResume();
        if (serviceBound) {
            Song current = musicService.getCurrentSong();
            if (current != null) {
                updateSongInfo(current);
            }
            updatePlayPauseButton(musicService.isPlaying());
            updateShuffleButton();
            updateRepeatButton();
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
                    btnPlayPause.setText(R.string.btn_pause);
                } else {
                    btnPlayPause.setText(R.string.btn_play);
                }
            }
        });
    }

    private void updateShuffleButton() {
        if (!serviceBound) return;
        if (musicService.isShuffleOn()) {
            btnShuffle.setText(R.string.shuffle_on);
        } else {
            btnShuffle.setText(R.string.shuffle_off);
        }
    }

    private void updateRepeatButton() {
        if (!serviceBound) return;
        int mode = musicService.getRepeatMode();
        switch (mode) {
            case MusicService.REPEAT_OFF:
                btnRepeat.setText(R.string.repeat_off);
                break;
            case MusicService.REPEAT_ALL:
                btnRepeat.setText(R.string.repeat_all);
                break;
            case MusicService.REPEAT_ONE:
                btnRepeat.setText(R.string.repeat_one);
                break;
        }
    }

    private void loadAlbumArt(long albumId) {
        recycleAlbumBitmap();

        try {
            Uri albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId);
            InputStream is = getContentResolver().openInputStream(albumArtUri);
            if (is != null) {
                // First pass: get dimensions
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();

                // Calculate inSampleSize
                int size = 300;
                int inSampleSize = 1;
                if (opts.outHeight > size || opts.outWidth > size) {
                    int halfH = opts.outHeight / 2;
                    int halfW = opts.outWidth / 2;
                    while ((halfH / inSampleSize) >= size && (halfW / inSampleSize) >= size) {
                        inSampleSize *= 2;
                    }
                }

                // Second pass: decode
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
            // No album art found, use placeholder
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
