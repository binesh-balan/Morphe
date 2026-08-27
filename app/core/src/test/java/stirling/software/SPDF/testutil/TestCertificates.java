package stirling.software.SPDF.testutil;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A self-signed signing certificate, generated once per JVM and rendered into every container
 * format the signing tests need.
 *
 * <p>This replaces the fixtures that used to live in {@code src/test/resources/certs}. They were
 * issued for one year and expired at 07:41 UTC on 2026-08-26, which turned the whole build red -
 * not just the obvious {@code CertSignControllerTest}, but every test that signed a PDF with them,
 * where an expired signer surfaced indirectly as null signature metadata. Signing code is
 * <em>supposed</em> to reject an expired certificate, so a committed fixture can never be the right
 * input for these tests: what they need is one that is valid right now, and only generating it
 * stays true over time.
 */
public final class TestCertificates {

    /** Matches the password the replaced fixtures used, so callers did not have to change. */
    public static final String PASSWORD = "password";

    public static final String ALIAS = "test";

    private static final KeyPair KEY_PAIR;
    private static final X509Certificate CERTIFICATE;

    static {
        try {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KEY_PAIR = keyPairGenerator.generateKeyPair();

            X500Principal dn =
                    new X500Principal("CN=Test Signer, OU=Test, O=Test, L=SF, ST=CA, C=US");
            long now = System.currentTimeMillis();
            // Backdated a day so a clock running slightly behind still sees it as valid.
            // Opens well before "now" and closes long after, so callers can assert against dates
            // either side of today without the window becoming a second expiry to maintain.
            Date notBefore = new Date(now - 1825L * 24 * 60 * 60 * 1000);
            Date notAfter = new Date(now + 3650L * 24 * 60 * 60 * 1000);
            ContentSigner signer =
                    new JcaContentSignerBuilder("SHA256WithRSA").build(KEY_PAIR.getPrivate());
            X509CertificateHolder holder =
                    new JcaX509v3CertificateBuilder(
                                    dn,
                                    BigInteger.valueOf(now),
                                    notBefore,
                                    notAfter,
                                    dn,
                                    KEY_PAIR.getPublic())
                            // The replaced fixture was a CA certificate and tests assert isCA().
                            .addExtension(
                                    Extension.basicConstraints, true, new BasicConstraints(true))
                            .build(signer);
            CERTIFICATE =
                    new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private TestCertificates() {}

    public static X509Certificate certificate() {
        return CERTIFICATE;
    }

    public static PrivateKey privateKey() {
        return KEY_PAIR.getPrivate();
    }

    public static Certificate[] chain() {
        return new Certificate[] {CERTIFICATE};
    }

    public static byte[] pkcs12() {
        return keyStoreBytes("PKCS12");
    }

    public static byte[] jks() {
        return keyStoreBytes("JKS");
    }

    /** PEM-encoded certificate - what the .pem, .crt and .cer fixtures all held. */
    public static byte[] certificatePem() {
        try {
            return pem("CERTIFICATE", CERTIFICATE.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode test certificate", e);
        }
    }

    public static byte[] certificateDer() {
        try {
            return CERTIFICATE.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode test certificate", e);
        }
    }

    /**
     * Traditional "RSA PRIVATE KEY" (PKCS#1), matching the replaced fixture.
     *
     * <p>Not PKCS#8: {@code CertSignController.getPrivateKeyFromPEM} casts the parsed object
     * straight to {@code PEMKeyPair}, so an unencrypted PKCS#8 "PRIVATE KEY" - what current OpenSSL
     * emits by default - fails there with a ClassCastException. That is a real gap in the upload
     * path, but widening it is a behaviour change and does not belong in a build fix.
     */
    public static byte[] privateKeyPem() {
        try {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(KEY_PAIR.getPrivate().getEncoded());
            return pem("RSA PRIVATE KEY", info.parsePrivateKey().toASN1Primitive().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode test private key", e);
        }
    }

    private static byte[] keyStoreBytes(String type) {
        try {
            char[] pin = PASSWORD.toCharArray();
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, pin);
            keyStore.setKeyEntry(ALIAS, KEY_PAIR.getPrivate(), pin, chain());
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                keyStore.store(out, pin);
                return out.toByteArray();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build " + type + " test keystore", e);
        }
    }

    private static byte[] pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }
}
