package com.conch.minecraftadmin.client;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the reply from Paper's RCON {@code tps} command and returns
 * only the 1-minute (most recent) TPS value. The 5m and 15m samples
 * are discarded for v1 — they're not displayed in the status strip.
 *
 * <p>Canonical format (with optional color codes):
 * <pre>§6TPS from last 1m, 5m, 15m: §a19.98, §a20.0, §a20.0</pre>
 */
public final class PaperTpsReplyParser {

    private static final Pattern SECTION_COLOR = Pattern.compile("§[0-9a-fklmnor]");
    private static final Pattern LINE = Pattern.compile(
        "TPS from last 1m, 5m, 15m:\\s*([0-9]+(?:\\.[0-9]+)?)");

    private PaperTpsReplyParser() {}

    public static double parseMostRecent(@NotNull String raw) {
        String stripped = SECTION_COLOR.matcher(raw).replaceAll("");
        Matcher m = LINE.matcher(stripped);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
