package com.termlab.vault.keygen;

import com.termlab.vault.model.VaultKey;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Generates SSH key pairs in OpenSSH on-disk format.
 *
 * <p>Uses BouncyCastle's low-level {@code org.bouncycastle.crypto} layer
 * (not JCA) so it works regardless of which security providers are
 * installed in the JVM — BC's own providers come directly from the
 * {@code bcprov} jar we already depend on for Argon2 and AES-GCM.
 *
 * <p>Each generated key produces two files under
 * {@code ~/.ssh/termlab_vault/}:
 * <ul>
 *   <li>{@code id_<algo>_<uuid-8>} — OpenSSH private key (mode 0600 on POSIX)</li>
 *   <li>{@code id_<algo>_<uuid-8>.pub} — OpenSSH public key line
 *       ({@code ssh-<algo> BASE64 comment})</li>
 * </ul>
 *
 * <p>The returned {@link VaultKey} holds the absolute paths, a
 * human-readable SHA-256 fingerprint (matching {@code ssh-keygen -l -f}
 * output), the user-facing name, and the comment. Callers are responsible
 * for adding the entry to the vault model and calling
 * {@link com.termlab.vault.lock.LockManager#save()}.
 */
public final class SshKeyGenerator {

    private static final SecureRandom RNG = new SecureRandom();

    /** Default output directory. Created on first use with mode 0700 on POSIX. */
    private final Path outputDir;

    public SshKeyGenerator() {
        this(Paths.get(System.getProperty("user.home"), ".ssh", "termlab_vault"));
    }

    public SshKeyGenerator(@NotNull Path outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Generate a fresh key pair of the given algorithm and write it to disk.
     *
     * @param algorithm the algorithm to generate
     * @param name      user-facing label stored in the vault
     *                  ("Work laptop", "GitHub CI"). Shown in the vault UI
     *                  and credential pickers.
     * @param comment   the comment appended to the public key line (usually
     *                  the user's email address or hostname — cosmetic, not
     *                  security; may be blank)
     * @return a {@link VaultKey} describing what was written
     */
    public @NotNull VaultKey generate(
        @NotNull KeyGenAlgorithm algorithm,
        @NotNull String name,
        @NotNull String comment
    ) throws IOException {
        UUID id = UUID.randomUUID();
        String shortId = id.toString().substring(0, 8);
        String baseName = "id_" + algorithm.id().replace('-', '_') + "_" + shortId;
        Path privateKey = outputDir.resolve(baseName);
        Path publicKey = outputDir.resolve(baseName + ".pub");

        AsymmetricCipherKeyPair kp = generateKeyPair(algorithm);

        ensureOutputDir();
        writePrivateKey(privateKey, kp.getPrivate());
        String publicKeyLine = buildPublicKeyLine(kp.getPublic(), sshKeyType(algorithm), comment);
        Files.writeString(publicKey, publicKeyLine + "\n", StandardCharsets.US_ASCII);

        String fingerprint = sha256Fingerprint(kp.getPublic(), sshKeyType(algorithm));

        return new VaultKey(
            id,
            name,
            algorithm.id(),
            fingerprint,
            comment,
            privateKey.toAbsolutePath().toString(),
            publicKey.toAbsolutePath().toString(),
            Instant.now()
        );
    }

    /** Delete the key files referenced by an entry. No-op if they're already gone. */
    public void delete(@NotNull VaultKey key) throws IOException {
        Files.deleteIfExists(Paths.get(key.privatePath()));
        Files.deleteIfExists(Paths.get(key.publicPath()));
    }

    // -- key generation -------------------------------------------------------

    private static AsymmetricCipherKeyPair generateKeyPair(KeyGenAlgorithm algorithm) {
        return switch (algorithm) {
            case ED25519 -> {
                Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
                gen.init(new Ed25519KeyGenerationParameters(RNG));
                yield gen.generateKeyPair();
            }
            case ECDSA_P256 -> ecdsaKeyPair("secp256r1");
            case ECDSA_P384 -> ecdsaKeyPair("secp384r1");
            case RSA_3072 -> rsaKeyPair(3072);
            case RSA_4096 -> rsaKeyPair(4096);
        };
    }

    private static AsymmetricCipherKeyPair ecdsaKeyPair(String curveName) {
        X9ECParameters curve = SECNamedCurves.getByName(curveName);
        ECDomainParameters domain = new ECDomainParameters(
            curve.getCurve(), curve.getG(), curve.getN(), curve.getH(), curve.getSeed());
        ECKeyPairGenerator gen = new ECKeyPairGenerator();
        gen.init(new ECKeyGenerationParameters(domain, RNG));
        return gen.generateKeyPair();
    }

    private static AsymmetricCipherKeyPair rsaKeyPair(int bits) {
        RSAKeyPairGenerator gen = new RSAKeyPairGenerator();
        gen.init(new RSAKeyGenerationParameters(
            BigInteger.valueOf(0x10001),  // standard public exponent e=65537
            RNG, bits, 80 /* certainty */));
        return gen.generateKeyPair();
    }

    // -- OpenSSH encoding -----------------------------------------------------

    private static String sshKeyType(KeyGenAlgorithm algorithm) {
        return switch (algorithm) {
            case ED25519 -> "ssh-ed25519";
            case ECDSA_P256 -> "ecdsa-sha2-nistp256";
            case ECDSA_P384 -> "ecdsa-sha2-nistp384";
            case RSA_3072, RSA_4096 -> "ssh-rsa";
        };
    }

    private static void writePrivateKey(Path target, AsymmetricKeyParameter privateKey) throws IOException {
        byte[] encoded = OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey);
        String pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + wrap(Base64.getEncoder().encodeToString(encoded), 70)
            + "\n-----END OPENSSH PRIVATE KEY-----\n";
        Files.writeString(target, pem, StandardCharsets.US_ASCII);
        setOwnerOnlyReadWrite(target);
    }

    private static String buildPublicKeyLine(
        AsymmetricKeyParameter publicKey, String sshKeyType, String comment) throws IOException {
        byte[] encoded = OpenSSHPublicKeyUtil.encodePublicKey(publicKey);
        String b64 = Base64.getEncoder().encodeToString(encoded);
        if (comment == null || comment.isBlank()) {
            return sshKeyType + " " + b64;
        }
        return sshKeyType + " " + b64 + " " + comment.trim();
    }

    private static String sha256Fingerprint(AsymmetricKeyParameter publicKey, String sshKeyType)
        throws IOException {
        try {
            byte[] encoded = OpenSSHPublicKeyUtil.encodePublicKey(publicKey);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(encoded);
            // ssh-keygen's fingerprint format: base64 without padding, prefixed "SHA256:".
            String b64 = Base64.getEncoder().withoutPadding().encodeToString(hash);
            return "SHA256:" + b64;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    // -- IO helpers -----------------------------------------------------------

    private void ensureOutputDir() throws IOException {
        Files.createDirectories(outputDir);
        if (supportsPosix()) {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(outputDir, perms);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX (Windows) — ignore.
            }
        }
    }

    private static void setOwnerOnlyReadWrite(Path target) throws IOException {
        if (!supportsPosix()) return;
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX — ignore.
        }
    }

    private static boolean supportsPosix() {
        return java.nio.file.FileSystems.getDefault()
            .supportedFileAttributeViews().contains("posix");
    }

    private static String wrap(String s, int width) {
        StringBuilder out = new StringBuilder(s.length() + s.length() / width);
        for (int i = 0; i < s.length(); i += width) {
            if (i > 0) out.append('\n');
            out.append(s, i, Math.min(i + width, s.length()));
        }
        return out.toString();
    }
}
