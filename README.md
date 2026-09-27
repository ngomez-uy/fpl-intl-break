# FPL International Break Tracker

Given an FPL team ID, shows for every squad player whether they were called up by their
national team, the international matches they've played during the break (minutes, subs,
injuries) and the matches still to come.

Data comes from the public Fantasy Premier League API and FotMob's (unofficial) web API.

## Parts

| Folder | What | Run |
| --- | --- | --- |
| `android/` | Standalone Android app (Kotlin + Compose). Fetches FPL/FotMob directly on the phone — no server needed. | Open in Android Studio, or `cd android && ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| `backend/` | Node/TypeScript API for the web app (`GET /api/team/:id/break`). | `cd backend && npm install && npm run dev` (port 4000) |
| `frontend/` | React/Vite web UI; proxies `/api` to the backend. | `cd frontend && npm install && npm run dev` (port 5173) |

The report logic exists twice: `backend/src/*.ts` for the web app and
`android/app/src/main/java/com/fplintbreak/app/engine/` for the phone. Change both.

## Secrets (not in the repo)

- `backend/.env` — `API_FOOTBALL_KEY=...` (optional; the current data sources don't need it).
- Android release signing key, when one exists — keep it and its password outside the repo.
