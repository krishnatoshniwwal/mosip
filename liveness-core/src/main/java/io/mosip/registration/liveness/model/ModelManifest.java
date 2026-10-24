package io.mosip.registration.liveness.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Contents of {@code manifest.properties} in a model package: identity, a monotonically increasing
 * sequence number (for rollback protection), the oldest engine that can run the models, and the
 * SHA-256 and size of every file. The manifest is what gets signed, so the signature covers every
 * model byte.
 *
 * <pre>
 * package.name=mosip-face-liveness
 * package.version=1.2.0
 * package.sequence=7
 * engine.minVersion=1.2.0
 * created=2026-10-24T09:00:00Z
 * file.face_detection_yunet_2023mar.onnx.sha256=8f2383e4...
 * file.face_detection_yunet_2023mar.onnx.size=232589
 * </pre>
 *
 * Packages are flat directories; file names may contain only letters, digits, '.', '_' and '-'.
 */
public final class ModelManifest {

    public static final String MANIFEST_FILE = "manifest.properties";
    public static final String SIGNATURE_FILE = "manifest.sig";

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** A file listed in the manifest. */
    public static final class Entry {
        private final String sha256;
        private final long size;

        Entry(String sha256, long size) {
            this.sha256 = sha256;
            this.size = size;
        }

        public String getSha256() { return sha256; }
        public long getSize() { return size; }
    }

    private final String name;
    private final String version;
    private final long sequence;
    private final String engineMinVersion;
    private final String created;
    private final Map<String, Entry> files;

    ModelManifest(String name, String version, long sequence, String engineMinVersion, String created,
                  Map<String, Entry> files) {
        this.name = name;
        this.version = version;
        this.sequence = sequence;
        this.engineMinVersion = engineMinVersion;
        this.created = created;
        this.files = Collections.unmodifiableMap(new TreeMap<>(files));
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    /** Strictly increasing across releases; a package never replaces one with an equal or higher sequence. */
    public long getSequence() { return sequence; }
    public String getEngineMinVersion() { return engineMinVersion; }
    public String getCreated() { return created; }
    public Map<String, Entry> getFiles() { return files; }

    /** Short label for audit records and {@code EngineInfo.modelVersion}. */
    public String label() {
        return name + " " + version + " #" + sequence;
    }

    /** Canonical text: sorted keys, LF line endings, no comments or timestamps added by {@link Properties#store}. */
    byte[] toBytes() {
        TreeMap<String, String> lines = new TreeMap<>();
        lines.put("package.name", name);
        lines.put("package.version", version);
        lines.put("package.sequence", Long.toString(sequence));
        lines.put("engine.minVersion", engineMinVersion);
        lines.put("created", created);
        for (Map.Entry<String, Entry> e : files.entrySet()) {
            lines.put("file." + e.getKey() + ".sha256", e.getValue().sha256);
            lines.put("file." + e.getKey() + ".size", Long.toString(e.getValue().size));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> line : lines.entrySet()) {
            sb.append(line.getKey()).append('=').append(line.getValue()).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static ModelManifest parse(byte[] bytes) throws ModelIntegrityException {
        Properties p = new Properties();
        try {
            p.load(new ByteArrayInputStream(bytes));
        } catch (IOException | IllegalArgumentException e) {
            throw new ModelIntegrityException("Unreadable model manifest", e);
        }
        String name = required(p, "package.name");
        String version = required(p, "package.version");
        String engineMin = required(p, "engine.minVersion");
        String created = required(p, "created");
        long sequence;
        try {
            sequence = Long.parseLong(required(p, "package.sequence"));
        } catch (NumberFormatException e) {
            throw new ModelIntegrityException("package.sequence is not a number");
        }
        if (sequence < 1) {
            throw new ModelIntegrityException("package.sequence must be positive");
        }
        Map<String, Entry> files = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (isHeader(key)) {
                continue;
            }
            // Every other key must be file.<name>.sha256 or file.<name>.size, and come in pairs.
            String file;
            if (key.startsWith("file.") && key.endsWith(".sha256")) {
                file = key.substring("file.".length(), key.length() - ".sha256".length());
            } else if (key.startsWith("file.") && key.endsWith(".size")) {
                file = key.substring("file.".length(), key.length() - ".size".length());
            } else {
                throw new ModelIntegrityException("Unknown manifest key " + key);
            }
            checkFileName(file);
            if (files.containsKey(file)) {
                continue;
            }
            String hash = required(p, "file." + file + ".sha256");
            if (!SHA256.matcher(hash).matches()) {
                throw new ModelIntegrityException("Bad SHA-256 for " + file);
            }
            long size;
            try {
                size = Long.parseLong(required(p, "file." + file + ".size"));
            } catch (NumberFormatException e) {
                throw new ModelIntegrityException("Bad size for " + file);
            }
            files.put(file, new Entry(hash, size));
        }
        if (files.isEmpty()) {
            throw new ModelIntegrityException("Manifest lists no files");
        }
        return new ModelManifest(name, version, sequence, engineMin, created, files);
    }

    static void checkFileName(String file) throws ModelIntegrityException {
        if (!FILE_NAME.matcher(file).matches() || file.startsWith(".")
                || MANIFEST_FILE.equals(file) || SIGNATURE_FILE.equals(file)) {
            throw new ModelIntegrityException("Illegal file name in model package: " + file);
        }
    }

    private static boolean isHeader(String key) {
        return key.equals("package.name") || key.equals("package.version") || key.equals("package.sequence")
                || key.equals("engine.minVersion") || key.equals("created");
    }

    private static String required(Properties p, String key) throws ModelIntegrityException {
        String v = p.getProperty(key);
        if (v == null || v.trim().isEmpty()) {
            throw new ModelIntegrityException("Manifest is missing " + key);
        }
        return v.trim();
    }

    /** Compares dotted numeric versions ("1.10.0" > "1.9.2"); non-numeric parts compare as 0. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(part(x, i), part(y, i));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
