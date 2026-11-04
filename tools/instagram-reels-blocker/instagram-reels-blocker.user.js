// ==UserScript==
// @name         Instagram Reels Blocker
// @namespace    instagram-reels-blocker
// @version      1.0.0
// @description  Hides Reels and short-form video on instagram.com and redirects Reels pages to your feed.
// @match        https://www.instagram.com/*
// @run-at       document-start
// @grant        GM_addStyle
// ==/UserScript==

GM_addStyle(`
/* Instagram Reels Blocker — hides Reels / short-form video on instagram.com.
   Instagram's class names are obfuscated and change often, so every rule
   keys off link targets (href), which are stable. */

/* Sidebar / bottom-bar "Reels" navigation entry */
a[href="/reels/"],
a[href^="/reels/"] {
  display: none !important;
}

/* Feed posts that are reels */
article:has(a[href*="/reel/"]) {
  display: none !important;
}

/* Explore grid tiles and profile grid tiles that link to a reel */
div:has(> a[href*="/reel/"]) {
  display: none !important;
}

/* "Reels" tab on profile pages (e.g. /username/reels/) */
a[href$="/reels/"][role="tab"],
a[role="tab"][href*="/reels"] {
  display: none !important;
}
`);

// Instagram Reels Blocker — redirects away from Reels pages and removes
// reel content that CSS alone can't catch. Instagram is a single-page app,
// so we watch for in-app navigation as well as initial page loads.
(() => {
  "use strict";

  const isReelsPath = (path) =>
    path === "/reels" ||
    path.startsWith("/reels/") ||
    path.startsWith("/reel/") ||
    /^\/[^/]+\/reels\/?$/.test(path); // profile Reels tab

  const redirectIfReels = () => {
    if (isReelsPath(location.pathname)) {
      location.replace("/");
    }
  };

  // Instagram navigates without full page loads. Content scripts can't hook
  // the page's own history.pushState (separate JS world), so poll the URL.
  let lastPath = location.pathname;
  setInterval(() => {
    if (location.pathname !== lastPath) {
      lastPath = location.pathname;
      redirectIfReels();
    }
  }, 300);
  window.addEventListener("popstate", redirectIfReels);

  // Block clicks on any reel link before Instagram's router handles them.
  document.addEventListener(
    "click",
    (event) => {
      const link = event.target.closest?.("a[href]");
      if (!link) return;
      const url = new URL(link.href, location.origin);
      if (url.origin === location.origin && isReelsPath(url.pathname)) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    },
    true
  );

  // Pause and remove any reel video that slips through (e.g. when the
  // browser lacks :has() support).
  const sweep = () => {
    document
      .querySelectorAll('article a[href*="/reel/"]')
      .forEach((a) => a.closest("article")?.remove());
    document
      .querySelectorAll('a[href^="/reels/"], a[href*="/reel/"]')
      .forEach((a) => (a.style.display = "none"));
  };

  redirectIfReels();
  const observer = new MutationObserver(() => {
    redirectIfReels();
    sweep();
  });
  const start = () => {
    sweep();
    observer.observe(document.documentElement, { childList: true, subtree: true });
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, { once: true });
  } else {
    start();
  }
})();
