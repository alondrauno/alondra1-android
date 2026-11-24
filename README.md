<p align="center"><img src="docs/images/alondra1-logo-512.png" alt="Alondra1 logo" width="128"></p>

# Alondra1 for Android

[![ci](https://github.com/nikita4sea/alondra1-android/actions/workflows/ci.yml/badge.svg)](https://github.com/nikita4sea/alondra1-android/actions/workflows/ci.yml)

Personal **To-do**, **Notes** and **Calendar** for Android phones and tablets. Local-first: everything works offline, with no account, and stays on the device. Optional paid **Cloud Sync** uses the Alondra1 backend ([alondra1-backend](https://github.com/nikita4sea/alondra1-backend)); see [Cloud account and sync](#cloud-account-and-sync).

Phase 1 scope and rules: [docs/CLIENT-REQUIREMENTS.md](docs/CLIENT-REQUIREMENTS.md). Every feature with its code, tests and status: [docs/FEATURE-MAP.md](docs/FEATURE-MAP.md). Not in this app: Mail, Contacts, task attachments (kept unchanged when synced), recurring events, system-calendar access. Task steps, repeats, start dates, several reminders, list groups and completion history work as on Windows (completion spec v1.1 §8.3).

## Build and run

You need JDK 17 and the Android SDK (platform 35). Android Studio works as is; from a terminal:

```sh
./gradlew assembleProdDebug                   # app/build/outputs/apk/prod/debug/app-prod-debug.apk
./gradlew installDevDebug                     # Alondra1 Dev (com.alondra1.app.dev.debug) next to prod
./gradlew lintProdDebug testProdDebugUnitTest # lint + all JVM tests (unit, Room, Robolectric UI)
./gradlew connectedProdDebugAndroidTest       # dialog flows on a device/emulator
./gradlew bundleProdRelease                   # app/build/outputs/bundle/prodRelease/app-prod-release.aab
scripts/check-environment.sh prod app/build/outputs/bundle/prodRelease/app-prod-release.aab
```

- **Min / target SDK:** 29 (Android 10) / 35.
- **Release signing:** only from environment variables in CI (`ALONDRA1_KEYSTORE_FILE`, `ALONDRA1_KEYSTORE_PASSWORD`, `ALONDRA1_KEY_ALIAS`, `ALONDRA1_KEY_PASSWORD`). Keys are never committed; without them the bundle is unsigned.
- **Environments:** product flavors `prod` and `dev` (completion spec v1.1 §8.3): own application id, name (*Alondra1 Dev*), API origin, discovery sources and trusted keys. Release builds find their endpoints through signed discovery (`cloud/discovery/`); debug builds use the local development server (`http://10.0.2.2:8080`, override with `-PapiBaseUrl=...`). A dev build never talks to prod and the reverse. Nothing contacts the network until the user turns the cloud on.
- **Dependencies:** versions are pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml); the Gradle wrapper is pinned and checksummed by Gradle.

## Architecture

Kotlin, Jetpack Compose with Material 3, single activity, Navigation Compose, MVVM with `StateFlow`, Room, DataStore, WorkManager. Dependencies are wired by hand in `AppContainer` (no DI framework).

| Package | Contents |
| --- | --- |
| `domain/` | Pure rules: smart views, sorting, counts, note titles, event dates and validation, note conflict compare and combine, the meeting-notes template. Unit-tested without Android. |
| `data/local/` | Room entities (field names and meanings follow the shared task contract), converters, DAOs, versioned schema in `app/schemas/`. |
| `data/repository/` | Offline-first repositories. All writes are local and immediate; multi-record changes run in transactions. |
| `data/notifications/` | Notification channels and reminders (WorkManager). |
| `data/settings/` | DataStore settings. |
| `data/sync/` | Cloud Sync: `SyncEngine`, `WireMapper`, `OutboxRecorder`, `SyncWorker`. The free build uses `ChangeRecorder.LocalOnly`: nothing is queued or sent. |
| `auth/` | Alondra1 account: API client, session, sign-in and the Account & Cloud settings section. |
| `features/` | Screens and view models: `todo`, `notes` (list, rich editor, compare versions, share to note), `calendar` (including meeting notes), `search`, `settings`, `onboarding`. |
| `navigation/` | Bottom bar on phones, navigation rail from 600dp, list/detail panes from 840dp; `alondra1://task|note|event/{id}` deep links. |
| `design/` | Brand tokens and the Material theme (dark by default, light, follow system). |

## To-do rules

Same semantics as the Windows app ([shared task contract](https://github.com/nikita4sea/alondra1-backend/tree/main/contract)):

- **My Day** contains tasks whose My Day date is today. It's explicit: tasks are added from quick add in My Day, from the task detail, or from Windows, and the view starts empty each day. Tasks due today are not added automatically.
- **Important:** open and starred. **Planned:** open with a due date. **Upcoming:** open and due after today. **Unscheduled:** open without a due date.
- **Overdue:** open and due before today, or due today at a time that has passed. A date-only task becomes overdue when its local day ends.
- **All Tasks:** everything not deleted, with an Open/All/Completed filter. **Completed:** newest completion first.
- Counts are computed from the same rules as the lists, so they always match.
- **Quick add** in My Day adds to My Day, in Important stars the task, in a list adds to that list, and otherwise uses the default list (Settings). Titles are trimmed, 1–500 characters.
- **Deleting a list** asks first. By default its tasks move to **Tasks**; the default list can't be deleted.
- Complete, delete and restore offer **Undo**. Deletes are tombstones, so a future sync can't bring a task back.
- New IDs are 32 lowercase hex characters (as on Windows), dates are date-only, and times are local wall-clock values.

## Notes and Calendar

- **Notes** are rich text, edited with the same editor as the Windows app, so formatting is kept on both ([docs/NOTES.md](docs/NOTES.md)):
  - fonts, sizes and colors, highlight, headings, alignment;
  - lists and nested checklists;
  - tables;
  - pictures from the photo picker or shared from other apps.

  Notes also have folders, Favorites, pinning, Archive and a 30-day Trash, plus full-text search. They autosave after a short pause and right away when you leave the note or the app goes to the background. A checklist item can become a task, and a note can link tasks and events.
- **Meeting notes:** an event's screen has **Meeting notes**, which opens the note linked to the event or creates one from the same template as Windows.
- **Sync conflicts:** when a note changed on two devices, both versions are kept, and a **Compare** screen lets you keep either one, combine both, or keep both.
- **Calendar** shows a month grid with a day panel, or an agenda for the next 60 days, filtered by Events, Tasks or Both. Task due dates are shown in their own color and open the task; they are never copied as events.
- **Events:** timed events store UTC instants plus their time zone. All-day events store dates only (end inclusive), so they never shift between zones. The end must not be before the start. All-day reminders count from 09:00 on the start day.
- The app does not read or write the system calendar and does not ask for calendar permission.

## Reminders and notifications

- Separate channels for task and event reminders. Each item has one unique pending reminder, so edits replace it and never duplicate it. Completing or deleting cancels it.
- Delivery is approximate (WorkManager). The app doesn't request exact alarms or battery-optimization exemptions. Reminders survive reboots and are re-planned when the time zone or clock changes, keeping their wall-clock time.
- `POST_NOTIFICATIONS` is requested only when you turn on a reminder (Android 13+). If it's denied, the app explains that and keeps working.
- Lock-screen privacy is on by default: the lock screen shows only "Alondra1 reminder".

## Data safety

- The database lives in the app's private storage, with no exported content providers.
- **Google cloud backup:** nothing is backed up (`data_extraction_rules.xml`), because OS backups are not Alondra1 Cloud Sync. **Device-to-device transfer** brings tasks, notes, events and settings to a new phone; shared preferences (account tokens) are excluded.
- No analytics, no crash upload, no ads. In free mode, no network request is made at all.
- **Settings → Privacy & data → Delete local data** wipes this device only, after showing counts and asking you to type DELETE.
- Schema changes need a new Room version and migration. `RepositoriesTest.committedSchemaMatchesTheEntities` fails if the entities change without one. Destructive migrations are never used.

## Free and paid

| Capability | Free | Paid (Personal and up) |
| --- | --- | --- |
| Tasks, lists, smart views, reminders, notes, calendar, offline search | Yes | Yes |
| Cloud Sync of tasks, lists, notes and events | No | Yes, after you opt in |
| Archive (mail archive and search) | No | Windows only (no mail on Android) |

Completed tasks and Undo are free and are not "Archive".

## Cloud account and sync

Signing in, plans and opt-in Cloud Sync are in **Settings → Account & Cloud**, and appear only when the build has an API URL. See [docs/CLOUD.md](docs/CLOUD.md).

## Tests

- **JVM** (`testDebugUnitTest`):
  - smart-view, Overdue and DST rules; note and event rules;
  - repositories on an in-memory Room database (transactions, list deletion, Undo, reminders, LIKE escaping);
  - persistence across a database restart, and the schema identity check;
  - Robolectric Compose flows: quick add, complete and Undo; task edit; note autosave across activity recreation; event creation and validation; cross-module search; first run; tablet two-pane; light theme at 200% font.
- **JVM, notes and sync:** `RichNotesTest` (documents, pictures and limits, search, conflict resolution, meeting notes), `MigrationTest` (1→2, 2→3, 3→4), `SyncEngineTest` and `WireMapperTest` (contract behavior against MockWebServer).
- **Instrumented** (`connectedDebugAndroidTest`): text-entry dialogs (create task from note, new list, add task from the calendar), and `DialogFlowsTest` with the real WebView editor: a Windows document opens and saves unchanged, checklist → task, linked task and event chips, delete confirmation, comparing and combining a note conflict, and meeting notes from an event. Robolectric can't idle a Compose `AlertDialog` that contains a text field and has no WebView, so these run on an emulator.
- **Manual release matrix** (from the spec): small phone (360dp), large phone, 7–11" tablet, portrait and landscape, TalkBack, keyboard, notifications denied, airplane mode, DST change, low storage, fresh and upgraded installs.

## License

[MIT](LICENSE)
