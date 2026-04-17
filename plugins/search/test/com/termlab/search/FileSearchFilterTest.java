package com.termlab.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileSearchFilterTest {

    @Test
    void expandsDefaultExcludePatterns() {
        FileSearchFilter filter = new FileSearchFilter();

        List<String> patterns = filter.allExcludePatterns();

        assertTrue(patterns.contains(".git"));
        assertTrue(patterns.contains("node_modules"));
        assertTrue(patterns.contains("build"));
        assertTrue(patterns.contains(".nodenv"));
        assertTrue(patterns.contains(".rbenv"));
        assertTrue(patterns.contains(".pyenv"));
    }

    @Test
    void buildsToolSpecificFlags() {
        FileSearchFilter filter = new FileSearchFilter();

        List<String> rgFlags = filter.toListCommandFlags(FileLister.Tool.RG);
        List<String> fdFlags = filter.toListCommandFlags(FileLister.Tool.FD);
        List<String> findFlags = filter.toListCommandFlags(FileLister.Tool.FIND);

        assertTrue(rgFlags.contains("-g"));
        assertTrue(fdFlags.contains("-E"));
        assertTrue(findFlags.contains("-path") || findFlags.contains("-name"));
    }

    @Test
    void validatesRegex() {
        FileSearchFilter filter = new FileSearchFilter();
        FileSearchFilter.State state = filter.copyState();
        state.excludeRegex = "[broken";
        filter.replace(state);

        assertNotNull(filter.regexError());
        assertFalse(filter.matchesRegex("/tmp/demo.txt"));
    }

    @Test
    void normalizesCustomPatterns() {
        FileSearchFilter filter = new FileSearchFilter();
        FileSearchFilter.State state = filter.copyState();
        state.customExcludes = List.of(" build ", "", "cache", "build");
        filter.replace(state);

        assertEquals(List.of("build", "cache"), filter.normalizedCustomExcludes());
    }

    @Test
    void excludesHiddenDirectoriesButKeepsDotfiles() {
        FileSearchFilter filter = new FileSearchFilter();

        assertTrue(filter.matchesPath("/tmp/project/.env"));
        assertFalse(filter.matchesPath("/tmp/project/.config/nvim/init.lua"));
        assertFalse(filter.matchesPath("/tmp/project/src/.cache/foo.txt"));
    }
}
