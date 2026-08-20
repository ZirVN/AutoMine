package de.labystudio.spotifyapi.open.totp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TOTP {
    private static final String DEFAULT_ALGORITHM = "HmacSHA1";

    public static String generateOtp(byte[] secret, long time, int period, int digits) {
        long counter = time / period;
        ByteBuffer buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(counter);
        byte[] counterBytes = buffer.array();
        try {
            Mac mac = Mac.getInstance(DEFAULT_ALGORITHM);
            mac.init(new SecretKeySpec(secret, DEFAULT_ALGORITHM));
            byte[] hmac = mac.doFinal(counterBytes);
            int offset = hmac[hmac.length - 1] & 15;
            int binary = ((hmac[offset] & Byte.MAX_VALUE) << 24) | ((hmac[offset + 1] & 255) << 16) | ((hmac[offset + 2] & 255) << 8) | (hmac[offset + 3] & 255);
            int otp = binary % ((int) Math.pow(10.0d, digits));
            return String.format("%0" + digits + "d", Integer.valueOf(otp));
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to generate TOTP", e);
        }
    }
}
