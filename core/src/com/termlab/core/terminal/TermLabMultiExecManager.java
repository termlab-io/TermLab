package com.termlab.core.terminal;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorComposite;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.EditorsSplitters;
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.Disposer;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service(Service.Level.PROJECT)
public final class TermLabMultiExecManager {
    private static final Logger LOG = Logger.getInstance(TermLabMultiExecManager.class);
    private static final String SSH_PROVIDER_ID = "com.termlab.ssh";

    private static final Method GET_TABBED_PANE_METHOD =
        findMethodByPrefix(EditorWindow.class, "getTabbedPane");
    private static final Method GET_COMPONENT_METHOD =
        findMethodByPrefix(EditorWindow.class, "getComponent");
    private static final Method ADD_COMPOSITE_METHOD =
        findMethodByPrefix(EditorWindow.class, "addComposite",
            EditorComposite.class,
            VirtualFile.class,
            FileEditorOpenOptions.class,
            boolean.class);

    private final Project project;

    private boolean active;
    private boolean rebuildingLayout;
    private @Nullable EditorsSplitters activeSplitters;
    private @Nullable LayoutNode savedLayout;
    private final LinkedHashMap<String, SavedComposite> participantsBySessionId = new LinkedHashMap<>();
    private final LinkedHashSet<String> excludedSessionIds = new LinkedHashSet<>();
    private final LinkedHashSet<String> closedSessionIds = new LinkedHashSet<>();
    private final LinkedHashMap<String, TermLabTerminalVirtualFile.SharedTerminalSession> pinnedSessions =
        new LinkedHashMap<>();
    private @Nullable String sourceSessionId;

    public TermLabMultiExecManager(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull TermLabMultiExecManager getInstance(@NotNull Project project) {
        return project.getService(TermLabMultiExecManager.class);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isParticipant(@Nullable VirtualFile file) {
        return file instanceof TermLabTerminalVirtualFile terminalFile
            && participantsBySessionId.containsKey(terminalFile.getSessionId());
    }

    public boolean isExcluded(@Nullable VirtualFile file) {
        return file instanceof TermLabTerminalVirtualFile terminalFile
            && excludedSessionIds.contains(terminalFile.getSessionId());
    }

    public int getBroadcastCount() {
        if (!active) {
            return 0;
        }
        int count = 0;
        for (String sessionId : participantsBySessionId.keySet()) {
            if (!excludedSessionIds.contains(sessionId) && !closedSessionIds.contains(sessionId)) {
                count++;
            }
        }
        return count;
    }

    public boolean canActivate(@NotNull TermLabTerminalVirtualFile file, @NotNull JComponent component) {
        if (!isSshTerminal(file)) {
            return false;
        }
        if (active) {
            return isParticipant(file);
        }
        return discoverEligibleFiles(resolveSplitters(component)).size() >= 2;
    }

    public boolean canActivateFromActiveWindow() {
        if (active) {
            return true;
        }
        EditorsSplitters splitters = resolveActiveSplitters();
        return splitters != null && discoverEligibleFiles(splitters).size() >= 2;
    }

    public void toggle(@NotNull TermLabTerminalVirtualFile file, @NotNull JComponent component) {
        if (active) {
            deactivate();
        } else {
            activate(file, component);
        }
    }

    public void toggleFromActiveWindow() {
        if (active) {
            deactivate();
            return;
        }

        EditorsSplitters splitters = resolveActiveSplitters();
        if (splitters == null) {
            return;
        }

        List<SavedComposite> eligible = discoverEligibleFiles(splitters);
        if (eligible.size() < 2) {
            return;
        }

        TermLabTerminalVirtualFile sourceFile = resolveActiveSourceFile(splitters, eligible);
        if (sourceFile == null) {
            return;
        }
        activate(sourceFile, splitters);
    }

    public void activate(@NotNull TermLabTerminalVirtualFile sourceFile, @NotNull JComponent component) {
        activate(sourceFile, resolveSplitters(component));
    }

    private void activate(@NotNull TermLabTerminalVirtualFile sourceFile, @NotNull EditorsSplitters splitters) {
        if (active || !isSshTerminal(sourceFile)) {
            return;
        }
        List<SavedComposite> eligible = discoverEligibleFiles(splitters);
        if (eligible.size() < 2) {
            return;
        }

        LayoutNode snapshot = captureLayout(splitters);
        if (snapshot == null) {
            return;
        }

        pinTerminalSessions(snapshot);

        LinkedHashMap<String, SavedComposite> participants = new LinkedHashMap<>();
        for (SavedComposite saved : eligible) {
            if (saved.file instanceof TermLabTerminalVirtualFile terminalFile) {
                participants.put(terminalFile.getSessionId(), saved);
            }
        }
        if (participants.size() < 2) {
            releasePinnedSessions();
            return;
        }

        active = true;
        rebuildingLayout = true;
        activeSplitters = splitters;
        savedLayout = snapshot;
        participantsBySessionId.clear();
        participantsBySessionId.putAll(participants);
        excludedSessionIds.clear();
        closedSessionIds.clear();
        sourceSessionId = sourceFile.getSessionId();

        try {
            EditorWindow baseWindow = detachAllVisibleTabs(splitters);
            LayoutNode multiExecLayout = buildBalancedLayout(new ArrayList<>(participants.values()), sourceFile.getSessionId());
            if (baseWindow != null && multiExecLayout != null) {
                restoreLayoutInto(splitters, baseWindow, multiExecLayout);
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to enter MultiExec mode", e);
            safeRestoreSavedLayout();
            active = false;
            activeSplitters = null;
            savedLayout = null;
            participantsBySessionId.clear();
            excludedSessionIds.clear();
            closedSessionIds.clear();
            sourceSessionId = null;
            releasePinnedSessions();
        } finally {
            rebuildingLayout = false;
            refreshOpenEditors();
        }
    }

    public void deactivate() {
        if (!active) {
            return;
        }

        rebuildingLayout = true;
        try {
            safeRestoreSavedLayout();
        } finally {
            rebuildingLayout = false;
            active = false;
            activeSplitters = null;
            savedLayout = null;
            participantsBySessionId.clear();
            excludedSessionIds.clear();
            closedSessionIds.clear();
            sourceSessionId = null;
            releasePinnedSessions();
            refreshOpenEditors();
        }
    }

    public void setExcluded(@NotNull TermLabTerminalVirtualFile file, boolean excluded) {
        if (!active) {
            return;
        }
        String sessionId = file.getSessionId();
        if (!participantsBySessionId.containsKey(sessionId)) {
            return;
        }

        if (excluded) {
            excludedSessionIds.add(sessionId);
            if (Objects.equals(sourceSessionId, sessionId)) {
                sourceSessionId = chooseFallbackSourceSessionId();
            }
        } else {
            excludedSessionIds.remove(sessionId);
            if (sourceSessionId == null) {
                sourceSessionId = sessionId;
            }
        }
        refreshOpenEditors();
    }

    public void onTerminalFocused(@Nullable TermLabTerminalVirtualFile file) {
        if (!active || rebuildingLayout || file == null || !isParticipant(file) || isExcluded(file)) {
            return;
        }
        sourceSessionId = file.getSessionId();
        refreshOpenEditors();
    }

    public void onSelectionChanged(@Nullable VirtualFile file) {
        if (file instanceof TermLabTerminalVirtualFile terminalFile) {
            onTerminalFocused(terminalFile);
        }
    }

    public void onFileClosed(@NotNull VirtualFile file) {
        if (!active || !(file instanceof TermLabTerminalVirtualFile terminalFile)) {
            return;
        }

        String sessionId = terminalFile.getSessionId();
        if (!participantsBySessionId.containsKey(sessionId)) {
            return;
        }

        closedSessionIds.add(sessionId);
        participantsBySessionId.remove(sessionId);
        excludedSessionIds.remove(sessionId);
        if (Objects.equals(sourceSessionId, sessionId)) {
            sourceSessionId = chooseFallbackSourceSessionId();
        }

        if (participantsBySessionId.size() < 2) {
            deactivate();
        } else {
            refreshOpenEditors();
        }
    }

    public void broadcastBytes(@NotNull TermLabTerminalVirtualFile sourceFile, byte @NotNull [] bytes) {
        if (!shouldBroadcastFrom(sourceFile)) {
            return;
        }
        sourceSessionId = sourceFile.getSessionId();
        for (SavedComposite target : participantsBySessionId.values()) {
            if (target.file.equals(sourceFile) || excludedSessionIds.contains(target.sessionId)
                || closedSessionIds.contains(target.sessionId)) {
                continue;
            }
            if (target.file instanceof TermLabTerminalVirtualFile terminalFile) {
                writeBytes(terminalFile, bytes);
            }
        }
    }

    public void broadcastString(@NotNull TermLabTerminalVirtualFile sourceFile, @NotNull String text) {
        if (!shouldBroadcastFrom(sourceFile)) {
            return;
        }
        sourceSessionId = sourceFile.getSessionId();
        for (SavedComposite target : participantsBySessionId.values()) {
            if (target.file.equals(sourceFile) || excludedSessionIds.contains(target.sessionId)
                || closedSessionIds.contains(target.sessionId)) {
                continue;
            }
            if (target.file instanceof TermLabTerminalVirtualFile terminalFile) {
                writeString(terminalFile, text);
            }
        }
    }

    private boolean shouldBroadcastFrom(@NotNull TermLabTerminalVirtualFile sourceFile) {
        if (!active || rebuildingLayout || !participantsBySessionId.containsKey(sourceFile.getSessionId())
            || excludedSessionIds.contains(sourceFile.getSessionId())) {
            return false;
        }
        sourceSessionId = sourceFile.getSessionId();
        return true;
    }

    private void writeBytes(@NotNull TermLabTerminalVirtualFile file, byte @NotNull [] bytes) {
        TermLabTerminalVirtualFile.SharedTerminalSession session = null;
        try {
            session = file.acquireSession(project);
            TtyConnector connector = session.getConnector();
            connector.write(bytes);
        } catch (IOException e) {
            LOG.warn("Failed to broadcast bytes to " + file.getPresentableName(), e);
        } finally {
            if (session != null) {
                session.release();
            }
        }
    }

    private void writeString(@NotNull TermLabTerminalVirtualFile file, @NotNull String text) {
        TermLabTerminalVirtualFile.SharedTerminalSession session = null;
        try {
            session = file.acquireSession(project);
            TtyConnector connector = session.getConnector();
            connector.write(text);
        } catch (IOException e) {
            LOG.warn("Failed to broadcast text to " + file.getPresentableName(), e);
        } finally {
            if (session != null) {
                session.release();
            }
        }
    }

    private void safeRestoreSavedLayout() {
        EditorsSplitters splitters = activeSplitters;
        LayoutNode layout = pruneDisposed(savedLayout);
        if (splitters == null || layout == null) {
            return;
        }

        EditorWindow baseWindow = detachAllVisibleTabs(splitters);
        if (baseWindow != null) {
            restoreLayoutInto(splitters, baseWindow, layout);
        }
    }

    private void restoreLayoutInto(@NotNull EditorsSplitters splitters,
                                   @NotNull EditorWindow baseWindow,
                                   @NotNull LayoutNode layout) {
        Map<LeafNode, EditorWindow> assignedWindows = new LinkedHashMap<>();
        rebuildNode(baseWindow, layout, assignedWindows);

        EditorWindow focusedWindow = null;
        VirtualFile focusedFile = null;
        for (Map.Entry<LeafNode, EditorWindow> entry : assignedWindows.entrySet()) {
            LeafNode leaf = entry.getKey();
            EditorWindow window = entry.getValue();
            VirtualFile selected = leaf.getSelectedFile();
            if (selected != null && window.getComposite(selected) != null) {
                window.setSelectedComposite(selected, false);
            } else if (!leaf.files.isEmpty()) {
                window.setSelectedComposite(leaf.files.get(0).file, false);
            }
            if (leaf.currentWindow) {
                focusedWindow = window;
                focusedFile = selected != null ? selected : (!leaf.files.isEmpty() ? leaf.files.get(0).file : null);
            }
        }

        if (focusedWindow == null && !assignedWindows.isEmpty()) {
            Map.Entry<LeafNode, EditorWindow> first = assignedWindows.entrySet().iterator().next();
            focusedWindow = first.getValue();
            LeafNode leaf = first.getKey();
            focusedFile = leaf.getSelectedFile();
            if (focusedFile == null && !leaf.files.isEmpty()) {
                focusedFile = leaf.files.get(0).file;
            }
        }

        if (focusedWindow != null) {
            focusedWindow.setAsCurrentWindow(false);
            if (focusedFile != null && focusedWindow.getComposite(focusedFile) != null) {
                focusedWindow.setSelectedComposite(focusedFile, true);
            } else {
                focusedWindow.requestFocus(false);
            }
        }
    }

    private void rebuildNode(@NotNull EditorWindow window,
                             @NotNull LayoutNode node,
                             @NotNull Map<LeafNode, EditorWindow> assignedWindows) {
        if (node instanceof LeafNode leaf) {
            populateLeaf(window, leaf);
            assignedWindows.put(leaf, window);
            return;
        }

        SplitNode split = (SplitNode) node;
        SavedComposite seed = firstComposite(split.firstChild);
        if (seed == null) {
            throw new IllegalStateException("Split node has no seed composite");
        }

        if (window.getFileList().isEmpty()) {
            attachComposite(window, seed, 0, false, false);
        }

        EditorWindow secondWindow = window.split(split.orientation, true, null, false);
        if (secondWindow == null) {
            throw new IllegalStateException("Failed to create MultiExec split");
        }

        rebuildNode(secondWindow, split.secondChild, assignedWindows);
        rebuildNode(window, split.firstChild, assignedWindows);

        Container parent = getWindowComponent(window).getParent();
        if (parent instanceof Splitter splitter) {
            splitter.setProportion(split.proportion);
        }
    }

    private void populateLeaf(@NotNull EditorWindow window, @NotNull LeafNode leaf) {
        List<VirtualFile> currentFiles = new ArrayList<>(window.getFileList());
        VirtualFile keep = null;
        if (currentFiles.size() == 1 && !leaf.files.isEmpty() && Objects.equals(currentFiles.get(0), leaf.files.get(0).file)) {
            keep = currentFiles.get(0);
        }

        if (keep == null && currentFiles.size() == 1 && !leaf.files.isEmpty()) {
            SavedComposite firstTarget = leaf.files.get(0);
            attachComposite(window, firstTarget, 0, false, false);
            currentFiles = new ArrayList<>(window.getFileList());
            if (Objects.equals(currentFiles.get(0), firstTarget.file)) {
                keep = firstTarget.file;
            }
        }

        for (VirtualFile current : currentFiles) {
            if (!Objects.equals(current, keep)) {
                window.closeFile(current);
            }
        }

        int index = 0;
        for (SavedComposite saved : leaf.files) {
            if (Objects.equals(saved.file, keep)) {
                index++;
                continue;
            }
            attachComposite(window, saved, index, false, false);
            index++;
        }
    }

    private void attachComposite(@NotNull EditorWindow window,
                                 @NotNull SavedComposite saved,
                                 int index,
                                 boolean selectAsCurrent,
                                 boolean requestFocus) {
        if (Disposer.isDisposed(saved.composite) || window.getComposite(saved.file) == saved.composite) {
            return;
        }

        FileEditorOpenOptions options = new FileEditorOpenOptions(
            selectAsCurrent,
            false,
            false,
            requestFocus,
            false,
            index,
            false,
            null,
            false,
            false,
            requestFocus,
            null
        );
        invokeMethod(ADD_COMPOSITE_METHOD, window, saved.composite, saved.file, options, true);
    }

    private @Nullable EditorWindow detachAllVisibleTabs(@NotNull EditorsSplitters splitters) {
        List<EditorWindow> windows = new ArrayList<>(Arrays.asList(splitters.getWindows()));
        if (windows.isEmpty()) {
            return null;
        }

        EditorWindow baseWindow = splitters.getCurrentWindow();
        if (baseWindow == null || !windows.contains(baseWindow)) {
            baseWindow = windows.get(0);
        }

        for (EditorWindow window : windows) {
            detachWindowTabs(window);
        }
        for (EditorWindow window : windows) {
            if (window != baseWindow && !window.isDisposed()) {
                window.removeFromSplitter();
            }
        }
        baseWindow.setAsCurrentWindow(false);
        return baseWindow;
    }

    private void detachWindowTabs(@NotNull EditorWindow window) {
        Object tabbedPane = invokeMethod(GET_TABBED_PANE_METHOD, window);
        if (tabbedPane == null) {
            return;
        }

        List<VirtualFile> files = new ArrayList<>(window.getFileList());
        for (int i = files.size() - 1; i >= 0; i--) {
            invokePublicMethod(tabbedPane, "removeTabAt", new Class<?>[]{int.class, int.class}, i, -1);
        }
    }

    private @Nullable LayoutNode captureLayout(@NotNull EditorsSplitters splitters) {
        if (splitters.getComponentCount() == 0) {
            return null;
        }

        Map<Component, EditorWindow> windowsByComponent = new HashMap<>();
        for (EditorWindow window : splitters.getWindows()) {
            windowsByComponent.put(getWindowComponent(window), window);
        }
        EditorWindow currentWindow = splitters.getCurrentWindow();
        return captureNode(splitters.getComponent(0), windowsByComponent, currentWindow);
    }

    private @Nullable LayoutNode captureNode(@NotNull Component component,
                                             @NotNull Map<Component, EditorWindow> windowsByComponent,
                                             @Nullable EditorWindow currentWindow) {
        if (component instanceof Splitter splitter) {
            LayoutNode first = captureNode(splitter.getFirstComponent(), windowsByComponent, currentWindow);
            LayoutNode second = captureNode(splitter.getSecondComponent(), windowsByComponent, currentWindow);
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            return new SplitNode(
                splitter.getOrientation() ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT,
                splitter.getProportion(),
                first,
                second
            );
        }

        EditorWindow window = windowsByComponent.get(component);
        if (window == null) {
            return null;
        }

        List<SavedComposite> files = new ArrayList<>();
        for (VirtualFile file : window.getFileList()) {
            EditorComposite composite = window.getComposite(file);
            if (composite != null) {
                files.add(new SavedComposite(file, composite));
            }
        }
        return new LeafNode(files, window.getSelectedFile(), window == currentWindow);
    }

    private @Nullable LayoutNode pruneDisposed(@Nullable LayoutNode node) {
        if (node == null) {
            return null;
        }
        if (node instanceof LeafNode leaf) {
            List<SavedComposite> files = new ArrayList<>();
            for (SavedComposite saved : leaf.files) {
                if (!Disposer.isDisposed(saved.composite)) {
                    files.add(saved);
                }
            }
            if (files.isEmpty()) {
                return null;
            }
            VirtualFile selected = leaf.selectedFile;
            VirtualFile selectedCandidate = selected;
            if (selectedCandidate != null
                && files.stream().noneMatch(saved -> saved.file.equals(selectedCandidate))) {
                selected = files.get(0).file;
            }
            return new LeafNode(files, selected, leaf.currentWindow);
        }

        SplitNode split = (SplitNode) node;
        LayoutNode first = pruneDisposed(split.firstChild);
        LayoutNode second = pruneDisposed(split.secondChild);
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return new SplitNode(split.orientation, split.proportion, first, second);
    }

    private void pinTerminalSessions(@NotNull LayoutNode node) {
        for (SavedComposite saved : collectComposites(node)) {
            if (!(saved.file instanceof TermLabTerminalVirtualFile terminalFile)) {
                continue;
            }
            pinnedSessions.computeIfAbsent(terminalFile.getSessionId(), key -> terminalFile.acquireSession(project));
        }
    }

    private void releasePinnedSessions() {
        for (TermLabTerminalVirtualFile.SharedTerminalSession session : pinnedSessions.values()) {
            session.release();
        }
        pinnedSessions.clear();
    }

    private @Nullable SavedComposite firstComposite(@NotNull LayoutNode node) {
        if (node instanceof LeafNode leaf) {
            return leaf.files.isEmpty() ? null : leaf.files.get(0);
        }
        SplitNode split = (SplitNode) node;
        SavedComposite first = firstComposite(split.firstChild);
        return first != null ? first : firstComposite(split.secondChild);
    }

    private @NotNull List<SavedComposite> collectComposites(@NotNull LayoutNode node) {
        List<SavedComposite> result = new ArrayList<>();
        collectComposites(node, result);
        return result;
    }

    private void collectComposites(@NotNull LayoutNode node, @NotNull List<SavedComposite> sink) {
        if (node instanceof LeafNode leaf) {
            sink.addAll(leaf.files);
            return;
        }
        SplitNode split = (SplitNode) node;
        collectComposites(split.firstChild, sink);
        collectComposites(split.secondChild, sink);
    }

    private @Nullable LayoutNode buildBalancedLayout(@NotNull List<SavedComposite> files,
                                                     @Nullable String sourceSessionId) {
        return buildBalancedLayout(files, sourceSessionId, 0);
    }

    private @Nullable LayoutNode buildBalancedLayout(@NotNull List<SavedComposite> files,
                                                     @Nullable String sourceSessionId,
                                                     int depth) {
        if (files.isEmpty()) {
            return null;
        }
        if (files.size() == 1) {
            SavedComposite only = files.get(0);
            return new LeafNode(
                Collections.singletonList(only),
                only.file,
                Objects.equals(only.sessionId, sourceSessionId)
            );
        }

        int mid = files.size() / 2;
        LayoutNode first = buildBalancedLayout(files.subList(0, mid), sourceSessionId, depth + 1);
        LayoutNode second = buildBalancedLayout(files.subList(mid, files.size()), sourceSessionId, depth + 1);
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        int orientation = depth % 2 == 0 ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT;
        return new SplitNode(orientation, 0.5f, first, second);
    }

    private @NotNull List<SavedComposite> discoverEligibleFiles(@NotNull EditorsSplitters splitters) {
        List<SavedComposite> eligible = new ArrayList<>();
        for (EditorWindow window : splitters.getWindows()) {
            for (VirtualFile file : window.getFileList()) {
                if (!(file instanceof TermLabTerminalVirtualFile terminalFile) || !isSshTerminal(terminalFile)) {
                    continue;
                }
                EditorComposite composite = window.getComposite(file);
                if (composite != null) {
                    eligible.add(new SavedComposite(file, composite));
                }
            }
        }
        return eligible;
    }

    private @Nullable String chooseFallbackSourceSessionId() {
        for (String sessionId : participantsBySessionId.keySet()) {
            if (!excludedSessionIds.contains(sessionId) && !closedSessionIds.contains(sessionId)) {
                return sessionId;
            }
        }
        return null;
    }

    private void refreshOpenEditors() {
        FileEditorManager manager = FileEditorManager.getInstance(project);
        for (FileEditor editor : manager.getAllEditors()) {
            if (editor instanceof TermLabTerminalEditor terminalEditor) {
                terminalEditor.refreshMultiExecHeader();
            }
        }
    }

    private @NotNull EditorsSplitters resolveSplitters(@NotNull JComponent component) {
        return FileEditorManagerEx.getInstanceEx(project).getSplittersFor(component);
    }

    private @Nullable EditorsSplitters resolveActiveSplitters() {
        FileEditorManagerEx manager = FileEditorManagerEx.getInstanceEx(project);
        EditorWindow currentWindow = manager.getCurrentWindow();
        if (currentWindow != null) {
            return manager.getSplittersFor(getWindowComponent(currentWindow));
        }

        VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        if (selectedFiles.length > 0) {
            FileEditor selectedEditor = manager.getSelectedEditor(selectedFiles[0]);
            if (selectedEditor != null) {
                return manager.getSplittersFor(selectedEditor.getComponent());
            }
        }
        return null;
    }

    private @Nullable TermLabTerminalVirtualFile resolveActiveSourceFile(@NotNull EditorsSplitters splitters,
                                                                         @NotNull List<SavedComposite> eligible) {
        EditorWindow currentWindow = splitters.getCurrentWindow();
        if (currentWindow != null) {
            VirtualFile selected = currentWindow.getSelectedFile();
            if (selected instanceof TermLabTerminalVirtualFile terminalFile && isSshTerminal(terminalFile)) {
                return terminalFile;
            }
        }

        for (SavedComposite saved : eligible) {
            if (saved.file instanceof TermLabTerminalVirtualFile terminalFile) {
                return terminalFile;
            }
        }
        return null;
    }

    private boolean isSshTerminal(@Nullable VirtualFile file) {
        return file instanceof TermLabTerminalVirtualFile terminalFile && isSshTerminal(terminalFile);
    }

    private boolean isSshTerminal(@NotNull TermLabTerminalVirtualFile file) {
        return SSH_PROVIDER_ID.equals(file.getProvider().getId());
    }

    private @NotNull JComponent getWindowComponent(@NotNull EditorWindow window) {
        Object component = invokeMethod(GET_COMPONENT_METHOD, window);
        if (component instanceof JComponent jComponent) {
            return jComponent;
        }
        throw new IllegalStateException("EditorWindow component was not a JComponent");
    }

    private static @Nullable Method findMethodByPrefix(@NotNull Class<?> owner,
                                                       @NotNull String prefix,
                                                       Class<?>... parameterTypes) {
        for (Method method : owner.getMethods()) {
            if (method.getName().startsWith(prefix)
                && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Missing method " + prefix + " on " + owner.getName());
    }

    private static @Nullable Object invokeMethod(@Nullable Method method, @NotNull Object target, Object... args) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to invoke " + method.getName(), e);
        }
    }

    private static @Nullable Object invokePublicMethod(@NotNull Object target,
                                                       @NotNull String name,
                                                       @NotNull Class<?>[] parameterTypes,
                                                       Object... args) {
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to invoke " + name + " on " + target.getClass().getName(), e);
        }
    }

    private abstract static class LayoutNode {
    }

    private static final class SplitNode extends LayoutNode {
        private final int orientation;
        private final float proportion;
        private final LayoutNode firstChild;
        private final LayoutNode secondChild;

        private SplitNode(int orientation,
                          float proportion,
                          @NotNull LayoutNode firstChild,
                          @NotNull LayoutNode secondChild) {
            this.orientation = orientation;
            this.proportion = proportion;
            this.firstChild = firstChild;
            this.secondChild = secondChild;
        }
    }

    private static final class LeafNode extends LayoutNode {
        private final List<SavedComposite> files;
        private final @Nullable VirtualFile selectedFile;
        private final boolean currentWindow;

        private LeafNode(@NotNull List<SavedComposite> files,
                         @Nullable VirtualFile selectedFile,
                         boolean currentWindow) {
            this.files = files;
            this.selectedFile = selectedFile;
            this.currentWindow = currentWindow;
        }

        private @Nullable VirtualFile getSelectedFile() {
            if (selectedFile != null) {
                for (SavedComposite saved : files) {
                    if (saved.file.equals(selectedFile)) {
                        return selectedFile;
                    }
                }
            }
            return files.isEmpty() ? null : files.get(0).file;
        }
    }

    private static final class SavedComposite {
        private final VirtualFile file;
        private final EditorComposite composite;
        private final @Nullable String sessionId;

        private SavedComposite(@NotNull VirtualFile file, @NotNull EditorComposite composite) {
            this.file = file;
            this.composite = composite;
            this.sessionId = file instanceof TermLabTerminalVirtualFile terminalFile ? terminalFile.getSessionId() : null;
        }
    }
}
