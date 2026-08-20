package de.labystudio.spotifyapi.open.totp.provider;

import de.labystudio.spotifyapi.open.totp.model.Secret;

public class DefaultSecretProvider implements SecretProvider {
    private final Secret secret;

    public DefaultSecretProvider(Secret secret) {
        this.secret = secret;
    }

    @Override // de.labystudio.spotifyapi.open.totp.provider.SecretProvider
    public Secret getSecret() {
        return this.secret;
    }
}
