# Notchify

A dynamic island for Android, built for the Nothing Phone (4a) Pro. Notchify turns the camera
punch-hole into a live, animated surface for music, calls, turn-by-turn navigation, deliveries,
timers, the torch and notifications. Tap the camera itself to open a control centre.

It is a native Kotlin app with no AndroidX or Compose: plain framework views, a hand-rolled
spring-physics engine and a 260 KB release APK.

## What it does

| Activity | Compact pill | Expanded |
|---|---|---|
| **Navigation** (top priority while routing) | Maneuver arrow and live distance | Driving HUD: maneuver tile, rolling distance count-down, approach ring that fills as the turn nears, trip facts (ETA, distance left, optional speed), Android 16 trip-progress segments |
| **Calls** (incl. WhatsApp and other VoIP apps) | Avatar and call timer | Mute, audio route (phone / speaker / Bluetooth / headset), keypad, call-volume bar, hang up. Incoming calls open with answer / decline |
| **Deliveries and rides** (Swiggy, Zomato, Blinkit, Zepto, Uber, Ola, Rapido, and any app posting Android 16 Live Updates) | App icon and ETA | Step tracker (Placed, Preparing, On the way, Arriving, Delivered), or the app's own ProgressStyle segments. Pops open by itself when the order changes stage |
| **Music** | Cover art and equaliser | Cover-coloured glow that breathes with playback, cover flip on track change, play/pause morph, seekable progress |
| **Torch** | Glyph with a light-burst animation | Beam whose brightness follows the strength, and tap-to-dim strength bars |
| **Timers, alarms, progress** | Live chronometer or progress ring | The app's own buttons |
| **Notifications** | — | One banner per alert, with the app's actions |
| **Charging, silent mode** | Battery that fills to the level, ringer state | — |

**Control centre** (tap the idle camera): rounded tiles for Wi-Fi (network name), mobile data
(SIM and 5G/4G), Bluetooth (connected devices), location, flashlight and the **Glyph Matrix
light** on the back, plus live chips for camera, microphone and GPS in use.

Two activities at once split into a pill and a bubble, as on iOS. The expanded view lists the
others, so you can jump straight from a call to the driving HUD.

## Design notes

- **Fits the real hole.** Geometry comes from `Display.getCutout().getCutoutPath()`, not the
  bounding rect, which Android stretches to the top edge. On the (4a) Pro that is a 70.6 px
  circle at (630.5, 85.4), and the idle island sits exactly on it. A calibration ring and
  sliders cover any other device.
- **Keeps status icons visible.** The compact pill grows to the right of the camera, so the
  notification icons left of it are never covered. The overlay window is sized per side, so no
  invisible window blocks the status bar either.
- **Motion.** Width, height and left extent are three damped springs integrated in fixed
  sub-steps, so they behave the same at 60 or 120 Hz. Growing is softer and bouncier;
  collapsing is stiffer and better damped. Content crossfades between double-buffered views
  with a short `RenderEffect` blur, and the shape rubber-bands under your finger.
- **No duplicate pop-ups.** While the island shows banners, Android's heads-up pop-ups are off.
  They are restored in landscape and when the service stops.
- **Power.**
  - Nothing polls. The frame callback runs only while a spring is moving.
  - Chronometers tick once a second and the equaliser at 24 fps, only while visible.
  - Reposted notifications whose visible content is unchanged are dropped before any icon
    is loaded, so Maps re-posting every second costs almost nothing.
  - Control-centre listeners exist only while the panel is open.
  - The speed readout uses the passive location provider, which reuses Maps' own fixes.
  - Album art is shrunk once per track.

## Architecture

```
NotificationService ─┐   (notifications → islands; MediaTracker for sessions)
NotchService torch ──┼──▶ Hub ──▶ NotchService (state machine: compact / expanded / banner / peek)
MediaTracker ────────┘                       │
                                             ▼
                              IslandView (springs, window sizing, gestures)
                               ├── CompactView  ×2 (double-buffered)
                               └── ExpandedView ×2 ─ NavHud · CallControls · Stages ·
                                                     Segments · Torch · Controls · Others
```

- `NotchService`: an `AccessibilityService` whose only job is a `TYPE_ACCESSIBILITY_OVERLAY`
  window, the one window type that may sit above the status bar and over the cutout. It
  subscribes to no accessibility events and reads no screen content.
- `NotificationService`: a `NotificationListenerService` that classifies notifications into
  calls, navigation, deliveries, timers, live updates and banners.
- `QuickControls`: radio, location, torch, Glyph and sensors-in-use state for the control centre.

## Building

Requirements: Android Studio (AGP 9.4, Gradle 9.6, JDK 17+), Android SDK 37, and a device on
Android 13 or later.

1. Download `glyph-matrix-sdk-2.0.aar` from Nothing's
   [GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)
   into `app/libs/`. Nothing's licence doesn't allow redistributing it, so it isn't in this repo.
2. `./gradlew assembleRelease`, or open the project in Android Studio.

## Setup on the phone

1. Install the APK and open Notchify.
2. Grant **Notification access**, then enable the **Notch overlay** accessibility service. If
   Android blocks it as a restricted setting, use App info → ⋮ → Allow restricted settings.
3. Optional, from a computer:

   ```
   adb shell pm grant com.saif.notchify android.permission.WRITE_SECURE_SETTINGS
   ```

   This lets Notchify hide the duplicate heads-up pop-ups, switch location from the control
   centre, and put the Glyph interface into developer mode.

Android doesn't let ordinary apps switch Wi-Fi, mobile data or Bluetooth, so those tiles open
the system's one-tap panel instead. Location, flashlight and the Glyph light toggle in place.

## Author

[DevSaif16](https://github.com/DevSaif16)
