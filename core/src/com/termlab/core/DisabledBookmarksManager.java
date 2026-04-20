package com.termlab.core;

import com.intellij.ide.bookmark.Bookmark;
import com.intellij.ide.bookmark.BookmarkGroup;
import com.intellij.ide.bookmark.BookmarkType;
import com.intellij.ide.bookmark.BookmarksManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TermLab disables the platform bookmarks feature entirely.
 */
public final class DisabledBookmarksManager implements BookmarksManager {

    @Override
    public @Nullable Bookmark createBookmark(@Nullable Object context) {
        return null;
    }

    @Override
    public @NotNull List<Bookmark> getBookmarks() {
        return Collections.emptyList();
    }

    @Override
    public @Nullable BookmarkGroup getDefaultGroup() {
        return null;
    }

    @Override
    public @Nullable BookmarkGroup getGroup(@NotNull String name) {
        return null;
    }

    @Override
    public @NotNull List<BookmarkGroup> getGroups() {
        return Collections.emptyList();
    }

    @Override
    public @NotNull @Unmodifiable List<BookmarkGroup> getGroups(@NotNull Bookmark bookmark) {
        return Collections.emptyList();
    }

    @Override
    public @Nullable BookmarkGroup addGroup(@NotNull String name, boolean isDefault) {
        return null;
    }

    @Override
    public @Nullable Bookmark getBookmark(@NotNull BookmarkType type) {
        return null;
    }

    @Override
    public @NotNull @Unmodifiable Set<BookmarkType> getAssignedTypes() {
        return Collections.emptySet();
    }

    @Override
    public @Nullable BookmarkType getType(@NotNull Bookmark bookmark) {
        return null;
    }

    @Override
    public void setType(@NotNull Bookmark bookmark, @NotNull BookmarkType type) {
    }

    @Override
    public void toggle(@NotNull Bookmark bookmark, @NotNull BookmarkType type) {
    }

    @Override
    public void add(@NotNull Bookmark bookmark, @NotNull BookmarkType type) {
    }

    @Override
    public void remove(@NotNull Bookmark bookmark) {
    }

    @Override
    public void remove() {
    }

    @Override
    public void update(@NotNull Map<@NotNull Bookmark, @Nullable Bookmark> map) {
    }
}
