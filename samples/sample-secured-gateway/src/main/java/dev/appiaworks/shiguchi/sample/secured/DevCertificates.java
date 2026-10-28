package dev.appiaworks.shiguchi.sample.secured;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A throwaway certificate authority and the three PEM files the sample needs, generated into a
 * temporary directory at startup.
 *
 * <p>Nothing cryptographic here is interesting — it exists so the sample can demonstrate mTLS
 * without committing a private key to the repository. Committed keys in sample projects get
 * copied into real deployments, and a key that is public in a git history is not a key.
 *
 * <p>In a real deployment these files come from your CA, cert-manager, a mounted secret, or a
 * mesh that handles the whole thing for you. What the gateway needs is only the paths.
 */
final class DevCertificates {

    private final Path dir;
    private final Path caCert;
    private final Path serverCert;
    private final Path serverKey;
    private final Path clientCert;
    private final Path clientKey;

    private DevCertificates(Path dir) {
        this.dir = dir;
        this.caCert = dir.resolve("ca.pem");
        this.serverCert = dir.resolve("server-cert.pem");
        this.serverKey = dir.resolve("server-key.pem");
        this.clientCert = dir.resolve("client-cert.pem");
        this.clientKey = dir.resolve("client-key.pem");
    }

    Path caCert() {
        return caCert;
    }

    Path serverCert() {
        return serverCert;
    }

    Path serverKey() {
        return serverKey;
    }

    Path clientCert() {
        return clientCert;
    }

    Path clientKey() {
        return clientKey;
    }

    Path dir() {
        return dir;
    }

    /**
     * One CA, a server certificate for {@code localhost} (what the backend presents), and a client
     * certificate (what the gateway presents back — the "mutual" half of mTLS).
     */
    static DevCertificates generate() throws Exception {
        Path dir = Files.createTempDirectory("shiguchi-sample-certs");
        dir.toFile().deleteOnExit();
        DevCertificates certs = new DevCertificates(dir);

        KeyPair caKeys = keyPair();
        X509Certificate ca = certificate("CN=sample-dev-ca", caKeys, "CN=sample-dev-ca", caKeys, true, null);

        KeyPair serverKeys = keyPair();
        X509Certificate server =
                certificate("CN=localhost", serverKeys, "CN=sample-dev-ca", caKeys, false, "localhost");

        KeyPair clientKeys = keyPair();
        X509Certificate client = certificate("CN=sample-gateway", clientKeys, "CN=sample-dev-ca", caKeys, false, null);

        writeCert(certs.caCert, ca);
        writeCert(certs.serverCert, server);
        writeKey(certs.serverKey, serverKeys);
        writeCert(certs.clientCert, client);
        writeKey(certs.clientKey, clientKeys);
        return certs;
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        return gen.generateKeyPair();
    }

    private static X509Certificate certificate(
            String subject, KeyPair subjectKeys, String issuer, KeyPair issuerKeys, boolean ca, String dnsName)
            throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Principal(issuer),
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minusSeconds(60)),
                Date.from(now.plusSeconds(86_400)), // a day is plenty for a sample
                new X500Principal(subject),
                subjectKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (dnsName != null) {
            // Without a SAN the gRPC client rejects the certificate: modern TLS stacks do not
            // fall back to matching the CN.
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, dnsName)));
        }
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static void writeCert(Path path, X509Certificate cert) throws Exception {
        write(path, "CERTIFICATE", cert.getEncoded());
    }

    private static void writeKey(Path path, KeyPair keys) throws Exception {
        // PKCS#8, which is what the gateway's TLS config expects.
        write(path, "PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    private static void write(Path path, String label, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der);
        Files.writeString(path, "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n");
        path.toFile().deleteOnExit();
    }
}
