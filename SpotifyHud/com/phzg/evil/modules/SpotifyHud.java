package com.phzg.evil.modules;

import com.phzg.evil.EvilAddon;
import de.labystudio.spotifyapi.SpotifyAPI;
import de.labystudio.spotifyapi.SpotifyAPIFactory;
import de.labystudio.spotifyapi.SpotifyListener;
import de.labystudio.spotifyapi.model.Track;
import java.awt.image.BufferedImage;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

public class SpotifyHud extends Module {
    private final SettingGroup sgGeneral;
    private final SettingGroup sgRender;
    private final Setting<Boolean> showArtist;
    private final Setting<Boolean> showProgress;
    private final Setting<Double> scale;
    private final Setting<SettingColor> accentColor;
    private final Setting<Integer> posX;
    private final Setting<Integer> posY;
    private String currentTrack;
    private String currentArtist;
    private String spotifyStatus;
    private int progressMs;
    private int durationMs;
    private boolean isPlaying;
    private SpotifyAPI spotifyAPI;
    private long lastRenderTime;
    private volatile BufferedImage coverArtImage;
    private Identifier coverArtTextureId;
    private String lastTrackId;

    public SpotifyHud() {
        super(EvilAddon.Evil, "spotify-hud", "Spotify music player HUD");
        this.currentTrack = "Not Playing";
        this.currentArtist = "Open Spotify";
        this.spotifyStatus = "Offline";
        this.progressMs = 0;
        this.durationMs = 0;
        this.isPlaying = false;
        this.lastRenderTime = 0L;
        this.coverArtImage = null;
        this.coverArtTextureId = null;
        this.lastTrackId = "";
        this.sgGeneral = this.settings.createGroup("General");
        this.sgRender = this.settings.createGroup("Render");
        this.showArtist = this.sgGeneral.add(((BoolSetting.Builder) ((BoolSetting.Builder) ((BoolSetting.Builder) new BoolSetting.Builder().name("show-artist")).description("Show artist name")).defaultValue(true)).build());
        this.showProgress = this.sgGeneral.add(((BoolSetting.Builder) ((BoolSetting.Builder) ((BoolSetting.Builder) new BoolSetting.Builder().name("show-progress")).description("Show music progress bar")).defaultValue(true)).build());
        this.scale = this.sgRender.add(((DoubleSetting.Builder) ((DoubleSetting.Builder) new DoubleSetting.Builder().name("scale")).description("Scale of the Spotify HUD")).defaultValue(1.0d).range(0.5d, 3.0d).sliderRange(0.5d, 3.0d).build());
        this.accentColor = this.sgRender.add(((ColorSetting.Builder) ((ColorSetting.Builder) new ColorSetting.Builder().name("accent-color")).description("Accent color (Spotify green)")).defaultValue(new SettingColor(29, 185, 84, 255)).build());
        this.posX = this.sgRender.add(((IntSetting.Builder) ((IntSetting.Builder) ((IntSetting.Builder) new IntSetting.Builder().name("pos-x")).description("X position")).defaultValue(10)).range(0, 4000).sliderRange(0, 1920).build());
        this.posY = this.sgRender.add(((IntSetting.Builder) ((IntSetting.Builder) ((IntSetting.Builder) new IntSetting.Builder().name("pos-y")).description("Y position")).defaultValue(10)).range(0, 4000).sliderRange(0, 1080).build());
    }

    public void onActivate() {
        this.lastRenderTime = System.currentTimeMillis();
        new Thread(() -> {
            try {
                this.spotifyAPI = SpotifyAPIFactory.create();
                this.spotifyAPI.registerListener(new SpotifyListener() { // from class: com.phzg.evil.modules.SpotifyHud.1
                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onConnect() {
                        SpotifyHud.this.spotifyStatus = SpotifyHud.this.isPlaying ? "Playing" : "Paused";
                    }

                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onTrackChanged(Track track) {
                        SpotifyHud.this.currentTrack = track.getName();
                        SpotifyHud.this.currentArtist = track.getArtist();
                        SpotifyHud.this.durationMs = track.getLength();
                        SpotifyHud.this.coverArtImage = track.getCoverArt();
                        SpotifyHud.this.lastTrackId = track.getId() != null ? track.getId() : "";
                    }

                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onPositionChanged(int position) {
                        SpotifyHud.this.progressMs = position;
                    }

                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onPlayBackChanged(boolean isPlayingNow) {
                        SpotifyHud.this.isPlaying = isPlayingNow;
                        SpotifyHud.this.spotifyStatus = isPlayingNow ? "Playing" : "Paused";
                    }

                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onSync() {
                    }

                    @Override // de.labystudio.spotifyapi.SpotifyListener
                    public void onDisconnect(Exception exception) {
                        SpotifyHud.this.spotifyStatus = "Offline";
                        SpotifyHud.this.currentTrack = "No song playing";
                        SpotifyHud.this.currentArtist = "Spotify";
                        SpotifyHud.this.isPlaying = false;
                        SpotifyHud.this.coverArtImage = null;
                    }
                });
                this.spotifyAPI.initialize();
            } catch (Exception e) {
                e.printStackTrace();
                this.spotifyStatus = "API Error";
            }
        }, "Spotify-API-Init").start();
    }

    public void onDeactivate() {
        if (this.spotifyAPI != null) {
            new Thread(() -> {
                try {
                    this.spotifyAPI.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, "Spotify-API-Stop").start();
        }
        cleanupTexture();
    }

    private void cleanupTexture() {
        if (this.coverArtTextureId != null && this.mc != null && this.mc.getTextureManager() != null) {
            try {
                this.mc.getTextureManager().destroyTexture(this.coverArtTextureId);
            } catch (Exception e) {
            }
            this.coverArtTextureId = null;
        }
    }

    private void uploadCoverArt(BufferedImage img) {
        try {
            cleanupTexture();
            int w = img.getWidth();
            int h = img.getHeight();
            int size = Math.min(w, h);
            NativeImage nativeImage = new NativeImage(size, size, false);
            int cornerRadius = size / 8;
            for (int py = 0; py < size; py++) {
                for (int px = 0; px < size; px++) {
                    int srcX = px + ((w - size) / 2);
                    int srcY = py + ((h - size) / 2);
                    int argb = img.getRGB(srcX, srcY);
                    boolean isCorner = false;
                    int cx = 0;
                    int cy = 0;
                    if (px < cornerRadius && py < cornerRadius) {
                        cx = cornerRadius;
                        cy = cornerRadius;
                        isCorner = true;
                    } else if (px >= size - cornerRadius && py < cornerRadius) {
                        cx = (size - 1) - cornerRadius;
                        cy = cornerRadius;
                        isCorner = true;
                    } else if (px < cornerRadius && py >= size - cornerRadius) {
                        cx = cornerRadius;
                        cy = (size - 1) - cornerRadius;
                        isCorner = true;
                    } else if (px >= size - cornerRadius && py >= size - cornerRadius) {
                        cx = (size - 1) - cornerRadius;
                        cy = (size - 1) - cornerRadius;
                        isCorner = true;
                    }
                    if (isCorner) {
                        float dx = px - cx;
                        float dy = py - cy;
                        float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
                        if (distance > cornerRadius) {
                            nativeImage.setColorArgb(px, py, 0);
                        }
                    }
                    int a = (argb >> 24) & 255;
                    int r = (argb >> 16) & 255;
                    int g = (argb >> 8) & 255;
                    int b = argb & 255;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setColorArgb(px, py, abgr);
                }
            }
            NativeImageBackedTexture texture = new NativeImageBackedTexture(nativeImage);
            this.coverArtTextureId = Identifier.of("evil-addon", "spotify_cover");
            this.mc.getTextureManager().registerTexture(this.coverArtTextureId, texture);
        } catch (Exception e) {
            e.printStackTrace();
            this.coverArtTextureId = null;
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (this.mc.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long delta = now - this.lastRenderTime;
        this.lastRenderTime = now;
        if (this.isPlaying && this.durationMs > 0) {
            this.progressMs += (int) delta;
            if (this.progressMs > this.durationMs) {
                this.progressMs = this.durationMs;
            }
        }
        BufferedImage pendingImage = this.coverArtImage;
        if (pendingImage != null) {
            this.coverArtImage = null;
            uploadCoverArt(pendingImage);
        }
        int x = ((Integer) this.posX.get()).intValue();
        int y = ((Integer) this.posY.get()).intValue();
        float s = (float) ((Double) this.scale.get()).doubleValue();
        Color accent = new Color((Color) this.accentColor.get());
        int accentPacked = accent.getPacked();
        DrawContext ctx = event.drawContext;
        int totalWidth = (int) (200.0f * s);
        int totalHeight = (int) (65.0f * s);
        int iconAreaSize = (int) (42.0f * s);
        int iconPadding = (int) (8.0f * s);
        int textStartX = x + iconAreaSize + (iconPadding * 2);
        int cornerRadius = Math.max(3, (int) (5.0f * s));
        drawRoundedRect(ctx, x, y, totalWidth, totalHeight, cornerRadius, -585886700);
        int borderThick = Math.max(1, (int) (1.5d * s));
        drawRoundedBorder(ctx, x, y, totalWidth, totalHeight, cornerRadius, borderThick, accentPacked);
        int iconX = x + iconPadding;
        int iconY = y + ((totalHeight - iconAreaSize) / 2);
        if (this.coverArtTextureId != null) {
            ctx.drawTexture(RenderLayer::getGuiTextured, this.coverArtTextureId, iconX, iconY, 0.0f, 0.0f, iconAreaSize, iconAreaSize, iconAreaSize, iconAreaSize);
        } else {
            ctx.fill(iconX, iconY, iconX + iconAreaSize, iconY + iconAreaSize, -14013910);
            int noteCX = iconX + (iconAreaSize / 2);
            int noteCY = iconY + (iconAreaSize / 2);
            drawMusicNote(ctx, noteCX, noteCY, s, accentPacked);
        }
        int labelY = y + ((int) (8.0f * s));
        ctx.drawText(this.mc.textRenderer, "SPOTIFY", textStartX, labelY, accentPacked, false);
        int trackY = labelY + ((int) (12.0f * s));
        int maxTextWidth = ((x + totalWidth) - textStartX) - ((int) (8.0f * s));
        String trackText = this.currentTrack;
        if (this.mc.textRenderer.getWidth(trackText) > maxTextWidth) {
            while (trackText.length() > 0 && this.mc.textRenderer.getWidth(trackText + "...") > maxTextWidth) {
                trackText = trackText.substring(0, trackText.length() - 1);
            }
            trackText = trackText + "...";
        }
        ctx.drawText(this.mc.textRenderer, trackText, textStartX, trackY, -1, false);
        if (((Boolean) this.showArtist.get()).booleanValue()) {
            int artistY = trackY + ((int) (11.0f * s));
            String artistText = this.currentArtist;
            if (this.mc.textRenderer.getWidth(artistText) > maxTextWidth) {
                while (artistText.length() > 0 && this.mc.textRenderer.getWidth(artistText + "...") > maxTextWidth) {
                    artistText = artistText.substring(0, artistText.length() - 1);
                }
                artistText = artistText + "...";
            }
            ctx.drawText(this.mc.textRenderer, artistText, textStartX, artistY, -5592406, false);
        }
        if (((Boolean) this.showProgress.get()).booleanValue() && this.durationMs > 0) {
            int barHeight = Math.max(4, (int) (4.0f * s));
            int barPadding = (int) (8.0f * s);
            int barY = ((y + totalHeight) - barHeight) - ((int) (14.0f * s));
            int barWidth = ((x + totalWidth) - barPadding) - textStartX;
            int barR = barHeight / 2;
            drawRoundedRect(ctx, textStartX, barY, barWidth, barHeight, barR, -12961222);
            float progressFraction = this.progressMs / this.durationMs;
            int progressWidth = (int) (barWidth * progressFraction);
            if (progressWidth > barWidth) {
                progressWidth = barWidth;
            }
            if (progressWidth > 0) {
                int fillR = Math.min(barR, progressWidth / 2);
                drawRoundedRect(ctx, textStartX, barY, progressWidth, barHeight, fillR, accentPacked);
            }
            int dotX = (textStartX + progressWidth) - (barHeight / 2);
            if (dotX < textStartX) {
                dotX = textStartX;
            }
            if (dotX + barHeight > textStartX + barWidth) {
                dotX = (textStartX + barWidth) - barHeight;
            }
            drawRoundedRect(ctx, dotX, barY, barHeight, barHeight, barR, -1);
            int timeY = barY + barHeight + ((int) (2.0f * s));
            String currentTimeStr = formatTime(this.progressMs);
            String totalTimeStr = formatTime(this.durationMs);
            ctx.drawText(this.mc.textRenderer, currentTimeStr, textStartX, timeY, -7829368, false);
            int totalTimeWidth = this.mc.textRenderer.getWidth(totalTimeStr);
            ctx.drawText(this.mc.textRenderer, totalTimeStr, (textStartX + barWidth) - totalTimeWidth, timeY, -7829368, false);
            return;
        }
        int barHeight2 = Math.max(4, (int) (4.0f * s));
        int barPadding2 = (int) (8.0f * s);
        int barY2 = ((y + totalHeight) - barHeight2) - ((int) (14.0f * s));
        int barWidth2 = ((x + totalWidth) - barPadding2) - textStartX;
        drawRoundedRect(ctx, textStartX, barY2, barWidth2, barHeight2, barHeight2 / 2, -12961222);
        int timeY2 = barY2 + barHeight2 + ((int) (2.0f * s));
        ctx.drawText(this.mc.textRenderer, "0:00", textStartX, timeY2, -7829368, false);
        int totalTimeWidth2 = this.mc.textRenderer.getWidth("0:00");
        ctx.drawText(this.mc.textRenderer, "0:00", (textStartX + barWidth2) - totalTimeWidth2, timeY2, -7829368, false);
    }

    private void drawMusicNote(DrawContext ctx, int cx, int cy, float s, int color) {
        int headW = Math.max(4, (int) (7.0f * s));
        int headH = Math.max(3, (int) (5.0f * s));
        int headX = (cx - (headW / 2)) - ((int) (2.0f * s));
        int headY = cy + ((int) (5.0f * s));
        ctx.fill(headX, headY, headX + headW, headY + headH, color);
        int head2X = cx + ((int) (2.0f * s));
        ctx.fill(head2X, headY - ((int) (3.0f * s)), head2X + headW, (headY - ((int) (3.0f * s))) + headH, color);
        int stemW = Math.max(1, (int) (2.0f * s));
        int stemH = (int) (16.0f * s);
        int stemX = (headX + headW) - stemW;
        int stemY = (headY - stemH) + (headH / 2);
        ctx.fill(stemX, stemY, stemX + stemW, headY + (headH / 2), color);
        int stem2X = (head2X + headW) - stemW;
        int stem2Y = ((headY - ((int) (3.0f * s))) - stemH) + (headH / 2);
        ctx.fill(stem2X, stem2Y, stem2X + stemW, (headY - ((int) (3.0f * s))) + (headH / 2), color);
        int beamH = Math.max(2, (int) (3.0f * s));
        int beamY = Math.min(stemY, stem2Y);
        ctx.fill(stemX, beamY, stem2X + stemW, beamY + beamH, color);
    }

    private String formatTime(int ms) {
        int seconds = ms / 1000;
        int minutes = seconds / 60;
        return String.format("%d:%02d", Integer.valueOf(minutes), Integer.valueOf(seconds % 60));
    }

    public String getInfoString() {
        return this.spotifyStatus;
    }

    private void drawRoundedRect(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (r > w / 2) {
            r = w / 2;
        }
        if (r > h / 2) {
            r = h / 2;
        }
        if (r <= 0) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        if (h - (2 * r) > 0) {
            ctx.fill(x, y + r, x + w, (y + h) - r, color);
        }
        for (int dy = 0; dy < r; dy++) {
            double cy = (r - 0.5d) - dy;
            double cx = Math.sqrt((r * r) - (cy * cy));
            int inset = (int) Math.round(r - cx);
            if (inset < 0) {
                inset = 0;
            }
            ctx.fill(x + inset, y + dy, (x + w) - inset, y + dy + 1, color);
            ctx.fill(x + inset, ((y + h) - 1) - dy, (x + w) - inset, (y + h) - dy, color);
        }
    }

    private void drawRoundedBorder(DrawContext ctx, int x, int y, int w, int h, int r, int thick, int color) {
        int ceil;
        if (r > w / 2) {
            r = w / 2;
        }
        if (r > h / 2) {
            r = h / 2;
        }
        for (int t = 0; t < thick; t++) {
            ctx.fill(x + r, y + t, (x + w) - r, y + t + 1, color);
        }
        for (int t2 = 0; t2 < thick; t2++) {
            ctx.fill(x + r, ((y + h) - 1) - t2, (x + w) - r, (y + h) - t2, color);
        }
        for (int t3 = 0; t3 < thick; t3++) {
            ctx.fill(x + t3, y + r, x + t3 + 1, (y + h) - r, color);
        }
        for (int t4 = 0; t4 < thick; t4++) {
            ctx.fill(((x + w) - 1) - t4, y + r, (x + w) - t4, (y + h) - r, color);
        }
        for (int dy = 0; dy < r; dy++) {
            double outerOffset = r - Math.sqrt((r * r) - ((r - dy) * (r - dy)));
            int outerInset = (int) Math.ceil(outerOffset);
            for (int t5 = 0; t5 < thick; t5++) {
                double innerR = (r - t5) - 1;
                if (innerR <= 0.0d) {
                    ceil = 0;
                } else {
                    double innerDy = r - dy;
                    if (innerDy > innerR) {
                        ceil = (int) Math.ceil(r - 0);
                    } else {
                        ceil = (int) Math.ceil(r - Math.sqrt((innerR * innerR) - (innerDy * innerDy)));
                    }
                }
                int innerInset = ceil;
                int startInset = Math.min(outerInset, innerInset);
                int endInset = Math.max(outerInset, innerInset);
                ctx.fill(x + startInset, y + dy, x + endInset + 1, y + dy + 1, color);
                ctx.fill(((x + w) - endInset) - 1, y + dy, (x + w) - startInset, y + dy + 1, color);
                ctx.fill(x + startInset, ((y + h) - 1) - dy, x + endInset + 1, (y + h) - dy, color);
                ctx.fill(((x + w) - endInset) - 1, ((y + h) - 1) - dy, (x + w) - startInset, (y + h) - dy, color);
            }
        }
    }
}
