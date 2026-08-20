package de.labystudio.spotifyapi.platform.windows.api.jna;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.file.Path;

public interface WindowsMediaControl extends Library {
    boolean isSpotifyAvailable();

    long getPlaybackPosition();

    long getTrackDuration();

    Pointer getTrackTitle();

    Pointer getArtistName();

    int isPlaying();

    boolean getCoverArt(PointerByReference pointerByReference, NativeLongByReference nativeLongByReference);

    void freeString(Pointer pointer);

    void freeCoverArt(Pointer pointer);

    static WindowsMediaControl loadLibrary(Path dllPath) {
        return (WindowsMediaControl) Native.load(dllPath.toAbsolutePath().toString(), WindowsMediaControl.class);
    }
}
