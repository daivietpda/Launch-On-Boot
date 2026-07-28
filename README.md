# Launch-On-Boot

Launch-On-Boot opens a selected Android TV application after the device boots. It also provides an optional **Advanced Actions** sequence that runs after the target application has opened.

The project is useful for TV devices that should start in a predictable state: a television app can open automatically and then receive a configured sequence such as waiting for the interface, entering a channel number, and confirming it.

Vietnamese documentation: [README-vi.md](README-vi.md).

## Main features

- Choose an installed Android TV application to open after boot.
- Optional launch after wake-up from sleep or screensaver.
- Configurable pre-launch and post-launch delays.
- Advanced Actions can run after the target application opens.
- Android TV remote-friendly UI: actions are arranged with buttons, not drag and drop.

## Advanced Actions

Advanced Actions is intended for two common scenarios:

1. **Easier television use for older people**: open a TV app, wait for it to load, enter a channel number, and press OK automatically.
2. **Kiosk and automation projects**: open a chosen app and perform a predictable set of navigation, timing, or text-entry actions after launch.

The Advanced Settings screen contains only general configuration: triggers, delays, and ADB connection settings. Select **Set up action sequence** to open the dedicated sequence editor.

The editor supports:

- Navigation keys: Up, Down, Left, Right, OK.
- Number keys: 0 through 9.
- Controls: Back, Home, Menu, Enter.
- `WAIT` actions.
- `TEXT` actions.
- Edit, delete, move up/down, clear, save, and test a sequence.

Example sequence for selecting channel 1:

```text
WAIT 1000 ms
KEYCODE_1 — wait after 300 ms — repeat 1
```

Sequences are stored internally as JSON in the app's private SharedPreferences. The normal UI does not require users to view or type JSON.

## ADB input and text

System key injection uses the embedded ADB client and requires ADB debugging to be enabled on the TV. The first connection may require confirmation of the Android RSA authorization dialog.

`TEXT` is sent through Android's `input text` command. It works only while an editable field in the target app has focus. For safety and compatibility, the current ADB text backend accepts printable ASCII except `%`; accented Vietnamese characters, emoji, and line breaks are kept in the saved sequence but are reported as unsupported when the sequence runs.

Do not enable ADB on untrusted networks. The default ADB target is `127.0.0.1:5555`.

## Android 14 background-launch note

Some Android 14 firmware blocks an app from opening another app after boot unless a permitted background-launch exemption is available. Launch-On-Boot can guide the user to grant **Display over other apps**. This is a user-controlled special permission and some vendor firmware may reset it after reboot; an ordinary app cannot restore it itself.

## Build and test

The project uses Gradle. A compatible JDK is required; the Android Studio bundled JDK is known to work.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Manual TV testing should cover boot, wake-up, target-app launch, ADB authorization, the action editor, and cancellation while a sequence is waiting.
