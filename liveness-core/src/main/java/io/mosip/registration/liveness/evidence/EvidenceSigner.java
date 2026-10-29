package io.mosip.registration.liveness.evidence;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Objects;

/**
 * Signs liveness evidence with the station's key. In the Registration Client this is the machine key the server
 * already knows (TPM-backed, used to sign packets): implement it over {@code ClientCryptoFacade} so the private
 * key never leaves the TPM. {@link #withKey} covers a software key, for stations without a TPM and for tests.
 */
public interface EvidenceSigner {

    /** JWS algorithm: {@code ES256} (ECDSA P-256) or {@code RS256} (RSA PKCS#1 v1.5). */
    String algorithm();

    /** Identifies the station key to the server, e.g. the machine ID or the key's fingerprint. */
    String keyId();

    /**
     * Signs the JWS signing input. ES256 returns the 64-byte R||S form JWS uses, RS256 the plain PKCS#1 signature
     * (what {@code SHA256withRSA} and a TPM produce).
     */
    byte[] sign(byte[] signingInput) throws GeneralSecurityException;

    /** A signer over a software key pair; the key ID is {@link LivenessEvidenceJws#fingerprint(PublicKey)}. */
    static EvidenceSigner withKey(PrivateKey privateKey, PublicKey publicKey) {
        Objects.requireNonNull(privateKey, "privateKey");
        String alg = LivenessEvidenceJws.algorithmFor(publicKey);
        String kid = LivenessEvidenceJws.fingerprint(publicKey);
        return new EvidenceSigner() {
            @Override
            public String algorithm() {
                return alg;
            }

            @Override
            public String keyId() {
                return kid;
            }

            @Override
            public byte[] sign(byte[] signingInput) throws GeneralSecurityException {
                Signature s = Signature.getInstance(LivenessEvidenceJws.javaAlgorithm(alg));
                s.initSign(privateKey);
                s.update(signingInput);
                byte[] sig = s.sign();
                return "ES256".equals(alg) ? LivenessEvidenceJws.derToRaw(sig) : sig;
            }
        };
    }
}
