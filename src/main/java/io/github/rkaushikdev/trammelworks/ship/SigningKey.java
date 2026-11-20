package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.Bytes;
import io.github.rkaushikdev.trammelworks.JsonFiles;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * The secret one site signs with, and the name the collector knows it by.
 *
 * <p>The key never goes in the config file: the config names a path, the file is read at startup
 * and held in memory only. A commissioning engineer copies site configs between boxes routinely,
 * and a secret in one would end up on every forecourt in the estate.
 */
public final class SigningKey {

    /** Anything shorter is not a key, whatever the commissioning sheet says. */
    public static final int MIN_SECRET_BYTES = 32;

    /** Anything longer is not a key either. */
    public static final int MAX_SECRET_BYTES = 4096;

    static final Pattern KEY_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$");

    private final String keyId;
    private final byte[] secret;

    /** A key, checked for shape. */
    public SigningKey(String keyId, byte[] secret) {
        if (!KEY_ID_PATTERN.matcher(keyId).find()) {
            throw new ShipError("malformed signing key id", keyId);
        }
        if (secret.length < MIN_SECRET_BYTES) {
            throw new ShipError(
                    "signing secret is too short", secret.length + " bytes, " + MIN_SECRET_BYTES + " needed");
        }
        if (secret.length > MAX_SECRET_BYTES) {
            throw new ShipError("signing secret is implausibly long", Integer.toString(secret.length));
        }
        this.keyId = keyId;
        this.secret = secret.clone();
    }

    /**
     * Read a signing secret off disk. The file holds the secret and nothing else; surrounding
     * whitespace is stripped because an editor will add a trailing newline.
     */
    public static SigningKey read(Path path, String keyId) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(path);
        } catch (IOException exc) {
            throw new ShipError("signing key file unreadable", JsonFiles.describe(exc));
        }
        byte[] secret = Bytes.strip(raw);
        if (secret.length == 0) {
            throw new ShipError("signing key file is empty", path.toString());
        }
        return new SigningKey(keyId, secret);
    }

    /** The name the collector knows the key by. */
    public String keyId() {
        return keyId;
    }

    /** The secret itself. */
    public byte[] secret() {
        return secret.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SigningKey key && keyId.equals(key.keyId) && Arrays.equals(secret, key.secret);
    }

    @Override
    public int hashCode() {
        return keyId.hashCode() * 31 + Arrays.hashCode(secret);
    }

    /** Never the secret: these objects end up in stack traces. */
    @Override
    public String toString() {
        return "SigningKey(key_id=" + Repr.of(keyId) + ", secret=<" + secret.length + " bytes>)";
    }
}
