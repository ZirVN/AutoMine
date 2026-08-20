package de.labystudio.spotifyapi.open;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import de.labystudio.spotifyapi.model.Track;
import de.labystudio.spotifyapi.open.model.AccessTokenResponse;
import de.labystudio.spotifyapi.open.model.track.OpenTrack;
import de.labystudio.spotifyapi.open.totp.TOTP;
import de.labystudio.spotifyapi.open.totp.gson.SecretDeserializer;
import de.labystudio.spotifyapi.open.totp.gson.SecretSerializer;
import de.labystudio.spotifyapi.open.totp.model.Secret;
import de.labystudio.spotifyapi.open.totp.provider.SecretProvider;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;

public class OpenSpotifyAPI {
    public static final Gson GSON = new GsonBuilder().registerTypeAdapter(Secret.class, new SecretDeserializer()).registerTypeAdapter(Secret.class, new SecretSerializer()).create();
    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537." + ((int) (Math.random() * 90.0d));
    public static final String URL_API_GEN_ACCESS_TOKEN = "https://open.spotify.com/api/token?reason=%s&productType=web-player&totp=%s&totpServer=%s&totpVer=%s";
    public static final String URL_API_TRACKS = "https://api.spotify.com/v1/tracks/%s";
    public static final String URL_API_SERVER_TIME = "https://open.spotify.com/api/server-time";
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Cache<BufferedImage> imageCache = new Cache<>(10);
    private final Cache<OpenTrack> openTrackCache = new Cache<>(100);
    private final SecretProvider secretProvider;
    private AccessTokenResponse accessTokenResponse;

    public OpenSpotifyAPI(SecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    private void generateAccessTokenAsync(Consumer<AccessTokenResponse> callback) {
        this.executor.execute(() -> {
            try {
                callback.accept(generateAccessToken());
            } catch (Exception error) {
                error.printStackTrace();
            }
        });
    }

    public long requestServerTime() throws IOException {
        URL url = new URL(URL_API_SERVER_TIME);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Host", "open.spotify.com");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String response = reader.readLine();
        reader.close();
        JsonObject obj = (JsonObject) GSON.fromJson(response, JsonObject.class);
        return obj.get("serverTime").getAsLong();
    }

    private AccessTokenResponse generateAccessToken() throws IOException {
        Secret secret = this.secretProvider.getSecret();
        if (secret == null) {
            throw new IOException("No TOTP secret provided");
        }
        long serverTime = requestServerTime();
        String totp = TOTP.generateOtp(secret.getSecretAsBytes(), serverTime, 30, 6);
        AccessTokenResponse response = getToken("transport", totp, secret.getVersion());
        if (!hasValidAccessToken(response)) {
            response = getToken("init", totp, secret.getVersion());
        }
        if (!hasValidAccessToken(response)) {
            throw new IOException("Could not generate access token");
        }
        return response;
    }

    private AccessTokenResponse getToken(String mode, String totp, int version) throws IOException {
        InputStream errorStream;
        String url = String.format(URL_API_GEN_ACCESS_TOKEN, mode, totp, totp, Integer.valueOf(version));
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.addRequestProperty("User-Agent", USER_AGENT);
        connection.addRequestProperty("referer", "https://open.spotify.com/");
        connection.addRequestProperty("app-platform", "WebPlayer");
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        if (code != 200 && (errorStream = connection.getErrorStream()) != null) {
            JsonReader reader = new JsonReader(new InputStreamReader(errorStream));
            JsonObject response = (JsonObject) GSON.fromJson(reader, JsonObject.class);
            throw new IOException("Could not retrieve access token: " + response.toString());
        }
        JsonReader reader2 = new JsonReader(new InputStreamReader(connection.getInputStream()));
        return (AccessTokenResponse) GSON.fromJson(reader2, AccessTokenResponse.class);
    }

    public void requestImageAsync(Track track, Consumer<BufferedImage> callback) {
        requestImageAsync(track.getId(), callback);
    }

    public void requestImageAsync(String trackId, Consumer<BufferedImage> callback) {
        if (!Track.isTrackIdValid(trackId)) {
            throw new IllegalArgumentException("Invalid track ID: " + trackId);
        }
        this.executor.execute(() -> {
            try {
                BufferedImage image = requestImage(trackId);
                if (image != null) {
                    callback.accept(image);
                }
            } catch (Exception error) {
                error.printStackTrace();
            }
        });
    }

    public void requestImageUrlAsync(String trackId, Consumer<String> callback) {
        this.executor.execute(() -> {
            try {
                String imageUrl = requestImageUrl(trackId);
                if (imageUrl != null) {
                    callback.accept(imageUrl);
                }
            } catch (Exception error) {
                error.printStackTrace();
            }
        });
    }

    public void requestOpenTrackAsync(Track track, Consumer<OpenTrack> callback) {
        requestOpenTrackAsync(track.getId(), callback);
    }

    public void requestOpenTrackAsync(String trackId, Consumer<OpenTrack> callback) {
        this.executor.execute(() -> {
            try {
                OpenTrack openTrack = requestOpenTrack(trackId);
                if (openTrack != null) {
                    callback.accept(openTrack);
                }
            } catch (Exception error) {
                error.printStackTrace();
            }
        });
    }

    public BufferedImage requestImage(Track track) throws IOException {
        return requestImage(track.getId());
    }

    public BufferedImage requestImage(String trackId) throws IOException {
        if (!Track.isTrackIdValid(trackId)) {
            throw new IllegalArgumentException("Invalid track ID: " + trackId);
        }
        BufferedImage cachedImage = this.imageCache.get(trackId);
        if (cachedImage != null) {
            return cachedImage;
        }
        String url = requestImageUrl(trackId);
        if (url == null) {
            return null;
        }
        BufferedImage image = ImageIO.read(new URL(url));
        if (image == null) {
            throw new IOException("Could not load image: " + url);
        }
        this.imageCache.push(trackId, image);
        return image;
    }

    private String requestImageUrl(String trackId) throws IOException {
        OpenTrack openTrack = requestOpenTrack(trackId);
        if (openTrack == null) {
            return null;
        }
        return openTrack.album.images.get(0).url;
    }

    public OpenTrack requestOpenTrack(Track track) throws IOException {
        return requestOpenTrack(track.getId());
    }

    public OpenTrack requestOpenTrack(String trackId) throws IOException {
        OpenTrack cachedOpenTrack = this.openTrackCache.get(trackId);
        if (cachedOpenTrack != null) {
            return cachedOpenTrack;
        }
        String url = String.format(URL_API_TRACKS, trackId);
        OpenTrack openTrack = (OpenTrack) request(url, OpenTrack.class, true);
        this.openTrackCache.push(trackId, openTrack);
        return openTrack;
    }

    public <T> T request(String str, Class<?> cls, boolean z) throws IOException {
        if (this.accessTokenResponse == null) {
            this.accessTokenResponse = generateAccessToken();
        }
        HttpsURLConnection httpsURLConnection = (HttpsURLConnection) new URL(str).openConnection();
        httpsURLConnection.addRequestProperty("User-Agent", USER_AGENT);
        httpsURLConnection.addRequestProperty("referer", "https://open.spotify.com/");
        httpsURLConnection.addRequestProperty("app-platform", "WebPlayer");
        httpsURLConnection.addRequestProperty("origin", "https://open.spotify.com");
        if (this.accessTokenResponse != null) {
            httpsURLConnection.addRequestProperty("authorization", "Bearer " + this.accessTokenResponse.accessToken);
        }
        if (httpsURLConnection.getResponseCode() / 100 != 2) {
            if (z) {
                this.accessTokenResponse = generateAccessToken();
                return (T) request(str, cls, false);
            }
            return null;
        }
        return (T) GSON.fromJson(new JsonReader(new InputStreamReader(httpsURLConnection.getInputStream(), StandardCharsets.UTF_8)), cls);
    }

    private boolean hasValidAccessToken(AccessTokenResponse response) {
        return (response == null || response.accessToken == null || response.accessToken.isEmpty()) ? false : true;
    }

    public Cache<BufferedImage> getImageCache() {
        return this.imageCache;
    }

    public Cache<OpenTrack> getOpenTrackCache() {
        return this.openTrackCache;
    }
}
