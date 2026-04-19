# Landing Page Redesign — Design Spec

Date: 2026-04-18
Scope: `docs/` (GitHub Pages static site — `index.html`, `styles.css`, `script.js`, `assets/`)

## Goal

Replace the current landing page with a screenshot-driven product tour that feels like a professional product page for both pros and amateurs. The current page is crowded, copy-heavy, and uses placeholder screens. The redesign trades words and tile-grids for real product imagery and whitespace, while keeping the existing brand palette.

Success looks like:
- A visitor can understand what TermLab is in under five seconds (hero shot does the talking).
- The unique bundle-sharing feature gets headline treatment, not a footnote.
- Total body copy is under ~250 words (current page: ~550).
- The page is still a zero-build static site that GitHub Pages serves directly.

## Audience & tone

- **Primary audience:** homelab enthusiasts and infrastructure professionals.
- **Tone:** terminal-first and technical, but polished. Hackerish restraint — mono accents, `>` prompt marks on section eyebrows — without feeling like a terminal-theme Halloween costume.
- **What to avoid:** generic SaaS marketing vocabulary ("seamless," "empower," "unleash"), copy-heavy tile grids, placeholder imagery.

## Visual language

- **Palette:** keep the existing dark navy palette (`--bg`, `--lab`, `--lab-bright`, `--term`). No change.
- **Type:** keep Manrope (body) + Space Grotesk (display). Increase weight/size contrast — larger displays, shorter lines, fewer body lines.
- **Accent:** mono-spaced eyebrow labels with a `>` prompt mark (`> connect`, `> 01 · connect`, `> inside`, `> download`). This is the only "terminal" flourish.
- **Background:** keep the current grid/scanline overlay (`body::before`), toned down slightly.
- **Screenshot treatment:**
  - Framed in realistic window chrome — rounded corners + macOS traffic-light dots top-left.
  - Soft, wide drop shadow (≈60px blur).
  - Hero images are tilted 2–3° for depth; tour images sit flat for readability.
- **Motion:** subtle fade-in on scroll and a hover lift on primary CTAs. Nothing else. All motion disabled under `prefers-reduced-motion`.

## Page structure (top to bottom)

1. Sticky topbar (slim)
2. Hero — layered composite + tight copy + download CTA + live GitHub trust pill
3. Tour — four alternating full-width steps: Connect · Secure · Transfer · Share
4. "Inside TermLab" strip — three small detail shots
5. Download — three-platform picker with live release data
6. Footer — single slim row

## Topbar

- Wordmark on the left (existing `Term` + `Lab` split styling).
- Right side: `Download` (primary pill), `★ <stars>` live count pill linking to GitHub, `Docs` link.
- Sticky, slightly translucent background so it reads over the hero.

The five-link nav (Download / Capabilities / Workflow / Screens / Docs) from the current page is cut — the new page is short enough to scroll in one pass, so in-page anchors aren't needed.

## Hero

**Layout:** asymmetric split — copy left (≈45%), visual right (≈55%). Stacks vertically on mobile with visual on top.

**Copy column (top → bottom):**
- Mono eyebrow: `> termlab / v<release>`.
- Headline, two lines, clamp(~3rem → 5rem):
  > **One environment**
  > **for every box you run.**
- One-line lede (≤20 words):
  > SSH, SFTP, tunnels, and an encrypted vault — in one desktop app. Share your whole setup as one file.
- CTA row:
  - Primary button: **Download for macOS** (auto-detects platform from `navigator.platform` / `navigator.userAgentData.platform`; label and glyph swap for Linux/Windows; falls back to "Download TermLab" if undetectable).
  - Ghost link right of primary: "Linux · Windows" — jumps to the download section.
- Trust pill below CTA, single mono line:
  > `★ <stars> on GitHub  ·  v<release>  ·  macOS · Linux · Windows`

**Visual column — layered composite:**
- Back layer: `main_layout.png`, window-chrome framed, 2–3° right tilt, 60px-blur shadow.
- Front layer: `adding_new_ssh_host_with_vault_credential.png`, ≈55% of back-layer width, overlapping the lower-right corner by ≈20%, counter-tilted −1°, with its own shadow so it clearly sits above.
- Subtle `--lab-bright` radial glow behind both images.
- Faint mono ticker under the composite: `SSH · SFTP · TUNNELS · VAULT · BUNDLES`.

**Mobile:** visual moves above copy; only the back layer (`main_layout.png`) shows — the front dialog is hidden to avoid clutter. CTA button goes full-width; secondary links wrap below.

**Retired from the current hero:**
- Wordmark-as-centerpiece splash (wordmark only lives in the topbar now).
- `command-rotator` JS and the orphan `.prompt` / `.command` CSS.
- The structural bug where `.hero-visual` is nested inside `.hero-copy`.

## The tour

Four full-width alternating rows.

**Shared pattern for every step:**
- Two-column row at desktop (55% image / 45% copy). Image/copy alternate across steps.
- Image frame: window chrome, soft shadow, no tilt (flat for readability).
- Copy column, top-to-bottom:
  - Mono eyebrow: `> <number> · <action>` (e.g. `> 01 · connect`).
  - Display headline, 2–4 words.
  - Single caption sentence, ≤18 words.
  - No buttons, no bullets — the screenshot carries the detail.
- 96–140px vertical breathing room between steps.
- Mobile: copy stacks above image, no alternation.

**Step 01 · Connect** (image right, copy left)
- Screenshot: `example_ssh_server_connect.png`.
- Headline: **Every host, one keystroke away.**
- Caption: *Command Palette opens a session instantly. Tabs, splits, and saved terminals feel native because they are.*

**Step 02 · Secure** (image left, copy right)
- Screenshot: `creating_new_credential_in_vault.png`.
- Headline: **One vault for everything.**
- Caption: *Passwords, SSH keys, and passphrases in an encrypted vault. Generate keys inside it, reuse them across hosts.*

**Step 03 · Transfer** (image right, copy left)
- Screenshot: `example_sftp_server_upload.png`.
- Headline: **Move files without switching tools.**
- Caption: *Dual-pane SFTP sits inside the same window as your terminal. Drag, drop, right-click upload — no separate app.*

**Step 04 · Share** (image left, copy right — the closer)
- Screenshot: `export_connections_example.png`.
- Headline: **Hand off your whole setup as one encrypted file.**
- Caption: *Export hosts, tunnels, keys, and credentials into a password-protected bundle. Your teammate imports it and they're in.*
- Mono note under the caption: `.termlab · password-protected · nobody else does this`
  - Avoids claiming a specific cipher the landing page can't verify. "password-protected" matches what the user sees in the export dialog.

**Ordering rationale:** Connect (the expected thing) → Secure (how it's safe) → Transfer (does more than you'd guess) → Share (the unique kicker). Share is the emotional peak and flows into the CTA.

## "Inside TermLab" strip

A flavor breather between the tour and the download.

- Mono eyebrow: `> inside`.
- One-line framing: **Built to feel like part of the OS, not a tab in your browser.**
- Row of three small shots, equal width, minimal card treatment — image + single mono caption underneath:
  1. `adding_new_ssh_host_with_vault_credential.png` — *Add a host*
  2. `empty_vault.png` — *Unlock the vault*
  3. `full_featured_settings_pane.png` — *Settings you recognize*
- Mobile: stack all three vertically.
- Deliberately understated: no headlines per shot, no shadow-heavy tile, just image + word.

## Download section

- Centered.
- Eyebrow: `> download`.
- Headline: **Get TermLab.**
- Subtext: *Free, open source, built on the IntelliJ platform.*
- Platform row — three buttons side-by-side, equal visual weight:
  - `macOS (.dmg)`
  - `Linux (.tar.gz)`
  - `Windows (.exe / .msi)`
  - The auto-detected OS renders as the primary-style button; the other two render secondary-style.
- Mono line under the buttons:
  > `v<release>  ·  ★ <stars>  ·  released <date>  ·  checksums`
  - Values come from the same GitHub Releases API fetch used by the hero pill (cache the result in a module-scope variable — fetch once, render twice).
  - `checksums` links to the release's assets page on GitHub.
- No newsletter capture, no Discord CTA, no contact form.

## Footer

Single slim row, muted colors:
- Left: small wordmark.
- Center: `GitHub · Docs · Releases · License`.
- Right: `built with ♥ on the intellij platform` (mono).

## Removed from today's page

- Metrics strip (Built For / Includes / Direction).
- Workflow timeline (01–04 process steps).
- Standalone Screens section with placeholder cards.
- Docs cards section (three-tile grid).
- Multi-link nav in the topbar (replaced by Download + stars pill + Docs).
- Dead `command-rotator` script and its orphan `.prompt` / `.command` CSS.
- The nested `.hero-visual` inside `.hero-copy` structural bug.

## Live GitHub data

Single fetch on page load against the GitHub REST API. The current `docs/index.html` references two different repos — `termlab-io/termlab` for releases and `an0nn30/conch_workbench` for the repo/docs links. The redesign resolves this to:

- **Releases / stars / version** → `termlab-io/termlab` (the product repo the public downloads from).
- **Docs / repository overview links** → same repo (`termlab-io/termlab/tree/main/docs`) for consistency. If the source-of-truth docs actually live in `an0nn30/conch_workbench`, the implementer should flag it and we decide before merging — the page must not ship with a split identity.

Endpoints:
- `GET https://api.github.com/repos/termlab-io/termlab` → `stargazers_count`.
- `GET https://api.github.com/repos/termlab-io/termlab/releases/latest` → `tag_name` (version), `published_at` (release date), `html_url` (used for the `checksums` link), and `assets[]` (used to pick per-platform download URLs for the three download buttons).

Implementation requirements:
- One module-scope cached Promise so the two render targets (hero pill, download line) and the three download buttons all share a single network call per endpoint.
- Hard-coded fallback values in the HTML (`data-*` attributes on the target nodes). The JS replaces them on successful fetch; on failure the fallback values stay visible and nothing is broken.
  - Suggested fallbacks: stars shown as `★ GitHub` (no number), version shown as `latest`, release date omitted from the mono line when unknown.
- Fallback download URLs point at `https://github.com/termlab-io/termlab/releases/latest` so the buttons always work even if the asset-matching fetch fails.
- No auth header (public endpoints only) — stays within the unauthenticated rate limit for a static page.
- No loading spinner — the fallback values are the "loading" state.

## File scope

Changes are confined to:
- `docs/index.html` — structure rewrite.
- `docs/styles.css` — palette untouched; layout rules rewritten for the new sections; old selectors for removed sections deleted.
- `docs/script.js` — remove the `command-rotator`; add the GitHub fetch + platform detection + reduced-motion guard.
- `docs/assets/` — add the new screenshot files (listed below).

No new JS dependencies. No build step. No framework. Stays a plain three-file static site.

## Assets required

Existing in `docs/assets/`:
- `favicon.png`, `logo.png`, `termlab-logo-just-text.png`, `termlab-splash.png` (splash no longer used on the page — safe to leave in place or remove later).

To be copied in from `~/Desktop/example_screenshots/`:
- `main_layout.png` → hero back layer.
- `adding_new_ssh_host_with_vault_credential.png` → hero front layer + "inside" strip.
- `example_ssh_server_connect.png` → Tour step 01.
- `creating_new_credential_in_vault.png` → Tour step 02.
- `example_sftp_server_upload.png` → Tour step 03.
- `export_connections_example.png` → Tour step 04.
- `empty_vault.png` → "inside" strip.
- `full_featured_settings_pane.png` → "inside" strip.

Note: the current screenshots carry the "Activate Windows" watermark. That's a production-clean-up step (retake or edit the images) outside the scope of this design — the HTML/CSS references the filenames regardless.

## Accessibility

- Every screenshot gets a descriptive `alt` (e.g. "TermLab main window showing terminal, hosts list, tunnels list, and SFTP pane"), not generic "screenshot".
- Single `<h1>` (hero headline). `<h2>` per tour step and per major section heading.
- Body copy contrast ≥ 4.5:1 against the dark background.
- Hero tilt is a CSS `transform`; the DOM order and semantics are straight. Screen readers see the images in natural order.
- `prefers-reduced-motion: reduce` disables fade-in, hover lift, and any transform animation.
- CTA buttons are real `<a>` tags with descriptive text, not icon-only.

## Out of scope

- Taking clean (un-watermarked) screenshots — separate task.
- Building a docs portal. The footer `Docs` link goes to the repo `/docs` folder as it does today.
- Light/dark mode toggle. The page stays dark; the app screenshots being light is an intentional contrast.
- Any kind of interactive demo (fake command palette, animated terminal, etc.). Considered and rejected — "professional product" feel wins over "clever gimmick".
- Internationalization.
- Analytics or cookie banners.

## Implementation order (for the plan that follows)

1. Copy new screenshots into `docs/assets/`.
2. Rewrite `docs/index.html` structure.
3. Rewrite `docs/styles.css` — delete removed-section rules, add new section rules.
4. Rewrite `docs/script.js` — GitHub fetch, platform detection, reduced-motion guard.
5. Local preview and cross-check against the spec.
6. Commit.
