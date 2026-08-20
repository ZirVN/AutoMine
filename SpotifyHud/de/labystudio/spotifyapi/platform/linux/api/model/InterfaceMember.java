package de.labystudio.spotifyapi.platform.linux.api.model;

public class InterfaceMember {
    private final String path;

    public InterfaceMember(String path) {
        this.path = path;
    }

    public String toString() {
        return this.path;
    }
}
