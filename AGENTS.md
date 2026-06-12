# Persistent Agent Instructions

Whenever you make any UI updates, features, bug fixes, or enhancements to this application, you MUST execute the following versioning steps:

1. **Local App Versioning**:
   - Locate `/app/build.gradle.kts`.
   - Increment `versionCode` by 2 (or set to the next valid integer, e.g. from 1 to 2).
   - Increment `versionName` appropriately (e.g. from `"1.0"` to `"1.0.1"` or `"1.1.0"`).

2. **Remote App Versioning**:
   - Locate `/.versions/version.json`.
   - Update `versionCode` and `versionName` to match or be higher than the local version (depending on whether you want to immediately trigger a force-update or prepare the JSON for the next release sequence).
   - Set `"forceUpdate": true` or match user intent.
   - Set `"changeLog"` to describe the exact list of visual changes, fixes, and improvements you just implemented during your turn.

Ensure that after editing these files, you run `compile_applet` to verify compilation.
