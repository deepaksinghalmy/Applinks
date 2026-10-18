# Android Clipboard Link Bucket (source project)

This is a native Android source project for collecting X/Twitter post URLs and appending them to Google Sheets.

## What it does

- Receives a shared text URL from Android's Share menu (`Share → Link Bucket`).
- Includes a **Collect Current Clipboard** button for the `Copy link` workflow.
- Detects direct X/Twitter status URLs only; ignores profile/search URLs.
- Normalizes `twitter.com` links to `x.com` and removes query/fragment.
- Skips duplicate canonical URLs.
- Stores links locally in Android SharedPreferences.
- Appends pending URLs to Google Sheets via a Google Apps Script Web App endpoint.
- Marks links as sent only after the server returns `{ "ok": true }`.
- Offers a share sheet for sending all links to WhatsApp or another app.
- Does not post to X and does not use X API.

## Build the APK (Android Studio)

This environment package contains source code, not a precompiled APK. Build it on Windows using Android Studio.

1. Install Android Studio: https://developer.android.com/studio
2. Open Android Studio → **Open** → select the extracted `AndroidClipboardLinkBucket` folder.
3. Let Gradle sync and install the requested Android SDK (API 35) if prompted.
4. Connect an Android phone with USB debugging enabled, or use an emulator.
5. Click **Run** to install and test.
6. To create an APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
7. APK output is typically `app/build/outputs/apk/debug/app-debug.apk`.

## Best way to collect links

### Method A: Android Share menu (recommended)

1. Open a published post in X.
2. Tap **Share**.
3. Select **Link Bucket** from Android's share sheet.
4. The URL is added to the local bucket.
5. Repeat for each post.
6. Tap **Send Pending Links to Google Sheets** when done.

The app receives `ACTION_SEND` text shares. Some X app versions may share a post card or URL differently; if Link Bucket does not appear, use **Share → Copy link**, open Link Bucket, and tap **Collect Current Clipboard**.

### Method B: Copy link + collect

1. In X, use **Share → Copy link**.
2. Open Link Bucket.
3. Tap **Collect Current Clipboard**.
4. Repeat for each post.

Android restricts arbitrary background clipboard reads. This app intentionally does not promise continuous background clipboard monitoring.

## Set up Google Sheets receiver (no X API)

This app uses Google Apps Script as a small authenticated bridge, so the Android app does not need Google OAuth inside the app. It still uses Google's Apps Script/Sheets service to write to your sheet.

### 1. Create the Sheet

1. Create/open a Google Sheet.
2. Copy its URL, for example:
   `https://docs.google.com/spreadsheets/d/PASTE_SPREADSHEET_ID_HERE/edit`
3. The spreadsheet ID is the string between `/d/` and `/edit`.

### 2. Create Apps Script

1. Open https://script.google.com/ while signed in to the Google account that can edit the Sheet.
2. Create a new project.
3. Replace the editor contents with `google-apps-script/Code.gs` from this project.
4. Set `SPREADSHEET_ID` to your real spreadsheet ID.
5. Replace `SECRET_TOKEN` with a long random secret. Example generation in PowerShell:
   `-join ((1..48) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 256) })`
   Use your own generated value; do not share it publicly.
6. Save the script.

### 3. Deploy as Web App

1. Click **Deploy → New deployment**.
2. Select type **Web app**.
3. Execute as: **Me**.
4. Who has access: **Anyone** (required for the Android app to POST without Google sign-in). The secret token in the request restricts write access.
5. Click Deploy and complete Google's authorization prompt.
6. Copy the Web App URL ending in `/exec`.

Important: anyone who knows the deployed URL *and* secret can write rows to the sheet. Keep the secret private. If exposed, rotate it in the script and app settings. This simple token is suitable for personal use; for higher-security deployments, use a properly authenticated backend.

### 4. Configure Android app

1. Build and run the Android app.
2. Paste the `/exec` Web App URL into **Apps Script Web App URL**.
3. Paste the exact same secret into **Secret token**.
4. Tap **Save Google Sheets Settings**.
5. Collect a test X post link.
6. Tap **Send Pending Links to Google Sheets**.
7. Verify a `Links` tab appears with columns: `Captured At`, `X/Twitter URL`, `Source`, `Collector`.

The script appends to the fixed `TAB_NAME = "Links"` tab. Change that constant in Apps Script if you want a different tab name, then redeploy a new version.

## Important limitations

- The project is source code; an APK must be built with Android Studio/Gradle.
- No X API is used, and the app cannot publish posts.
- No automatic background clipboard polling. Use Android Share menu or the in-app Collect Clipboard button.
- Google Apps Script has quotas and can be slower than a direct API. Export is initiated by the user.
- A network failure leaves links pending, but if a server writes rows and the response is lost, retrying can theoretically create duplicate rows in Sheets. Local duplicate collection is prevented.
- Keep the Google Apps Script secret private.


## Build APK in GitHub Actions (no Android Studio on your PC)

A workflow is included at `.github/workflows/build-apk.yml`.

1. Create a new GitHub repository.
2. Upload all files and folders from this project to the repository root (including `.github`).
3. Open the repository's **Actions** tab.
4. Select **Build Android APK** and click **Run workflow** (or push to the `main` branch).
5. Wait for the workflow to finish successfully.
6. Open the completed workflow run and download the artifact named `ClipboardLinkBucket-debug-apk`.
7. Extract the artifact; it contains `app-debug.apk`.

This is a debug APK for personal testing, not a Play Store release. The APK must be built by the workflow; it is not included in this source ZIP.
