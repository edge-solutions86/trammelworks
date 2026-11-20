package io.github.rkaushikdev.trammelworks.ship;

/**
 * A detached signature and the envelope it is checked against.
 *
 * @param keyId the key it was made with
 * @param timestamp when, as signed
 * @param digest the HMAC, hex
 * @param version the signing scheme
 */
public record Signature(String keyId, String timestamp, String digest, String version) {

    /** A signature in the current scheme. */
    public Signature(String keyId, String timestamp, String digest) {
        this(keyId, timestamp, digest, Signing.SIGNATURE_VERSION);
    }

    /** The one line form that goes in a header or beside a file. */
    public String header() {
        return version + " keyid=" + keyId + " ts=" + timestamp + " sig=" + digest;
    }
}
