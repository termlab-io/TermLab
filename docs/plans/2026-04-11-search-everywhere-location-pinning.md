# Search Everywhere Location Pinning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Search Everywhere (Conch's command palette) open horizontally centered on the Conch window with its vertical center at 25% of window height, every time, ignoring any previously-saved popup location.

**Architecture:** Register an application-level `AnActionListener` that clears the platform's persisted `WindowStateService` location for `SearchEverywhereManagerImpl.LOCATION_SETTINGS_KEY` before each `SearchEverywhereAction` invocation. With no saved location, IntelliJ's existing `calcPositionAndShow` fresh-install branch runs and computes the exact upper-half-center position we want — no platform subclassing, no custom position math in Conch.

**Tech Stack:** IntelliJ Platform API (`AnActionListener`, `WindowStateService`, `SearchEverywhereManagerImpl`), Java 21.

**Reference spec:** `docs/specs/2026-04-11-search-everywhere-location-pinning-design.md`

---

## File Structure

**New files:**
- `core/src/com/conch/core/palette/ConchSearchEverywhereLocationPinner.java` — single-method `AnActionListener` that clears the SE popup location before every `SearchEverywhereAction` invocation.

**Modified files:**
- `core/resources/META-INF/plugin.xml` — add one `<listener>` entry inside the existing `<applicationListeners>` block.

**Unchanged (confirmed by reading the spec scope section):**
- `ConchSearchEverywhereCustomizer` — the existing appFrameCreated listener that strips unwanted built-in SE contributors. Separate concern.
- `ConchTabsCustomizationStrategy` — tab allowlist. Separate concern.
- `TerminalPaletteContributor`, `HostsSearchEverywhereContributor`, `VaultSearchEverywhereContributor` — contributor implementations. Unrelated to positioning.
- Any file under `plugins/ssh/` or `plugins/vault/` — positioning is a core concern.
- `sdk/` — no SDK changes.

---

## Build & test commands

From `/Users/dustin/projects/conch_workbench`:

```bash
# Compile the whole conch product:
make conch-build

# Launch Conch for the manual smoke test:
make conch
```

No automated tests for this feature — the `AnActionListener` is pure platform glue with two null guards, and the positioning behavior itself lives in platform code that Conch doesn't own. Coverage is manual smoke testing.

---

## Task 1: `ConchSearchEverywhereLocationPinner` + registration

Single-commit task. New Java file + one `plugin.xml` line.

**Files:**
- Create: `core/src/com/conch/core/palette/ConchSearchEverywhereLocationPinner.java`
- Modify: `core/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create `ConchSearchEverywhereLocationPinner.java`**

Create `core/src/com/conch/core/palette/ConchSearchEverywhereLocationPinner.java` with exactly this content:

```java
package com.conch.core.palette;

import com.intellij.ide.actions.SearchEverywhereAction;
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManagerImpl;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.WindowStateService;
import org.jetbrains.annotations.NotNull;

/**
 * Application-level listener that wipes Search Everywhere's persisted
 * popup location before each {@link SearchEverywhereAction} invocation.
 *
 * <p>With the saved location cleared, {@code SearchEverywhereManagerImpl.
 * calcPositionAndShow} sees {@code savedLocation == null} and falls into
 * its fresh-install code path, which computes a horizontally-centered
 * position with the popup's vertical center at {@code windowHeight / 4}
 * — the "center of the upper half" look users expect from a command
 * palette. No subclassing, no reflection, no duplicated position math
 * in Conch.
 *
 * <p>There is explicit precedent for this {@code putLocation(key, null)}
 * pattern in the platform itself: see
 * {@code SearchEverywhereRiderMainToolbarAction.beforeActionPerformed}.
 *
 * <p>Registered via {@code <applicationListener>} in the core plugin
 * XML, subscribing to {@link AnActionListener#TOPIC}.
 */
public final class ConchSearchEverywhereLocationPinner implements AnActionListener {

    @Override
    public void beforeActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event) {
        if (!(action instanceof SearchEverywhereAction)) return;
        Project project = event.getProject();
        if (project == null) return;
        WindowStateService.getInstance(project)
            .putLocation(SearchEverywhereManagerImpl.LOCATION_SETTINGS_KEY, null);
    }
}
```

- [ ] **Step 2: Register the listener in `core/resources/META-INF/plugin.xml`**

Read `core/resources/META-INF/plugin.xml` first to see the current state.

Find the existing `<applicationListeners>` block (it already contains `ConchProjectCloseListener`, `ConchSearchEverywhereCustomizer`, and `ConchToolWindowCustomizer`). At the end of that block, before the closing `</applicationListeners>` tag, add:

```xml
        <!--
          Clears Search Everywhere's persisted popup location before each
          SearchEverywhereAction invocation, so the platform's fresh-install
          "upper-half center" positioning runs on every open. Without this,
          a user who drags the popup once would have the dragged position
          persisted forever. See ConchSearchEverywhereLocationPinner.
        -->
        <listener class="com.conch.core.palette.ConchSearchEverywhereLocationPinner"
                  topic="com.intellij.openapi.actionSystem.ex.AnActionListener"/>
```

No other edits to `plugin.xml`.

- [ ] **Step 3: Build**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build 2>&1 | tail -15
```

Expected: `Build completed successfully`.

If the build fails with `cannot find symbol: class SearchEverywhereAction` or `SearchEverywhereManagerImpl`, the core plugin's `BUILD.bazel` may be missing `//platform/lang-api:lang` or `//platform/lang-impl`. Core already has `//platform/lang-impl` in its deps (that's where `TerminalPaletteContributor` reaches its `SearchEverywhereContributor` base class from), so this shouldn't happen, but if it does the fix is adding the dep alphabetically in `core/BUILD.bazel`'s deps list.

- [ ] **Step 4: Commit**

```bash
cd /Users/dustin/projects/conch_workbench
git add core/src/com/conch/core/palette/ConchSearchEverywhereLocationPinner.java \
        core/resources/META-INF/plugin.xml
git commit -m "$(cat <<'EOF'
feat(core): pin Search Everywhere to upper-half center of Conch window

Register an app-level AnActionListener that wipes the persisted popup
location before each SearchEverywhereAction invocation. The platform's
calcPositionAndShow fresh-install branch then runs on every open and
computes the horizontally-centered, vertical-center-at-25% position we
want — no subclassing, no reflection, no duplicated position math.

Same pattern the platform's SearchEverywhereRiderMainToolbarAction
already uses for its own one-off location reset.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Smoke-test gate

Manual checklist. Block merging until every item passes.

- [ ] **Step 1: Full product build (regression check)**

```bash
cd /Users/dustin/projects/conch_workbench && make conch-build 2>&1 | tail -10
```

Expected: `Build completed successfully`.

- [ ] **Step 2: Launch Conch**

```bash
cd /Users/dustin/projects/conch_workbench && make conch
```

- [ ] **Step 3: Initial positioning check**

Hit `Cmd+Shift+P` to open the command palette.

Expected:
- The popup is horizontally centered on the Conch window.
- Its vertical center is at about 25% of the window height from the top (so the top edge of the popup is at ~15-20% from the top of the window, depending on popup height).

- [ ] **Step 4: Drag-and-reopen check**

With the popup open, drag it to a random screen position (e.g., bottom-right corner of the monitor). Press `Escape` to close it.

Hit `Cmd+Shift+P` again.

Expected: ignores the dragged position, reopens in the upper-half-center of the Conch window. Prior IntelliJ default behavior would have reopened it wherever it was dragged.

- [ ] **Step 5: Multiple-reopen check**

Repeat step 4 three times in rapid succession. Drag, close, reopen, drag, close, reopen, drag, close, reopen.

Expected: every reopen snaps back to upper-half center. No drift, no staleness.

- [ ] **Step 6: Window-move check**

Move the Conch window to a different position on screen (or a different monitor if you have one). Open the palette.

Expected: the popup tracks the Conch window — it's positioned relative to the Conch window's new coordinates, not frozen on the old ones.

- [ ] **Step 7: Known caveat — view type persistence**

Open the palette. Drag the popup's bottom edge down to make it taller (this switches the internal view type from SHORT to FULL). Close it. Reopen.

Expected: the popup is still horizontally centered on the Conch window, but the vertical offset may no longer be exactly `windowHeight/4`. This is the platform's fallback behavior when view type is FULL and is acknowledged in the spec as a deferred follow-up. Not a blocker.

- [ ] **Step 8: Non-Conch entry points check (no regression)**

If you have the Rider-style toolbar Search Everywhere button configured in the main toolbar, click it. Same upper-half-center positioning should apply — our listener matches `SearchEverywhereAction` by `instanceof`, so any subclass is covered.

If any step 1-6 fails, stop and fix before merging. Step 7 failing in a way that's worse than described is a new bug; step 8 failing means our `instanceof` check is wrong.

---

## Self-review checklist (plan author ran this)

**1. Spec coverage:**
- `ConchSearchEverywhereLocationPinner` class → Task 1 Step 1 ✓
- `putLocation(key, null)` on `beforeActionPerformed` → Task 1 Step 1 ✓
- `instanceof SearchEverywhereAction` guard → Task 1 Step 1 ✓
- Null project guard → Task 1 Step 1 ✓
- Registration via `<applicationListener>` in core plugin.xml → Task 1 Step 2 ✓
- Manual smoke test covering fresh open, drag-reopen, multi-reopen, window-move, view type caveat, non-Conch entry point → Task 2 Steps 3-8 ✓

No gaps.

**2. Type / method signature consistency:**
- `SearchEverywhereManagerImpl.LOCATION_SETTINGS_KEY` — public static String constant, confirmed in platform source.
- `WindowStateService.getInstance(Project).putLocation(String, Point)` — accepts `null` for the `Point` parameter (platform convention, confirmed by `SearchEverywhereRiderMainToolbarAction` using the same call shape).
- `AnActionListener#beforeActionPerformed(AnAction, AnActionEvent)` — correct signature for the `AnActionListener.TOPIC` message bus topic.
- `SearchEverywhereAction` — parent class check; `instanceof` also matches any Rider/new-UI toolbar subclasses.

**3. Placeholder scan:**
No "TBD", no hand-waving, no "similar to". Task 1 has the complete Java file and the exact XML snippet to insert. Task 2 is a runtime gate with no code content (appropriate).
