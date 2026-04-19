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
