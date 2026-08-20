package de.labystudio.spotifyapi.platform.windows.api.spotify;

import de.labystudio.spotifyapi.model.Track;
import de.labystudio.spotifyapi.platform.windows.api.WinProcess;
import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import de.labystudio.spotifyapi.platform.windows.api.jna.WindowsMediaControl;
import de.labystudio.spotifyapi.platform.windows.api.playback.PlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.playback.source.LegacyPlaybackAccessor;
import de.labystudio.spotifyapi.platform.windows.api.playback.source.MediaControlPlaybackAccessor;

public class SpotifyProcess extends WinProcess {
    private static final boolean DEBUG;
    private static final String PREFIX_SPOTIFY_TRACK = "spotify:track:";
    private static final long[] OFFSETS_TRACK_ID;
    private final long addressTrackId;
    private final PlaybackAccessor playbackAccessor;

    static {
        DEBUG = System.getProperty("SPOTIFY_API_DEBUG") != null;
        OFFSETS_TRACK_ID = new long[]{1621136, 1395296, 1374768, 1073560, 1362416, 1057144, 1350128, 1044456};
    }

    public SpotifyProcess(WindowsMediaControl mediaControl) {
        super("Spotify.exe");
        PlaybackAccessor accessor;
        if (DEBUG) {
            System.out.println("Spotify process loaded! Searching for addresses...");
        }
        long timeScanStart = System.currentTimeMillis();
        this.addressTrackId = findTrackIdAddress();
        if (DEBUG) {
            System.out.println("Scanning took " + (System.currentTimeMillis() - timeScanStart) + "ms");
        }
        try {
        } catch (Throwable e) {
            e.printStackTrace();
            accessor = new LegacyPlaybackAccessor(this);
        }
        if (mediaControl == null) {
            throw new IllegalArgumentException("MediaControl not available");
        }
        accessor = new MediaControlPlaybackAccessor(mediaControl);
        this.playbackAccessor = accessor;
    }

    private long findTrackIdAddress() {
        Psapi.ModuleInfo chromeElfModule = getModuleInfo("chrome_elf.dll");
        if (chromeElfModule == null) {
            throw new IllegalStateException("Could not find chrome_elf.dll module");
        }
        long chromeElfAddress = chromeElfModule.getBaseOfDll();
        long addressTrackId = -1;
        long minTrackIdOffset = Long.MAX_VALUE;
        long maxTrackIdOffset = Long.MIN_VALUE;
        long[] jArr = OFFSETS_TRACK_ID;
        int length = jArr.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                break;
            }
            long trackIdOffset = jArr[i];
            minTrackIdOffset = Math.min(minTrackIdOffset, trackIdOffset);
            maxTrackIdOffset = Math.max(maxTrackIdOffset, trackIdOffset);
            long targetAddressTrackId = chromeElfAddress + trackIdOffset;
            if (!Track.isTrackIdValid(readTrackId(targetAddressTrackId))) {
                i++;
            } else {
                addressTrackId = targetAddressTrackId;
                break;
            }
        }
        if (addressTrackId == -1) {
            if (DEBUG) {
                System.out.println("Could not find track id with hardcoded offsets. Trying to find it dynamically...");
            }
            long threshold = (maxTrackIdOffset - minTrackIdOffset) * 3;
            long scanAddressFrom = (chromeElfAddress + minTrackIdOffset) - threshold;
            long scanAddressTo = chromeElfAddress + maxTrackIdOffset + threshold;
            addressTrackId = findAddressOfText(scanAddressFrom, scanAddressTo, PREFIX_SPOTIFY_TRACK, (address, index) -> {
                return Track.isTrackIdValid(readTrackId(address));
            });
        }
        if (addressTrackId == -1) {
            throw new IllegalStateException("Could not find track id in memory");
        }
        if (DEBUG) {
            System.out.printf("Found track id address: %s (+%s) [%s%s]%n", Long.toHexString(addressTrackId), Long.toHexString(addressTrackId - chromeElfAddress), PREFIX_SPOTIFY_TRACK, readTrackId(addressTrackId));
        }
        return addressTrackId;
    }

    private String readTrackId(long address) {
        return readString(address + 14, 22);
    }

    public String readTrackId() {
        return readTrackId(this.addressTrackId);
    }

    public PlaybackAccessor getPlaybackAccessor() {
        return this.playbackAccessor;
    }
}
