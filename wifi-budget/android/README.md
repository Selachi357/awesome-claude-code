# WiFi Budget for Android

Counts your phone's Wi-Fi data per day, **resetting at 00:00 UTC**, and has a **Data Saver** that blocks
every app you haven't allowed. Made for capped Wi-Fi like a ship's 1.5 GB a day. No ads, no internet access
of its own, and nothing leaves your phone. Works on Android 8 and newer.

## Install

1. Download **[WifiBudget.apk](../WifiBudget.apk)** on your phone.
2. Open it. Android will ask to allow installing from this source (your browser or Files app). Allow it, then tap **Install**.
   - If Play Protect warns about an unknown app, tap **More details → Install anyway**.
     It warns because the app isn't from the Play Store.
3. Open **WiFi Budget** and tap **Allow usage access** → find WiFi Budget → switch it on.
   This is Android's permission for reading data-usage counters.
4. Allow notifications when asked, so you get the 50 / 75 / 90 / 100 % warnings.

## What you get

- **Used today / left**, from 00:00 UTC. The bar turns orange at 75% and red at 90%.
- **Speed now**, and **"At this speed you run out at HH:MM UTC"**.
- **What used your Wi-Fi today**: the top apps, so you can see what's eating your data.
- **Last 14 days**, with days over the limit shown in red.
- **Correct to portal**: if the ship's portal shows a different number, tap
  *"Doesn't match the ship's portal?"* and enter its figure. Today's count then follows the portal.
- **Warnings** at 50, 75, 90 and 100%, checked every 15 minutes in the background
  (every minute while Data Saver is on).

## Data Saver

Switch it on and **only the apps you allow can use the internet**. Everything else (Play Store updates,
photo backup, TikTok/Instagram preloading, game downloads, and so on) is cut off.

- Tap **Choose allowed apps** to pick them. Recommended ones are already ticked: WhatsApp, Signal,
  Telegram, Messenger, Messages, Gmail/Outlook, Chrome/Firefox/Samsung Internet, Google Play services
  (needed for message notifications) and the Wi-Fi login page.
  The list is sorted by today's usage, so the data-hungry apps are at the top.
- The first time, Android asks you to allow a **VPN connection**. That's how Android lets an app
  block other apps. It's only a filter on your phone: traffic from blocked apps is simply discarded,
  and allowed apps connect normally. You'll see a 🔑 key icon in the status bar while it's on.
- It **turns on by itself at 80%** (you can change this in Settings). If Android doesn't let it start
  in the background, you get a notification instead; tap it to turn Data Saver on.
- If it switched on automatically, it **switches off at 00:00 UTC** when your allowance resets.
- You can turn it off from the app or with **Turn off** in its notification.

Only one VPN can run at a time. If you use another VPN, Data Saver will replace it while it's on.

## Extra savings (built into Android)

- **Mark the ship Wi-Fi as metered**: Settings → Wi-Fi → tap the network (⚙) → **Metered → Treat as metered**.
  Android and most apps then hold back big background downloads and updates.
- Play Store → profile → Settings → Network preferences → Auto-update apps → **Don't auto-update apps**.
- Google Photos → Backup → **off** (or "Back up over mobile data" settings).
- WhatsApp → Settings → Storage and data → **Media auto-download: No media** for Wi-Fi.
- YouTube / Netflix: pick **480p or lower**. HD video can use the whole 1.5 GB in about an hour.

## Notes

- The count comes from Android's own Wi-Fi counters, which also power the system data-usage screen.
  The ship counts at its end, so expect the two to differ by a few percent. Use the portal correction if they drift.
- Updating the app later: if Android says the package conflicts, uninstall the old version first.
  Your settings will reset.

## Building it yourself

```
cd wifi-budget/android
./gradlew assembleRelease     # needs the Android SDK (platform 35) and JDK 17+
```
The output is `app/build/outputs/apk/release/app-release.apk`. Plain Java, no third-party libraries.
