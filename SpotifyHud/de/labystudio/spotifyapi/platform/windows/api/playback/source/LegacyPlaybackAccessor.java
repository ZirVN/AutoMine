package de.labystudio.spotifyapi.platform.windows.api.playback.source;

import de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.spotify.SpotifyProcess;
import de.labystudio.spotifyapi.platform.windows.api.spotify.SpotifyWindowTitle;

public class LegacyPlaybackAccessor implements PlaybackAccessor {
    private final SpotifyProcess spotifyProcess;
    private boolean playing;
    private SpotifyWindowTitle windowTitle = SpotifyWindowTitle.UNKNOWN;

    public LegacyPlaybackAccessor(SpotifyProcess spotifyProcess) {
        this.spotifyProcess = spotifyProcess;
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public void updatePlayback() {
        SpotifyWindowTitle windowTitle = SpotifyWindowTitle.of(this.spotifyProcess.getWindowTitle());
        if (windowTitle == null) {
            this.playing = false;
        } else {
            this.windowTitle = windowTitle;
            this.playing = true;
        }
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public void updateTrack() {
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public boolean isValid() {
        return true;
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public int getLength() {
        return -1;
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public int getPosition() {
        return -1;
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public boolean isPlaying() {
        return this.playing;
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public String getTitle() {
        return this.windowTitle.getTrackName();
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public String getArtist() {
        return this.windowTitle.getTrackArtist();
    }

    @Override // de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor
    public byte[] getCoverArt() {
        return null;
    }
}
