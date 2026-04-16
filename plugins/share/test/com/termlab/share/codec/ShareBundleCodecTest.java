package com.termlab.share.codec;

import com.termlab.share.codec.exceptions.BundleCorruptedException;
import com.termlab.share.codec.exceptions.UnsupportedBundleVersionException;
import com.termlab.share.codec.exceptions.WrongBundlePasswordException;
import com.termlab.share.model.BundleMetadata;
import com.termlab.share.model.BundledVault;
import com.termlab.share.model.ShareBundle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShareBundleCodecTest {

    private ShareBundle sampleBundle() {
        BundleMetadata meta = new BundleMetadata(
            Instant.parse("2026-04-14T10:32:00Z"),
            "test-host",
            "0.14.2",
            false
        );
        return new ShareBundle(
            ShareBundle.CURRENT_SCHEMA_VERSION,
            meta,
            List.of(),
            List.of(),
            BundledVault.empty(),
            List.of()
        );
    }

    @Test
    void encode_thenDecode_roundTrip() throws Exception {
        ShareBundle original = sampleBundle();
        byte[] password = "correct-horse".getBytes();
        try {
            byte[] encoded = ShareBundleCodec.encode(original, password);
            assertNotNull(encoded);
            assertTrue(encoded.length > 40, "encoded bundle must have header + ciphertext");

            ShareBundle decoded = ShareBundleCodec.decode(encoded, password);
            assertEquals(original.schemaVersion(), decoded.schemaVersion());
            assertEquals(original.metadata().sourceHost(), decoded.metadata().sourceHost());
            assertEquals(original.metadata().createdAt(), decoded.metadata().createdAt());
            assertEquals(original.metadata().termlabVersion(), decoded.metadata().termlabVersion());
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    void decode_wrongPassword_throws() throws Exception {
        byte[] encoded = ShareBundleCodec.encode(sampleBundle(), "right".getBytes());
        assertThrows(
            WrongBundlePasswordException.class,
            () -> ShareBundleCodec.decode(encoded, "wrong".getBytes())
        );
    }

    @Test
    void decode_badMagic_throws() {
        byte[] garbage = new byte[128];
        assertThrows(
            BundleCorruptedException.class,
            () -> ShareBundleCodec.decode(garbage, "any".getBytes())
        );
    }

    @Test
    void decode_schemaVersion999_throws() {
        BundleMetadata meta = new BundleMetadata(Instant.now(), "h", "v", false);
        ShareBundle future = new ShareBundle(999, meta, List.of(), List.of(), BundledVault.empty(), List.of());
        byte[] encoded = ShareBundleCodec.encode(future, "pw".getBytes());
        assertThrows(
            UnsupportedBundleVersionException.class,
            () -> ShareBundleCodec.decode(encoded, "pw".getBytes())
        );
    }

    @Test
    void decode_truncated_throws() {
        byte[] tooShort = new byte[10];
        assertThrows(
            BundleCorruptedException.class,
            () -> ShareBundleCodec.decode(tooShort, "pw".getBytes())
        );
    }
}
