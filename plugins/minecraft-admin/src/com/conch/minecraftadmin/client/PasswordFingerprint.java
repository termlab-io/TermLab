package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Debug-only helper that produces a short, safe-to-log fingerprint of a
 * password's contents so we can verify the same password is flowing
 * through every layer (vault resolver → profile controller → poller →
 * rcon client → socket) without logging the plaintext.
 *
 * <p>A fingerprint looks like:
 * <pre>len=12 ascii=true first=ab last=yz sha8=deadbeef</pre>
 *
 * <ul>
 *   <li>{@code len} — char array length.</li>
 *   <li>{@code ascii} — whether every char is printable ASCII (0x20-0x7E).
 *       False flags potential encoding / whitespace issues.</li>
 *   <li>{@code first} / {@code last} — first and last two chars (only if
 *       length >= 4). Enough to spot leading/trailing whitespace without
 *       leaking the middle.</li>
 *   <li>{@code sha8} — first 8 hex chars of SHA-256(UTF-8 password).
 *       Deterministic across layers: if resolver says sha8=deadbeef and
 *       rconClient says sha8=deadbeef, the bytes match. If they differ,
 *       something's mutating the password.</li>
 * </ul>
 *
 * <p>This is diagnostic output. Revert after the RCON auth issue is
 * resolved. The first/last char fields are mild secret leakage, so the
 * fingerprint should only be logged at INFO/WARN level while actively
 * debugging a connection problem.
 */
public final class PasswordFingerprint {

    private PasswordFingerprint() {}

    public static @NotNull String of(@Nullable char[] password) {
        if (password == null) return "<null>";
        if (password.length == 0) return "<empty>";
        boolean allAscii = true;
        for (char c : password) {
            if (c < 0x20 || c > 0x7E) {
                allAscii = false;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("len=").append(password.length);
        sb.append(" ascii=").append(allAscii);
        if (password.length >= 4) {
            sb.append(" first=").append(password[0]).append(password[1]);
            sb.append(" last=").append(password[password.length - 2]).append(password[password.length - 1]);
        }
        try {
            byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            sb.append(" sha8=");
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i] & 0xff));
            }
            sb.append(" utf8Bytes=").append(bytes.length);
        } catch (NoSuchAlgorithmException e) {
            sb.append(" sha8=<err>");
        }
        return sb.toString();
    }
}
