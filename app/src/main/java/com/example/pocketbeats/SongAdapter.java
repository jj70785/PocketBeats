package com.example.pocketbeats;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;

public class SongAdapter extends ArrayAdapter<Song> {

    private final LayoutInflater inflater;
    private final Context context;
    private final Handler mainHandler = new Handler();
    private String nowPlayingPath = null;

    public SongAdapter(Context context, List<Song> songs) {
        super(context, R.layout.item_song, songs);
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setNowPlayingPath(String path) {
        this.nowPlayingPath = path;
        notifyDataSetChanged();
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        final ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_song, parent, false);
            holder = new ViewHolder();
            holder.albumArt = (ImageView) convertView.findViewById(R.id.songAlbumArt);
            holder.title = (TextView) convertView.findViewById(R.id.songTitle);
            holder.artist = (TextView) convertView.findViewById(R.id.songArtist);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Song song = getItem(position);
        if (song != null) {
            holder.title.setText(song.getTitle());

            // Build subtitle: "Artist . Duration"
            String subtitle = song.getArtist();
            if (song.getDuration() > 0) {
                subtitle = subtitle + " \u2022 " + formatTime(song.getDuration());
            }
            holder.artist.setText(subtitle);

            // Now-playing indicator: accent green title
            if (nowPlayingPath != null && nowPlayingPath.equals(song.getPath())) {
                holder.title.setTextColor(0xFF4CAF50);
            } else {
                holder.title.setTextColor(0xFFFFFFFF);
            }

            // Album art with cache
            final long albumId = song.getAlbumId();
            holder.position = position;

            AlbumArtCache cache = AlbumArtCache.getInstance();
            if (cache.contains(albumId)) {
                Bitmap cached = cache.get(albumId);
                if (cached != null) {
                    holder.albumArt.setImageBitmap(cached);
                } else {
                    holder.albumArt.setImageResource(R.drawable.ic_default_album);
                }
            } else {
                // Set placeholder and load async
                holder.albumArt.setImageResource(R.drawable.ic_default_album);
                final int tagPosition = position;
                new Thread(new Runnable() {
                    public void run() {
                        loadAlbumArtAsync(albumId, holder, tagPosition);
                    }
                }).start();
            }
        }

        return convertView;
    }

    private void loadAlbumArtAsync(final long albumId, final ViewHolder holder, final int tagPosition) {
        final AlbumArtCache cache = AlbumArtCache.getInstance();

        // Double-check cache (another thread may have loaded it)
        if (cache.contains(albumId)) {
            final Bitmap cached = cache.get(albumId);
            mainHandler.post(new Runnable() {
                public void run() {
                    if (holder.position == tagPosition) {
                        if (cached != null) {
                            holder.albumArt.setImageBitmap(cached);
                        } else {
                            holder.albumArt.setImageResource(R.drawable.ic_default_album);
                        }
                    }
                }
            });
            return;
        }

        Bitmap result = null;
        try {
            Uri albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId);
            InputStream is = context.getContentResolver().openInputStream(albumArtUri);
            if (is != null) {
                // First pass: get dimensions
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();

                // Calculate inSampleSize for 72px target
                int targetSize = 72;
                int inSampleSize = 1;
                if (opts.outHeight > targetSize || opts.outWidth > targetSize) {
                    int halfH = opts.outHeight / 2;
                    int halfW = opts.outWidth / 2;
                    while ((halfH / inSampleSize) >= targetSize && (halfW / inSampleSize) >= targetSize) {
                        inSampleSize *= 2;
                    }
                }

                // Second pass: decode scaled
                is = context.getContentResolver().openInputStream(albumArtUri);
                if (is != null) {
                    BitmapFactory.Options opts2 = new BitmapFactory.Options();
                    opts2.inSampleSize = inSampleSize;
                    result = BitmapFactory.decodeStream(is, null, opts2);
                    is.close();
                }
            }
        } catch (Exception e) {
            // No album art available
        }

        if (result != null) {
            // Scale to exact 72x72
            final Bitmap scaled = Bitmap.createScaledBitmap(result, 72, 72, true);
            if (scaled != result) {
                result.recycle();
            }
            cache.put(albumId, scaled);
            mainHandler.post(new Runnable() {
                public void run() {
                    if (holder.position == tagPosition) {
                        holder.albumArt.setImageBitmap(scaled);
                    }
                }
            });
        } else {
            cache.putNoArt(albumId);
            mainHandler.post(new Runnable() {
                public void run() {
                    if (holder.position == tagPosition) {
                        holder.albumArt.setImageResource(R.drawable.ic_default_album);
                    }
                }
            });
        }
    }

    private String formatTime(long millis) {
        int totalSeconds = (int) (millis / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private static class ViewHolder {
        ImageView albumArt;
        TextView title;
        TextView artist;
        int position;
    }
}
