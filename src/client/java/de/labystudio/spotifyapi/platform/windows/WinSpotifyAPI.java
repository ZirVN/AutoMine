package de.labystudio.spotifyapi.platform.windows;

import de.labystudio.spotifyapi.model.MediaKey;
import de.labystudio.spotifyapi.model.Track;
import de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI;
import de.labystudio.spotifyapi.platform.windows.api.WinApi;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import de.labystudio.spotifyapi.platform.windows.api.jna.WindowsMediaControl;
import de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.spotify.SpotifyProcess;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Objects;
import javax.imageio.ImageIO;

public class WinSpotifyAPI extends AbstractTickSpotifyAPI {
    private static WindowsMediaControl mediaControl;
    private SpotifyProcess process;
    private Track currentTrack;
    private boolean isPlaying;
    private long lastTimePositionUpdated;
    private int currentPosition = -1;
    private boolean hasTrackPosition = false;
    private long prevLastReportedPosition = -1;

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI
    protected void onInitialized() {
        try {
            initializeMediaControl(this.configuration.getNativesDirectory());
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void initializeMediaControl(Path nativesDirectory) throws IOException {
        if (mediaControl != null) {
            return;
        }
        boolean is64Bit = System.getProperty("os.arch").contains("64");
        String path = "/natives/windows-x" + (is64Bit ? 64 : 86) + "/windowsmediacontrol.dll";
        InputStream nativesStream = SpotifyProcess.class.getResourceAsStream(path);
        Throwable th = null;
        try {
            if (nativesStream == null) {
                throw new IOException("Could not find native library: " + path);
            }
            Path nativeLibraryPath = nativesDirectory.resolve("windowsmediacontrol.dll");
            try {
                if (!Files.exists(nativesDirectory, new LinkOption[0])) {
                    Files.createDirectories(nativesDirectory, new FileAttribute[0]);
                }
                Files.copy(nativesStream, nativeLibraryPath, StandardCopyOption.REPLACE_EXISTING);
                mediaControl = WindowsMediaControl.loadLibrary(nativeLibraryPath);
                if (nativesStream != null) {
                    if (0 != 0) {
                        try {
                            nativesStream.close();
                            return;
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                            return;
                        }
                    }
                    nativesStream.close();
                }
            } catch (IOException e) {
                throw new IOException("Failed to copy native library to " + nativeLibraryPath, e);
            }
        } catch (Throwable th3) {
            if (nativesStream != null) {
                if (0 != 0) {
                    try {
                        nativesStream.close();
                    } catch (Throwable th4) {
                        th.addSuppressed(th4);
                    }
                } else {
                    nativesStream.close();
                }
            }
            throw th3;
        }
    }

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI
    protected void onTick() {
        if (!isConnected()) {
            this.process = new SpotifyProcess(mediaControl);
            this.listeners.forEach((v0) -> {
                v0.onConnect();
            });
        }
        String trackId = this.process.readTrackId();
        if (!Track.isTrackIdValid(trackId)) {
            throw new IllegalStateException("Invalid track ID: " + trackId);
        }
        PlaybackAccessor accessor = this.process.getPlaybackAccessor();
        accessor.updatePlayback();
        String currentTrackId = this.currentTrack == null ? null : this.currentTrack.getId();
        if (!Objects.equals(trackId, currentTrackId)) {
            accessor.updateTrack();
            String trackTitle = accessor.getTitle();
            String trackArtist = accessor.getArtist();
            String currentTrackTitle = this.currentTrack == null ? null : this.currentTrack.getName();
            String currentTrackArtist = this.currentTrack == null ? null : this.currentTrack.getArtist();
            if (!Objects.equals(trackTitle, currentTrackTitle) || !Objects.equals(trackArtist, currentTrackArtist)) {
                BufferedImage coverArt = toBufferedImage(accessor.getCoverArt());
                Track track = new Track(trackId, trackTitle, trackArtist, accessor.getLength(), coverArt);
                this.currentTrack = track;
                this.listeners.forEach(listener -> {
                    listener.onTrackChanged(track);
                });
            }
        }
        boolean isPlaying = accessor.isPlaying();
        if (isPlaying != this.isPlaying) {
            this.isPlaying = isPlaying;
            this.listeners.forEach(listener2 -> {
                listener2.onPlayBackChanged(isPlaying);
            });
        }
        if (accessor.hasTrackPosition()) {
            this.hasTrackPosition = true;
            int lastReportedPosition = accessor.getPosition();
            if (this.prevLastReportedPosition != lastReportedPosition) {
                this.prevLastReportedPosition = lastReportedPosition;
                int expectedPosition = getPosition();
                boolean seeked = ((long) Math.abs(lastReportedPosition - expectedPosition)) > 1000;
                this.currentPosition = lastReportedPosition;
                this.lastTimePositionUpdated = System.currentTimeMillis();
                if (seeked) {
                    this.listeners.forEach(listener3 -> {
                        listener3.onPositionChanged(this.currentPosition);
                    });
                }
            }
        } else {
            this.currentPosition = -1;
            this.hasTrackPosition = false;
            this.lastTimePositionUpdated = System.currentTimeMillis();
        }
        this.listeners.forEach((v0) -> {
            v0.onSync();
        });
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public Track getTrack() {
        return this.currentTrack;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public int getPosition() {
        if (!hasPosition()) {
            throw new IllegalStateException("Position is not known yet. Pause the song for a second and try again.");
        }
        if (this.isPlaying) {
            long timePassed = System.currentTimeMillis() - this.lastTimePositionUpdated;
            long interpolatedPosition = this.currentPosition + timePassed;
            if (hasTrack()) {
                return (int) Math.min(interpolatedPosition, this.currentTrack.getLength());
            }
            return (int) interpolatedPosition;
        }
        return this.currentPosition;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean hasPosition() {
        if (!isConnected()) {
            return false;
        }
        return this.hasTrackPosition;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void pressMediaKey(MediaKey mediaKey) {
        if (!isConnected()) {
            throw new IllegalStateException("Spotify is not connected");
        }
        switch (AnonymousClass1.$SwitchMap$de$labystudio$spotifyapi$model$MediaKey[mediaKey.ordinal()]) {
            case Psapi.ModuleFilter.X32BIT /* 1 */:
                this.process.pressKey(WinApi.VK_MEDIA_NEXT_TRACK);
                break;
            case Psapi.ModuleFilter.X64BIT /* 2 */:
                this.process.pressKey(WinApi.VK_MEDIA_PREV_TRACK);
                break;
            case Psapi.ModuleFilter.ALL /* 3 */:
                this.process.pressKey(WinApi.VK_MEDIA_PLAY_PAUSE);
                break;
        }
        onInternalTick();
    }

    /* renamed from: de.labystudio.spotifyapi.platform.windows.WinSpotifyAPI$1, reason: invalid class name */
    static /* synthetic */ class AnonymousClass1 {
        static final /* synthetic */ int[] $SwitchMap$de$labystudio$spotifyapi$model$MediaKey = new int[MediaKey.values().length];

        static {
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.NEXT.ordinal()] = 1;
            } catch (NoSuchFieldError e) {
            }
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.PREV.ordinal()] = 2;
            } catch (NoSuchFieldError e2) {
            }
            try {
                $SwitchMap$de$labystudio$spotifyapi$model$MediaKey[MediaKey.PLAY_PAUSE.ordinal()] = 3;
            } catch (NoSuchFieldError e3) {
            }
        }
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean isPlaying() {
        return this.isPlaying;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean isConnected() {
        return this.process != null && this.process.isOpen();
    }

    @Override // de.labystudio.spotifyapi.platform.AbstractTickSpotifyAPI, de.labystudio.spotifyapi.SpotifyAPI
    public void stop() {
        super.stop();
        if (this.process != null) {
            this.process.close();
            this.process = null;
        }
        this.currentTrack = null;
        this.currentPosition = -1;
        this.hasTrackPosition = false;
        this.isPlaying = false;
        this.lastTimePositionUpdated = 0L;
        this.prevLastReportedPosition = -1L;
    }

    private BufferedImage toBufferedImage(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Throwable e) {
            e.printStackTrace();
            return null;
        }
    }
}
