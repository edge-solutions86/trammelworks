package io.github.rkaushikdev.trammelworks.ship;

import io.github.rkaushikdev.trammelworks.NanoTime;
import io.github.rkaushikdev.trammelworks.ParseError;
import io.github.rkaushikdev.trammelworks.Repr;
import io.github.rkaushikdev.trammelworks.ShipError;
import io.github.rkaushikdev.trammelworks.Text;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Proving a batch came off the box it says it did.
 *
 * <p>TLS covers the wire but not the other route batches take: a site whose modem has been dead
 * for a week is visited and its batches leave on a memory stick. So each batch carries a detached
 * HMAC-SHA256 signature over {@code version \n key id \n timestamp \n site \n batch id \n body}.
 * Binding the key id, site and batch id into what is signed stops a batch being replayed as another
 * site's or under another key, and the signed timestamp is checked against a window so a batch
 * pulled off a stick a month later is refused.
 */
public final class Signing {

    /** The signing scheme. */
    public static final String SIGNATURE_VERSION = "stanchion-batch-v1";

    /** How far out of step a timestamp may be: about how long a rural site waits for a visit. */
    public static final double DEFAULT_SKEW_SECONDS = 10 * 24 * 60 * 60;

    static final Pattern HEADER_PATTERN =
            Pattern.compile("^(?<version>\\S+) keyid=(?<keyid>\\S+) ts=(?<ts>\\S+) sig=(?<sig>[0-9a-f]{64})$");

    private Signing() {}

    /** Everything the signature covers, in order. */
    public static byte[] signingString(String keyId, String timestamp, String site, String batchId, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : List.of(SIGNATURE_VERSION, keyId, timestamp, site, batchId)) {
            if (part.contains("\n")) {
                throw new ShipError("a signed field cannot contain a newline", part);
            }
            out.writeBytes(part.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** Read the one line form back. */
    public static Signature parse(String raw) {
        Matcher match = HEADER_PATTERN.matcher(Text.strip(raw));
        if (!match.find()) {
            throw new ShipError("signature is malformed", raw.substring(0, Math.min(80, raw.length())));
        }
        return new Signature(match.group("keyid"), match.group("ts"), match.group("sig"), match.group("version"));
    }

    /** Sign one batch body. */
    public static Signature sign(SigningKey key, String site, String batchId, byte[] body, NanoTime at) {
        String timestamp = at.isoformat();
        byte[] material = signingString(key.keyId(), timestamp, site, batchId, body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.secret(), "HmacSHA256"));
            return new Signature(key.keyId(), timestamp, HexFormat.of().formatHex(mac.doFinal(material)));
        } catch (GeneralSecurityException exc) {
            throw new IllegalStateException("HmacSHA256 is part of every Java runtime", exc);
        }
    }

    /** Check a signature with the default window. */
    public static Check verify(
            SigningKey key, Signature signature, String site, String batchId, byte[] body, NanoTime now) {
        return verify(key, signature, site, batchId, body, now, DEFAULT_SKEW_SECONDS);
    }

    /**
     * Check a signature, and say plainly why not. Every failure returns rather than throws: the
     * caller is a collector walking a memory stick and one bad file must not stop the rest.
     */
    public static Check verify(
            SigningKey key,
            Signature signature,
            String site,
            String batchId,
            byte[] body,
            NanoTime now,
            double skewSeconds) {
        if (!signature.version().equals(SIGNATURE_VERSION)) {
            return Check.refused("signed by another version: " + signature.version());
        }
        if (!signature.keyId().equals(key.keyId())) {
            return Check.refused("signed by another key: " + signature.keyId());
        }
        NanoTime signedAt;
        try {
            signedAt = NanoTime.parse(signature.timestamp());
        } catch (ParseError exc) {
            return Check.refused("signature timestamp is unreadable");
        }
        double drift = Math.abs(now.epochNs() - signedAt.epochNs()) / 1_000_000_000.0;
        if (drift > skewSeconds) {
            return Check.refused("signed " + Repr.fixed(drift, 0) + " seconds away from now");
        }
        Signature expected = sign(key, site, batchId, body, signedAt);
        boolean same = MessageDigest.isEqual(
                expected.digest().getBytes(StandardCharsets.US_ASCII),
                signature.digest().getBytes(StandardCharsets.US_ASCII));
        return same ? Check.passed() : Check.refused("signature does not match");
    }
}
