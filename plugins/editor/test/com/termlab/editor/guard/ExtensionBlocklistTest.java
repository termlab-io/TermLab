package com.termlab.editor.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionBlocklistTest {

    @Test
    void blocksCommonImageExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("screenshot.png"));
        assertTrue(ExtensionBlocklist.isBlocked("photo.jpg"));
        assertTrue(ExtensionBlocklist.isBlocked("photo.JPEG"));
        assertTrue(ExtensionBlocklist.isBlocked("icon.GIF"));
    }

    @Test
    void blocksArchiveExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("release.zip"));
        assertTrue(ExtensionBlocklist.isBlocked("backup.tar"));
        assertTrue(ExtensionBlocklist.isBlocked("backup.tar.gz"));
        assertTrue(ExtensionBlocklist.isBlocked("pack.7z"));
    }

    @Test
    void blocksExecutableExtensions() {
        assertTrue(ExtensionBlocklist.isBlocked("tool.exe"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.dll"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.so"));
        assertTrue(ExtensionBlocklist.isBlocked("lib.dylib"));
        assertTrue(ExtensionBlocklist.isBlocked("Main.class"));
    }

    @Test
    void allowsTextExtensions() {
        assertFalse(ExtensionBlocklist.isBlocked("config.yaml"));
        assertFalse(ExtensionBlocklist.isBlocked("script.sh"));
        assertFalse(ExtensionBlocklist.isBlocked("notes.md"));
        assertFalse(ExtensionBlocklist.isBlocked("code.py"));
        assertFalse(ExtensionBlocklist.isBlocked("data.json"));
    }

    @Test
    void allowsNoExtension() {
        assertFalse(ExtensionBlocklist.isBlocked("Makefile"));
        assertFalse(ExtensionBlocklist.isBlocked("Dockerfile"));
        assertFalse(ExtensionBlocklist.isBlocked(".bashrc"));
    }

    @Test
    void allowsDotfilesWithNonBlockedExtension() {
        assertFalse(ExtensionBlocklist.isBlocked(".config.yaml"));
    }
}
