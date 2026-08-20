package de.labystudio.spotifyapi.platform;

import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.config.SpotifyConfiguration;
import de.labystudio.spotifyapi.open.OpenSpotifyAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class AbstractTickSpotifyAPI implements SpotifyAPI {
    protected static final long TICK_INTERVAL = 1000;
    private OpenSpotifyAPI openAPI;
    protected SpotifyConfiguration configuration;
    private ScheduledFuture<?> task;
    protected final List<SpotifyListener> listeners = new ArrayList();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private long timeLastException = -1;

    protected abstract void onTick() throws Exception;

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public SpotifyAPI initialize(SpotifyConfiguration configuration) {
        synchronized (this) {
            this.configuration = configuration;
            if (this.executor.isShutdown()) {
                throw new IllegalStateException("This SpotifyAPI has been shutdown and cannot be reused");
            }
            if (isInitialized()) {
                throw new IllegalStateException("This SpotifyAPI is already initialized");
            }
            onInitialized();
            this.task = this.executor.scheduleWithFixedDelay(this::onInternalTick, 0L, TICK_INTERVAL, TimeUnit.MILLISECONDS);
            onInternalTick();
        }
        return this;
    }

    protected void onInitialized() {
    }

    /* JADX INFO: Access modifiers changed from: protected */
    public synchronized void onInternalTick() {
        try {
            long timeSinceLastException = System.currentTimeMillis() - this.timeLastException;
            if (timeSinceLastException < this.configuration.getExceptionReconnectDelay()) {
                return;
            }
            onTick();
        } catch (Exception e) {
            this.timeLastException = System.currentTimeMillis();
            stop();
            this.listeners.forEach(listener -> {
                listener.onDisconnect(e);
            });
            if (this.configuration.isAutoReconnect()) {
                initialize(this.configuration);
            }
        }
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void registerListener(SpotifyListener listener) {
        this.listeners.add(listener);
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void unregisterListener(SpotifyListener listener) {
        this.listeners.remove(listener);
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public boolean isInitialized() {
        return this.task != null;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public SpotifyConfiguration getConfiguration() {
        return this.configuration;
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void stop() {
        synchronized (this) {
            if (this.task != null) {
                this.task.cancel(true);
                this.task = null;
            }
        }
    }

    @Override // de.labystudio.spotifyapi.SpotifyAPI
    public void shutdown() {
        stop();
        this.executor.shutdownNow();
    }
}
