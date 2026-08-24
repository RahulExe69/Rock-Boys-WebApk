# Persistent Agent Instructions

## Versioning Rules
Whenever you make any UI updates, features, bug fixes, or enhancements to this application, you MUST execute the following versioning steps UNLESS the user prompt contains `#keepversion`:

1. **Local App Versioning**:
   - If the user prompt contains `#keepversion`, **DO NOT** increment `versionCode` or `versionName`. Keep them as they are.
   - Otherwise:
     - Locate `/app/build.gradle.kts`.
     - Increment `versionCode` by 2 (or set to the next valid integer, e.g. from 1 to 2).
     - Increment `versionName` appropriately (e.g. from `"1.0"` to `"1.0.1"` or `"1.1.0"`).

2. **User-Facing Changelogs Only**:
   - When writing or generating update changelogs (in release metadata, dialogs, or version JSONs), NEVER include internal technical background/CI workflow details (such as git, CI/CD, Gradle arguments, base64, YAML, or developer-only changes).
   - Only include user-facing changes (e.g. visual improvements, faster download speeds, gameplay features, UI fixes, performance and stability enhancements).

3. **Informational Query Flags (`#ans`, `#answer`, `#qna`)**:
   - If the user prompt contains `#ans`, `#answer`, or `#qna`, do NOT perform any code changes or version bumps; provide a clear, direct explanatory response only.

Ensure that after editing `/app/build.gradle.kts`, you run `compile_applet` to verify compilation.
