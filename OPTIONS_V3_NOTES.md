# Glass Home Options v3 — dock position

- Adds `Nâng vị trí dock` (0–48 dp in 8 dp increments) to the existing floating options panel.
- Mirrors the setting in the phone web UI as `dockLift`.
- Persists it using SharedPreferences, defaulting to 0 to preserve v2 positioning.
- Recomputes dock and widget layout together, so widgets follow the dock's vertical position.
- No package/launcher/debloat changes. No new background service.
- Source patch only; APK build and on-device testing have not been performed here.
