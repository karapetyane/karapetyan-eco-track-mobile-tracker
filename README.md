# Eco-Track Mobile Tracker (Android)

Minimal Android GPS tracker that uploads fixes to the Eco-Track HTTPS telemetry ingest API.

## Defaults
- Ingest URL: `https://167.233.232.174/api/v1/gps/telemetry/ingest`
- Auth header: `x-gps-telemetry-api-key` (configured in the app; never logged)
- Device code: `NMEA-TCP-001`
- Interval: `5` seconds

## Build (Windows)
Open the project in **Android Studio** and let Gradle sync.

Then build a debug APK:

```bash
.\gradlew.bat :app:assembleDebug
```

APK output path:
- `app\build\outputs\apk\debug\app-debug.apk`
