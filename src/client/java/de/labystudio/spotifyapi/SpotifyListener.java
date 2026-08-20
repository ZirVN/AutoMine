package de.labystudio.spotifyapi;

import de.labystudio.spotifyapi.model.Track;

public interface SpotifyListener {
    void onConnect();

    void onTrackChanged(Track track);

    void onPositionChanged(int i);

    void onPlayBackChanged(boolean z);

    void onSync();

    void onDisconnect(Exception exc);
}
