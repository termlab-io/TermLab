# Video-First Landing Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the screenshot-driven product tour in `docs/` with a five-step video tour (connect → secure → transfer → automate → share), swap the hero's front tile for a looping video, drop the "Inside TermLab" section, and wire up in-viewport autoplay with `prefers-reduced-motion` fallback.

**Architecture:** Zero-build static site. Edits are localized to `docs/index.html`, `docs/styles.css`, `docs/script.js`, and `docs/assets/`. Video playback is controlled by one new function in `script.js` using `IntersectionObserver` — the same pattern already used for `.reveal`. No new dependencies, no new build step.

**Tech Stack:** Plain HTML5 `<video>`, vanilla JS, CSS. No framework.

**Testing note:** The site has no automated test suite and adding one for a static landing page is not in scope. Verification is manual browser QA at the end of each task, with concrete acceptance criteria listed per task.

**Source of truth:** `docs/superpowers/specs/2026-04-19-video-first-landing-page-design.md`

---

## Pre-flight checks

Run these once before starting Task 1. These sanity-check the workspace so nothing surprises you mid-task.

- [ ] **P.1: Confirm you are on the intended branch and the tree is clean**

```bash
cd /Users/dustin/projects/conch_workbench
git status
git rev-parse --abbrev-ref HEAD
```

Expected: clean working tree; branch matches the one you expect to merge from.

- [ ] **P.2: Confirm all source assets exist**

```bash
ls -la icons/assets/videos/
```

Expected output lists these six files (sizes approximate):

```
connect_to_server.mp4           ~1.2 MB
credential_vault.mp4            ~4.6 MB
importing_shared_bundle.mp4     ~3.8 MB
main_layout.png                 ~179 KB
running_script_remotely.mp4     ~5.9 MB
sftp_upload.mp4                 ~3.0 MB
```

If any file is missing, STOP and ask the user.

- [ ] **P.3: Confirm target directory exists**

```bash
ls -d docs/assets
```

Expected: `docs/assets` (the directory exists and is not a symlink).

---

## Task 1: Copy new assets into `docs/assets/` and remove unreferenced screenshots

**Files:**
- Create: `docs/assets/connect_to_server.mp4`
- Create: `docs/assets/credential_vault.mp4`
- Create: `docs/assets/sftp_upload.mp4`
- Create: `docs/assets/running_script_remotely.mp4`
- Create: `docs/assets/importing_shared_bundle.mp4`
- Modify (overwrite): `docs/assets/main_layout.png`
- Delete: `docs/assets/adding_new_ssh_host_with_vault_credential.png`
- Delete: `docs/assets/creating_new_credential_in_vault.png`
- Delete: `docs/assets/empty_vault.png`
- Delete: `docs/assets/example_sftp_server_upload.png`
- Delete: `docs/assets/example_ssh_server_connect.png`
- Delete: `docs/assets/export_connections_example.png`
- Delete: `docs/assets/full_featured_settings_pane.png`

- [ ] **Step 1: Copy the five MP4s and the new main_layout.png into `docs/assets/`**

```bash
cd /Users/dustin/projects/conch_workbench
cp icons/assets/videos/connect_to_server.mp4 docs/assets/
cp icons/assets/videos/credential_vault.mp4 docs/assets/
cp icons/assets/videos/sftp_upload.mp4 docs/assets/
cp icons/assets/videos/running_script_remotely.mp4 docs/assets/
cp icons/assets/videos/importing_shared_bundle.mp4 docs/assets/
cp icons/assets/videos/main_layout.png docs/assets/main_layout.png
```

- [ ] **Step 2: Verify the copies landed and file sizes are non-trivial**

```bash
ls -la docs/assets/*.mp4 docs/assets/main_layout.png
```

Expected: five `.mp4` files each ≥ 1 MB, plus `main_layout.png` ≥ 150 KB. If any is 0 bytes, STOP.

- [ ] **Step 3: Delete the seven screenshots that the new page no longer references**

```bash
rm docs/assets/adding_new_ssh_host_with_vault_credential.png
rm docs/assets/creating_new_credential_in_vault.png
rm docs/assets/empty_vault.png
rm docs/assets/example_sftp_server_upload.png
rm docs/assets/example_ssh_server_connect.png
rm docs/assets/export_connections_example.png
rm docs/assets/full_featured_settings_pane.png
```

- [ ] **Step 4: Confirm `docs/assets/` is clean**

```bash
ls docs/assets/
```

Expected to see exactly:

```
connect_to_server.mp4
credential_vault.mp4
favicon.png
importing_shared_bundle.mp4
logo.png
main_layout.png
running_script_remotely.mp4
sftp_upload.mp4
termlab-logo-just-text.png
termlab-splash.png
```

Nothing else. If stray screenshots remain, delete them now.

- [ ] **Step 5: Sanity check — grep for any still-referenced screenshot filenames in `docs/`**

```bash
```

Use the Grep tool (not bash `grep`):

```
pattern: "adding_new_ssh_host_with_vault_credential|creating_new_credential_in_vault|empty_vault|example_sftp_server_upload|example_ssh_server_connect|export_connections_example|full_featured_settings_pane"
path: docs/
output_mode: files_with_matches
```

Expected: matches in `docs/index.html` (those will be fixed in later tasks) and possibly in `docs/superpowers/specs/2026-04-18-landing-page-redesign-design.md` (the older spec — leave it). **Do not** act on `index.html` yet.

- [ ] **Step 6: Stage and commit the asset swap**

```bash
git add docs/assets/
git commit -m "$(cat <<'EOF'
docs(site): swap screenshot assets for demo videos + updated main_layout

Adds the five demo MP4s used by the new video-first tour and overwrites
main_layout.png with the newer recording-era version. Deletes seven
screenshots that are no longer referenced by the redesign.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:** `git status` clean, `ls docs/assets/` matches the expected list exactly.

---

## Task 2: Add the base CSS rule for tour videos

**Files:**
- Modify: `docs/styles.css`

**Why this task is first among the code changes:** the CSS rule is additive and harmless on its own. Landing it before touching `index.html` means when Task 3/4 introduces `<video>` elements, they render correctly on first load with no intermediate broken state.

- [ ] **Step 1: Open `docs/styles.css` and locate the existing `.window-chrome img` block**

The block looks like this (around line 274–279 in the current file):

```css
.window-chrome img {
  width: 100%;
  height: auto;
  display: block;
  background: #0f1828;
}
```

- [ ] **Step 2: Add a matching rule for `<video>` right after it**

Insert this block immediately after the `.window-chrome img { … }` rule:

```css
.window-chrome video {
  width: 100%;
  height: auto;
  display: block;
  background: #0f1828;
}
```

Rationale: the `<video>` element needs the same "fill the window-chrome frame, no black flash on first paint" treatment the `<img>` elements already get. Separate selector (not a combined `.window-chrome img, .window-chrome video`) to keep the file diff-friendly for future changes.

- [ ] **Step 3: Verify the file still parses (open it in a browser or check for syntax errors)**

```bash
cd /Users/dustin/projects/conch_workbench
node -e "const f=require('fs').readFileSync('docs/styles.css','utf8'); const o=(f.match(/{/g)||[]).length, c=(f.match(/}/g)||[]).length; console.log('open:',o,'close:',c); if(o!==c) process.exit(1);"
```

Expected: `open: N close: N` (equal counts). Non-zero exit means unbalanced braces — fix before proceeding.

- [ ] **Step 4: Commit**

```bash
git add docs/styles.css
git commit -m "$(cat <<'EOF'
docs(site): style .window-chrome video like its sibling img

Gives <video> the same fill-the-frame + dark background treatment as
the existing <img> rule, so the new tour videos render cleanly inside
the window-chrome cards with no first-paint flash.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:** `docs/styles.css` contains both `.window-chrome img { … }` and `.window-chrome video { … }` rules with identical bodies.

---

## Task 3: Swap the hero front tile from `<img>` to `<video>`

**Files:**
- Modify: `docs/index.html` (the `.hero-front` figure block, currently around lines 95–101)

- [ ] **Step 1: Locate the current `.hero-front` block in `docs/index.html`**

Current block:

```html
      <figure class="window-chrome hero-front" aria-hidden="true">
        <img
          src="./assets/adding_new_ssh_host_with_vault_credential.png"
          alt=""
          loading="eager"
        />
      </figure>
```

- [ ] **Step 2: Replace the `<img>` with a looping, autoplay-capable `<video>`**

Replace the whole figure block with:

```html
      <figure class="window-chrome hero-front" aria-hidden="true">
        <video
          class="hero-video"
          src="./assets/connect_to_server.mp4"
          autoplay
          muted
          loop
          playsinline
          preload="metadata"
        ></video>
      </figure>
```

Notes:
- `autoplay` is in markup here (not stripped to JS-only), because the hero is above the fold — we want it starting ASAP. `script.js` will pause + rewind it under `prefers-reduced-motion`.
- No `poster` — relying on the `background: #0f1828` in `.window-chrome video` to avoid the black-flash (see spec "Implementation notes").
- `class="hero-video"` is a hook used by Task 6 so JS can target the hero video specifically without having to walk up to `.hero-front`.

- [ ] **Step 3: Browser-verify the hero**

Open `docs/index.html` in a browser (file URL or a local static server — `python3 -m http.server -d docs 8000` works).

Expected:
- Hero loads with the main_layout screenshot rotated in the back and the `connect_to_server` video playing muted/looping in the front tile.
- No JS console errors.
- Desktop layout looks identical to before aside from the front tile being animated.

If the video doesn't play: check DevTools → Network that `connect_to_server.mp4` returned 200.
If you see a black rectangle briefly: this is acceptable per spec — only revisit if it's persistent.

- [ ] **Step 4: Commit**

```bash
git add docs/index.html
git commit -m "$(cat <<'EOF'
docs(site): hero front tile is now a looping connect_to_server video

Replaces the rotated add-host screenshot with a muted/looped MP4 that
shows the primary verb of the app — opening a terminal on a remote
host. main_layout.png remains the back tile, unchanged.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:** visiting the page shows a looping video in the hero front tile on desktop; `git log -1` shows this commit.

---

## Task 4: Replace the tour section with five video steps

**Files:**
- Modify: `docs/index.html` (the entire `<section class="tour">` block, currently lines 109–188)

This task is a single bulk replacement because the five steps are structurally identical twins and are easier to reason about as one unit than five near-duplicate edits.

- [ ] **Step 1: Locate the current `<section class="tour">` block**

It starts with `<section class="tour" aria-label="Product tour">` and ends with the matching `</section>` (includes four `<article class="tour-step …">` blocks).

- [ ] **Step 2: Replace the entire `<section class="tour">…</section>` block with the new 5-step version**

New block (paste verbatim):

```html
      <section class="tour" aria-label="Product tour">
  <div class="container tour-container">
    <article class="tour-step tour-step--img-right reveal">
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 01 &middot; connect</p>
        <h2 class="tour-headline">Every host, one keystroke away.</h2>
        <p class="tour-caption">
          Search Everywhere opens a session instantly. Tabs, splits, and saved
          terminals feel native because they are.
        </p>
      </div>
      <figure class="window-chrome tour-image tour-video">
        <video
          src="./assets/connect_to_server.mp4"
          muted
          loop
          playsinline
          preload="metadata"
          aria-label="Demo: every host, one keystroke away."
        ></video>
      </figure>
    </article>

    <article class="tour-step tour-step--img-left reveal">
      <figure class="window-chrome tour-image tour-video">
        <video
          src="./assets/credential_vault.mp4"
          muted
          loop
          playsinline
          preload="metadata"
          aria-label="Demo: one vault for everything."
        ></video>
      </figure>
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 02 &middot; secure</p>
        <h2 class="tour-headline">One vault for everything.</h2>
        <p class="tour-caption">
          Passwords, SSH keys, and passphrases in an encrypted vault.
          Generate keys inside it, reuse them across hosts.
        </p>
      </div>
    </article>

    <article class="tour-step tour-step--img-right reveal">
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 03 &middot; transfer</p>
        <h2 class="tour-headline">Move files without switching tools.</h2>
        <p class="tour-caption">
          Dual-pane SFTP sits inside the same window as your terminal.
          Drag, drop, right-click upload &mdash; no separate app.
        </p>
      </div>
      <figure class="window-chrome tour-image tour-video">
        <video
          src="./assets/sftp_upload.mp4"
          muted
          loop
          playsinline
          preload="metadata"
          aria-label="Demo: move files without switching tools."
        ></video>
      </figure>
    </article>

    <article class="tour-step tour-step--img-left reveal">
      <figure class="window-chrome tour-image tour-video">
        <video
          src="./assets/running_script_remotely.mp4"
          muted
          loop
          playsinline
          preload="metadata"
          aria-label="Demo: run local scripts on remote boxes."
        ></video>
      </figure>
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 04 &middot; automate</p>
        <h2 class="tour-headline">Run local scripts on remote boxes.</h2>
        <p class="tour-caption">
          Write a script once, execute it on any saved host. No SSH
          gymnastics, no copy-paste dance.
        </p>
      </div>
    </article>

    <article class="tour-step tour-step--img-right reveal">
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 05 &middot; share</p>
        <h2 class="tour-headline">
          Import a teammate's whole setup in one click.
        </h2>
        <p class="tour-caption">
          Open an encrypted <code>.termlab</code> bundle and their hosts,
          tunnels, keys, and credentials land in your app &mdash; ready to
          connect. No setup guide, no shared doc, no rebuild.
        </p>
      </div>
      <figure class="window-chrome tour-image tour-video">
        <video
          src="./assets/importing_shared_bundle.mp4"
          muted
          loop
          playsinline
          preload="metadata"
          aria-label="Demo: import a teammate's whole setup in one click."
        ></video>
      </figure>
    </article>
  </div>
</section>
```

Key points:
- Five articles; layout alternates `--img-right` (01, 03, 05) and `--img-left` (02, 04). This matches the existing desktop pattern and is already handled by the stylesheet.
- Every `<figure>` gets `tour-image tour-video` — `tour-image` preserves the existing shadow/layout, `tour-video` is a hook for Task 6.
- No `autoplay` attribute — Task 6 will turn play/pause on via JS and `IntersectionObserver`.
- `preload="metadata"` keeps first-load light.
- `aria-label` echoes the headline so screen readers announce the video region meaningfully.

- [ ] **Step 3: Browser-verify the tour layout**

Reload the page.

Expected:
- Five tour steps are visible, alternating left/right image placement.
- Videos show the first frame (possibly solid dark if the browser hasn't decoded yet) — they do **not** autoplay. That's correct; Task 6 will turn them on.
- No layout regressions (headlines, captions, and mono eyebrows are positioned as before).
- No JS console errors.

If a video cell is broken/404: recheck the `src` path against `docs/assets/` (Task 1 should have placed them there).

- [ ] **Step 4: Commit**

```bash
git add docs/index.html
git commit -m "$(cat <<'EOF'
docs(site): replace 4-screenshot tour with 5-step video tour

Tour now follows the onboarding narrative: connect → secure → transfer
→ automate → share. The "share" step is reframed to the receiving side
(importing a .termlab bundle), which is the more concrete beat and
matches the new recording. Videos are paused at this step; a later
change wires in-viewport autoplay/pause.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:** tour section renders five video cards alternating left/right; no 404s in DevTools Network; headlines and eyebrows match the spec table.

---

## Task 5: Remove the "Inside TermLab" section

**Files:**
- Modify: `docs/index.html` (the `<section class="inside">` block, currently lines 189–231)

- [ ] **Step 1: Locate the `<section class="inside">` block**

It starts with `<section class="inside" aria-label="Inside TermLab">` and ends with the next matching `</section>`.

- [ ] **Step 2: Delete the entire section (opening tag through closing tag, inclusive)**

After the edit, the line that was `</section>` closing the `.tour` section should be immediately followed by the line that starts `<section id="download" class="download" …>`. Do not leave an empty line where the inside section used to be — remove it cleanly.

- [ ] **Step 3: Browser-verify**

Reload.

Expected:
- After the fifth tour step, the page goes straight to the download section. No empty gap, no leftover heading.
- No console errors.

- [ ] **Step 4: (Optional) Remove now-unused CSS for `.inside` if the stylesheet feels cluttered**

The `.inside`, `.inside-header`, `.inside-headline`, `.inside-grid`, `.inside-card` rules in `docs/styles.css` are dead code after this task. Leaving them doesn't break anything, but deleting them is tidy.

**Default: leave them in place.** The CSS cost is trivial and keeping them means a future "put the inside strip back" edit is a one-line HTML add. If you delete them, delete the whole `/* ---------- Inside TermLab ---------- */` region (around lines 520–570) plus the matching `@media (max-width: 860px)` block for `.inside-grid`.

- [ ] **Step 5: Commit**

```bash
git add docs/index.html
git commit -m "$(cat <<'EOF'
docs(site): remove Inside TermLab section

The video tour now carries the entire product story; reintroducing
loose UI-chrome screenshots underneath it mixed media in a way that
looked unfinished. Page flow is now hero → 5-step tour → download.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:** page scrolls from tour directly into download; no orphan "Built to feel like part of the OS" heading.

---

## Task 6: Wire in-viewport autoplay/pause + prefers-reduced-motion in `script.js`

**Files:**
- Modify: `docs/script.js`

This is the one behavioral addition. One new function, one call from `boot()`.

- [ ] **Step 1: Open `docs/script.js` and find the `boot()` function**

It's near the bottom of the file:

```js
  // ---------- Boot ----------
  function boot() {
    applyPlatform(detectPlatform());
    fetchRepo().then(applyRepo);
    fetchRelease().then(applyRelease);
    setupReveal();
  }
```

- [ ] **Step 2: Add `setupVideoTour()` as a new section above `boot()`**

Insert this block immediately above the `// ---------- Boot ----------` comment (so it sits between `setupReveal` and `boot`):

```js
  // ---------- Video tour ----------
  function setupVideoTour() {
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const hasIO = "IntersectionObserver" in window;
    const hero = document.querySelector(".hero-video");
    const tourVideos = Array.from(document.querySelectorAll(".tour-video video"));

    // Fallback path: reduced-motion OR no IntersectionObserver support.
    // Both cases mean "don't autoplay, let the user drive."
    if (reduced || !hasIO) {
      if (hero) {
        // script.js is deferred, so the hero may already be playing —
        // pausing + rewinding is the correct cleanup, not attribute removal.
        hero.pause();
        hero.currentTime = 0;
        hero.setAttribute("controls", "");
        hero.removeAttribute("autoplay");
      }
      tourVideos.forEach((v) => v.setAttribute("controls", ""));
      return;
    }

    if (!tourVideos.length) return;

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          const video = entry.target;
          if (entry.isIntersecting) {
            // play() returns a Promise that rejects if autoplay is blocked.
            // Swallow the rejection — the user can still click to play.
            const p = video.play();
            if (p && typeof p.catch === "function") p.catch(() => {});
          } else {
            video.pause();
          }
        });
      },
      { threshold: 0.35 }
    );

    tourVideos.forEach((v) => io.observe(v));
  }
```

Rationale for each bit:
- `reduced` branch first because it short-circuits everything below it. Pause+rewind of the hero handles the "deferred script means the element may have already started playing" case called out in the spec.
- `.play()` returns a Promise that can reject if the browser refuses autoplay (e.g., Safari without user gesture even for muted video in rare configs). We catch and swallow — the video will just stay on its first frame, which is not a crash.
- Threshold `0.35` per spec.
- No `unobserve` after intersect — we want the video to pause when the user scrolls back out, not stay playing off-screen.

- [ ] **Step 3: Call `setupVideoTour()` from `boot()`**

Change `boot()` from:

```js
  function boot() {
    applyPlatform(detectPlatform());
    fetchRepo().then(applyRepo);
    fetchRelease().then(applyRelease);
    setupReveal();
  }
```

to:

```js
  function boot() {
    applyPlatform(detectPlatform());
    fetchRepo().then(applyRepo);
    fetchRelease().then(applyRelease);
    setupReveal();
    setupVideoTour();
  }
```

- [ ] **Step 4: Browser-verify the normal path**

Reload the page in a normal browser window (no reduced-motion override).

Open DevTools → Console. Expected: no errors.

Expected behavior as you scroll:
- Hero video is already playing on page load (from its markup `autoplay`).
- Each tour video starts playing when it crosses ~35% of the viewport.
- Each tour video pauses when it scrolls out. (Quick check: scroll past step 02, then back up — step 02's video should be in a mid-clip frame if it played, not stuck on frame 0.)

A useful DevTools probe — paste into the console:

```js
Array.from(document.querySelectorAll(".tour-video video")).map(v => ({
  src: v.src.split("/").pop(),
  paused: v.paused,
  currentTime: v.currentTime.toFixed(2),
}));
```

While scrolled into step 03, the `sftp_upload.mp4` row should show `paused: false` and a non-zero `currentTime`; the others should be `paused: true`.

- [ ] **Step 5: Browser-verify the reduced-motion path**

In DevTools: open Command Menu (Cmd+Shift+P) → `Emulate CSS prefers-reduced-motion: reduce` → hard-reload (Cmd+Shift+R).

Expected:
- Hero video shows native `controls` and is not playing (paused at frame 0).
- All tour videos show `controls` and are not playing.
- No console errors.
- Clicking play on any video plays it normally with sound muted.

Reset emulation when done.

- [ ] **Step 6: Commit**

```bash
git add docs/script.js
git commit -m "$(cat <<'EOF'
docs(site): autoplay/pause tour videos in viewport; honor reduced motion

Adds setupVideoTour(): IntersectionObserver-driven play/pause with a
0.35 threshold, plus a prefers-reduced-motion branch that pauses the
hero, rewinds it, and exposes native <video> controls on every clip.
Preserves the existing reveal-on-scroll behavior untouched.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

**Acceptance:**
- Normal browser: hero plays immediately; each tour video plays when scrolled into view and pauses when scrolled out.
- Reduced-motion emulation: no videos autoplay; all show native controls.
- No console errors in either mode.

---

## Task 7: Full-page final verification

No code changes. This is a read-through of everything shipped, on a live browser, with a short checklist.

**Files:** none modified.

- [ ] **Step 1: Serve the site locally**

```bash
cd /Users/dustin/projects/conch_workbench
python3 -m http.server -d docs 8000
```

Open http://localhost:8000/ in a normal browser window.

- [ ] **Step 2: Work the checklist from top to bottom**

Desktop (width ≥ 1024px), no reduced motion:

- [ ] Topbar unchanged (wordmark left, GitHub pill + Docs + Download right).
- [ ] Hero: `main_layout.png` rotated in back, `connect_to_server.mp4` rotated in front and looping muted.
- [ ] Hero download button label reflects platform (e.g., "Download for macOS" on a Mac).
- [ ] `> termlab / vX.Y.Z` eyebrow shows a real version (via GitHub live data).
- [ ] Tour step 01 `> 01 · connect`, video on the right, headline "Every host, one keystroke away." — video plays when on screen.
- [ ] Tour step 02 `> 02 · secure`, video on the left, headline "One vault for everything."
- [ ] Tour step 03 `> 03 · transfer`, video on the right, headline "Move files without switching tools."
- [ ] Tour step 04 `> 04 · automate`, video on the left, headline "Run local scripts on remote boxes."
- [ ] Tour step 05 `> 05 · share`, video on the right, headline "Import a teammate's whole setup in one click."
- [ ] No "Inside TermLab" section exists between tour and download.
- [ ] Download section unchanged (three buttons, live release info).
- [ ] Footer unchanged.
- [ ] DevTools Console is empty of errors across the full scroll.
- [ ] DevTools Network shows five MP4 requests — each only fetches `metadata` range initially, then expands when its video enters view.

Mobile width (≤ 640px) via DevTools device emulation:

- [ ] Hero front tile (video) is hidden; hero shows only `main_layout.png` (matches pre-existing behavior).
- [ ] Tour steps stack single-column, video above text on each step.
- [ ] Download row stacks full-width.

Reduced motion:

- [ ] Via DevTools emulation, all videos show native controls and none autoplay.

- [ ] **Step 3: Spot-check JS disabled path**

DevTools → Settings → Debugger → "Disable JavaScript" (or the appropriate toggle). Hard reload.

Expected:
- Page still renders correctly end-to-end.
- Hero video autoplays (markup-level), tour videos show their first frame with no controls (no harm — without JS, there's no observer to start them, which is acceptable). Re-enable JS when done.

- [ ] **Step 4: Stop the local server**

Ctrl+C the Python server.

- [ ] **Step 5: Confirm `git status` is clean**

```bash
git status
```

Expected: clean tree. All commits from Tasks 1–6 are present.

- [ ] **Step 6: (Optional) Tag or open a PR if that's the project's norm**

Not prescribed here — defer to the user's release workflow.

**Acceptance:** the checklist above is fully green. If any item fails, open the relevant earlier task, fix, re-verify, and add a fix-up commit (not an amend).

---

## Post-implementation note

If step 04 ("automate") copy reads wrong once you actually watch `running_script_remotely.mp4` in context, it's a one-line edit in `docs/index.html` — change the `<h2 class="tour-headline">` and/or `<p class="tour-caption">` inside the fourth `<article>`. The spec explicitly flags that caption as "default-good, not final-if-wrong."
