package de.labystudio.spotifyapi.platform.osx;

import de.labystudio.spotifyapi.model.MediaKey;
import de.labystudio.spotifyapi.model.Track;
import de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI;
import de.labystudio.spotifyapi.platform.osx.api.spotify.SpotifyAppleScript;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import java.util.Objects;

public class OSXSpotifyApi extends AbstractTickSpotifyAPI {
    private Track currentTrack;
    private boolean isPlaying;
    private long lastTimePositionUpdated;
    private final SpotifyAppleScript appleScript = new SpotifyAppleScript();
    private boolean connected = false;
    private int currentPosition = -1;

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI
    protected void onTick() throws Exception {
        String trackId = this.appleScript.getTrackId();
        if (!this.connected && !trackId.isEmpty()) {
            this.connected = true;
            this.listeners.forEach((v0) -> {
                v0.onConnect();
            });
        }
        if (!Objects.equals(trackId, this.currentTrack == null ? null : this.currentTrack.getId())) {
            String trackName = this.appleScript.getTrackName();
            String trackArtist = this.appleScript.getTrackArtist();
            int trackLength = this.appleScript.getTrackLength();
            boolean isFirstTrack = !hasTrack();
            Track track = new Track(trackId, trackName, trackArtist, trackLength, null);
            this.currentTrack = track;
            this.listeners.forEach(listener -> {
                listener.onTrackChanged(track);
            });
            if (!isFirstTrack) {
                updatePosition(0);
            }
        }
        boolean isPlaying = this.appleScript.getPlayerState();
        if (isPlaying != this.isPlaying) {
            this.isPlaying = isPlaying;
            this.listeners.forEach(listener2 -> {
                listener2.onPlayBackChanged(isPlaying);
            });
        }
        int position = this.appleScript.getPlayerPosition();
        if (!hasPosition() || Math.abs(position - getPosition()) > 1000) {
            updatePosition(position);
        }
        this.listeners.forEach((v0) -> {
            v0.onSync();
        });
    }

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI, de.labystudio.spotifyapi.SpotifyAPI
    public void stop() {
        super.stop();
        this.connected = false;
        this.currentTrack = null;
        this.currentPosition = -1;
        this.isPlaying = false;
        this.lastTimePositionUpdated = 0L;
    }

    private void updatePosition(int position) {
        if (position == this.currentPosition) {
            return;
        }
        this.currentPosition = position;
        this.lastTimePositionUpdated = System.currentTimeMillis();
        this.listeners.forEach(listener -> {
            listener.onPositionChanged(position);
        });
    }

    /* renamed from: de.labystudio.spotifyapi.platform.osx.OSXSpotifyApi$1, reason: invalid class name */
    static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$de$labystudio$spotifyapi$model$MediaKey = new int[MediaKey.values().length];

        static {
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.PLAY_PAUSE.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.NEXT.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.PREV.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
        }
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void pressMediaKey(MediaKey mediaKey) {
        try {
            switch (AnonymousClass1.$SwitchMap$de$labystudio$spotifyapi$model$MediaKey[mediaKey.ordinal()]) {
                case Psapi.ModuleFilter.X32BIT /* 1 */:
                    this.appleScript.playPause();
                    break;
                case Psapi.ModuleFilter.X64BIT /* 2 */:
                    this.appleScript.nextTrack();
                    break;
                case Psapi.ModuleFilter.ALL /* 3 */:
                    this.appleScript.previousTrack();
                    break;
            }
        } catch (Exception e) {
            this.listeners.forEach(listener -> {
                listener.onDisconnect(e);
            });
            this.connected = false;
        }
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public int getPosition() {
        if (!hasPosition()) {
            throw new IllegalStateException("Position is not known yet");
        }
        if (this.isPlaying) {
            long timePassed = System.currentTimeMillis() - this.lastTimePositionUpdated;
            return this.currentPosition + ((int) timePassed);
        }
        return this.currentPosition;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public Track getTrack() {
        return this.currentTrack;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean isPlaying() {
        return this.isPlaying;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean isConnected() {
        return this.connected;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean hasPosition() {
        return this.currentPosition != -1;
    }
}
