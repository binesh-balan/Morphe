package stirling.software.proprietary.testutil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Signing keystores for the certificate tests, generated per JVM rather than committed.
 *
 * <p>These replace the fixtures under {@code src/test/resources/test-certs}, which carried two
 * separate time bombs on the same date. {@code valid-test.p12} was valid until 2027-03-25, so the
 * tests asserting a good certificate would start failing after it. {@code not-yet-valid-test.p12}
 * opened on 2027-03-25, so the tests asserting rejection would start failing once it came into
 * force - the same trap in the opposite direction, and the harder one to spot.
 *
 * <p>"Expired" and "not yet valid" are relative to now by definition, so a fixed file can never
 * express them for long. Each window here is built around the current time instead. The sibling
 * core-module fixtures caused a repository-wide CI outage when they expired on 2026-08-26; this is
 * the same class of problem, fixed before it fires.
 */
public final class TestKeystores {

    /** The password the PKCS#12 consumers pass. */
    public static final String PASSWORD = "testpass";

    /** The JKS fixture used a different password, and its consumers still pass this one. */
    public static final String JKS_PASSWORD = "jkspass";

    public static final String ALIAS = "test";

    private static final long DAY = 24L * 60 * 60 * 1000;

    private TestKeystores() {}

    /** Valid now and for a decade. */
    public static byte[] validPkcs12() {
        long now = System.currentTimeMillis();
        return keystore(
                "PKCS12",
                PASSWORD,
                "CN=Test Signer",
                new Date(now - DAY),
                new Date(now + 3650 * DAY));
    }

    /** Same shape in a JKS container, under the JKS fixture's own password. */
    public static byte[] validJks() {
        long now = System.currentTimeMillis();
        return keystore(
                "JKS",
                JKS_PASSWORD,
                "CN=Test Signer",
                new Date(now - DAY),
                new Date(now + 3650 * DAY));
    }

    /** Window closed a year ago, so it is expired whenever the suite runs. */
    public static byte[] expiredPkcs12() {
        long now = System.currentTimeMillis();
        return keystore(
                "PKCS12",
                PASSWORD,
                "CN=Expired Signer",
                new Date(now - 730 * DAY),
                new Date(now - 365 * DAY));
    }

    /** Window opens a year from now, so it is never yet in force when the suite runs. */
    public static byte[] notYetValidPkcs12() {
        long now = System.currentTimeMillis();
        return keystore(
                "PKCS12",
                PASSWORD,
                "CN=Future Signer",
                new Date(now + 365 * DAY),
                new Date(now + 730 * DAY));
    }

    /**
     * Resolves the fixture filenames the tests still refer to, so call sites read the same as
     * before and only the source of the bytes changed.
     */
    public static byte[] byFixtureName(String filename) {
        return switch (filename) {
            case "valid-test.p12" -> validPkcs12();
            case "valid-test.jks" -> validJks();
            case "expired-test.p12" -> expiredPkcs12();
            case "not-yet-valid-test.p12" -> notYetValidPkcs12();
            default -> throw new IllegalArgumentException("Unknown test keystore: " + filename);
        };
    }

    private static byte[] keystore(
            String type, String password, String cn, Date notBefore, Date notAfter) {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            X500Principal dn = new X500Principal(cn + ", O=Stirling Test, C=GB");
            ContentSigner signer =
                    new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
            X509CertificateHolder holder =
                    new JcaX509v3CertificateBuilder(
                                    dn,
                                    BigInteger.valueOf(System.nanoTime()),
                                    notBefore,
                                    notAfter,
                                    dn,
                                    keyPair.getPublic())
                            .build(signer);
            X509Certificate certificate =
                    new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

            char[] pin = password.toCharArray();
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, pin);
            keyStore.setKeyEntry(ALIAS, keyPair.getPrivate(), pin, new Certificate[] {certificate});
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                keyStore.store(out, pin);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build " + type + " test keystore", e);
        }
    }
}
