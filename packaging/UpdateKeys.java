import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signing keys for automatic updates. Run with plain Java 21 (no build needed):
 *
 *   java packaging/UpdateKeys.java gen
 *       Makes a new key pair. Writes the public key to packaging/update-public-key.txt (commit it)
 *       and prints the private key: save it as the GitHub secret UPDATE_SIGNING_KEY. Never commit it.
 *
 *   UPDATE_SIGNING_KEY=... java packaging/UpdateKeys.java feed <version> <downloadBaseUrl> <notesFile> windows=<dir> mac=<dir>
 *       Signs the staged jar lists (build/stage/<os>, from ./gradlew stageApp) and prints latest.json (used by the release workflow).
 *
 * Installed copies accept an update only if its signature matches the public key they were built with,
 * so nobody else can push software to the workshop PC, even if they could change the download.
 */
public class UpdateKeys {
    public static void main(String[] a) throws Exception {
        Path pubFile = Path.of("packaging/update-public-key.txt");
        switch (a.length == 0 ? "" : a[0]) {
            case "gen" -> {
                KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                Files.writeString(pubFile, b64(kp.getPublic().getEncoded()) + "\n");
                System.out.println("Public key written to " + pubFile + " (commit this file).");
                System.out.println();
                System.out.println("Private key - add it to GitHub as the repository secret UPDATE_SIGNING_KEY, and keep a copy somewhere safe:");
                System.out.println(b64(kp.getPrivate().getEncoded()));
            }
            case "feed" -> {
                String version = a[1], base = a[2].replaceAll("/+$", "");
                String notes = Files.exists(Path.of(a[3])) ? Files.readString(Path.of(a[3])).trim() : "";
                String env = System.getenv("UPDATE_SIGNING_KEY");
                if (env == null || env.isBlank()) throw new IllegalStateException("UPDATE_SIGNING_KEY is not set");
                PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(env.trim())));
                PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(Files.readString(pubFile).trim())));
                StringBuilder platforms = new StringBuilder();
                for (int i = 4; i < a.length; i++) {
                    // os=<staged folder holding classpath.txt and lib/>
                    String os = a[i].substring(0, a[i].indexOf('=')); Path dir = Path.of(a[i].substring(a[i].indexOf('=') + 1));
                    StringBuilder msg = new StringBuilder("WorkshopManager\n" + version + "\n" + os + "\n"), list = new StringBuilder();
                    for (String name : Files.readAllLines(dir.resolve("classpath.txt"))) {
                        if (name.isBlank()) continue;
                        byte[] bytes = Files.readAllBytes(dir.resolve("lib").resolve(name));
                        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                        msg.append(name).append(' ').append(bytes.length).append(' ').append(sha).append('\n');
                        if (list.length() > 0) list.append(",\n");
                        list.append("        {\"name\": \"").append(name).append("\", \"size\": ").append(bytes.length).append(", \"sha256\": \"").append(sha).append("\"}");
                    }
                    byte[] m = msg.toString().getBytes(StandardCharsets.UTF_8);
                    Signature s = Signature.getInstance("Ed25519"); s.initSign(priv); s.update(m); byte[] sig = s.sign();
                    Signature v = Signature.getInstance("Ed25519"); v.initVerify(pub); v.update(m);
                    if (!v.verify(sig)) throw new IllegalStateException("UPDATE_SIGNING_KEY does not match packaging/update-public-key.txt");
                    if (platforms.length() > 0) platforms.append(",\n");
                    platforms.append("    \"").append(os).append("\": {\n      \"sig\": \"").append(b64(sig)).append("\",\n      \"files\": [\n").append(list).append("\n      ]\n    }");
                }
                System.out.println("{\n  \"version\": \"" + version + "\",\n  \"date\": \"" + java.time.LocalDate.now() + "\",\n  \"minJava\": 21,\n"
                        + "  \"notes\": \"" + json(notes) + "\",\n  \"base\": \"" + base + "\",\n  \"platforms\": {\n" + platforms + "\n  }\n}");
            }
            default -> System.err.println("usage: java packaging/UpdateKeys.java gen | feed <version> <baseUrl> <notesFile> os=<stageDir>...");
        }
    }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    static String json(String s) {
        StringBuilder o = new StringBuilder();
        for (char c : s.toCharArray()) switch (c) {
            case '"' -> o.append("\\\""); case '\\' -> o.append("\\\\"); case '\n' -> o.append("\\n"); case '\r' -> { } case '\t' -> o.append("\\t");
            default -> { if (c < 0x20) o.append(String.format("\\u%04x", (int) c)); else o.append(c); }
        }
        return o.toString();
    }
}
