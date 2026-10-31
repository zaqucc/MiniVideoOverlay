# Mini Video Overlay — diagnostic cloud build

This package keeps the app source and adds a GitHub Actions workflow that captures the **complete Gradle build log**, even when the build fails. It does not change the app's overlay/output specifications.

## Build from a phone (no PC / Android Studio)

1. Open the GitHub repository in your mobile browser.
2. Upload the contents of this folder to the repository root, replacing the existing files when prompted. Make sure `.github/workflows/android-apk.yml` is included. If the browser hides `.github`, enable hidden files in your file manager before uploading.
3. Commit the changes to `main`.
4. Open **Actions → Build Android APK** and open the newest run.
5. If the build fails, download the artifact named **MiniVideoOverlay-build-diagnostic-log**. It contains `build-debug.log` and `build-exit-code.txt`. Send those files back for diagnosis.
6. If the build succeeds, download **MiniVideoOverlay-debug-apk**, extract it, and install `app-debug.apk`.

The APK artifact is only uploaded when the build succeeds. A successful cloud build does not replace testing the exported MP4 on the target phone.
