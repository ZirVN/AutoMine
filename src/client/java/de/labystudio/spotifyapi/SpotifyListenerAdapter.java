package de.labystudio.spotifyapi;

import de.labystudio.spotifyapi.model.Track;

public class SpotifyListenerAdapter implements SpotifyListener {
    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onConnect() {
    }

    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onTrackChanged(Track track) {
    }

    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onPositionChanged(int position) {
    }

    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onPlayBackChanged(boolean isPlaying) {
    }

    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onSync() {
    }

    @Override // de.labystudio.spotifyapi.SpotifyListener
    public void onDisconnect(Exception exception) {
    }
}
