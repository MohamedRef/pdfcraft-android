import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.HexFormat;

/** Fail fast on incorrect GitHub Actions signing secrets, without printing secret values. */
public final class VerifyAndroidSigning {
    /** Optional pin for an already published app; an unset pin does not disable APK verification. */
    private static String optionalCertificatePin() {
        String raw = System.getenv("PDFCRAFT_CERT_SHA256");
        if (raw == null || raw.isBlank()) return null;
        String value = raw.replace(":", "").replaceAll("\\s", "")
                .toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            fail("Invalid signing certificate fingerprint",
                    "PDFCRAFT_CERT_SHA256 Actions variable must contain a valid SHA-256 fingerprint.");
        }
        return value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            fail("Missing Android signing secret",
                    "Set " + name + " under Settings > Secrets and variables > Actions > Secrets.");
        }
        return value;
    }

    private static void fail(String title, String message) {
        System.err.println("::error title=" + title + "::" + message);
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(required("PDFCRAFT_KEYSTORE"));
        String storePassword = required("PDFCRAFT_KEYSTORE_PASSWORD");
        String keyAlias = required("PDFCRAFT_KEY_ALIAS");
        String keyPassword = required("PDFCRAFT_KEY_PASSWORD");

        if (!Files.isRegularFile(path) || Files.size(path) == 0) {
            fail("Invalid Android keystore", "PDFCRAFT_KEYSTORE_BASE64 did not decode to a nonempty keystore file.");
        }

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) {
            keystore.load(input, storePassword.toCharArray());
        } catch (Exception e) {
            fail("Incorrect keystore password or file", "Cannot open PKCS12 signing key. Check PDFCRAFT_KEYSTORE_PASSWORD against the original .p12 and confirm PDFCRAFT_KEYSTORE_BASE64 encodes that same file.");
            return;
        }

        if (!keystore.isKeyEntry(keyAlias)) {
            fail("Incorrect signing key alias", "PDFCRAFT_KEY_ALIAS must name a private key entry in the original .p12 file.");
            return;
        }

        try {
            Key key = keystore.getKey(keyAlias, keyPassword.toCharArray());
            if (!(key instanceof PrivateKey)) {
                fail("Invalid signing key entry", "The configured alias does not contain a private key.");
                return;
            }
        } catch (Exception e) {
            fail("Incorrect private key password", "PDFCRAFT_KEY_PASSWORD does not unlock the private key stored under PDFCRAFT_KEY_ALIAS.");
            return;
        }

        Certificate cert = keystore.getCertificate(keyAlias);
        if (cert == null) {
            fail("Missing signing certificate", "The private key entry has no certificate.");
            return;
        }
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        String pin = optionalCertificatePin();
        if (pin != null && !pin.equals(actual)) {
            fail("Unexpected signing certificate",
                    "The configured signing certificate differs from PDFCRAFT_CERT_SHA256. Preserve the original signing key for app updates.");
            return;
        }

        // GitHub Actions passes GITHUB_ENV to subsequent steps. The public certificate digest
        // is derived from the very keystore used to sign the APK, never from PR-provided code.
        String githubEnv = System.getenv("GITHUB_ENV");
        if (githubEnv != null && !githubEnv.isBlank()) {
            Files.writeString(Path.of(githubEnv), "PDFCRAFT_SIGNING_CERT_SHA256=" + actual + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }
        System.out.println("Verified PKCS12 store password, private key alias/password, and certificate."
                + (pin == null ? " No pinned certificate variable was configured." : " Pinned certificate matches."));
    }
}
