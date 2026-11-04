# Calm Launcher

A deliberately boring home screen for Android (built for a Samsung Galaxy A56, works on Android 10+).
It replaces Samsung's One UI Home with something that's harder to get lost in.

## What makes it less addictive

- **No icons, no colour, no notification badges.** Just a clock, the date and text.
- **Max 6 pinned apps** on the home screen. Pin the useful ones (phone, messages, maps), not the feeds.
- **No app drawer.** Every other app has to be found by *typing its name*, so opening one is a choice, not a reflex swipe.
- **Pause before time sinks.** Instagram, TikTok, YouTube, Facebook, X, Snapchat, Reddit, Netflix and similar
  show a "Breathe. Why are you opening this?" screen with a countdown (10 s by default) and how many times
  you've opened it today. Leaving that screen resets the timer. Apps with a pause are marked `·`.
- **One-tap grayscale** (optional, see below). A grey screen is much less tempting.

## Install on the A56

1. On the phone, download `CalmLauncher.apk` from this folder on GitHub (open the file, then "Download raw").
2. Open it; allow "Install unknown apps" for your browser/Files app when asked, then tap Install.
3. Press the Home button → choose **Calm** → **Always**.
   (Or: Settings → Apps → Choose default apps → Home app → Calm.)

To go back: Settings → Apps → Choose default apps → Home app → One UI Home.

## How to use it

| Action | How |
|---|---|
| Open an app | Type its name in the bottom field, tap it (or press Enter for the first match) |
| Pin / unpin, add / remove pause, App info | Long-press any app name |
| Settings (grayscale, pause length, Digital Wellbeing, default home) | Long-press the clock |

## Grayscale

Android only lets apps toggle grayscale with a special permission. Grant it once from a computer with
[adb](https://developer.android.com/tools/releases/platform-tools) (USB debugging on):

```sh
adb shell pm grant app.calmlauncher android.permission.WRITE_SECURE_SETTINGS
```

No computer? Turn it on by hand: Settings → Accessibility → Visibility enhancements → Colour adjustment → Greyscale.
Samsung's **Bedtime mode** (Settings → Modes and Routines) can also switch to grayscale on a schedule.

## Pair it with these One UI settings

- Settings → Notifications → App notifications: turn off everything that isn't from a person.
- Settings → Notifications → Advanced settings → App icon badges: off.
- Settings → Digital Wellbeing and parental controls → App timers: set daily limits on the worst apps.

## Build from source

Requires JDK 17+ and the Android SDK (platform 35).

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

No third-party libraries; plain Java + the Android framework, ~500 lines.
