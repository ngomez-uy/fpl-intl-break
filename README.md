# FPL International Break Tracker

Given an FPL team ID, shows for every squad player whether they were called up by their
national team, the international matches they've played during the break (minutes, subs,
injuries) and the matches still to come.

Data comes from the public Fantasy Premier League API and FotMob's (unofficial) web API, with
call-ups cross-checked against Transfermarkt's current national-team squads. See
[docs/DATA_SOURCES.md](docs/DATA_SOURCES.md).

## Download (Android)

Latest version 0.5: [releases/BreakTracker-0.5.apk](releases/BreakTracker-0.5.apk). On the phone, open the download link in a browser logged in to
GitHub (the GitHub app can't download files), tap the `.apk`, and allow installs from that app when
Android asks. Newer versions install over it.

Direct link: `https://github.com/ngomez-uy/fpl-intl-break/raw/main/releases/BreakTracker-0.5.apk`

## Parts

| Folder | What | Run |
| --- | --- | --- |
| `android/` | Standalone Android app (Kotlin + Compose). Fetches FPL/FotMob directly on the phone — no server needed. | Open in Android Studio, or `cd android && ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| `backend/` | Node/TypeScript API for the web app (`GET /api/team/:id/break`). | `cd backend && npm install && npm run dev` (port 4000) |
| `frontend/` | React/Vite web UI; proxies `/api` to the backend. | `cd frontend && npm install && npm run dev` (port 5173) |

The report logic exists twice: `backend/src/*.ts` for the web app and
`android/app/src/main/java/com/fplintbreak/app/engine/` for the phone. Change both.

## Releasing the Android app

Release APKs are signed with a personal key kept **outside the repo**. Every update must be
signed with the same key, or phones refuse to install it over the old version.

1. Put the key folder somewhere on the machine (e.g. `~/fpl-release-signing/`), containing
   `fpl-break-release.jks` and `keystore.properties`:
   ```
   storeFile=/absolute/path/to/fpl-break-release.jks
   storePassword=…
   keyAlias=fpl-break
   keyPassword=…
   ```
2. Copy or symlink that `keystore.properties` to `android/keystore.properties` (git-ignored).
3. Bump `versionCode` / `versionName` in `android/app/build.gradle.kts`.
4. `cd android && ./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`

Without `keystore.properties` the release build is produced unsigned (not installable).

## Secrets (not in the repo)

- `backend/.env` — `API_FOOTBALL_KEY=...` (optional; the current data sources don't need it).
- Android release signing key + password (`fpl-break-release.jks`, `keystore.properties`) —
  back them up somewhere personal; losing them means friends must uninstall to update.
