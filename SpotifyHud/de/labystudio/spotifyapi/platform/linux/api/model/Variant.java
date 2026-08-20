package de.labystudio.spotifyapi.platform.linux.api.model;

import de.labystudio.spotifyapi.platform.windows.api.jna.Psapi;
import java.util.ArrayList;
import java.util.List;

public class Variant {
    private final String sig;
    private final Object value;

    public Variant(String sig, Object value) {
        this.sig = sig;
        this.value = value;
    }

    public String getSig() {
        return this.sig;
    }

    public <T> T getValue() {
        return (T) this.value;
    }

    public String toString() {
        return this.sig + ":" + this.value;
    }

    public static Variant parse(String raw) {
        String replace = raw.trim().replace("\n", "");
        while (true) {
            String raw2 = replace;
            if (raw2.contains("  ")) {
                replace = raw2.replace("  ", " ");
            } else {
                return new Variant("variant", parse0(raw2));
            }
        }
    }

    private static Object parse0(String raw) {
        String[] segments = raw.split(" ", 2);
        if (segments.length != 2) {
            throw new IllegalArgumentException("Invalid variant: " + raw);
        }
        String signature = segments[0];
        String payload = segments[1];
        if (signature.startsWith("variant")) {
            String[] variantSegments = payload.split(" ", 2);
            return parseVariant(variantSegments[0], variantSegments[1]);
        }
        if (signature.startsWith("dict")) {
            return parseDict(payload);
        }
        throw new IllegalArgumentException("Invalid variant signature: " + signature);
    }

    private static Object parseVariant(String type, String value) {
        boolean z = -1;
        switch (type.hashCode()) {
            case -1325958191:
                if (type.equals("double")) {
                    z = 6;
                    break;
                }
                break;
            case -891985903:
                if (type.equals("string")) {
                    z = true;
                    break;
                }
                break;
            case -844996807:
                if (type.equals("uint32")) {
                    z = 3;
                    break;
                }
                break;
            case -844996712:
                if (type.equals("uint64")) {
                    z = 5;
                    break;
                }
                break;
            case 93090393:
                if (type.equals("array")) {
                    z = false;
                    break;
                }
                break;
            case 100359822:
                if (type.equals("int32")) {
                    z = 2;
                    break;
                }
                break;
            case 100359917:
                if (type.equals("int64")) {
                    z = 4;
                    break;
                }
                break;
        }
        switch (z) {
            case Psapi.ModuleFilter.NONE /* 0 */:
                String collection = value.substring(1, value.length() - 1).trim();
                List<Object> list = new ArrayList<>();
                StringBuilder buffer = new StringBuilder();
                boolean nested = false;
                boolean escaped = false;
                boolean primitive = false;
                String tempType = null;
                for (int i = 0; i < collection.length(); i++) {
                    char c = collection.charAt(i);
                    if (c == '\"') {
                        escaped = !escaped;
                    }
                    if (!escaped) {
                        if (c == '(') {
                            nested = true;
                        }
                        if (c == ')') {
                            nested = false;
                            list.add(parseDict(((Object) buffer) + ")"));
                            buffer = new StringBuilder();
                        } else if (!nested && c == ' ' && buffer.length() > 0) {
                            String keyword = buffer.toString().trim();
                            buffer = new StringBuilder();
                            if (tempType == null) {
                                if (!keyword.equals("dict")) {
                                    tempType = keyword;
                                }
                            } else {
                                Object variant = parseVariant(tempType, keyword);
                                if (!(variant instanceof Variant)) {
                                    primitive = true;
                                }
                                list.add(variant);
                                tempType = null;
                            }
                        }
                    }
                    buffer.append(c);
                }
                if (tempType != null) {
                    Object variant2 = parseVariant(tempType, buffer.toString().trim());
                    if (!(variant2 instanceof Variant)) {
                        primitive = true;
                    }
                    list.add(variant2);
                }
                if (primitive) {
                    if (list.get(0) instanceof String) {
                        return list.toArray(new String[0]);
                    }
                    return list.toArray();
                }
                return list.toArray(new Variant[0]);
            case Psapi.ModuleFilter.X32BIT /* 1 */:
                return value.substring(1, value.length() - 1);
            case Psapi.ModuleFilter.X64BIT /* 2 */:
                return Integer.valueOf(Integer.parseInt(value));
            case Psapi.ModuleFilter.ALL /* 3 */:
                return Integer.valueOf(Integer.parseUnsignedInt(value));
            case true:
                return Long.valueOf(Long.parseLong(value));
            case true:
                return Long.valueOf(Long.parseUnsignedLong(value));
            case true:
                return Double.valueOf(Double.parseDouble(value));
            default:
                return value;
        }
    }

    private static Variant parseDict(String payload) {
        String sigType = null;
        String sig = null;
        StringBuilder buffer = new StringBuilder();
        boolean nested = false;
        boolean escaped = false;
        for (int i = 0; i < payload.length(); i++) {
            char c = payload.charAt(i);
            if (c == '\"') {
                escaped = !escaped;
            }
            if (!escaped) {
                if (c == '(') {
                    nested = true;
                } else if (c == ')') {
                    nested = false;
                } else if (nested && c == ' ') {
                    if (buffer.length() == 0) {
                        continue;
                    } else if (sigType == null) {
                        sigType = buffer.toString();
                        buffer = new StringBuilder();
                        if (!sigType.equals("string")) {
                            throw new IllegalArgumentException("Invalid dict sig type: " + sigType);
                        }
                    } else if (sig == null) {
                        sig = (String) parseVariant(sigType, buffer.toString().trim());
                        buffer = new StringBuilder();
                    }
                }
            }
            if (nested) {
                buffer.append(c);
            }
        }
        return new Variant(sig, parse0(buffer.toString().trim()));
    }
}
