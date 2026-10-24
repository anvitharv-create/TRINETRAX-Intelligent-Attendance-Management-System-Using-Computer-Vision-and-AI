# TrinetraX Android App

Native Android client for the TrinetraX Smart Attendance System. Kotlin + Jetpack
Compose, talking to the **same FastAPI backend and database as the web portal** -
no data is copied or mocked.

```
Phone (Compose app) --HTTP--> FastAPI (backend/main.py) --> attendance.db
Web portal (React)  --HTTP--^
```

## Requirements

- JDK 21 (Android Gradle Plugin does not support newer JDKs)
- Android SDK: platform 35, build-tools 35.0.0
- Gradle 8.11.1 (a `gradlew` wrapper is included)
- The attendance backend running on port 8000

## Build

```powershell
$env:JAVA_HOME  = "C:\android-toolchain\jdk-21.0.12.1+1"
$env:ANDROID_HOME = "C:\android-toolchain\android-sdk"

# If the project folder is synced by OneDrive, build outside it:
.\gradlew.bat assembleDebug -PbuildDirOverride=C:/android-toolchain/trinetrax-build/app
```

APK output: `<buildDirOverride>/outputs/apk/debug/app-debug.apk`

## Run on a phone

1. Start the backend:
   ```powershell
   cd ..\backend
   ..\.venv\Scripts\python.exe -m uvicorn main:app --host 0.0.0.0 --port 8000
   ```
2. Connect the phone (USB debugging) and tunnel the port:
   ```
   adb reverse tcp:8000 tcp:8000
   ```
   The app defaults to `http://127.0.0.1:8000/`, which resolves **on the phone
   itself**, so the reverse tunnel routes it back to the PC.
3. Install and launch:
   ```
   adb install -r <apk>
   adb shell monkey -p com.trinetrax.attendance -c android.intent.category.LAUNCHER 1
   ```

### Changing the server URL

Set it in `gradle.properties` before building:

```properties
# same PC over USB/localhost (with adb reverse) - default
trinetraApiUrl=http://127.0.0.1:8000/
# Android emulator
trinetraApiUrl=http://10.0.2.2:8000/
# LAN server
trinetraApiUrl=http://192.168.1.50:8000/
```

Cleartext HTTP is enabled in the debug manifest for development.

## Features (all reading live data, all gated by backend role permissions)

| Screen | Who sees it | What it does |
|---|---|---|
| Login / Session | everyone | `POST /api/auth/login`, token stored in EncryptedSharedPreferences |
| Home dashboard | 4 role variants | student: overall %, low-subject warning, **server-computed report card**; teacher/hod: subject list, today's marked counts; admin: totals + branch stats |
| Attendance history | student (own), teacher (per subject) | newest-first records, 75% rule |
| Capture | teacher/hod/admin | camera capture -> `POST /api/attendance/capture` (face recognition on the server) |
| Face registration | teacher/hod/admin | `POST /api/recognition/register` |
| Students / Subjects | staff | `GET /api/students`, `GET /api/subjects` (admin gets create/edit) |
| Reports | staff | `GET /api/attendance`, `GET /api/analytics/overall` |
| Notifications | everyone | `GET /api/notifications/{user_id}` |
| Profile | everyone | `GET/PUT /api/users/me` |

Permission rules are **not** duplicated in the app: the backend decides what each
role may fetch, the app simply renders it (admin-only endpoints return 403 for
teachers/HODs, exactly like the web portal).

### Student report card

The Home screen shows the student's report from `GET /api/student/report`
(`backend/student_report.py`): eligibility, percentage, present/absent/other,
shortage % and classes-to-attend. The app **displays the server's numbers
verbatim** - it never recomputes them, so web and phone always agree.

## Build results / verification

- `gradlew assembleDebug` -> **BUILD SUCCESSFUL** (no errors, deprecation warnings only)
- Backend test suite (including student-report tests): **77/77 passed**
- Installed on a physical device (Realme CPH2761IN) and verified against the
  live database: profile, capture status (`opencv-yunet+sface`, 5 registered
  faces) and student report (40 records, 75.0%, eligible) all returned real data.
- **No backend files were modified for this app** - zero changes to `main.py`,
  the database, or the web frontend.

## Project layout

```
android/
  build.gradle.kts, settings.gradle.kts, gradle.properties
  gradlew.bat, gradle/wrapper/
  app/build.gradle.kts          # BASE_URL via trinetraApiUrl, buildDirOverride
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/trinetrax/attendance/
    TrinetraXApp.kt, MainActivity.kt
    core/     SessionManager.kt, NetworkModule.kt
    data/     api/TrinetraApi.kt, model/Models.kt, AppRepository.kt
    viewmodel/SessionViewModel.kt
    ui/       theme/, components/, nav/, screens/
```

## Known limitations

- Debug build over cleartext HTTP - for LAN/demo use only; add HTTPS + certificate
  pinning before any production deployment.
- The Gradle wrapper distribution URL must be reachable the first time
  (`services.gradle.org`), or pre-install Gradle 8.11.1 and use `gradle`.
