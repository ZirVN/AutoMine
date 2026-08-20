package de.labystudio.spotifyapi.platform.linux;

import de.labystudio.spotifyapi.model.MediaKey;
import de.labystudio.spotifyapi.model.Track;
import de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI;
import de.labystudio.spotifyapi.platform.linux.api.MPRISCommunicator;
import de.labystudio.spotifyapi.platform.linux.api.model.Metadata;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.Objects;
import javax.imageio.ImageIO;

public class LinuxSpotifyApi extends AbstractTickSpotifyAPI {
    private Track currentTrack;
    private boolean isPlaying;
    private long lastTimePositionUpdated;
    private boolean connected = false;
    private int currentPosition = -1;
    private final MPRISCommunicator mediaPlayer = new MPRISCommunicator();

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI
    protected void onTick() throws Exception {
        Metadata metadata = this.mediaPlayer.readMetadata();
        String trackId = metadata.getTrackId();
        if (!this.connected) {
            this.connected = true;
            this.listeners.forEach((v0) -> {
                v0.onConnect();
            });
        }
        String currentTrackId = this.currentTrack == null ? null : this.currentTrack.getId();
        if (!Objects.equals(trackId, currentTrackId)) {
            String trackName = metadata.getTrackName();
            String trackArtist = metadata.getArtistsJoined();
            int trackLength = metadata.getTrackLength();
            BufferedImage coverArt = toBufferedImage(metadata.getArtUrl());
            boolean isFirstTrack = !hasTrack();
            Track track = new Track(trackId, trackName, trackArtist, trackLength, coverArt);
            this.currentTrack = track;
            this.listeners.forEach(listener -> {
                listener.onTrackChanged(track);
            });
            if (!isFirstTrack) {
                updatePosition(0);
            }
        }
        boolean isPlaying = this.mediaPlayer.readIsPlaying();
        if (isPlaying != this.isPlaying) {
            this.isPlaying = isPlaying;
            this.listeners.forEach(listener2 -> {
                listener2.onPlayBackChanged(isPlaying);
            });
        }
        int position = this.mediaPlayer.readPosition().intValue();
        if (!hasPosition() || Math.abs(position - getPosition()) >= 1000) {
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

    /* renamed from: de.labystudio.spotifyapi.platform.linux.LinuxSpotifyApi$1, reason: invalid class name */
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
                    this.mediaPlayer.playPause();
                    break;
                case Psapi.ModuleFilter.X64BIT /* 2 */:
                    this.mediaPlayer.next();
                    break;
                case Psapi.ModuleFilter.ALL /* 3 */:
                    this.mediaPlayer.previous();
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

    private BufferedImage toBufferedImage(String artUrl) {
        if (artUrl == null || artUrl.isEmpty()) {
            return null;
        }
        try {
            return ImageIO.read(new URL(artUrl));
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
