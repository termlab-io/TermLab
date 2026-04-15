package com.conch.core.filepicker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFileSourceTest {

    @Test
    void idIsLocal() {
        assertEquals("local", new LocalFileSource().id());
    }

    @Test
    void labelIsLocal() {
        assertEquals("Local", new LocalFileSource().label());
    }

    @Test
    void listReturnsEntriesInDirectory(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("subdir"));
        Files.writeString(tmp.resolve("a.txt"), "hello");
        Files.writeString(tmp.resolve("b.txt"), "world");

        LocalFileSource source = new LocalFileSource();
        List<FileEntry> entries = source.list(tmp.toString());

        List<String> names = entries.stream().map(FileEntry::name).sorted().collect(Collectors.toList());
        assertEquals(List.of("a.txt", "b.txt", "subdir"), names);
    }

    @Test
    void listOfMissingPathThrows(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist");
        LocalFileSource source = new LocalFileSource();
        assertThrows(IOException.class, () -> source.list(missing.toString()));
    }

    @Test
    void isDirectoryTrueForDirectories(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertTrue(source.isDirectory(tmp.toString()));
    }

    @Test
    void isDirectoryFalseForFiles(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("x.txt");
        Files.writeString(file, "content");
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.isDirectory(file.toString()));
    }

    @Test
    void isDirectoryFalseForMissing(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.isDirectory(tmp.resolve("missing").toString()));
    }

    @Test
    void existsTrueForDirectoriesAndFiles(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("x.txt");
        Files.writeString(file, "content");
        LocalFileSource source = new LocalFileSource();
        assertTrue(source.exists(tmp.toString()));
        assertTrue(source.exists(file.toString()));
    }

    @Test
    void existsFalseForMissing(@TempDir Path tmp) {
        LocalFileSource source = new LocalFileSource();
        assertFalse(source.exists(tmp.resolve("missing").toString()));
    }

    @Test
    void parentOfRootReturnsNull() {
        LocalFileSource source = new LocalFileSource();
        assertNull(source.parentOf("/"));
    }

    @Test
    void parentOfNestedPath() {
        LocalFileSource source = new LocalFileSource();
        assertEquals("/a", source.parentOf("/a/b"));
        assertEquals("/a/b", source.parentOf("/a/b/c"));
    }

    @Test
    void resolveJoinsPaths() {
        LocalFileSource source = new LocalFileSource();
        assertEquals("/a/b", source.resolve("/a", "b"));
        assertEquals("/a/b/c.txt", source.resolve("/a/b", "c.txt"));
    }

    @Test
    void writeFileCreatesNewFile(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("new.txt");
        source.writeFile(target.toString(), "hello".getBytes());
        assertEquals("hello", Files.readString(target));
    }

    @Test
    void writeFileOverwritesExisting(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("x.txt");
        Files.writeString(target, "old");
        source.writeFile(target.toString(), "new".getBytes());
        assertEquals("new", Files.readString(target));
    }

    @Test
    void readFileReturnsContent(@TempDir Path tmp) throws IOException {
        LocalFileSource source = new LocalFileSource();
        Path target = tmp.resolve("x.txt");
        Files.writeString(target, "content");
        try (InputStream in = source.readFile(target.toString())) {
            assertEquals("content", new String(in.readAllBytes()));
        }
    }
}
