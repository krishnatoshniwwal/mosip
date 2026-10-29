package io.mosip.registration.liveness.evidence;

import io.mosip.registration.liveness.device.sbi.MiniJson;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Liveness evidence as a compact JWS (RFC 7515), the same envelope MOSIP devices use for signed biometrics, so the
 * server can check it with any JOSE library or with {@link #verify}. Header: {@code alg} (ES256 or RS256),
 * {@code kid} and {@code typ}; payload: {@link LivenessEvidence#toJson()}.
 *
 * <p>Verification fails closed. The algorithm must match the station's key type (no {@code none}, no switching
 * algorithms), the signature is checked before the payload is read, and the evidence is accepted only if liveness
 * passed and its capture hash matches the biometric in the packet.
 */
public final class LivenessEvidenceJws {

    public static final String TYPE = "mosip-liveness-evidence+jws";

    /** Upper bound on an evidence token, so a hostile packet cannot make the verifier parse megabytes. */
    static final int MAX_LENGTH = 16 * 1024;

    /** Accepted clock difference between station and server for {@code iat}. */
    static final long CLOCK_SKEW_SECONDS = 300;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private LivenessEvidenceJws() {
    }

    public static String sign(LivenessEvidence evidence, EvidenceSigner signer) throws GeneralSecurityException {
        Objects.requireNonNull(evidence, "evidence");
        String alg = signer.algorithm();
        javaAlgorithm(alg);
        String header = "{\"alg\":\"" + alg + "\",\"kid\":" + jsonString(signer.keyId()) + ",\"typ\":\"" + TYPE + "\"}";
        String input = B64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(evidence.toJson().getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + B64.encodeToString(signature);
    }

    /**
     * Server-side check of the evidence that came with a face capture.
     *
     * @param stationKey        the public key registered for the station that created the packet
     * @param capturedBiometric the face bytes in the packet; the evidence must name exactly these
     * @param subjectKey        the packet's registration ID (or user ID for operator/supervisor), or null to skip
     * @param now               the server's clock; evidence issued later than this (plus skew) is refused
     */
    public static EvidenceVerification verify(String jws, PublicKey stationKey, byte[] capturedBiometric,
                                              String subjectKey, Instant now) {
        Objects.requireNonNull(stationKey, "stationKey");
        if (jws == null || jws.length() > MAX_LENGTH) {
            return EvidenceVerification.of(EvidenceVerification.Status.MALFORMED, null, "missing or too long");
        }
        String[] parts = jws.trim().split("\\.", -1);
        if (parts.length != 3) {
            return EvidenceVerification.of(EvidenceVerification.Status.MALFORMED, null, "not a compact JWS");
        }
        String expectedAlg;
        try {
            expectedAlg = algorithmFor(stationKey);
            Object header = MiniJson.parse(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
            if (!(header instanceof Map) || !TYPE.equals(((Map<?, ?>) header).get("typ"))) {
                return EvidenceVerification.of(EvidenceVerification.Status.MALFORMED, null, "not liveness evidence");
            }
            if (!expectedAlg.equals(((Map<?, ?>) header).get("alg"))) {
                return EvidenceVerification.of(EvidenceVerification.Status.BAD_SIGNATURE, null,
                        "algorithm " + ((Map<?, ?>) header).get("alg") + " does not match the station key");
            }
        } catch (IllegalArgumentException e) {
            return EvidenceVerification.of(EvidenceVerification.Status.MALFORMED, null, e.getMessage());
        }

        try {
            byte[] signature = B64D.decode(parts[2]);
            Signature s = Signature.getInstance(javaAlgorithm(expectedAlg));
            s.initVerify(stationKey);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            boolean ok = s.verify("ES256".equals(expectedAlg) ? rawToDer(signature) : signature);
            if (!ok) {
                return EvidenceVerification.of(EvidenceVerification.Status.BAD_SIGNATURE, null, "signature does not verify");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return EvidenceVerification.of(EvidenceVerification.Status.BAD_SIGNATURE, null, e.getMessage());
        }

        LivenessEvidence evidence;
        try {
            evidence = LivenessEvidence.fromJson(new String(B64D.decode(parts[1]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return EvidenceVerification.of(EvidenceVerification.Status.MALFORMED, null, e.getMessage());
        }
        if (evidence.getIssuedAt() > now.getEpochSecond() + CLOCK_SKEW_SECONDS) {
            return EvidenceVerification.of(EvidenceVerification.Status.NOT_YET_VALID, evidence,
                    "issued at " + Instant.ofEpochSecond(evidence.getIssuedAt()));
        }
        if (subjectKey != null && !LivenessEvidence.sha256Hex(subjectKey.getBytes(StandardCharsets.UTF_8))
                .equals(evidence.getSubjectSha256())) {
            return EvidenceVerification.of(EvidenceVerification.Status.SUBJECT_MISMATCH, evidence,
                    "evidence is for another registration or user");
        }
        if (!evidence.isLivenessPassed()) {
            return EvidenceVerification.of(EvidenceVerification.Status.LIVENESS_NOT_PASSED, evidence,
                    evidence.getOutcome() + " / " + evidence.getGateStatus());
        }
        if (capturedBiometric == null
                || !LivenessEvidence.sha256Hex(capturedBiometric).equals(evidence.getCaptureSha256())) {
            return EvidenceVerification.of(EvidenceVerification.Status.CAPTURE_MISMATCH, evidence,
                    "evidence does not name this face capture");
        }
        return EvidenceVerification.of(EvidenceVerification.Status.ACCEPTED, evidence, "liveness verified on station "
                + evidence.getStationId());
    }

    /** First 16 bytes of the SHA-256 of the key's X.509 encoding, hex: a short, stable key ID. */
    public static String fingerprint(PublicKey key) {
        return LivenessEvidence.sha256Hex(key.getEncoded()).substring(0, 32);
    }

    static String algorithmFor(PublicKey key) {
        switch (key.getAlgorithm()) {
            case "EC":
                return "ES256";
            case "RSA":
                return "RS256";
            default:
                throw new IllegalArgumentException("unsupported key type " + key.getAlgorithm());
        }
    }

    static String javaAlgorithm(String jwsAlg) {
        switch (String.valueOf(jwsAlg)) {
            case "ES256":
                return "SHA256withECDSA";
            case "RS256":
                return "SHA256withRSA";
            default:
                throw new IllegalArgumentException("unsupported JWS algorithm " + jwsAlg);
        }
    }

    /** DER {@code SEQUENCE {INTEGER r, INTEGER s}} (what Java's ECDSA produces) to the 64-byte JWS form. */
    static byte[] derToRaw(byte[] der) throws GeneralSecurityException {
        int[] pos = {0};
        expectTag(der, pos, 0x30);
        int seqLength = length(der, pos);
        if (pos[0] + seqLength != der.length) {
            throw new GeneralSecurityException("bad ECDSA signature encoding");
        }
        byte[] raw = new byte[64];
        for (int k = 0; k < 2; k++) {
            expectTag(der, pos, 0x02);
            int n = length(der, pos);
            int start = pos[0];
            // Drop the sign byte(s) DER adds; the integer must then fit in 32 bytes.
            while (n > 32 && der[start] == 0) {
                start++;
                n--;
            }
            if (n > 32 || start + n > der.length) {
                throw new GeneralSecurityException("bad ECDSA signature encoding");
            }
            System.arraycopy(der, start, raw, k * 32 + 32 - n, n);
            pos[0] = start + n;
        }
        return raw;
    }

    /** The 64-byte JWS form back to DER for {@link Signature#verify}. */
    static byte[] rawToDer(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 64) {
            throw new GeneralSecurityException("ES256 signature must be 64 bytes, got " + raw.length);
        }
        ByteArrayOutputStream ints = new ByteArrayOutputStream(72);
        for (int k = 0; k < 2; k++) {
            byte[] v = Arrays.copyOfRange(raw, k * 32, k * 32 + 32);
            int start = 0;
            while (start < 31 && v[start] == 0) {
                start++;
            }
            boolean pad = (v[start] & 0x80) != 0;
            ints.write(0x02);
            ints.write(32 - start + (pad ? 1 : 0));
            if (pad) {
                ints.write(0);
            }
            ints.write(v, start, 32 - start);
        }
        byte[] body = ints.toByteArray();
        ByteArrayOutputStream der = new ByteArrayOutputStream(body.length + 3);
        der.write(0x30);
        if (body.length >= 128) {
            der.write(0x81);
        }
        der.write(body.length);
        der.write(body, 0, body.length);
        return der.toByteArray();
    }

    private static void expectTag(byte[] der, int[] pos, int tag) throws GeneralSecurityException {
        if (pos[0] >= der.length || (der[pos[0]++] & 0xFF) != tag) {
            throw new GeneralSecurityException("bad ECDSA signature encoding");
        }
    }

    private static int length(byte[] der, int[] pos) throws GeneralSecurityException {
        if (pos[0] >= der.length) {
            throw new GeneralSecurityException("bad ECDSA signature encoding");
        }
        int b = der[pos[0]++] & 0xFF;
        if (b < 0x80) {
            return b;
        }
        if (b != 0x81 || pos[0] >= der.length) {
            throw new GeneralSecurityException("bad ECDSA signature encoding");
        }
        return der[pos[0]++] & 0xFF;
    }

    private static String jsonString(String s) {
        StringBuilder j = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                j.append('\\').append(c);
            } else if (c >= 0x20) {
                j.append(c);
            }
        }
        return j.append('"').toString();
    }
}
