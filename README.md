# Eco-Track Mobile Tracker (Android)

Minimal Android TCP GPS tracker that sends NMEA `RMC` + `GGA` every N seconds to an Eco-Track TCP server.

## Defaults
- Host: `167.233.232.174`
- Port: `9100`
- Device code: `NMEA-TCP-001`
- Interval: `5` seconds

## Build (Windows)
Open the project in **Android Studio** and let Gradle sync.

If this repo is missing Gradle wrapper files (`gradlew.bat`, `gradle-wrapper.jar`), generate them from Android Studio:
- Gradle tool window → run task `wrapper` (or run `gradle wrapper` if you have Gradle installed).

Then build a debug APK:

```bash
.\gradlew.bat :app:assembleDebug
```

APK output path:
- `app\build\outputs\apk\debug\app-debug.apk`

