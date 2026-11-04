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
