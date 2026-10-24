package io.mosip.registration.liveness.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Keys, signatures and hashing for model packages. Signatures are ECDSA P-256 with SHA-256 (or
 * RSA with SHA-256 for RSA keys), both available on Java 11 and Android without extra libraries.
 * Keys are PEM files: X.509 {@code PUBLIC KEY} and PKCS#8 {@code PRIVATE KEY}.
 *
 * <p>The private key belongs to the release process, never to a Registration Client; clients hold
 * only the public key.
 */
public final class ModelSigning {

    private ModelSigning() {
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    public static String toPem(Key key) {
        String type = key instanceof PrivateKey ? "PRIVATE KEY" : "PUBLIC KEY";
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded());
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    public static PublicKey readPublicKey(Path pem) throws IOException, GeneralSecurityException {
        byte[] der = fromPem(new String(Files.readAllBytes(pem), StandardCharsets.US_ASCII), "PUBLIC KEY");
        GeneralSecurityException last = null;
        for (String algorithm : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw last;
    }

    public static PrivateKey readPrivateKey(Path pem) throws IOException, GeneralSecurityException {
        byte[] der = fromPem(new String(Files.readAllBytes(pem), StandardCharsets.US_ASCII), "PRIVATE KEY");
        GeneralSecurityException last = null;
        for (String algorithm : new String[] {"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (GeneralSecurityException e) {
                last = e;
            }
        }
        throw last;
    }

    static byte[] sign(byte[] data, PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(algorithm(key));
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    static boolean verify(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance(algorithm(key));
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static String algorithm(Key key) {
        return "RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
    }

    /**
     * Writes {@code manifest.properties} and {@code manifest.sig} for every file in {@code dir}
     * (a flat directory of model files). Used by the release process, not by clients.
     */
    public static ModelManifest signPackage(Path dir, String name, String version, long sequence,
                                            String engineMinVersion, PrivateKey key)
            throws IOException, GeneralSecurityException, ModelIntegrityException {
        Map<String, ModelManifest.Entry> files = new TreeMap<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path file : entries) {
                String fileName = file.getFileName().toString();
                if (fileName.equals(ModelManifest.MANIFEST_FILE) || fileName.equals(ModelManifest.SIGNATURE_FILE)) {
                    continue;
                }
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                    throw new ModelIntegrityException("Model packages are flat directories of regular files: " + file);
                }
                ModelManifest.checkFileName(fileName);
                files.put(fileName, new ModelManifest.Entry(sha256(file), Files.size(file)));
            }
        }
        ModelManifest manifest = new ModelManifest(name, version, sequence, engineMinVersion,
                Instant.now().toString(), files);
        byte[] bytes = manifest.toBytes();
        Files.write(dir.resolve(ModelManifest.MANIFEST_FILE), bytes);
        Files.write(dir.resolve(ModelManifest.SIGNATURE_FILE),
                Base64.getEncoder().encode(sign(bytes, key)));
        return ModelManifest.parse(bytes);
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static byte[] fromPem(String pem, String type) throws GeneralSecurityException {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw new GeneralSecurityException("Not a PEM " + type);
        }
        try {
            return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("Corrupt PEM " + type, e);
        }
    }
}
