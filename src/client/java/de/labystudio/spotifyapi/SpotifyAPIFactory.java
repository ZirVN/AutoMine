package de.labystudio.spotifyapi;

import de.labystudio.spotifyapi.config.SpotifyConfiguration;
import de.labystudio.spotifyapi.platform.osx.OSXSpotifyApi;
import de.labystudio.spotifyapi.platform.windows.WinSpotifyAPI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class SpotifyAPIFactory {
    // Nhánh Linux bị cắt: bản decompile của nó hỏng không sửa nổi (Variant.java
    // switch-trên-boolean), và mod này chỉ chạy trên Windows của chủ nhân.
    public static SpotifyAPI create() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        if (os.contains("win")) {
            return new WinSpotifyAPI();
        }
        if (os.contains("mac")) {
            return new OSXSpotifyApi();
        }
        throw new IllegalStateException("Unsupported OS: " + os);
    }

    public static SpotifyAPI createInitialized() {
        return create().initialize();
    }

    public static SpotifyAPI createInitialized(SpotifyConfiguration configuration) {
        return create().initialize(configuration);
    }

    public static CompletableFuture<SpotifyAPI> createInitializedAsync() {
        return CompletableFuture.supplyAsync(SpotifyAPIFactory::createInitialized);
    }

    public static CompletableFuture<SpotifyAPI> createInitializedAsync(SpotifyConfiguration configuration) {
        return CompletableFuture.supplyAsync(() -> {
            return createInitialized(configuration);
        });
    }
}
