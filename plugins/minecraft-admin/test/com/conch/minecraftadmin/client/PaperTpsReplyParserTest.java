package com.conch.minecraftadmin.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperTpsReplyParserTest {

    @Test
    void stockPaperOutput() {
        double tps = PaperTpsReplyParser.parseMostRecent(
            "§6TPS from last 1m, 5m, 15m: §a19.98, §a20.0, §a20.0");
        assertEquals(19.98, tps, 0.001);
    }

    @Test
    void withoutColorCodes() {
        double tps = PaperTpsReplyParser.parseMostRecent("TPS from last 1m, 5m, 15m: 18.5, 19.1, 19.8");
        assertEquals(18.5, tps, 0.001);
    }

    @Test
    void laggySampledBelow15IsStillParsed() {
        double tps = PaperTpsReplyParser.parseMostRecent("§6TPS from last 1m, 5m, 15m: §c10.2, §e16.1, §a19.9");
        assertEquals(10.2, tps, 0.001);
    }

    @Test
    void unparseable_returnsNaN() {
        assertTrue(Double.isNaN(PaperTpsReplyParser.parseMostRecent("garbage")));
        assertTrue(Double.isNaN(PaperTpsReplyParser.parseMostRecent("")));
    }
}
