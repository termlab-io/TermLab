# Search Everywhere Location Pinning Design

**Goal:** Make Search Everywhere (TermLab's command palette) always open horizontally centered on the TermLab workbench window with its vertical center at 25% of the window height — the "center of the upper half" look users expect from a command palette. Ignore any previously-saved popup location.

**Driving constraint:** we can't modify IntelliJ platform source. The platform already has the exact positioning we want for fresh-install/first-open cases; the problem is that it persists the last-known location via `WindowStateService` and uses that location on every subsequent open. The fix is to keep the stored location always empty so the platform's fresh-install code path runs on every show.

## Architecture

`SearchEverywhereManagerImpl.calcPositionAndShow` already contains the target behavior:

```java
Point savedLocation = getStateService().getLocation(LOCATION_SETTINGS_KEY);
if (savedLocation == null && viewType == SHORT) {
    // horizontally centered + balloon center at windowHeight/4
    Point screenPoint = new Point(
        (parent.getSize().width  - balloonSize.width)  / 2,
        parent.getHeight() / 4 - balloonSize.height / 2);
    ...
    balloon.show(showPoint);
    return;
}
balloon.showCenteredInCurrentWindow(project);  // ← undesired fallback
```

TermLab registers an application-level `AnActionListener` that fires before any `SearchEverywhereAction` and calls `WindowStateService.putLocation(LOCATION_SETTINGS_KEY, null)`. The next `calcPositionAndShow` sees `savedLocation == null`, hits the fresh-install branch, and computes the upper-half-center position from scratch. No platform subclassing, no reflection, no position math in our code — we reuse the platform's existing calculation.

There is explicit precedent for this pattern in the platform: `SearchEverywhereRiderMainToolbarAction.beforeActionPerformed` does the same `putLocation(key, null)` clear.

## Component: `TermLabSearchEverywhereLocationPinner`

New file at `core/src/com/termlab/core/palette/TermLabSearchEverywhereLocationPinner.java`.

```java
package com.termlab.core.palette;

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
 * in TermLab.
 *
 * <p>Registered via {@code <applicationListener>} in core plugin.xml.
 */
public final class TermLabSearchEverywhereLocationPinner implements AnActionListener {

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

### Behavior notes

- **Matches any `SearchEverywhereAction` subclass.** The `instanceof` check catches both the base action (bound to `Cmd+Shift+P` in TermLab as `TermLab.CommandPalette`) and any platform-provided subclasses like `SearchEverywhereRiderMainToolbarAction`. If we ever add a custom TermLab subclass, it's covered automatically.
- **Null project guard.** Some action invocations happen at the welcome screen with `project == null`. Those use `showInFocusCenter` anyway, so we short-circuit and return.
- **Cost per call.** One `WindowStateService` service lookup plus one `putLocation` map-put. Constant-time, negligible.

## Registration

One line added inside the existing `<applicationListeners>` block in `core/resources/META-INF/plugin.xml`:

```xml
<listener class="com.termlab.core.palette.TermLabSearchEverywhereLocationPinner"
          topic="com.intellij.openapi.actionSystem.ex.AnActionListener"/>
```

No project-level registration needed — this is a pure side-effect listener with no per-project state.

## Testing

No unit tests. `AnActionListener` semantics are platform-enforced and our listener has zero branching logic beyond the null guards. The behavior is covered by a manual smoke test:

1. Launch TermLab. Open palette with `Cmd+Shift+P`. Expected: appears horizontally centered in the TermLab window, vertical center at ~25% of window height.
2. Drag the popup to a random corner of the screen. Close it with `Escape`.
3. Reopen with `Cmd+Shift+P`. Expected: ignores the position from step 2, snaps back to upper-half center.
4. Move the TermLab window to a different monitor. Open palette. Expected: positioned relative to the TermLab window on the new monitor, not frozen on the old one.
5. Open the palette, drag it again, hit `Cmd+Shift+P` twice in a row without closing. Expected: always reopens in upper-half center.

## Caveats and known limits

1. **View type persistence.** The platform's upper-half-center branch is gated on `viewType == SHORT`. If the user drag-resizes the popup tall enough to trip `FULL` view type, the platform persists that and subsequent opens take the fallback `showCenteredInCurrentWindow` path — which will still be centered on the TermLab window but not at the upper-half vertical offset. Deferred fix: also reset view type, either by adding it to this listener or by a supplemental service override. Not addressed in this spec unless the caveat actually bites in practice.

2. **Coverage.** The listener only fires for action-system invocations. All TermLab entry points for Search Everywhere go through `AnAction` today (`TermLab.CommandPalette` action, the Rider-style toolbar action if it's ever re-enabled). A hypothetical non-action entry point that directly calls `SearchEverywhereManager.show()` would bypass the listener. Not a current concern.

3. **Clearing on every action invocation, not just SE invocations.** The `instanceof` check happens before the service lookup, so non-SE actions pay only an `instanceof` and a method return. No cost to other actions.

## Scope

**In scope:**
- `TermLabSearchEverywhereLocationPinner.java` (new file)
- One `<listener>` entry in `core/resources/META-INF/plugin.xml`

**Out of scope:**
- View type reset (caveat #1 — deferred)
- Custom position math (reusing platform's existing calculation)
- Popup size reset — not part of the user's ask
- Any override of `SearchEverywhereManagerImpl`, `WindowStateService`, or related platform services
- Changes to `TermLabSearchEverywhereCustomizer` or `TermLabTabsCustomizationStrategy`
- Tests — manual smoke test in the plan is sufficient for a ~20-line listener
