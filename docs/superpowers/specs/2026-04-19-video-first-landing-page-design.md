# Video-First Landing Page — Design Spec

Date: 2026-04-19
Scope: `docs/` (GitHub Pages static site — `index.html`, `styles.css`, `script.js`, `assets/`)
Builds on: `docs/superpowers/specs/2026-04-18-landing-page-redesign-design.md`

## Goal

Replace the screenshot-driven product tour with a video-driven one. The current tour uses still images; we now have five short screen recordings that show the same flows in motion, plus a sixth recorded flow ("run a script remotely") that isn't on the site yet. Moving to videos makes the page feel like a live product demo instead of a gallery, and lets each step actually *show* the interaction rather than describe it.

The visual language, palette, typography, topbar, download section, and footer from the 2026-04-18 redesign all stay. The only sections that change are the hero visual and the tour.

### Success looks like

- Each tour step plays its matching video only while the step is on-screen, and pauses as soon as it scrolls out of view. No muted-loop tab-murder on the page.
- A visitor who lands on the page cold sees one autoplaying loop in the hero immediately, signaling "this page has motion" without hitting them with five videos at once.
- The tour narrative now covers the full onboarding arc — **connect → secure → transfer → automate → share** — including the previously-unshown "run a script remotely" feature.
- The page still works with JS disabled (video elements fall back to a poster frame and native controls).
- The page still works under `prefers-reduced-motion: reduce` — no video autoplays; posters are shown and the user can click to play.

## Assets

Videos and the replacement main-layout still live in `icons/assets/videos/`. They must be copied into `docs/assets/` so the static site can serve them (GitHub Pages serves from `docs/`). Existing screenshots in `docs/assets/` that are no longer referenced after this change should be deleted, to keep the assets directory honest.

**Stays:**
- `docs/assets/main_layout.png` — replaced with the newer version from `icons/assets/videos/main_layout.png` (same filename, overwrite).
- `docs/assets/favicon.png`, `logo.png`, `termlab-logo-just-text.png`, `termlab-splash.png` — untouched.

**Added (copied from `icons/assets/videos/`):**
- `connect_to_server.mp4`
- `credential_vault.mp4`
- `sftp_upload.mp4`
- `running_script_remotely.mp4`
- `importing_shared_bundle.mp4`

**Removed from `docs/assets/`:**
- `adding_new_ssh_host_with_vault_credential.png`
- `creating_new_credential_in_vault.png`
- `empty_vault.png`
- `example_sftp_server_upload.png`
- `example_ssh_server_connect.png`
- `export_connections_example.png`
- `full_featured_settings_pane.png`

## Page structure (after change)

1. Sticky topbar — **unchanged**
2. Hero — *changed* (video tile replaces front screenshot)
3. Tour — *changed* (five video steps replace four screenshot steps)
4. ~~"Inside TermLab" strip~~ — **removed**
5. Download — **unchanged**
6. Footer — **unchanged**

Removing the "Inside TermLab" section is deliberate: none of the new videos map to the flows it showed (Add host / Empty vault / Settings pane), and reintroducing loose screenshots alongside a video tour would mix media in a way that looks unfinished. The tour now carries enough weight on its own.

## Hero

**Composition:** unchanged from the 2026-04-18 design — asymmetric split (copy left ~45%, visual right ~55%), rotated two-layer composite on the right.

**Change:** the front tile is now a looping video, not a screenshot.

- **Back tile** (`.hero-back`): `assets/main_layout.png`, rotated +2.5°. Unchanged.
- **Front tile** (`.hero-front`): `assets/connect_to_server.mp4`, rotated −1°.
  - `autoplay muted loop playsinline`
  - `poster` attribute set to a still frame that matches the main_layout background tonally (see Implementation notes)
  - `aria-hidden="true"` (decorative; the back tile carries the descriptive alt text)
  - `preload="metadata"` so the browser fetches the poster + headers immediately but doesn't download the whole MP4 until autoplay kicks in

**Why `connect_to_server.mp4`:** it's the shortest of the five and shows the primary verb of the app ("open a terminal on a remote host") in a single clean action — exactly what a hero should say.

On mobile (≤860px) the front tile is already hidden by the existing stylesheet. That rule stays — mobile gets only `main_layout.png` in the hero, matching current behavior.

## Tour

Five alternating full-width steps. Layout alternates image-right / image-left / image-right / image-left / image-right (odd = image right, even = image left), extending the existing `.tour-step--img-right` / `.tour-step--img-left` pattern. No new CSS grid is needed — the existing `.tour-container` / `.tour-step` styles handle it.

Each step replaces its `<figure class="window-chrome tour-image"><img…></figure>` with a `<figure class="window-chrome tour-image tour-video"><video…></video></figure>`. The `window-chrome` wrapper, the border/shadow treatment, and the reveal-on-scroll classes all stay.

### Step content

| # | Eyebrow | Video | Headline | Caption |
|---|---|---|---|---|
| 01 | `> 01 · connect` | `connect_to_server.mp4` | Every host, one keystroke away. | Search Everywhere opens a session instantly. Tabs, splits, and saved terminals feel native because they are. |
| 02 | `> 02 · secure` | `credential_vault.mp4` | One vault for everything. | Passwords, SSH keys, and passphrases in an encrypted vault. Generate keys inside it, reuse them across hosts. |
| 03 | `> 03 · transfer` | `sftp_upload.mp4` | Move files without switching tools. | Dual-pane SFTP sits inside the same window as your terminal. Drag, drop, right-click upload — no separate app. |
| 04 | `> 04 · automate` | `running_script_remotely.mp4` | Run local scripts on remote boxes. | Write a script once, execute it on any saved host. No SSH gymnastics, no copy-paste dance. |
| 05 | `> 05 · share` | `importing_shared_bundle.mp4` | Import a teammate's whole setup in one click. | Open an encrypted `.termlab` bundle and their hosts, tunnels, keys, and credentials land in your app — ready to connect. No setup guide, no shared doc, no rebuild. |

**Step 05 reframes the share story** from the *sender's* side (the old "export bundle" copy) to the *receiver's* side. The recording shows the import flow, and importing is the more emotionally resonant beat — it's the moment a teammate actually gets useful. The underlying feature (encrypted `.termlab` bundles) is the same; only the narration changes.

**Step 04 ("automate") is new** to the site. Tighten copy if the recording shows a flow meaningfully different from "run a local script on a remote host." Treat the caption above as default-good, not final-if-wrong.

The old Step 04 ("share / export") `.tour-note` line ("`.termlab · password-protected · nobody else does this`") is cut — at five steps the page is already denser, and the note reads as filler once the import framing makes the same point more concretely.

## Video playback behavior

Hybrid autoplay: videos **autoplay muted and loop only while the player is intersecting the viewport**, and pause when scrolled out. Driven by the same `IntersectionObserver` machinery that already powers `.reveal` in `script.js`.

### Requirements

1. All `<video>` elements in the tour carry `muted loop playsinline preload="metadata"` but **not** `autoplay`. JS controls play/pause.
2. A new observer (threshold ~0.35) watches every `.tour-video video`. When a video intersects ≥35%, call `.play()`. When it stops intersecting, call `.pause()`.
3. The hero video **is** allowed `autoplay` in markup — it's above the fold and should start as soon as the browser lets it. It still gets `muted loop playsinline`.
4. Under `window.matchMedia("(prefers-reduced-motion: reduce)").matches`:
   - At boot, the hero video is paused, rewound to `currentTime = 0`, and has `controls` added. (Because `script.js` is deferred, the element may have already started — pause + rewind is the correct cleanup, not attribute removal.)
   - The tour observer is not installed. Each tour video is given native `controls` and left paused, showing its first frame.
5. If `IntersectionObserver` is unavailable (very old browsers), fall back to the reduced-motion branch: all videos get `controls`, none autoplay. No polyfill.
6. Videos must have an explicit `poster` attribute so the first paint isn't a black rectangle. Posters can be generated as PNG stills via `ffmpeg -ss 0 -i file.mp4 -frames:v 1 file-poster.jpg` as part of the build-less asset copy step, or — simpler — the tour can ship without custom posters and rely on `preload="metadata"` plus CSS `background-color: #0f1828` on `.window-chrome` to avoid the black-flash. **Pick the no-poster path by default;** only add posters if QA shows a visible black-flash on first load.
7. No inline controls UI; click-to-pause is nice-to-have but not in scope.

### Bandwidth note

Five MP4s total ~19 MB. Because tour steps are separated by tall gutters, at normal scroll speeds only one tour video is typically intersecting at a time — so the observer effectively keeps playback single-threaded. `preload="metadata"` keeps the initial page load light: only headers/first frame are fetched until a video enters view.

## Accessibility

- Every video has an `aria-label` that matches the step's headline, e.g. `aria-label="Demo: every host, one keystroke away."` The existing captions in the text column provide the actual descriptive content; the `aria-label` exists so a screen reader announces the video region meaningfully instead of just "video."
- Videos are muted loops without speech, so no captions/transcripts are required. If future recordings add narration, this changes.
- The hero video is `aria-hidden="true"` (decorative), matching the current hero-front behavior.
- Under `prefers-reduced-motion: reduce`, native `controls` are shown and nothing autoplays — users stay in control.

## Implementation notes

- **Assets step is a copy, not a symlink:** `cp icons/assets/videos/*.mp4 docs/assets/` and `cp icons/assets/videos/main_layout.png docs/assets/main_layout.png`. GitHub Pages must serve real files from the `docs/` tree.
- **HTML changes are localized** to two regions of `docs/index.html`: the `.hero-front` figure and the `.tour-container` block (replacing one `<article>` and adding one new one). The "Inside TermLab" `<section class="inside">` block is deleted whole.
- **CSS changes** are small: one new `.tour-video video { width: 100%; height: auto; display: block; background: #0f1828; }` rule to make videos behave like the existing `.window-chrome img` rule. No new selectors for the tour layout — the existing `.tour-step--img-right` / `.tour-step--img-left` classes are reused.
- **JS changes:** add one function `setupVideoTour()` to `script.js`, call it from `boot()`. It installs the IntersectionObserver for tour videos and handles the reduced-motion branch. Roughly 25–35 lines.
- The existing `[data-gh-*]` live GitHub release data logic, platform detection, and download-row rewiring are untouched.

## Out of scope

- Custom-generated poster images (see playback requirement 6).
- A lightbox / expanded video player on click.
- Audio, captions, or transcripts (none of the videos have speech).
- Serving WebM alongside MP4. MP4 is universally supported; a dual-format pipeline isn't worth the build complexity for a static site.
- Any changes to the topbar, download section, footer, or GitHub live-data script.
