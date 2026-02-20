package com.example.pocketbeats;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

public class MusicService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "MusicService";
    private static final int NOTIFICATION_ID = 1;

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private MediaPlayer player;
    private final IBinder binder = new MusicBinder();

    private ArrayList<Song> songList = new ArrayList<Song>();
    private ArrayList<Song> playQueue = new ArrayList<Song>();
    private int currentIndex = 0;
    private boolean isPrepared = false;
    private boolean shuffleOn = false;
    private int repeatMode = REPEAT_OFF;
    private boolean wasPlayingBeforeFocusLoss = false;

    private OnPlaybackChangedListener listener;

    public interface OnPlaybackChangedListener {
        void onSongChanged(Song song);
        void onPlayStateChanged(boolean isPlaying);
    }

    public class MusicBinder extends Binder {
        public MusicService getService() {
            return MusicService.this;
        }
    }

    public void onCreate() {
        super.onCreate();
        initPlayer();
    }

    private void initPlayer() {
        player = new MediaPlayer();
        player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
    }

    public IBinder onBind(Intent intent) {
        return binder;
    }

    public boolean onUnbind(Intent intent) {
        return true;
    }

    public void onRebind(Intent intent) {
        super.onRebind(intent);
    }

    public void onDestroy() {
        cancelNotification();
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player", e);
            }
            player = null;
        }
        abandonAudioFocus();
        super.onDestroy();
    }

    public void onTaskRemoved(Intent rootIntent) {
        cancelNotification();
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error in onTaskRemoved", e);
            }
            player = null;
        }
        stopSelf();
        if (Build.VERSION.SDK_INT >= 14) {
            super.onTaskRemoved(rootIntent);
        }
    }

    public void setOnPlaybackChangedListener(OnPlaybackChangedListener listener) {
        this.listener = listener;
    }

    public void setSongList(ArrayList<Song> songs) {
        this.songList = new ArrayList<Song>(songs);
        buildQueue();
    }

    private void buildQueue() {
        playQueue = new ArrayList<Song>(songList);
        if (shuffleOn && playQueue.size() > 0) {
            Song current = null;
            if (currentIndex >= 0 && currentIndex < playQueue.size()) {
                current = playQueue.get(currentIndex);
            }
            shuffleList(playQueue);
            if (current != null) {
                playQueue.remove(current);
                playQueue.add(0, current);
                currentIndex = 0;
            }
        }
    }

    private void shuffleList(ArrayList<Song> list) {
        Random rng = new Random();
        for (int i = list.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Song temp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, temp);
        }
    }

    public void playSongAtIndex(int index) {
        if (playQueue.isEmpty()) return;

        if (shuffleOn) {
            // Find the song by index in the original list
            if (index >= 0 && index < songList.size()) {
                Song target = songList.get(index);
                // Find it in the queue
                for (int i = 0; i < playQueue.size(); i++) {
                    if (playQueue.get(i).getId() == target.getId()) {
                        currentIndex = i;
                        break;
                    }
                }
            }
        } else {
            currentIndex = index;
        }

        if (currentIndex < 0 || currentIndex >= playQueue.size()) {
            currentIndex = 0;
        }

        playCurrent();
    }

    private void playCurrent() {
        if (playQueue.isEmpty()) return;
        if (currentIndex < 0 || currentIndex >= playQueue.size()) {
            currentIndex = 0;
        }

        isPrepared = false;
        Song song = playQueue.get(currentIndex);

        try {
            player.reset();
            Uri uri = Uri.parse(song.getPath());
            player.setDataSource(getApplicationContext(), uri);
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error setting data source for: " + song.getPath(), e);
            // Try next song
            playNext();
        }
    }

    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        requestAudioFocus();
        mp.start();
        Song song = playQueue.get(currentIndex);
        showNotification(song);
        if (listener != null) {
            listener.onSongChanged(song);
            listener.onPlayStateChanged(true);
        }
    }

    public void onCompletion(MediaPlayer mp) {
        if (repeatMode == REPEAT_ONE) {
            playCurrent();
            return;
        }

        if (currentIndex < playQueue.size() - 1) {
            currentIndex++;
            playCurrent();
        } else if (repeatMode == REPEAT_ALL) {
            currentIndex = 0;
            playCurrent();
        } else {
            // Reached end, no repeat
            isPrepared = false;
            cancelNotification();
            if (listener != null) {
                listener.onPlayStateChanged(false);
            }
        }
    }

    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
        isPrepared = false;
        try {
            player.reset();
        } catch (Exception e) {
            Log.e(TAG, "Error resetting player", e);
        }
        return true;
    }

    public void togglePlayPause() {
        if (!isPrepared) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                cancelNotification();
                if (listener != null) {
                    listener.onPlayStateChanged(false);
                }
            } else {
                player.start();
                Song song = getCurrentSong();
                if (song != null) {
                    showNotification(song);
                }
                if (listener != null) {
                    listener.onPlayStateChanged(true);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling play/pause", e);
        }
    }

    public void playNext() {
        if (playQueue.isEmpty()) return;
        if (currentIndex < playQueue.size() - 1) {
            currentIndex++;
        } else if (repeatMode == REPEAT_ALL) {
            currentIndex = 0;
        } else {
            return;
        }
        playCurrent();
    }

    public void playPrev() {
        if (playQueue.isEmpty()) return;
        // If more than 3 seconds in, restart current song
        if (isPrepared) {
            try {
                if (player.getCurrentPosition() > 3000) {
                    player.seekTo(0);
                    if (listener != null) {
                        listener.onSongChanged(getCurrentSong());
                    }
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking position", e);
            }
        }
        if (currentIndex > 0) {
            currentIndex--;
        } else if (repeatMode == REPEAT_ALL) {
            currentIndex = playQueue.size() - 1;
        } else {
            currentIndex = 0;
        }
        playCurrent();
    }

    public void seekTo(int position) {
        if (isPrepared) {
            try {
                player.seekTo(position);
            } catch (Exception e) {
                Log.e(TAG, "Error seeking", e);
            }
        }
    }

    public int getCurrentPosition() {
        if (isPrepared) {
            try {
                return player.getCurrentPosition();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public int getDuration() {
        if (isPrepared) {
            try {
                return player.getDuration();
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public boolean isPlaying() {
        if (isPrepared) {
            try {
                return player.isPlaying();
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    public boolean isPrepared() {
        return isPrepared;
    }

    public Song getCurrentSong() {
        if (playQueue.isEmpty() || currentIndex < 0 || currentIndex >= playQueue.size()) {
            return null;
        }
        return playQueue.get(currentIndex);
    }

    public boolean isShuffleOn() {
        return shuffleOn;
    }

    public void toggleShuffle() {
        shuffleOn = !shuffleOn;
        Song current = getCurrentSong();
        buildQueue();
        // Re-find current song in new queue
        if (current != null) {
            for (int i = 0; i < playQueue.size(); i++) {
                if (playQueue.get(i).getId() == current.getId()) {
                    currentIndex = i;
                    break;
                }
            }
        }
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public void cycleRepeatMode() {
        repeatMode = (repeatMode + 1) % 3;
    }

    // Audio focus
    private void requestAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.abandonAudioFocus(this);
    }

    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isPrepared && isPlaying()) {
                    wasPlayingBeforeFocusLoss = true;
                    try {
                        player.pause();
                        cancelNotification();
                        if (listener != null) {
                            listener.onPlayStateChanged(false);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error pausing on focus loss", e);
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (wasPlayingBeforeFocusLoss && isPrepared) {
                    try {
                        player.start();
                        Song song = getCurrentSong();
                        if (song != null) {
                            showNotification(song);
                        }
                        if (listener != null) {
                            listener.onPlayStateChanged(true);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error resuming on focus gain", e);
                    }
                }
                wasPlayingBeforeFocusLoss = false;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lower volume
                if (isPrepared && isPlaying()) {
                    try {
                        player.setVolume(0.3f, 0.3f);
                    } catch (Exception e) {
                        Log.e(TAG, "Error ducking", e);
                    }
                }
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private void showNotification(Song song) {
        try {
            Intent intent = new Intent(this, PlayerActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, intent, 0);

            Notification notification = new Notification();
            notification.icon = android.R.drawable.ic_media_play;
            notification.tickerText = song.getTitle() + " - " + song.getArtist();
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.setLatestEventInfo(this, song.getTitle(), song.getArtist(), pi);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Error showing notification", e);
        }
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIFICATION_ID);
    }
}
