package stirling.software.SPDF.controller.api.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;

import stirling.software.SPDF.model.api.security.SignPDFWithCertRequest;
import stirling.software.SPDF.service.HardwareKeyStoreService;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;

@ExtendWith(MockitoExtension.class)
class CertSignControllerTest {

    private static final String KEYSTORE_PASSWORD = "password";

    /** A self-signed key pair rendered into every container format the controller accepts. */
    private record CertificateMaterial(
            byte[] pkcs12,
            byte[] jks,
            byte[] certificatePem,
            byte[] certificateDer,
            byte[] privateKeyPem) {

        static CertificateMaterial generate(String password) throws Exception {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            X500Principal dn = new X500Principal("CN=Test, OU=Test, O=Test, L=SF, ST=CA, C=US");
            long now = System.currentTimeMillis();
            // Backdated a day so a clock skewed slightly behind the runner still sees it as valid.
            Date notBefore = new Date(now - 24L * 60 * 60 * 1000);
            Date notAfter = new Date(now + 3650L * 24 * 60 * 60 * 1000);
            ContentSigner signer =
                    new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
            X509CertificateHolder holder =
                    new JcaX509v3CertificateBuilder(
                                    dn,
                                    BigInteger.valueOf(now),
                                    notBefore,
                                    notAfter,
                                    dn,
                                    keyPair.getPublic())
                            .build(signer);
            X509Certificate certificate =
                    new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);

            char[] pin = password.toCharArray();
            Certificate[] chain = {certificate};
            return new CertificateMaterial(
                    keyStoreBytes("PKCS12", pin, keyPair.getPrivate(), chain),
                    keyStoreBytes("JKS", pin, keyPair.getPrivate(), chain),
                    pem("CERTIFICATE", certificate.getEncoded()),
                    certificate.getEncoded(),
                    pkcs1Pem(keyPair.getPrivate()));
        }

        private static byte[] keyStoreBytes(
                String type, char[] pin, PrivateKey privateKey, Certificate[] chain)
                throws Exception {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, pin);
            keyStore.setKeyEntry("test", privateKey, pin, chain);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                keyStore.store(out, pin);
                return out.toByteArray();
            }
        }

        /**
         * Emits the traditional "RSA PRIVATE KEY" (PKCS#1) form, which is what the replaced fixture
         * held. CertSignController's PEM branch casts straight to PEMKeyPair, so an unencrypted
         * PKCS#8 "PRIVATE KEY" - the modern OpenSSL default - throws ClassCastException there.
         */
        private static byte[] pkcs1Pem(PrivateKey privateKey) throws Exception {
            PrivateKeyInfo info = PrivateKeyInfo.getInstance(privateKey.getEncoded());
            return pem("RSA PRIVATE KEY", info.parsePrivateKey().toASN1Primitive().getEncoded());
        }

        private static byte[] pem(String label, byte[] der) {
            String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
            return ("-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n")
                    .getBytes(StandardCharsets.US_ASCII);
        }
    }

    private static ResponseEntity<Resource> streamingOk(byte[] bytes) {
        return ResponseEntity.ok(new ByteArrayResource(bytes));
    }

    private static byte[] drainBody(ResponseEntity<Resource> response) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream __in = response.getBody().getInputStream()) {
            __in.transferTo(baos);
        }
        return baos.toByteArray();
    }

    @Mock private CustomPDFDocumentFactory pdfDocumentFactory;
    @Mock private TempFileManager tempFileManager;
    @Mock private HardwareKeyStoreService hardwareKeyStoreService;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks private CertSignController certSignController;

    private byte[] pdfBytes;
    private byte[] pfxBytes;
    private byte[] p12Bytes;
    private byte[] jksBytes;
    private byte[] pemKeyBytes;
    private byte[] pemCertBytes;
    private byte[] keyBytes;
    private byte[] crtCertBytes;
    private byte[] cerCertBytes;
    private byte[] derCertBytes;

    /**
     * Signing material generated fresh for the run.
     *
     * <p>These used to be committed fixtures under {@code src/test/resources/certs}. They carried a
     * one-year validity and expired on 2026-08-26, which broke the whole build - and would have
     * broken it again every year. The controller is right to reject an expired certificate
     * (CreateSignatureBase calls X509Certificate.checkValidity), so the test has to supply one that
     * is currently valid; generating it is the only way that stays true over time.
     */
    private static CertificateMaterial certificateMaterial;

    @BeforeAll
    static void generateCertificateMaterial() throws Exception {
        certificateMaterial = CertificateMaterial.generate(KEYSTORE_PASSWORD);
    }

    @BeforeEach
    void setUp() throws Exception {
        lenient()
                .when(tempFileManager.createManagedTempFile(anyString()))
                .thenAnswer(
                        inv -> {
                            File f =
                                    Files.createTempFile("test", inv.<String>getArgument(0))
                                            .toFile();
                            TempFile tf = mock(TempFile.class);
                            lenient().when(tf.getFile()).thenReturn(f);
                            lenient().when(tf.getPath()).thenReturn(f.toPath());
                            return tf;
                        });
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            pdfBytes = baos.toByteArray();
        }
        // .pfx and .p12 are the same PKCS#12 container, and .crt/.cer/.pem the same PEM
        // certificate - the tests only vary the filename to exercise extension handling.
        pfxBytes = certificateMaterial.pkcs12();
        p12Bytes = certificateMaterial.pkcs12();
        jksBytes = certificateMaterial.jks();
        pemKeyBytes = certificateMaterial.privateKeyPem();
        keyBytes = certificateMaterial.privateKeyPem();
        pemCertBytes = certificateMaterial.certificatePem();
        crtCertBytes = certificateMaterial.certificatePem();
        cerCertBytes = certificateMaterial.certificatePem();
        derCertBytes = certificateMaterial.certificateDer();

        lenient()
                .when(pdfDocumentFactory.load(any(MultipartFile.class)))
                .thenAnswer(
                        invocation -> {
                            MultipartFile file = invocation.getArgument(0);
                            return Loader.loadPDF(file.getBytes());
                        });
    }

    @Test
    void testSignPdfWithPfx() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile pfxFile =
                new MockMultipartFile("p12File", "test-cert.pfx", "application/x-pkcs12", pfxBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PFX");
        request.setP12File(pfxFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithPkcs12() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile p12File =
                new MockMultipartFile("p12File", "test-cert.p12", "application/x-pkcs12", p12Bytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PKCS12");
        request.setP12File(p12File);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithMissingPkcs12FileThrowsError() {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PFX");
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> certSignController.signPDFWithCert(request, httpRequest));

        assertTrue(exception.getMessage().contains("PKCS12 keystore"));
    }

    @Test
    void testSignPdfWithJks() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile jksFile =
                new MockMultipartFile(
                        "jksFile", "test-cert.jks", "application/octet-stream", jksBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("JKS");
        request.setJksFile(jksFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithPem() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.pem", "application/x-pem-file", pemKeyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.pem", "application/x-pem-file", pemCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithCrt() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.crt", "application/x-x509-ca-cert", crtCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithCer() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.cer", "application/x-x509-ca-cert", cerCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }

    @Test
    void testSignPdfWithDer() throws Exception {
        MockMultipartFile pdfFile =
                new MockMultipartFile(
                        "fileInput", "test.pdf", MediaType.APPLICATION_PDF_VALUE, pdfBytes);
        MockMultipartFile keyFile =
                new MockMultipartFile(
                        "privateKeyFile", "test-key.key", "application/x-pem-file", keyBytes);
        MockMultipartFile certFile =
                new MockMultipartFile(
                        "certFile", "test-cert.der", "application/x-x509-ca-cert", derCertBytes);

        SignPDFWithCertRequest request = new SignPDFWithCertRequest();
        request.setFileInput(pdfFile);
        request.setCertType("PEM");
        request.setPrivateKeyFile(keyFile);
        request.setCertFile(certFile);
        request.setPassword("password");
        request.setShowSignature(false);
        request.setReason("test");
        request.setLocation("test");
        request.setName("tester");
        request.setPageNumber(1);
        request.setShowLogo(false);

        ResponseEntity<Resource> response =
                certSignController.signPDFWithCert(request, httpRequest);

        assertNotNull(response.getBody());
        assertTrue(drainBody(response).length > 0);
    }
}
