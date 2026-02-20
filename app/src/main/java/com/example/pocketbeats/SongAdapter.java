package com.example.pocketbeats;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class SongAdapter extends ArrayAdapter<Song> {

    private final LayoutInflater inflater;

    public SongAdapter(Context context, List<Song> songs) {
        super(context, R.layout.item_song, songs);
        this.inflater = LayoutInflater.from(context);
    }

    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_song, parent, false);
            holder = new ViewHolder();
            holder.title = (TextView) convertView.findViewById(R.id.songTitle);
            holder.artist = (TextView) convertView.findViewById(R.id.songArtist);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Song song = getItem(position);
        if (song != null) {
            holder.title.setText(song.getTitle());
            holder.artist.setText(song.getArtist());
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView title;
        TextView artist;
    }
}
