# Instagram Reels Blocker

Hides Reels and short-form video on **instagram.com** (desktop or mobile browser):

- Removes the **Reels** button from the navigation bar
- Hides reel posts from your home feed
- Hides reel tiles in Explore and on profile grids, and the profile **Reels** tab
- Redirects any `/reels/` or `/reel/...` page back to your feed

Photo posts, carousels, Stories and DMs are not affected.

## Install

**As a browser extension (Chrome / Edge / Brave)**
1. Open `chrome://extensions` and turn on **Developer mode**.
2. Click **Load unpacked** and select this folder.

**Firefox**: open `about:debugging#/runtime/this-firefox` → **Load Temporary Add-on** → pick `manifest.json`.

**As a userscript** (Tampermonkey / Violentmonkey, including Firefox for Android or Safari via the Userscripts app):
install `instagram-reels-blocker.user.js`.

## Limitations

- This works only in a web browser. The official Instagram iOS/Android app can't be modified; to avoid
  Reels on your phone, delete the app and use instagram.com in a browser that supports extensions or userscripts.
- Instagram changes its markup often. Rules match link targets (`/reel/`, `/reels/`) instead of
  obfuscated class names, so they should hold up, but may need updating if Instagram changes its URLs.
