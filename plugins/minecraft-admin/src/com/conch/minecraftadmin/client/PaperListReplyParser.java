package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the reply from Paper's RCON {@code list} command into
 * {@code (online, max, players)}. Strips Minecraft's {@code §}
 * color codes and tolerates minor plugin variations.
 *
 * <p>Canonical format:
 * <pre>There are N of a max of M players online: a, b, c</pre>
 *
 * <p>If a player's name is suffixed with {@code [Nms]} (plugin
 * convention) the ping value is extracted. Otherwise
 * {@link Player#PING_UNKNOWN} is stored.
 */
public final class PaperListReplyParser {

    private static final Pattern HEADER = Pattern.compile(
        "There are (\\d+) of a max of (\\d+) players online:(.*)");

    private static final Pattern PING_SUFFIX = Pattern.compile(
        "^(.+?)\\s*\\[(\\d+)ms\\]$");

    private static final Pattern SECTION_COLOR = Pattern.compile("§[0-9a-fklmnor]");

    private PaperListReplyParser() {}

    public record Result(int online, int max, @NotNull List<Player> players) {}

    public static @NotNull Result parse(@NotNull String raw) {
        String stripped = SECTION_COLOR.matcher(raw).replaceAll("").trim();
        Matcher m = HEADER.matcher(stripped);
        if (!m.find()) return new Result(0, 0, List.of());

        int online = Integer.parseInt(m.group(1));
        int max = Integer.parseInt(m.group(2));
        String tail = m.group(3).trim();
        if (tail.isEmpty()) return new Result(online, max, List.of());

        List<Player> players = new ArrayList<>();
        for (String chunk : tail.split(",")) {
            String entry = chunk.trim();
            if (entry.isEmpty()) continue;
            Matcher pm = PING_SUFFIX.matcher(entry);
            if (pm.matches()) {
                players.add(new Player(pm.group(1).trim(), Integer.parseInt(pm.group(2))));
            } else {
                players.add(Player.unknownPing(entry));
            }
        }
        return new Result(online, max, players);
    }
}
