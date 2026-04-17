package com.termlab.search;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@State(
    name = "TermLabFileSearchFilter",
    storages = @Storage("termlab-file-search.xml")
)
public final class FileSearchFilter implements PersistentStateComponent<FileSearchFilter.State> {

    public static final class State {
        public boolean excludeGit = true;
        public boolean excludeNodeModules = true;
        public boolean excludeIdea = true;
        public boolean excludeBuild = true;
        public boolean excludeCache = true;
        public boolean excludeHiddenDirectories = true;
        public boolean excludeDsStore = true;
        public List<String> customExcludes = new ArrayList<>();
        public String excludeRegex = "";
        public Set<String> dismissedRipgrepHints = new LinkedHashSet<>();
    }

    private State state = new State();

    public static @NotNull FileSearchFilter getInstance() {
        return ApplicationManager.getApplication().getService(FileSearchFilter.class);
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public @NotNull State copyState() {
        State copy = new State();
        copy.excludeGit = state.excludeGit;
        copy.excludeNodeModules = state.excludeNodeModules;
        copy.excludeIdea = state.excludeIdea;
        copy.excludeBuild = state.excludeBuild;
        copy.excludeCache = state.excludeCache;
        copy.excludeHiddenDirectories = state.excludeHiddenDirectories;
        copy.excludeDsStore = state.excludeDsStore;
        copy.customExcludes = new ArrayList<>(normalizedCustomExcludes());
        copy.excludeRegex = state.excludeRegex == null ? "" : state.excludeRegex.trim();
        copy.dismissedRipgrepHints = new LinkedHashSet<>(state.dismissedRipgrepHints);
        return copy;
    }

    public void replace(@NotNull State replacement) {
        this.state = replacement;
    }

    public void resetDefaults() {
        this.state = new State();
    }

    public boolean isModifiedFromDefaults() {
        State defaults = new State();
        return state.excludeGit != defaults.excludeGit
            || state.excludeNodeModules != defaults.excludeNodeModules
            || state.excludeIdea != defaults.excludeIdea
            || state.excludeBuild != defaults.excludeBuild
            || state.excludeCache != defaults.excludeCache
            || state.excludeHiddenDirectories != defaults.excludeHiddenDirectories
            || state.excludeDsStore != defaults.excludeDsStore
            || !normalizedCustomExcludes().isEmpty()
            || !normalizedRegex().isEmpty();
    }

    public @NotNull List<String> normalizedCustomExcludes() {
        return state.customExcludes == null
            ? List.of()
            : state.customExcludes.stream()
                .map(pattern -> pattern == null ? "" : pattern.trim())
                .filter(pattern -> !pattern.isEmpty())
                .distinct()
                .toList();
    }

    public @NotNull String normalizedRegex() {
        return state.excludeRegex == null ? "" : state.excludeRegex.trim();
    }

    public @Nullable Pattern compiledRegex() {
        String regex = normalizedRegex();
        if (regex.isEmpty()) return null;
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException ignored) {
            return null;
        }
    }

    public @Nullable String regexError() {
        String regex = normalizedRegex();
        if (regex.isEmpty()) return null;
        try {
            Pattern.compile(regex);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    public boolean matchesRegex(@NotNull String path) {
        String raw = normalizedRegex();
        if (raw.isEmpty()) return true;
        Pattern regex = compiledRegex();
        if (regex == null) return false;
        return !regex.matcher(path).find();
    }

    public boolean matchesPath(@NotNull String path) {
        return !isExcludedByPattern(path) && !isExcludedHiddenDirectoryPath(path) && matchesRegex(path);
    }

    public boolean matchesDirectoryPath(@NotNull String directoryPath) {
        String normalized = directoryPath.endsWith("/") ? directoryPath : directoryPath + "/";
        return !isExcludedByPattern(normalized) && !isExcludedHiddenDirectoryPath(normalized);
    }

    public boolean isHintDismissed(@NotNull String key) {
        return state.dismissedRipgrepHints.contains(key);
    }

    public void dismissHint(@NotNull String key) {
        state.dismissedRipgrepHints.add(key);
    }

    public @NotNull List<String> allExcludePatterns() {
        LinkedHashSet<String> patterns = new LinkedHashSet<>();
        if (state.excludeGit) {
            patterns.add(".git");
            patterns.add(".svn");
            patterns.add(".hg");
        }
        if (state.excludeNodeModules) {
            patterns.add("node_modules");
        }
        if (state.excludeIdea) {
            patterns.add(".idea");
            patterns.add(".vscode");
        }
        if (state.excludeBuild) {
            patterns.add("build");
            patterns.add("dist");
            patterns.add("target");
            patterns.add("out");
        }
        if (state.excludeCache) {
            patterns.add(".cache");
            patterns.add("__pycache__");
            patterns.add(".gradle");
            patterns.add(".nodenv");
            patterns.add(".rbenv");
            patterns.add(".pyenv");
            patterns.add(".asdf");
        }
        if (state.excludeDsStore) {
            patterns.add(".DS_Store");
            patterns.add("Thumbs.db");
        }
        patterns.addAll(normalizedCustomExcludes());
        return List.copyOf(patterns);
    }

    public @NotNull List<String> toListCommandFlags(@NotNull FileLister.Tool tool) {
        List<String> patterns = allExcludePatterns();
        List<String> flags = new ArrayList<>();
        for (String pattern : patterns) {
            switch (tool) {
                case RG, BUNDLED_RG -> {
                    flags.add("-g");
                    flags.add("!" + toGlob(pattern));
                }
                case FD -> {
                    flags.add("-E");
                    flags.add(pattern);
                }
                case FIND, WALK, SFTP_WALK -> addFindFlags(flags, pattern);
            }
        }
        return List.copyOf(flags);
    }

    private boolean isExcludedByPattern(@NotNull String path) {
        String normalized = path.replace('\\', '/');
        for (String pattern : allExcludePatterns()) {
            if (normalized.contains("/" + pattern + "/")
                || normalized.endsWith("/" + pattern)
                || normalized.endsWith(pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedHiddenDirectoryPath(@NotNull String path) {
        if (!state.excludeHiddenDirectories) return false;
        String normalized = path.replace('\\', '/');
        String[] segments = normalized.split("/");
        int start = normalized.startsWith("/") ? 1 : 0;
        for (int i = start; i < segments.length - 1; i++) {
            String segment = segments[i];
            if (segment.length() > 1 && segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static @NotNull String toGlob(@NotNull String pattern) {
        if (pattern.contains("*") || pattern.contains("?")) {
            return pattern;
        }
        if (pattern.contains(".")) {
            return "**/" + pattern;
        }
        return "**/" + pattern + "/**";
    }

    private static void addFindFlags(@NotNull List<String> flags, @NotNull String pattern) {
        boolean fileLike = pattern.contains(".") && !pattern.contains("*");
        flags.add("-not");
        flags.add(fileLike ? "-name" : "-path");
        flags.add(fileLike ? pattern : "*/" + pattern + "/*");
    }
}
