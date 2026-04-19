# Landing Page Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the crowded, copy-heavy current landing page at `docs/` with a screenshot-driven product tour that feels like a professional product page for pros and amateurs, while staying a zero-build three-file static site served by GitHub Pages.

**Architecture:** Single-page static site with `docs/index.html` + `docs/styles.css` + `docs/script.js` + 8 new screenshot assets. The page is built section-by-section: topbar → hero (layered composite) → 4-step tour (Connect/Secure/Transfer/Share) → "Inside TermLab" strip → download picker → footer. Live GitHub API data (stars, latest release version/date/assets) is fetched once on load with hard-coded fallbacks so the page always renders. No framework, no bundler, no tests-as-code — each task ends with specific browser verification and a commit.

**Tech Stack:** Plain HTML5, CSS3 (custom properties, grid, clamp, prefers-reduced-motion), vanilla ES modules (`fetch`, `navigator.userAgentData` / `navigator.platform`). Fonts: Manrope + Space Grotesk (Google Fonts, already linked).

**Reference design spec:** `docs/superpowers/specs/2026-04-18-landing-page-redesign-design.md` — consult it when a task leaves something ambiguous.

---

## File structure

Every task below touches some subset of these four files:

- `docs/index.html` — single page, fully rewritten structure. One `<header class="topbar">` + one `<main>` containing `<section>`s: `.hero`, `.tour` (with 4 `.tour-step`s), `.inside`, `.download`, plus `<footer>`.
- `docs/styles.css` — fully rewritten. Order: CSS custom props (root) → base resets → typography → shared utility classes (`.eyebrow-mono`, `.window-chrome`, `.btn`) → per-section blocks (`.topbar`, `.hero`, `.tour`, `.inside`, `.download`, `.footer`) → media queries.
- `docs/script.js` — fully rewritten as one IIFE or plain top-level module. Three responsibilities: platform detection, live GitHub fetch with caching + fallback, reduced-motion-aware fade-in-on-scroll. No dependencies.
- `docs/assets/` — gains 8 new screenshot PNGs (see Task 1).

## Repo context the engineer needs

- Current working tree has unrelated in-progress changes in `plugins/sftp/`, `BUILD.bazel`, `Makefile`, and `customization/`. **DO NOT** stage those in any of the commits below. Every `git add` in this plan is scoped to `docs/` only.
- The product repo used for live GitHub data is **`termlab-io/termlab`** (per the spec's reconciliation of the two repos the current page references).
- The current page lives at `docs/index.html`, `docs/styles.css`, `docs/script.js`. All three will be fully replaced.

## Verification strategy (read before starting)

This is a static site with no test framework (YAGNI — the spec forbids a build step). In place of unit tests, every task that produces visible output ends with a **browser verification** step. To run:

```bash
cd docs && python3 -m http.server 8000
```

Then open `http://localhost:8000/` in Chrome or Safari. Keep the server running across tasks. Use DevTools:
- **Elements tab** to confirm DOM structure.
- **Console tab** to paste provided JS snippets that check live values.
- **Device toolbar** (Cmd+Shift+M) to check mobile at 375×812 and tablet at 820×1180.
- **Network tab throttling → Offline** to verify the GitHub fallback path.

The "expected" lines in each verification step are the acceptance criteria. If any one doesn't match, the task is not done.

---

## Task 1: Copy new screenshot assets into `docs/assets/`

**Files:**
- Create: `docs/assets/main_layout.png`
- Create: `docs/assets/example_ssh_server_connect.png`
- Create: `docs/assets/creating_new_credential_in_vault.png`
- Create: `docs/assets/example_sftp_server_upload.png`
- Create: `docs/assets/export_connections_example.png`
- Create: `docs/assets/adding_new_ssh_host_with_vault_credential.png`
- Create: `docs/assets/empty_vault.png`
- Create: `docs/assets/full_featured_settings_pane.png`

- [ ] **Step 1: Define "done" for this task**

All 8 files above exist at the listed paths, each non-empty, each a valid PNG. No other files in `docs/assets/` are touched.

- [ ] **Step 2: Copy the files**

Run:
```bash
cp ~/Desktop/example_screenshots/main_layout.png docs/assets/main_layout.png
cp ~/Desktop/example_screenshots/example_ssh_server_connect.png docs/assets/example_ssh_server_connect.png
cp ~/Desktop/example_screenshots/creating_new_credential_in_vault.png docs/assets/creating_new_credential_in_vault.png
cp ~/Desktop/example_screenshots/example_sftp_server_upload.png docs/assets/example_sftp_server_upload.png
cp ~/Desktop/example_screenshots/export_connections_example.png docs/assets/export_connections_example.png
cp ~/Desktop/example_screenshots/adding_new_ssh_host_with_vault_credential.png docs/assets/adding_new_ssh_host_with_vault_credential.png
cp ~/Desktop/example_screenshots/empty_vault.png docs/assets/empty_vault.png
cp ~/Desktop/example_screenshots/full_featured_settings_pane.png docs/assets/full_featured_settings_pane.png
```

- [ ] **Step 3: Verify the files**

Run:
```bash
ls -la docs/assets/*.png | awk '{print $5, $9}'
file docs/assets/main_layout.png docs/assets/export_connections_example.png
```

Expected: 8 PNG files listed with non-zero sizes. `file` output contains `PNG image data` for both.

- [ ] **Step 4: Commit**

```bash
git add docs/assets/main_layout.png docs/assets/example_ssh_server_connect.png docs/assets/creating_new_credential_in_vault.png docs/assets/example_sftp_server_upload.png docs/assets/export_connections_example.png docs/assets/adding_new_ssh_host_with_vault_credential.png docs/assets/empty_vault.png docs/assets/full_featured_settings_pane.png
git commit -m "docs(landing): add app screenshots for landing page redesign"
```

---

## Task 2: Reset `docs/index.html`, `docs/styles.css`, `docs/script.js` to a working skeleton

**Files:**
- Modify (full rewrite): `docs/index.html`
- Modify (full rewrite): `docs/styles.css`
- Modify (full rewrite): `docs/script.js`

This task replaces all three files with a clean skeleton: new HTML structure with empty section containers, a new CSS base layer (tokens + resets + typography + grid overlay), and an empty script that we'll fill in later tasks. After this task the page will render a nearly-blank dark canvas with the topbar wordmark visible — that's the intended intermediate state.

- [ ] **Step 1: Define "done" for this task**

- Page loads without console errors.
- Page background shows the dark navy gradient and faint grid overlay from the current design.
- Topbar wordmark renders "Term" (light) + "Lab" (blue) at top-left, sticky.
- `main` contains five empty `<section>`s with IDs: `hero`, `tour`, `inside`, `download`. Plus a `<footer>`. (Yes, four sections + footer — `hero` is at the top of `main`.)
- No references remain to: `command-rotator`, `.prompt`, `.command`, `.hero-copy` containing `.hero-visual`, `.metrics`, `.feature-grid`, `.timeline`, `.screen-grid`, `.docs-grid`.

- [ ] **Step 2: Replace `docs/index.html`**

Write this content to `docs/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta
      name="description"
      content="TermLab is an Integrated SysOps Environment for SSH, SFTP, tunnels, credentials, and terminal-first operations workflows."
    />
    <title>TermLab | Integrated SysOps Environment</title>
    <link rel="icon" type="image/png" href="./assets/favicon.png" />
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link
      href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=Manrope:wght@400;500;600;700;800&family=Space+Grotesk:wght@500;700&display=swap"
      rel="stylesheet"
    />
    <link rel="stylesheet" href="./styles.css" />
  </head>
  <body>
    <header class="topbar">
      <a class="brand" href="#top" aria-label="TermLab home">
        <img src="./assets/logo.png" alt="" />
        <span><em>Term</em><strong>Lab</strong></span>
      </a>
    </header>

    <main id="top">
      <section class="hero" aria-labelledby="hero-headline"></section>
      <section class="tour" aria-label="Product tour"></section>
      <section class="inside" aria-label="Inside TermLab"></section>
      <section id="download" class="download" aria-labelledby="download-headline"></section>
    </main>

    <footer class="site-footer"></footer>

    <script src="./script.js" defer></script>
  </body>
</html>
```

Note: added JetBrains Mono to the font link — we'll need it for the mono accents in later tasks. `logo.png` alt is empty because the wordmark text next to it already provides the name.

- [ ] **Step 3: Replace `docs/styles.css`**

Write this content to `docs/styles.css`:

```css
/* ---------- Tokens ---------- */
:root {
  --bg-0: #0a1120;
  --bg-1: #0d1524;
  --bg-2: #121e31;
  --panel: rgba(12, 20, 35, 0.72);
  --stroke: rgba(167, 186, 216, 0.18);
  --text: #ecf0f3;
  --muted: #aeb8c7;
  --term: #dedfdf;
  --lab: #5e7293;
  --lab-bright: #7d91b6;
  --glow: rgba(125, 145, 182, 0.24);
  --shadow-lg: 0 30px 80px rgba(4, 8, 16, 0.42);
  --shadow-md: 0 18px 40px rgba(2, 5, 11, 0.22);
  --radius-lg: 20px;
  --radius-md: 14px;
  --content-max: 1200px;
  --gutter: clamp(20px, 4vw, 48px);
  --font-display: "Space Grotesk", system-ui, sans-serif;
  --font-body: "Manrope", system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
}

/* ---------- Reset ---------- */
*,
*::before,
*::after {
  box-sizing: border-box;
}

html {
  scroll-behavior: smooth;
}

body {
  margin: 0;
  min-height: 100vh;
  color: var(--text);
  font-family: var(--font-body);
  font-size: 16px;
  line-height: 1.6;
  background:
    radial-gradient(circle at 15% 10%, rgba(125, 145, 182, 0.18), transparent 30%),
    radial-gradient(circle at 85% 5%, rgba(222, 223, 223, 0.06), transparent 22%),
    linear-gradient(135deg, var(--bg-0) 0%, var(--bg-1) 52%, var(--bg-2) 100%);
}

body::before {
  content: "";
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.02) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.02) 1px, transparent 1px);
  background-size: 80px 80px;
  mask-image: radial-gradient(circle at center, black 40%, transparent 85%);
}

img {
  max-width: 100%;
  display: block;
}

a {
  color: inherit;
  text-decoration: none;
}

main,
footer {
  position: relative;
  z-index: 1;
}

/* ---------- Utilities ---------- */
.container {
  width: min(calc(100% - (var(--gutter) * 2)), var(--content-max));
  margin-inline: auto;
}

.eyebrow-mono {
  display: inline-block;
  margin: 0 0 18px;
  font-family: var(--font-mono);
  font-size: 0.78rem;
  font-weight: 500;
  letter-spacing: 0.04em;
  color: var(--lab-bright);
  text-transform: lowercase;
}

/* ---------- Topbar (skeleton) ---------- */
.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px var(--gutter);
  background: rgba(10, 17, 32, 0.72);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--stroke);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.brand img {
  width: 28px;
  height: 28px;
  border-radius: 8px;
}

.brand span {
  font-family: var(--font-display);
  font-size: 1.1rem;
  letter-spacing: -0.04em;
}

.brand em {
  color: var(--term);
  font-style: normal;
  font-weight: 500;
}

.brand strong {
  color: var(--lab-bright);
  font-weight: 700;
}

/* ---------- Section placeholders (temporary) ---------- */
main > section,
main > section:empty {
  min-height: 10vh;
}
```

- [ ] **Step 4: Replace `docs/script.js`**

Write this content to `docs/script.js`:

```javascript
// docs/script.js — landing page behaviour.
// Progressive enhancement only: page fully works if this file fails to load.
(() => {
  "use strict";
  // Implementations added in later tasks.
})();
```

- [ ] **Step 5: Verify in the browser**

Start the local server if not already running:
```bash
cd docs && python3 -m http.server 8000
```

Open `http://localhost:8000/`. Check:

- Page background is dark navy with a subtle grid overlay.
- Topbar wordmark "TermLab" (split colors) is visible top-left and stays pinned when scrolling.
- Open DevTools Console — no errors.
- Open DevTools Elements — confirm `<main>` has four `<section>` children with classes `hero`, `tour`, `inside`, `download` and a `<footer class="site-footer">` after `<main>`.

Run in the Console:
```javascript
console.log([...document.querySelectorAll("main > section")].map(s => s.className));
```
Expected output: `["hero", "tour", "inside", "download"]`

- [ ] **Step 6: Commit**

```bash
git add docs/index.html docs/styles.css docs/script.js
git commit -m "docs(landing): reset page to clean skeleton

Fresh HTML structure with five empty section containers, rewritten CSS
base layer (tokens, resets, typography, grid overlay, skeleton topbar),
and an empty script. Removes the dead command-rotator and the nested
.hero-visual-in-.hero-copy bug from the old page."
```

---

## Task 3: Build the topbar

**Files:**
- Modify: `docs/index.html` (fill the existing `<header class="topbar">`)
- Modify: `docs/styles.css` (extend the `/* Topbar */` section)

- [ ] **Step 1: Define "done" for this task**

- Topbar shows (left→right): wordmark, flexible gap, `★ GitHub` pill (muted), `Docs` link, `Download` primary button.
- The `★` pill links to `https://github.com/termlab-io/termlab` in a new tab.
- The `Download` button links to `#download` and scrolls smoothly.
- The `Docs` link points to `https://github.com/termlab-io/termlab/tree/main/docs` in a new tab.
- Topbar is sticky at top with blurred translucent background.
- On mobile (≤640px) the right-side items shrink: the stars pill keeps its icon but hides its text; `Docs` stays; `Download` stays but is sized smaller.

- [ ] **Step 2: Update `docs/index.html`**

Replace the existing `<header class="topbar">` block with:

```html
<header class="topbar">
  <a class="brand" href="#top" aria-label="TermLab home">
    <img src="./assets/logo.png" alt="" />
    <span><em>Term</em><strong>Lab</strong></span>
  </a>

  <nav class="topbar-actions" aria-label="Primary">
    <a
      class="star-pill"
      href="https://github.com/termlab-io/termlab"
      target="_blank"
      rel="noreferrer noopener"
    >
      <span class="star-pill__icon" aria-hidden="true">★</span>
      <span class="star-pill__label" data-gh-stars>GitHub</span>
    </a>
    <a
      class="topbar-link"
      href="https://github.com/termlab-io/termlab/tree/main/docs"
      target="_blank"
      rel="noreferrer noopener"
    >Docs</a>
    <a class="btn btn-primary btn-sm" href="#download">Download</a>
  </nav>
</header>
```

- [ ] **Step 3: Add topbar CSS**

Append to `docs/styles.css`, AFTER the existing `/* ---------- Topbar (skeleton) ---------- */` block (it's fine to leave the earlier rules — they still apply):

```css
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 0.92rem;
}

.topbar-link {
  color: var(--muted);
  transition: color 160ms ease;
}

.topbar-link:hover,
.topbar-link:focus-visible {
  color: var(--text);
}

.star-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--stroke);
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: 0.82rem;
  color: var(--muted);
  transition: color 160ms ease, border-color 160ms ease;
}

.star-pill:hover,
.star-pill:focus-visible {
  color: var(--text);
  border-color: rgba(167, 186, 216, 0.38);
}

.star-pill__icon {
  color: var(--lab-bright);
}

/* ---------- Buttons ---------- */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 18px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-family: var(--font-body);
  font-weight: 700;
  font-size: 0.95rem;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  transition: transform 180ms ease, background 180ms ease, border-color 180ms ease, color 180ms ease;
}

.btn-sm {
  padding: 9px 14px;
  font-size: 0.88rem;
}

.btn-primary {
  color: #07111e;
  background: linear-gradient(135deg, var(--term), #f6f7f7);
}

.btn-primary:hover,
.btn-primary:focus-visible {
  transform: translateY(-1px);
}

.btn-secondary {
  color: var(--text);
  background: linear-gradient(135deg, rgba(95, 114, 147, 0.3), rgba(95, 114, 147, 0.08));
  border-color: rgba(143, 164, 199, 0.3);
}

.btn-secondary:hover,
.btn-secondary:focus-visible {
  transform: translateY(-1px);
  border-color: rgba(143, 164, 199, 0.6);
}

.btn-ghost {
  color: var(--muted);
  border-color: rgba(222, 223, 223, 0.18);
}

.btn-ghost:hover,
.btn-ghost:focus-visible {
  color: var(--text);
  border-color: rgba(222, 223, 223, 0.4);
}

@media (prefers-reduced-motion: reduce) {
  .btn,
  .topbar-link,
  .star-pill {
    transition: none;
  }
  .btn:hover,
  .btn:focus-visible {
    transform: none;
  }
}

@media (max-width: 640px) {
  .topbar {
    padding-inline: 16px;
    gap: 12px;
  }
  .topbar-actions {
    gap: 10px;
    font-size: 0.88rem;
  }
  .star-pill__label {
    display: none;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/`.

- Topbar right side shows: `★ GitHub` pill, `Docs` text link, `Download` white button.
- Click `Download` — page smoothly scrolls to the empty download section (nothing visible there yet, that's fine).
- Click `★ GitHub` — opens `github.com/termlab-io/termlab` in a new tab.
- Resize the browser to ≤640px — the `★` pill loses its "GitHub" text label, icon stays.
- DevTools Elements → no duplicate or dangling elements; topbar has correct children.

Run in Console:
```javascript
const star = document.querySelector(".topbar .star-pill");
console.log(star.href, star.target);
```
Expected: `https://github.com/termlab-io/termlab _blank` (URL, then target).

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): build topbar with stars pill, docs link, and download CTA"
```

---

## Task 4: Build the hero section (layered composite)

**Files:**
- Modify: `docs/index.html` (fill the empty `<section class="hero">`)
- Modify: `docs/styles.css` (append a `/* Hero */` block)

- [ ] **Step 1: Define "done" for this task**

- Hero is a two-column grid on desktop: copy on the left (~45%), visual on the right (~55%).
- Copy column: mono eyebrow `> termlab / latest`, H1 two-line headline "One environment / for every box you run.", one-line lede, button row (primary Download + ghost "Linux · Windows" link), trust pill below.
- Visual column: layered composite — `main_layout.png` framed in window chrome with a 3° right tilt; `adding_new_ssh_host_with_vault_credential.png` floating over the lower-right corner with a −1° tilt, partially overlapping.
- Window chrome is CSS-only — three red/yellow/green dots top-left, no extra images.
- Subtle radial glow behind the composite in `--lab-bright` hue.
- Faint mono ticker under the composite: `SSH · SFTP · TUNNELS · VAULT · BUNDLES`.
- Mobile (≤860px): visual stacks above copy; only the back-layer image renders (front dialog hidden); button row stacks full-width; headline keeps readable wrapping.
- `prefers-reduced-motion`: tilts and glow stay (they're static), but any hover transform is disabled.
- Fallback text in the trust pill and eyebrow shows `latest` and `★ GitHub` when JS hasn't updated them.

- [ ] **Step 2: Update `docs/index.html`**

Replace the empty `<section class="hero">` with:

```html
<section class="hero" aria-labelledby="hero-headline">
  <div class="container hero-grid">
    <div class="hero-copy">
      <p class="eyebrow-mono">
        <span>&gt; termlab</span>
        <span aria-hidden="true"> / </span>
        <span data-gh-version>latest</span>
      </p>
      <h1 id="hero-headline" class="hero-headline">
        <span>One environment</span>
        <span>for every box you run.</span>
      </h1>
      <p class="hero-lede">
        SSH, SFTP, tunnels, and an encrypted vault — in one desktop app.
        Share your whole setup as one file.
      </p>
      <div class="hero-actions">
        <a
          class="btn btn-primary"
          href="https://github.com/termlab-io/termlab/releases/latest"
          data-download-primary
        >
          <span data-download-label>Download TermLab</span>
        </a>
        <a class="btn btn-ghost" href="#download">Linux &middot; Windows</a>
      </div>
      <p class="hero-trust" data-gh-trust>
        <span class="hero-trust__icon" aria-hidden="true">★</span>
        <span data-gh-stars>GitHub</span>
        <span class="hero-trust__sep" aria-hidden="true">·</span>
        <span data-gh-version>latest</span>
        <span class="hero-trust__sep" aria-hidden="true">·</span>
        <span>macOS · Linux · Windows</span>
      </p>
    </div>

    <div class="hero-visual" aria-hidden="false">
      <div class="hero-glow" aria-hidden="true"></div>

      <figure class="window-chrome hero-back">
        <img
          src="./assets/main_layout.png"
          alt="TermLab main window showing terminal, hosts list, tunnels list, and SFTP pane together."
          loading="eager"
        />
      </figure>

      <figure class="window-chrome hero-front" aria-hidden="true">
        <img
          src="./assets/adding_new_ssh_host_with_vault_credential.png"
          alt=""
          loading="eager"
        />
      </figure>
    </div>
  </div>

  <p class="hero-ticker" aria-hidden="true">
    SSH &middot; SFTP &middot; TUNNELS &middot; VAULT &middot; BUNDLES
  </p>
</section>
```

- [ ] **Step 3: Add hero CSS**

Append to `docs/styles.css`:

```css
/* ---------- Window chrome (shared) ---------- */
.window-chrome {
  position: relative;
  margin: 0;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: #0f1828;
  border: 1px solid var(--stroke);
  box-shadow: var(--shadow-lg);
}

.window-chrome::before {
  content: "";
  position: absolute;
  top: 10px;
  left: 14px;
  width: 54px;
  height: 10px;
  border-radius: 999px;
  background:
    radial-gradient(circle at 5px 5px, #ff5f56 4.5px, transparent 5px),
    radial-gradient(circle at 25px 5px, #ffbd2e 4.5px, transparent 5px),
    radial-gradient(circle at 45px 5px, #27c93f 4.5px, transparent 5px);
  z-index: 2;
  pointer-events: none;
}

.window-chrome img {
  width: 100%;
  height: auto;
  display: block;
  margin-top: 26px; /* leave room for the traffic lights above the image */
  background: #0f1828;
}

/* ---------- Hero ---------- */
.hero {
  padding: clamp(56px, 9vw, 120px) 0 clamp(40px, 6vw, 72px);
  position: relative;
}

.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: clamp(32px, 5vw, 72px);
  align-items: center;
}

.hero-copy {
  max-width: 48ch;
}

.hero-headline {
  margin: 0 0 18px;
  font-family: var(--font-display);
  font-weight: 700;
  letter-spacing: -0.04em;
  line-height: 1.02;
  font-size: clamp(2.4rem, 5.4vw, 4.6rem);
}

.hero-headline span {
  display: block;
}

.hero-headline span:first-child {
  color: var(--lab-bright);
}

.hero-lede {
  margin: 0 0 28px;
  max-width: 40ch;
  color: var(--muted);
  font-size: clamp(1rem, 1.2vw, 1.1rem);
  line-height: 1.6;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 22px;
}

.hero-trust {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 6px 12px;
  border: 1px solid var(--stroke);
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: 0.8rem;
  color: var(--muted);
}

.hero-trust__icon {
  color: var(--lab-bright);
}

.hero-trust__sep {
  opacity: 0.5;
}

.hero-visual {
  position: relative;
  aspect-ratio: 16 / 11;
}

.hero-glow {
  position: absolute;
  inset: -10% -8% -6% -6%;
  background: radial-gradient(ellipse at 55% 45%, var(--glow), transparent 60%);
  filter: blur(20px);
  z-index: 0;
  pointer-events: none;
}

.hero-back {
  position: absolute;
  top: 2%;
  left: 0;
  right: 6%;
  transform: rotate(2.5deg);
  transform-origin: center center;
  z-index: 1;
}

.hero-front {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 55%;
  transform: rotate(-1deg);
  transform-origin: center center;
  z-index: 2;
}

.hero-ticker {
  margin: clamp(32px, 5vw, 56px) 0 0;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 0.78rem;
  letter-spacing: 0.28em;
  color: var(--muted);
  opacity: 0.55;
}

@media (max-width: 860px) {
  .hero {
    padding-top: clamp(36px, 6vw, 72px);
  }
  .hero-grid {
    grid-template-columns: 1fr;
    gap: 32px;
  }
  .hero-visual {
    order: -1;
    aspect-ratio: 16 / 10;
  }
  .hero-back {
    position: static;
    transform: none;
    margin: 0 auto;
    max-width: 520px;
  }
  .hero-front {
    display: none;
  }
  .hero-actions {
    flex-direction: column;
    align-items: stretch;
  }
  .hero-actions .btn {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .hero-back,
  .hero-front {
    transform: none;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/`.

At desktop width:
- Left column: mono `> termlab / latest`, big two-line headline (blue first line, white second), short lede, `Download TermLab` white button next to a `Linux · Windows` ghost link, and a single-line trust pill.
- Right column: the main-layout screenshot in a rounded dark frame with three red/yellow/green dots top-left, tilted slightly right; the Add-SSH-Host dialog image overlapping its lower-right corner, counter-tilted slightly left; a soft blue glow behind both.
- Under the composite, centered faint text: `SSH · SFTP · TUNNELS · VAULT · BUNDLES`.
- No console errors.

At 375px width (mobile):
- Screenshot stacks above copy.
- Only the main-layout image renders — the Add-SSH-Host dialog is hidden.
- Buttons stack vertically full-width.

Run in Console:
```javascript
const back = document.querySelector(".hero-back");
const front = document.querySelector(".hero-front");
console.log(getComputedStyle(back).transform);
console.log(getComputedStyle(front).transform);
```
Expected: two `matrix(...)` strings reflecting non-zero rotation at desktop, `none` when the device toolbar is set narrower than 860px.

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): build hero with layered screenshot composite and trust pill"
```

---

## Task 5: Build the product tour (4 alternating steps)

**Files:**
- Modify: `docs/index.html` (fill the empty `<section class="tour">`)
- Modify: `docs/styles.css` (append a `/* Tour */` block)

- [ ] **Step 1: Define "done" for this task**

- `.tour` contains four `<article class="tour-step">` children in order: Connect, Secure, Transfer, Share.
- Each step is a two-column grid at desktop (55% image / 45% copy), image and copy alternating left/right.
- Steps 1 and 3 have image on the right; steps 2 and 4 have image on the left.
- Each step's copy column has: mono eyebrow `> NN · action`, H2 headline (2–4 words), one sentence ≤18 words. No bullets, no buttons.
- Step 4 also has a mono note under the caption: `.termlab · password-protected · nobody else does this`.
- Images are framed in the same window chrome as the hero, no tilt (flat).
- Vertical rhythm between steps: ~100px on desktop, ~48px on mobile.
- Mobile (≤860px): copy stacks above image in every step; alternation disabled.

- [ ] **Step 2: Update `docs/index.html`**

Replace the empty `<section class="tour">` with:

```html
<section class="tour" aria-label="Product tour">
  <div class="container tour-container">
    <article class="tour-step tour-step--img-right">
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 01 &middot; connect</p>
        <h2 class="tour-headline">Every host, one keystroke away.</h2>
        <p class="tour-caption">
          Command Palette opens a session instantly. Tabs, splits, and saved
          terminals feel native because they are.
        </p>
      </div>
      <figure class="window-chrome tour-image">
        <img
          src="./assets/example_ssh_server_connect.png"
          alt="TermLab with an SSH session open to a remote host, showing neofetch output in the terminal."
          loading="lazy"
        />
      </figure>
    </article>

    <article class="tour-step tour-step--img-left">
      <figure class="window-chrome tour-image">
        <img
          src="./assets/creating_new_credential_in_vault.png"
          alt="Add Credential dialog in TermLab's encrypted vault, with options for password, SSH key, and key plus password."
          loading="lazy"
        />
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

    <article class="tour-step tour-step--img-right">
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 03 &middot; transfer</p>
        <h2 class="tour-headline">Move files without switching tools.</h2>
        <p class="tour-caption">
          Dual-pane SFTP sits inside the same window as your terminal.
          Drag, drop, right-click upload — no separate app.
        </p>
      </div>
      <figure class="window-chrome tour-image">
        <img
          src="./assets/example_sftp_server_upload.png"
          alt="TermLab's dual-pane SFTP view with a right-click context menu uploading a file to a remote server."
          loading="lazy"
        />
      </figure>
    </article>

    <article class="tour-step tour-step--img-left">
      <figure class="window-chrome tour-image">
        <img
          src="./assets/export_connections_example.png"
          alt="Export TermLab Bundle dialog with SSH host and tunnel lists and a bundle password field."
          loading="lazy"
        />
      </figure>
      <div class="tour-copy">
        <p class="eyebrow-mono">&gt; 04 &middot; share</p>
        <h2 class="tour-headline">
          Hand off your whole setup as one encrypted file.
        </h2>
        <p class="tour-caption">
          Export hosts, tunnels, keys, and credentials into a
          password-protected bundle. Your teammate imports it and they're in.
        </p>
        <p class="tour-note">
          .termlab &middot; password-protected &middot; nobody else does this
        </p>
      </div>
    </article>
  </div>
</section>
```

- [ ] **Step 3: Add tour CSS**

Append to `docs/styles.css`:

```css
/* ---------- Tour ---------- */
.tour {
  padding: clamp(56px, 9vw, 120px) 0;
  border-top: 1px solid var(--stroke);
}

.tour-container {
  display: grid;
  gap: clamp(96px, 10vw, 140px);
}

.tour-step {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: clamp(32px, 5vw, 72px);
  align-items: center;
}

.tour-step--img-right > .tour-copy {
  grid-column: 1;
}
.tour-step--img-right > .tour-image {
  grid-column: 2;
}

.tour-step--img-left > .tour-image {
  grid-column: 1;
}
.tour-step--img-left > .tour-copy {
  grid-column: 2;
}

.tour-copy {
  max-width: 44ch;
}

.tour-headline {
  margin: 0 0 14px;
  font-family: var(--font-display);
  font-weight: 700;
  letter-spacing: -0.035em;
  line-height: 1.05;
  font-size: clamp(1.7rem, 3vw, 2.4rem);
  color: var(--text);
}

.tour-caption {
  margin: 0;
  color: var(--muted);
  font-size: 1rem;
  line-height: 1.65;
}

.tour-note {
  margin: 14px 0 0;
  font-family: var(--font-mono);
  font-size: 0.78rem;
  letter-spacing: 0.02em;
  color: var(--lab-bright);
  opacity: 0.85;
}

.tour-image {
  box-shadow: var(--shadow-lg);
}

@media (max-width: 860px) {
  .tour-container {
    gap: 48px;
  }
  .tour-step,
  .tour-step--img-left,
  .tour-step--img-right {
    grid-template-columns: 1fr;
    gap: 28px;
  }
  .tour-step--img-left > .tour-image,
  .tour-step--img-left > .tour-copy,
  .tour-step--img-right > .tour-image,
  .tour-step--img-right > .tour-copy {
    grid-column: 1;
  }
  /* Copy above image on mobile regardless of desktop alternation */
  .tour-step--img-left > .tour-copy {
    order: -1;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/`.

- Scroll past the hero. Four tour rows visible in order.
- Row 1 (Connect): image right, headline "Every host, one keystroke away.", neofetch screenshot.
- Row 2 (Secure): image left, headline "One vault for everything.", Add Credential dialog.
- Row 3 (Transfer): image right, headline "Move files without switching tools.", dual-pane SFTP with context menu.
- Row 4 (Share): image left, headline "Hand off your whole setup as one encrypted file.", Export Bundle dialog, mono note underneath starting with `.termlab`.
- Generous vertical space between each row.
- All four screenshots have the macOS traffic-light dots in their chrome frame.

At 375px width:
- All four steps are single-column.
- In every step, the copy (eyebrow + headline + caption) appears ABOVE the image.

Run in Console:
```javascript
const steps = [...document.querySelectorAll(".tour-step")];
console.log(steps.length, steps.map(s => s.querySelector(".tour-headline").textContent.trim().slice(0, 16)));
```
Expected: `4 ["Every host, one", "One vault for e", "Move files with", "Hand off your w"]`

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): add 4-step screenshot tour (connect, secure, transfer, share)"
```

---

## Task 6: Build the "Inside TermLab" strip

**Files:**
- Modify: `docs/index.html` (fill the empty `<section class="inside">`)
- Modify: `docs/styles.css` (append an `/* Inside */` block)

- [ ] **Step 1: Define "done" for this task**

- Section has the mono eyebrow `> inside`, a one-line framing headline (smaller than tour headlines), and a horizontal row of three small shots with single mono captions underneath.
- Three shots in order: Add a host (`adding_new_ssh_host_with_vault_credential.png`), Unlock the vault (`empty_vault.png`), Settings you recognize (`full_featured_settings_pane.png`).
- No per-shot headline, no card shadow/border clutter — just image in window chrome + single mono word-pair caption.
- Desktop: 3 equal-width columns.
- Mobile: all three stack.

- [ ] **Step 2: Update `docs/index.html`**

Replace the empty `<section class="inside">` with:

```html
<section class="inside" aria-label="Inside TermLab">
  <div class="container inside-container">
    <header class="inside-header">
      <p class="eyebrow-mono">&gt; inside</p>
      <h2 class="inside-headline">
        Built to feel like part of the OS, not a tab in your browser.
      </h2>
    </header>

    <div class="inside-grid">
      <figure class="inside-card">
        <div class="window-chrome">
          <img
            src="./assets/adding_new_ssh_host_with_vault_credential.png"
            alt="Add SSH Host dialog with vault credential selected for authentication."
            loading="lazy"
          />
        </div>
        <figcaption>Add a host</figcaption>
      </figure>
      <figure class="inside-card">
        <div class="window-chrome">
          <img
            src="./assets/empty_vault.png"
            alt="Empty Credential Vault window with Accounts and Keys tabs."
            loading="lazy"
          />
        </div>
        <figcaption>Unlock the vault</figcaption>
      </figure>
      <figure class="inside-card">
        <div class="window-chrome">
          <img
            src="./assets/full_featured_settings_pane.png"
            alt="TermLab Settings pane showing Appearance, Keymap, Editor, Credential Vault, and TermLab Terminal sections."
            loading="lazy"
          />
        </div>
        <figcaption>Settings you recognize</figcaption>
      </figure>
    </div>
  </div>
</section>
```

- [ ] **Step 3: Add inside CSS**

Append to `docs/styles.css`:

```css
/* ---------- Inside TermLab ---------- */
.inside {
  padding: clamp(56px, 9vw, 120px) 0;
  border-top: 1px solid var(--stroke);
}

.inside-header {
  max-width: 52ch;
  margin-bottom: clamp(28px, 4vw, 48px);
}

.inside-headline {
  margin: 0;
  font-family: var(--font-display);
  font-weight: 500;
  letter-spacing: -0.025em;
  font-size: clamp(1.3rem, 2.2vw, 1.8rem);
  line-height: 1.25;
  color: var(--text);
}

.inside-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: clamp(20px, 3vw, 36px);
}

.inside-card {
  margin: 0;
  display: grid;
  gap: 12px;
}

.inside-card .window-chrome {
  box-shadow: var(--shadow-md);
}

.inside-card figcaption {
  font-family: var(--font-mono);
  font-size: 0.82rem;
  color: var(--muted);
  letter-spacing: 0.02em;
  text-align: left;
}

@media (max-width: 860px) {
  .inside-grid {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/`.

- Scroll past the tour. "Inside" section appears.
- Top-left of the section: mono `> inside`, underneath a smaller (not H1-sized) headline about "Built to feel like part of the OS".
- Below that, three equal-width screenshots in a row, each with macOS chrome and a single mono caption below: "Add a host", "Unlock the vault", "Settings you recognize".
- No headlines on the cards, no extra tile framing.

At 375px width: three cards stack vertically.

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): add inside-termlab strip with three detail shots"
```

---

## Task 7: Build the download section

**Files:**
- Modify: `docs/index.html` (fill the empty `<section class="download">`)
- Modify: `docs/styles.css` (append a `/* Download */` block)

This task ships the download section with working fallback links. The live asset-picking from the GitHub API is wired up later in Task 9.

- [ ] **Step 1: Define "done" for this task**

- Section has a mono eyebrow `> download`, a large centered headline "Get TermLab.", a one-line subtext, a row of three platform buttons (macOS / Linux / Windows), and a single mono info line below.
- All three platform buttons initially link to `https://github.com/termlab-io/termlab/releases/latest` and open in a new tab.
- Each button has a `data-os` attribute (`mac`, `linux`, `windows`) that Task 9's JS uses to pick the specific asset URL at runtime.
- The info line below shows `v latest · ★ GitHub · checksums`, with `checksums` linking to the same releases page by default.
- The section is centered on desktop, with the buttons laid out in a row.
- On mobile the buttons stack full-width vertically.

- [ ] **Step 2: Update `docs/index.html`**

Replace the empty `<section id="download" class="download">` with:

```html
<section id="download" class="download" aria-labelledby="download-headline">
  <div class="container download-container">
    <p class="eyebrow-mono">&gt; download</p>
    <h2 id="download-headline" class="download-headline">Get TermLab.</h2>
    <p class="download-sub">Free, open source, built on the IntelliJ platform.</p>

    <div class="download-row" role="group" aria-label="Choose your platform">
      <a
        class="btn btn-secondary"
        data-os="mac"
        data-default-href="https://github.com/termlab-io/termlab/releases/latest"
        href="https://github.com/termlab-io/termlab/releases/latest"
        target="_blank"
        rel="noreferrer noopener"
      >macOS (.dmg)</a>
      <a
        class="btn btn-secondary"
        data-os="linux"
        data-default-href="https://github.com/termlab-io/termlab/releases/latest"
        href="https://github.com/termlab-io/termlab/releases/latest"
        target="_blank"
        rel="noreferrer noopener"
      >Linux (.tar.gz)</a>
      <a
        class="btn btn-secondary"
        data-os="windows"
        data-default-href="https://github.com/termlab-io/termlab/releases/latest"
        href="https://github.com/termlab-io/termlab/releases/latest"
        target="_blank"
        rel="noreferrer noopener"
      >Windows (.exe)</a>
    </div>

    <p class="download-info">
      <span>v</span><span data-gh-version>latest</span>
      <span class="download-info__sep" aria-hidden="true">·</span>
      <span class="download-info__icon" aria-hidden="true">★</span>
      <span data-gh-stars>GitHub</span>
      <span class="download-info__sep" aria-hidden="true">·</span>
      <span class="download-info__date" data-gh-date hidden></span>
      <span class="download-info__sep" aria-hidden="true">·</span>
      <a
        class="download-info__link"
        data-gh-checksums
        href="https://github.com/termlab-io/termlab/releases/latest"
        target="_blank"
        rel="noreferrer noopener"
      >checksums</a>
    </p>
  </div>
</section>
```

- [ ] **Step 3: Add download CSS**

Append to `docs/styles.css`:

```css
/* ---------- Download ---------- */
.download {
  padding: clamp(72px, 10vw, 140px) 0 clamp(56px, 8vw, 100px);
  border-top: 1px solid var(--stroke);
  text-align: center;
}

.download-container {
  max-width: 760px;
}

.download-headline {
  margin: 0 0 12px;
  font-family: var(--font-display);
  font-weight: 700;
  letter-spacing: -0.04em;
  line-height: 1;
  font-size: clamp(2.4rem, 5vw, 4rem);
}

.download-sub {
  margin: 0 0 32px;
  color: var(--muted);
  font-size: 1.05rem;
}

.download-row {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 24px;
}

.download-row .btn {
  min-width: 180px;
}

.download-info {
  margin: 0 auto;
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  justify-content: center;
  gap: 6px;
  font-family: var(--font-mono);
  font-size: 0.82rem;
  color: var(--muted);
}

.download-info__sep {
  opacity: 0.5;
}

.download-info__icon {
  color: var(--lab-bright);
}

.download-info__link {
  color: var(--lab-bright);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-color: rgba(125, 145, 182, 0.4);
}

.download-info__link:hover,
.download-info__link:focus-visible {
  color: var(--text);
  text-decoration-color: var(--text);
}

/* When JS hides the date span, also hide the sep that follows it
   so we don't render a double-dot ("·  ·") in the fallback state. */
.download-info__date[hidden] + .download-info__sep {
  display: none;
}

@media (max-width: 640px) {
  .download-row {
    flex-direction: column;
    align-items: stretch;
  }
  .download-row .btn {
    width: 100%;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/` and scroll to the download section (or click `Download` in the topbar).

- Centered layout: `> download` eyebrow, big "Get TermLab." headline, subtext, three platform buttons in a row (macOS · Linux · Windows), mono info line reading roughly `v latest · ★ GitHub · checksums`.
- Clicking any of the three platform buttons opens `https://github.com/termlab-io/termlab/releases/latest` in a new tab.
- `checksums` link goes to the same URL in a new tab.

At 640px and below:
- Three buttons stack full-width vertically.
- Mono info line wraps cleanly, still centered.

Run in Console:
```javascript
const btns = [...document.querySelectorAll(".download-row .btn")];
console.log(btns.map(b => [b.dataset.os, b.href]));
```
Expected: `[["mac", "…/releases/latest"], ["linux", "…/releases/latest"], ["windows", "…/releases/latest"]]` (all three href values are the fallback URL).

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): build download section with platform buttons (static fallback)"
```

---

## Task 8: Build the footer

**Files:**
- Modify: `docs/index.html` (fill the empty `<footer class="site-footer">`)
- Modify: `docs/styles.css` (append a `/* Footer */` block)

- [ ] **Step 1: Define "done" for this task**

- Footer is a single slim row with three groups: left = small wordmark, center = text links (GitHub · Docs · Releases · License), right = `built with ♥ on the intellij platform` (mono).
- All links open the termlab-io repo or its subpaths in a new tab, except License which points to `https://github.com/termlab-io/termlab/blob/main/LICENSE` (the repo's license file — if the file doesn't exist the link will 404, which is acceptable for now; the spec lists License as a footer link).
- On mobile the three groups stack vertically, centered.
- Footer sits below the download section with a hairline top border.

- [ ] **Step 2: Update `docs/index.html`**

Replace the empty `<footer class="site-footer">` with:

```html
<footer class="site-footer">
  <div class="container site-footer__row">
    <a class="brand brand--sm" href="#top" aria-label="TermLab home">
      <img src="./assets/logo.png" alt="" />
      <span><em>Term</em><strong>Lab</strong></span>
    </a>

    <nav class="site-footer__links" aria-label="Footer">
      <a href="https://github.com/termlab-io/termlab" target="_blank" rel="noreferrer noopener">GitHub</a>
      <a href="https://github.com/termlab-io/termlab/tree/main/docs" target="_blank" rel="noreferrer noopener">Docs</a>
      <a href="https://github.com/termlab-io/termlab/releases" target="_blank" rel="noreferrer noopener">Releases</a>
      <a href="https://github.com/termlab-io/termlab/blob/main/LICENSE" target="_blank" rel="noreferrer noopener">License</a>
    </nav>

    <p class="site-footer__note">
      built with <span aria-hidden="true">♥</span><span class="sr-only">love</span> on the intellij platform
    </p>
  </div>
</footer>
```

- [ ] **Step 3: Add footer CSS**

Append to `docs/styles.css`:

```css
/* ---------- Footer ---------- */
.site-footer {
  padding: 28px 0 48px;
  border-top: 1px solid var(--stroke);
  color: var(--muted);
  font-size: 0.88rem;
}

.site-footer__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  flex-wrap: wrap;
}

.brand--sm img {
  width: 22px;
  height: 22px;
  border-radius: 6px;
}

.brand--sm span {
  font-size: 1rem;
}

.site-footer__links {
  display: flex;
  gap: 18px;
}

.site-footer__links a {
  color: var(--muted);
  transition: color 160ms ease;
}

.site-footer__links a:hover,
.site-footer__links a:focus-visible {
  color: var(--text);
}

.site-footer__note {
  margin: 0;
  font-family: var(--font-mono);
  font-size: 0.78rem;
  color: var(--muted);
  letter-spacing: 0.02em;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 720px) {
  .site-footer__row {
    justify-content: center;
    text-align: center;
  }
  .site-footer__links {
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .site-footer__links a {
    transition: none;
  }
}
```

- [ ] **Step 4: Verify in the browser**

Reload `http://localhost:8000/`.

- Footer visible at the bottom with: wordmark left, four links center, mono credit line right.
- Clicking `GitHub`, `Docs`, `Releases`, `License` each open the expected termlab-io URL in a new tab.

At 375px: three groups stack vertically and center-align.

- [ ] **Step 5: Commit**

```bash
git add docs/index.html docs/styles.css
git commit -m "docs(landing): add slim footer"
```

---

## Task 9: Wire up live GitHub data + platform detection + reduced-motion-aware fade-in

**Files:**
- Modify (full rewrite): `docs/script.js`

This is the only task that writes real JavaScript. It has three responsibilities:

1. **Platform detection** — pick the visitor's OS and update the hero CTA label + the download button highlighting.
2. **GitHub data** — one cached fetch per endpoint (stars + latest release) with results written into every element bearing a matching `data-gh-*` attribute. Download buttons get their `href` swapped to the matching release asset when available.
3. **Fade-in on scroll** — tour steps and inside-cards fade/rise in as they enter the viewport, disabled under `prefers-reduced-motion`.

All three degrade gracefully: the page is fully usable with JS disabled (fallbacks stay visible, buttons use the default release-page URL, content is not hidden on load).

- [ ] **Step 1: Define "done" for this task**

- Opening the page on macOS shows the hero primary button labelled `Download for macOS` and the macOS platform button in the download section rendered in the primary (white) style, while Linux and Windows stay secondary-style.
- Stars count in the hero trust pill, topbar star pill, and download info line all update from the API response, showing the actual integer.
- Version (`v<tag>`) and release date render in the download info line; date span's `hidden` attribute is removed when populated.
- Each platform button in the download section has its `href` swapped to the matching asset URL from the release's `assets[]` if a recognizable match exists; otherwise it keeps the default releases-latest URL.
- With the Network tab set to Offline, the page still loads cleanly, buttons still work (pointing at `releases/latest`), and the fallback texts (`latest`, `GitHub`) stay visible.
- `prefers-reduced-motion: reduce` disables the fade-in; elements are visible immediately at full opacity.
- No console errors in any of the above scenarios.

- [ ] **Step 2: Add reveal-on-scroll base CSS**

Before writing the JS, append to `docs/styles.css` so the fade-in has styles to toggle:

```css
/* ---------- Reveal-on-scroll ---------- */
.reveal {
  opacity: 0;
  transform: translateY(16px);
  transition: opacity 600ms ease, transform 600ms ease;
  will-change: opacity, transform;
}

.reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}

@media (prefers-reduced-motion: reduce) {
  .reveal,
  .reveal.is-visible {
    opacity: 1;
    transform: none;
    transition: none;
  }
}
```

- [ ] **Step 3: Add `reveal` class to elements we want to animate in**

Edit `docs/index.html`:

- Add `reveal` (alongside existing classes) to each `<article class="tour-step">` — all 4 of them.
- Add `reveal` to each `<figure class="inside-card">` — all 3 of them.

Example: `<article class="tour-step tour-step--img-right reveal">`.

No other elements get `reveal`.

- [ ] **Step 4: Replace `docs/script.js`**

Write this content to `docs/script.js`:

```javascript
// docs/script.js — landing page behaviour.
// Progressive enhancement only: page fully works if this file fails to load.
(() => {
  "use strict";

  const GH_REPO_API = "https://api.github.com/repos/termlab-io/termlab";
  const GH_RELEASES_LATEST_API = GH_REPO_API + "/releases/latest";
  const RELEASES_LATEST_PAGE = "https://github.com/termlab-io/termlab/releases/latest";

  // ---------- Platform detection ----------
  const PLATFORM_LABELS = {
    mac: "Download for macOS",
    linux: "Download for Linux",
    windows: "Download for Windows",
  };

  function detectPlatform() {
    const ua =
      (navigator.userAgentData && navigator.userAgentData.platform) ||
      navigator.platform ||
      navigator.userAgent ||
      "";
    const s = ua.toLowerCase();
    if (s.includes("mac") || s.includes("darwin")) return "mac";
    if (s.includes("win")) return "windows";
    if (s.includes("linux") || s.includes("x11")) return "linux";
    return null;
  }

  function applyPlatform(platform) {
    if (!platform) return;

    const primaryBtn = document.querySelector("[data-download-primary]");
    const primaryLabel = document.querySelector("[data-download-label]");
    if (primaryBtn && primaryLabel && PLATFORM_LABELS[platform]) {
      primaryLabel.textContent = PLATFORM_LABELS[platform];
    }

    // Promote the matching download-row button to primary style.
    const downloadButtons = document.querySelectorAll(".download-row .btn[data-os]");
    downloadButtons.forEach((btn) => {
      if (btn.dataset.os === platform) {
        btn.classList.remove("btn-secondary");
        btn.classList.add("btn-primary");
      }
    });
  }

  // ---------- GitHub data ----------
  let repoPromise = null;
  let releasePromise = null;

  function fetchRepo() {
    if (!repoPromise) {
      repoPromise = fetch(GH_REPO_API, { headers: { Accept: "application/vnd.github+json" } })
        .then((res) => (res.ok ? res.json() : Promise.reject(new Error("repo " + res.status))))
        .catch(() => null);
    }
    return repoPromise;
  }

  function fetchRelease() {
    if (!releasePromise) {
      releasePromise = fetch(GH_RELEASES_LATEST_API, { headers: { Accept: "application/vnd.github+json" } })
        .then((res) => (res.ok ? res.json() : Promise.reject(new Error("release " + res.status))))
        .catch(() => null);
    }
    return releasePromise;
  }

  function setAll(selector, value) {
    document.querySelectorAll(selector).forEach((el) => {
      el.textContent = value;
    });
  }

  function formatStars(n) {
    if (typeof n !== "number") return null;
    if (n >= 1000) return (n / 1000).toFixed(n >= 10000 ? 0 : 1) + "k";
    return String(n);
  }

  function formatDate(iso) {
    if (!iso) return null;
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return null;
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" });
  }

  function pickAssetUrl(assets, os) {
    if (!Array.isArray(assets)) return null;
    const matchers = {
      mac: [/\.dmg$/i, /mac/i, /darwin/i],
      linux: [/\.tar\.gz$/i, /\.AppImage$/i, /linux/i],
      windows: [/\.exe$/i, /\.msi$/i, /win/i],
    }[os] || [];
    for (const re of matchers) {
      const match = assets.find((a) => re.test(a.name || ""));
      if (match && match.browser_download_url) return match.browser_download_url;
    }
    return null;
  }

  function applyRepo(repo) {
    if (!repo) return;
    const stars = formatStars(repo.stargazers_count);
    if (stars) {
      setAll("[data-gh-stars]", stars);
    }
  }

  function applyRelease(release) {
    if (!release) return;

    if (release.tag_name) {
      setAll("[data-gh-version]", release.tag_name);
    }

    const dateText = formatDate(release.published_at);
    if (dateText) {
      document.querySelectorAll("[data-gh-date]").forEach((el) => {
        el.textContent = "released " + dateText;
        el.hidden = false;
      });
    }

    if (release.html_url) {
      document.querySelectorAll("[data-gh-checksums]").forEach((el) => {
        el.href = release.html_url;
      });
    }

    // Swap per-OS download URLs when a matching asset exists.
    document.querySelectorAll(".download-row .btn[data-os]").forEach((btn) => {
      const url = pickAssetUrl(release.assets, btn.dataset.os);
      if (url) btn.href = url;
    });

    // Also point the hero primary button at the matching platform asset.
    const platform = detectPlatform();
    const primaryBtn = document.querySelector("[data-download-primary]");
    if (platform && primaryBtn) {
      const url = pickAssetUrl(release.assets, platform);
      if (url) primaryBtn.href = url;
    }
  }

  // ---------- Reveal on scroll ----------
  function setupReveal() {
    const els = document.querySelectorAll(".reveal");
    if (!els.length) return;

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduced || !("IntersectionObserver" in window)) {
      els.forEach((el) => el.classList.add("is-visible"));
      return;
    }

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            io.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.12, rootMargin: "0px 0px -10% 0px" }
    );

    els.forEach((el) => io.observe(el));
  }

  // ---------- Boot ----------
  function boot() {
    applyPlatform(detectPlatform());
    fetchRepo().then(applyRepo);
    fetchRelease().then(applyRelease);
    setupReveal();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot, { once: true });
  } else {
    boot();
  }
})();
```

- [ ] **Step 5: Verify in the browser — happy path**

Reload `http://localhost:8000/` with the Network tab **Online**.

- Hero primary button label updated to match your OS (e.g., `Download for macOS`).
- Hero trust pill shows an actual star count number and a real version tag.
- Topbar stars pill shows the same star count next to `★`.
- Download section's macOS button rendered in the white primary style; the other two are darker secondary.
- Download info line shows `v<tag> · ★ <count> · released <date> · checksums`.
- Clicking a platform download button opens a URL in a new tab. If the release has a matching asset, the URL is the asset's direct link; otherwise it's the releases page.
- Tour steps and inside-cards fade/rise into view as you scroll.

Run in Console:
```javascript
document.querySelectorAll("[data-gh-stars]").forEach(e => console.log(e.closest(".star-pill, .hero-trust, .download-info")?.className || "(?)", "=>", e.textContent));
```
Expected: 3 lines, one each for `star-pill`, `hero-trust`, and `download-info`, all showing a numeric string like `42` (not the literal `GitHub`).

- [ ] **Step 6: Verify in the browser — offline fallback**

In DevTools Network tab, set throttling to **Offline**. Hard-reload (Cmd+Shift+R).

- Page still renders with all sections visible.
- Hero trust pill shows `★ GitHub · latest · macOS · Linux · Windows`.
- Topbar stars pill shows `★ GitHub` (text restored or remained as fallback).
- Download info line shows `v latest · ★ GitHub · checksums` (the released-date span stays `hidden`, its separator hidden too).
- All three platform download buttons link to `https://github.com/termlab-io/termlab/releases/latest`.
- Console shows the fetch errors (expected) but no script-execution errors.

Re-enable network when done.

- [ ] **Step 7: Verify reduced-motion**

In DevTools → Rendering tab → `Emulate CSS media feature prefers-reduced-motion: reduce`.
Hard-reload the page.

- Tour steps and inside-cards are immediately visible at full opacity — no fade-in-on-scroll.
- No transform on hero images.
- CTA buttons don't lift on hover.

- [ ] **Step 8: Commit**

```bash
git add docs/index.html docs/styles.css docs/script.js
git commit -m "docs(landing): wire live GitHub data, platform detection, reveal-on-scroll

- One cached fetch each for repo and latest release, results shared across
  hero trust pill, topbar pill, and download info line.
- Platform detection swaps the hero CTA label and promotes the matching
  download-row button to primary style.
- Release assets[] matched per-OS; buttons fall back to the releases page
  when no match is found or the fetch fails.
- IntersectionObserver-driven fade-in on tour steps and inside cards,
  fully disabled under prefers-reduced-motion."
```

---

## Task 10: Cross-check against the spec and final polish

**Files:** potentially any of `docs/index.html`, `docs/styles.css`, `docs/script.js`

This is the integration pass. Read the spec and walk the page side-by-side.

- [ ] **Step 1: Define "done" for this task**

Every spec requirement maps to something visible on the page, OR is explicitly out of scope. Any discrepancy is either fixed inline or noted in the commit message.

- [ ] **Step 2: Spec walkthrough**

Open `docs/superpowers/specs/2026-04-18-landing-page-redesign-design.md` and the live page at `http://localhost:8000/` side-by-side. Walk through each section of the spec and confirm:

- **Goal:** word count of body copy is under ~250. Count roughly by selecting all the visible prose from the hero through the footer. If it's way over, flag in the commit but don't rewrite copy — the spec-listed copy was agreed.
- **Visual language:** palette untouched from old page tokens (modulo renaming), grid overlay present, hero images have chrome + tilt, tour images have chrome + no tilt, mono eyebrows use the `>` prompt mark.
- **Page structure:** topbar → hero → tour (4) → inside (3) → download → footer.
- **Topbar:** wordmark + star pill + Docs + Download button.
- **Hero:** mono eyebrow, two-line headline (first line in `--lab-bright`), lede, primary + ghost buttons, trust pill, layered composite with two tilted images + glow, ticker under composite.
- **Tour:** 4 steps, alternation matches (01 right, 02 left, 03 right, 04 left), headlines and captions match the spec verbatim, Share step has the `.termlab · password-protected · nobody else does this` note.
- **Inside:** `> inside` eyebrow + "Built to feel like part of the OS" headline + 3 cards (Add a host, Unlock the vault, Settings you recognize).
- **Download:** centered, eyebrow + headline + subtext + 3 platform buttons + mono info line.
- **Footer:** wordmark + 4 links + "built with ♥ on the intellij platform".
- **Removed sections:** search the HTML for any of `command-rotator`, `class="metrics"`, `class="feature-grid"`, `class="timeline"`, `class="screen-grid"`, `class="docs-grid"`. All should return zero matches.
- **Live GitHub data:** all three endpoints/targets work online, fallbacks work offline.
- **Accessibility:** single `<h1>` on the page, `<h2>` per tour step and section, `alt` text on every screenshot is descriptive, reduced-motion disables motion.

Run the removed-section check from the terminal:
```bash
grep -nE 'class="(metrics|feature-grid|timeline|screen-grid|docs-grid)"|command-rotator|\.prompt|\.command\b' docs/index.html docs/styles.css docs/script.js || echo "clean"
```
Expected: `clean`.

Count H1s:
```bash
grep -c '<h1' docs/index.html
```
Expected: `1`.

- [ ] **Step 3: Cross-browser sanity**

Open the page in at least one second browser (Safari if you built in Chrome, or vice versa). Confirm:
- Fonts load (Manrope + Space Grotesk + JetBrains Mono).
- `backdrop-filter` on the topbar renders (or degrades to solid — acceptable fallback).
- `aspect-ratio` on the hero visual doesn't collapse the layout.
- No layout breaks at 375px, 768px, 1024px, 1440px.

- [ ] **Step 4: Fix anything that's off**

If any check failed in Step 2 or 3, fix it inline in the appropriate file. Keep changes minimal — if the spec says something the implementation missed, match the spec; if the spec and the implementation disagree on something a user wouldn't notice, leave it and note it in the commit.

- [ ] **Step 5: Commit (only if Step 4 produced changes)**

If there are no changes, skip this step — do not create an empty commit. Otherwise:

```bash
git add docs/index.html docs/styles.css docs/script.js
git commit -m "docs(landing): fix <short description of what was off>"
```

- [ ] **Step 6: Final status check**

Run:
```bash
git log --oneline -15 -- docs/
```

Expected: 9–10 recent commits touching `docs/`, matching the order Tasks 1–9 were executed (plus Task 10's commit if Step 4 produced changes). The commit subjects should start with `docs(landing):`.

Run:
```bash
git status --short docs/
```

Expected: no pending changes under `docs/` — everything is committed.

---

## Appendix: things this plan deliberately does NOT do

- No test framework. The spec forbids a build step; adding Jest/Vitest for a ~100-line JS file is over-engineering.
- No service worker / offline caching beyond the natural browser cache.
- No analytics, no cookie banner, no newsletter widget.
- No image compression / WebP conversion. If the PNGs end up heavy, that's a follow-up optimization task — the screenshots ship as-is.
- No fix for the "Activate Windows" watermark in several screenshots. That's a separate screenshot-capture task outside this plan.
- No update to any of the product repo's non-`docs/` files. If you find something in the app repo that should be touched, open a separate plan.
