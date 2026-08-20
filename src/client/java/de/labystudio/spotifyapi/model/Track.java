package de.labystudio.spotifyapi.model;

import java.awt.image.BufferedImage;

public class Track {
    public static final int ID_LENGTH = 22;
    private final String id;
    private final String name;
    private final String artist;
    private final int length;
    private final BufferedImage coverArt;

    public Track(String id, String name, String artist, int length, BufferedImage coverArt) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.length = length;
        this.coverArt = coverArt;
    }

    public boolean isIdValid() {
        return isTrackIdValid(this.id);
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getArtist() {
        return this.artist;
    }

    public int getLength() {
        return this.length;
    }

    public BufferedImage getCoverArt() {
        return this.coverArt;
    }

    public boolean equals(Object obj) {
        return (obj instanceof Track) && this.id.equals(((Track) obj).id);
    }

    public int hashCode() {
        return this.id.hashCode();
    }

    public String toString() {
        return String.format("[%s] %s - %s", this.id, this.name, this.artist);
    }

    public static boolean isTrackIdValid(String trackId) {
        if (trackId == null) {
            return false;
        }
        char[] charArray = trackId.toCharArray();
        int length = charArray.length;
        for (int i = 0; i < length; i++) {
            char c = charArray[i];
            boolean isValidCharacter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!isValidCharacter) {
                return false;
            }
        }
        return !trackId.contains(" ") && trackId.length() == 22;
    }
}
