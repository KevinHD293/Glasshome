# Glass Home Options v4 — source changes

- Dock baseline bottom clearance reduced from 56dp to 32dp (24dp lower at lift=0). Existing saved dockLift values are retained.
- Widget options: show/hide calendar, show/hide weather, widget height 64–128dp (default 92dp).
- Options available on TV and phone web UI, persisted in SharedPreferences.
- Hidden widget is skipped when navigating by remote.
- No changes to package name, HOME registration, debloat scripts or app ordering.

## Testing checklist

1. Build with existing GitHub Actions workflow; install over v3 and check settings survive.
2. Check dock at 0dp and 48dp lift on actual TV, including overscan and app-name label.
3. Toggle calendar/weather individually and both off, test remote navigation and Back.
4. Test widget heights 64dp and 128dp; check header overlap at 720p and 1080p.
5. Save from phone web UI, reopen TV options and verify settings.
6. Test reset defaults and TV restart.

Not built or tested on TV in this environment. Keep the working v3 APK as a rollback.
