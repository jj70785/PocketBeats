package com.example.pocketbeats;

import java.io.Serializable;

public class Song implements Serializable {
    private long id;
    private String title;
    private String artist;
    private String album;
    private long albumId;
    private String path;
    private long duration;

    public Song(long id, String title, String artist, String album, long albumId, String path, long duration) {
        this.id = id;
        this.title = title != null ? title : "Unknown";
        this.artist = artist != null ? artist : "Unknown Artist";
        this.album = album != null ? album : "Unknown Album";
        this.albumId = albumId;
        this.path = path;
        this.duration = duration;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getAlbumId() { return albumId; }
    public String getPath() { return path; }
    public long getDuration() { return duration; }

    public void setTitle(String t) { if (t != null && t.length() > 0) this.title = t; }
    public void setArtist(String a) { if (a != null && a.length() > 0) this.artist = a; }
    public void setAlbum(String a) { if (a != null && a.length() > 0) this.album = a; }
    public void setDuration(long d) { if (d > 0) this.duration = d; }
}
