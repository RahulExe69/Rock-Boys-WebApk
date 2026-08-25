# Persistent Agent Instructions

## Versioning Rules
Whenever you make any UI updates, features, bug fixes, or enhancements to this application, you MUST follow these versioning rules:

1. **Version Code & Version Name Controls**:
   - **Keep All Versions**: If the user prompt contains `#keepversion`, `#keepv`, or `#vkeep`, **DO NOT** increment `versionCode` or `versionName`. Keep both exactly as they are.
   - **Version Name Increment**: ONLY increment `versionName` (e.g. from `"1.0.0"` to `"1.0.1"` or `"1.1.0"`) if the user prompt explicitly contains `#vup`, `#versionup`, or `#upv`. Otherwise, keep `versionName` unchanged.
   - **Version Code (Default)**: In all regular update turns (unless `#keepversion`, `#keepv`, or `#vkeep` is present), increment `versionCode` in `/app/build.gradle.kts` by 2 (or next valid integer) to ensure Android package manager and update detectors always recognize the new build.
   - Otherwise:
     - Keep `versionName` constant unless `#vup`, `#versionup`, or `#upv` is specified.

2. **User-Facing Changelogs Only**:
   - When writing or generating update changelogs (in release metadata, dialogs, or version JSONs), NEVER include internal technical background/CI workflow details (such as git, CI/CD, Gradle arguments, base64, YAML, or developer-only changes).
   - Only include user-facing changes (e.g. visual improvements, faster download speeds, gameplay features, UI fixes, performance and stability enhancements).

3. **Informational Query Flags (`#ans`, `#answer`, `#qna`)**:
   - If the user prompt contains `#ans`, `#answer`, or `#qna`, do NOT perform any code changes or version bumps; provide a clear, direct explanatory response only.

Ensure that after editing `/app/build.gradle.kts`, you run `compile_applet` to verify compilation.
