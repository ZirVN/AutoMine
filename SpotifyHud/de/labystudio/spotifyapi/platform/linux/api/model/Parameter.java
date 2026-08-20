package de.labystudio.spotifyapi.platform.linux.api.model;

public class Parameter {
    private final String key;
    private final String value;

    public Parameter(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public Parameter(String key) {
        this(key, null);
    }

    public String toString() {
        Object[] objArr = new Object[2];
        objArr[0] = this.key;
        objArr[1] = this.value == null ? "" : "=" + this.value;
        return String.format("--%s%s", objArr);
    }
}
