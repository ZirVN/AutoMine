package de.labystudio.spotifyapi;

import de.labystudio.spotifyapi.config.SpotifyConfiguration;
import de.labystudio.spotifyapi.model.MediaKey;
import de.labystudio.spotifyapi.model.Track;
import java.util.concurrent.CompletableFuture;

public interface SpotifyAPI {
    SpotifyAPI initialize(SpotifyConfiguration spotifyConfiguration);

    Track getTrack();

    int getPosition();

    boolean hasPosition();

    boolean isPlaying();

    void pressMediaKey(MediaKey mediaKey);

    boolean isConnected();

    boolean isInitialized();

    void registerListener(SpotifyListener spotifyListener);

    void unregisterListener(SpotifyListener spotifyListener);

    SpotifyConfiguration getConfiguration();

    void stop();

    void shutdown();

    default SpotifyAPI initialize() {
        return initialize(new SpotifyConfiguration.Builder().build());
    }

    default CompletableFuture<SpotifyAPI> initializeAsync(SpotifyConfiguration configuration) {
        return CompletableFuture.supplyAsync(() -> {
            return initialize(configuration);
        });
    }

    default CompletableFuture<SpotifyAPI> initializeAsync() {
        return CompletableFuture.supplyAsync(this::initialize);
    }

    default boolean hasTrack() {
        return getTrack() != null;
    }
}
