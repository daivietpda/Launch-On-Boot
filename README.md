# Quick Links
<img src='https://raw.githubusercontent.com/ITVlab/Launch-On-Boot/master/promo/banner2.png' />

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/news.androidtv.launchonboot/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=news.androidtv.launchonboot)

# Launch-On-Boot
_Launches a TV app when the device boots_

On Google TV, there was a way to launch a specific app when the device booted. By default the device would display the TV stream, making the OS feel more like an overlay on top of your television than something completely isolated.

Android TV will simply just display the launcher on a reboot, a small distraction for users expecting to see TV and annoying for individuals using Android TV as a dumb kiosk displaying a single video or stream.

This app allows the user to select a specific app to open when the device boots. It's just that simple. Any leanback-enabled app can be opened. Alternatively, the default TV app can be opened, returning you to the channel you just saw.

Want to make your Android TV act more like a dumb TV? Want to launch Sling TV immediately. You should download this small utility app.

## Features
* **Auto-Launch**: Select any installed TV app to launch automatically after the device finishes booting.
* **Live Channels Support**: Option to launch the default TV app to resume your last watched channel.
* **Leanback Ready**: Fully compatible with Android TV and Google TV interfaces.
* **Lightweight**: Minimal resource usage, runs once at boot.

## Technical Details
This project has been updated to use modern Android development standards:
* **AndroidX**: Migrated from the legacy Support Library to AndroidX for better stability and modern APIs.
* **Target SDK 34**: Updated to target Android 14 (API level 34).
* **Java 17**: Configured to use Java 17 for builds.
* **Gradle 8+**: Updated build system for faster and more reliable builds.

## Development
To build the project, open it in Android Studio or use the Gradle wrapper:
```bash
./gradlew assembleDebug
```

## Screenshots
<img src='https://raw.githubusercontent.com/ITVlab/Launch-On-Boot/master/promo/device-2016-10-24-191303.png' />

<img src='https://raw.githubusercontent.com/ITVlab/Launch-On-Boot/master/promo/device-2016-10-24-192435.png' />
