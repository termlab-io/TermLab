package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperListReplyParserTest {

    @Test
    void emptyServer() {
        var r = PaperListReplyParser.parse("There are 0 of a max of 20 players online: ");
        assertEquals(0, r.online());
        assertEquals(20, r.max());
        assertTrue(r.players().isEmpty());
    }

    @Test
    void singlePlayer() {
        var r = PaperListReplyParser.parse("There are 1 of a max of 20 players online: alice");
        assertEquals(1, r.online());
        assertEquals(20, r.max());
        assertEquals(List.of(new Player("alice", Player.PING_UNKNOWN)), r.players());
    }

    @Test
    void multiplePlayers() {
        var r = PaperListReplyParser.parse("There are 3 of a max of 20 players online: alice, bob, carol");
        assertEquals(3, r.online());
        assertEquals(List.of(
            new Player("alice", Player.PING_UNKNOWN),
            new Player("bob", Player.PING_UNKNOWN),
            new Player("carol", Player.PING_UNKNOWN)
        ), r.players());
    }

    @Test
    void stripsSectionColorCodes() {
        var r = PaperListReplyParser.parse("§6There are §a2§6 of a max of §a20§6 players online: §falice§6, §fbob");
        assertEquals(2, r.online());
        assertEquals(20, r.max());
        assertEquals(2, r.players().size());
        assertEquals("alice", r.players().get(0).name());
        assertEquals("bob", r.players().get(1).name());
    }

    @Test
    void unparseable_returnsEmpty() {
        var r = PaperListReplyParser.parse("some garbage the server said");
        assertEquals(0, r.online());
        assertEquals(0, r.max());
        assertTrue(r.players().isEmpty());
    }

    @Test
    void extractsPingSuffixIfPresent() {
        // EssentialsX-style "[Nms]" annotation.
        var r = PaperListReplyParser.parse("There are 2 of a max of 20 players online: alice [42ms], bob [100ms]");
        assertEquals(2, r.online());
        assertEquals(42, r.players().get(0).pingMs());
        assertEquals(100, r.players().get(1).pingMs());
    }
}
