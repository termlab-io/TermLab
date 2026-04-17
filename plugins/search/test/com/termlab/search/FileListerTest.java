package com.termlab.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileListerTest {

    @Test
    void buildsFindCommandWithSubstringQuery() {
        FileSearchFilter filter = new FileSearchFilter();

        List<String> command = FileLister.buildFindCommand(".", "term", filter);

        assertEquals("find", command.getFirst());
        assertTrue(command.contains("-iname"));
        assertTrue(command.contains("*term*"));
    }

    @Test
    void buildsFdCommandForLiveQuery() {
        FileSearchFilter filter = new FileSearchFilter();

        List<String> command = FileLister.buildFdCommand("fd", "hello", filter);

        assertEquals(List.of("fd", "--type", "f", "--hidden", "--no-ignore"), command.subList(0, 5));
        assertEquals("hello", command.get(command.size() - 2));
        assertEquals(".", command.getLast());
    }

    @Test
    void quotesShellArgumentsSafely() {
        assertEquals("'it'\"'\"'s'", FileLister.shellQuote("it's"));
    }
}
