# Feature map: Alondra1 for Android

A complete, structured list of what the Android app does: high-level **capabilities**, the **features** inside each, and every **atomic feature** (one behavior you can test). Each feature names the code that implements it, the tests that cover it and the document that explains it. Use it to plan and add a feature **without scanning the code**: find the closest existing feature, follow its pointers, and use the [recipes](#recipes-adding-a-feature) at the end.

- User-facing description: [README](../README.md), [NOTES.md](NOTES.md), [CLOUD.md](CLOUD.md). Scope rules: [CLIENT-REQUIREMENTS.md](CLIENT-REQUIREMENTS.md).
- The same capabilities on Windows and in the backend: [alondra1-windows FEATURE-MAP](https://github.com/alondrauno/alondra1-windows/blob/main/docs/FEATURE-MAP.md) and [alondra1-backend FEATURE-MAP](https://github.com/alondrauno/alondra1-backend/blob/main/docs/FEATURE-MAP.md) (cross-platform parity table). IDs for shared features match the Windows map (`TODO-…`, `NOTE-…`, `SYNC-…`).
- Keep this file current: a change that adds, removes or changes a user-visible behavior updates its row here in the same commit.

**Status:** **Done**: implemented and tested. **Partial**: works with a stated limit. **Gap**: known missing piece.

**Paths** are relative to `app/src/main/java/com/alondra1/app/`. JVM tests are in `app/src/test/…`, device tests in `app/src/androidTest/…`.

## Capability overview

| # | Capability | Features | Main code |
| --- | --- | --- | --- |
| 1 | [App shell and navigation](#1-app-shell-and-navigation-shell) | Tabs, adaptive panes, deep links, onboarding, search | `navigation/AppNavigation.kt`, `MainActivity.kt`, `features/search`, `features/onboarding` |
| 2 | [To-do](#2-to-do-todo) | Tasks, lists, smart views, quick add, reminders, undo | `features/todo`, `data/repository/TodoRepository.kt`, `domain/TaskRules.kt` |
| 3 | [Notes](#3-notes-note) | Organization, rich editor, pictures, saving, search, links, sharing, sync conflicts | `features/notes`, `data/repository/NotesRepository.kt`, `NoteAssets.kt`, `domain/NoteMerge.kt` |
| 4 | [Calendar](#4-calendar-cal) | Month and agenda views, events, task dates, meeting notes | `features/calendar`, `data/repository/CalendarRepository.kt`, `domain/EventRules.kt` |
| 5 | [Reminders and notifications](#5-reminders-and-notifications-rem) | Channels, scheduling, permission, privacy | `data/notifications` |
| 6 | [Alondra1 account and cloud](#6-alondra1-account-and-cloud-cloud) | Sign-in, plan, devices | `auth/` |
| 7 | [Cloud Sync](#7-cloud-sync-sync) | Enable and merge, outbox, push and pull, conflicts, pictures, worker | `data/sync/` |
| 8 | [Settings, appearance and data](#8-settings-appearance-and-data-set) | Settings, theme, local data deletion, backup rules | `features/settings`, `data/settings`, `design/Theme.kt` |
| 9 | [Build and quality](#9-build-and-quality-ops) | Builds, signing, free vs. cloud builds, CI, schema check | `app/build.gradle.kts`, `.github/workflows` |

---

## 1. App shell and navigation (`SHELL`)

Code: `navigation/AppNavigation.kt` (`Routes`, `AppNavHost`, `LocalOpenNoteConflict`), `MainActivity.kt`, `ui/Common.kt`. Tests: `AppFlowsTest`, `TabletAndOnboardingTest` (Robolectric).

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| SHELL-NAV-01 | Three tabs: To-do, Notes, Calendar | Done | |
| SHELL-NAV-02 | Bottom bar on phones, navigation rail from 600 dp, list/detail panes from 840 dp | Done | |
| SHELL-NAV-03 | Deep links `alondra1://task|note|event/{id}` | Done | |
| SHELL-ONB-01 | First run: welcome, optionally start with example tasks | Done | `features/onboarding/WelcomeScreen.kt`. |
| SHELL-SRCH-01 | One search across tasks, notes (full text) and events | Done | `features/search`, `data/repository/SearchRepository.kt`. |
| SHELL-UNDO-01 | Snackbar Undo for complete, delete, trash | Done | |

## 2. To-do (`TODO`)

Code: `features/todo/` (`TodoScreen`, `TodoViewModel`, `TaskDetailScreen`), `data/repository/TodoRepository.kt`, `domain/TaskRules.kt`, `data/local/Entities.kt` (`TaskEntity`, `TaskListEntity`). Tests: `TaskRulesTest`, `RepositoriesTest`, `AppFlowsTest`, `DialogFlowsTest`. Contract: alondra1-backend `contract/schemas/todo-v1.schema.json`.

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| TODO-TASK-01 | Create, edit, delete (Undo), complete and reopen | Done | |
| TODO-TASK-02 | Title (1–500 chars), notes, important | Done | |
| TODO-TASK-03 | Steps (subtasks): add, check, reorder, remove; unknown keys of each step kept | Done | `TaskJson`, `TaskParityRepositoryTest`. |
| TODO-TASK-06 | File attachments | Gap | Out of scope for phase 1. |
| TODO-TASK-08 | Move a task to another list | Done | Task detail. |
| TODO-TASK-10 | A task can remember the note it came from (`sourceNoteId`) | Done | Checklist → task. |
| TODO-DATE-01 | Due date and optional time | Done | |
| TODO-DATE-02 | My Day (explicit; starts empty each day) | Done | |
| TODO-DATE-03 | Repeats: daily, weekdays, weekly, monthly, yearly, every N; series anchor (Jan 31 → Feb 28 → Mar 31), end date | Done | `domain/TaskModel.kt` (`Recurrence`, port of the Windows rules), `TaskParityTest`. |
| TODO-DATE-04 | Completing a repeating task moves it to the next occurrence (start date and reminders shift, steps reset) in one transaction; one completion record per occurrence | Done | `TaskCompletion`, `TodoRepository.setCompleted`. |
| TODO-DATE-05 | Start date | Done | |
| TODO-HIST-01 | Completion history (local, synced with the completing change as `append_completion`, received from other devices) | Done | `completion_records`; Undo removes an unsent entry. |
| TODO-REM-01 | Several reminders per task (definitions synced; delivery state stays on the device) | Done | One WorkManager job per reminder, no duplicates. |
| TODO-LIST-01 | Create and rename lists; reorder with Move up/down | Done | |
| TODO-LIST-02 | Delete a list (asks): move its tasks to Tasks (recommended) or delete them | Done | Default list can't be deleted. |
| TODO-LIST-03 | List groups: create, rename, remove (lists are kept, ungrouped), move a list to a group, collapse (device-local) | Done | Synced as kind `group`. |
| TODO-VIEW-01 | Smart views: My Day, Important, Planned, Upcoming, Overdue, All, Completed, Unscheduled | Done | Same rules as Windows. |
| TODO-VIEW-02 | Sort | Done | |
| TODO-VIEW-03 | All Tasks filter: Open, All, Completed | Done | |
| TODO-VIEW-06 | Counts computed from the same rules as the lists | Done | |
| TODO-ADD-03 | Quick add in context (My Day, Important, list, default list) | Done | |
| TODO-ADD-01 | Natural-language quick add | Gap | |
| TODO-DATA-01 | Export or import | Gap | Cloud export via the backend. |

## 3. Notes (`NOTE`)

Code: `features/notes/` (`NotesScreens.kt`: list, folders, editor screens, view models; `RichNoteEditor.kt`: WebView bridge; `NoteConflictScreen.kt`; `ShareToNote.kt`), `data/repository/NotesRepository.kt`, `data/repository/NoteAssets.kt`, `domain/NoteRules.kt`, `domain/NoteMerge.kt`, editor bundle `app/src/main/assets/notes-editor` (built in alondra1-backend `contract/notes-editor`). Room: `notes`, `note_folders`, `note_assets`, FTS4 `notes_fts`. Tests: `RichNotesTest`, `MigrationTest`, `DialogFlowsTest`. Docs: [NOTES.md](NOTES.md).

### NOTE-ORG: Organization

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-ORG-02 | Views: All notes, Favorites, folders, Archive, Trash (chooser with counts) | Done | |
| NOTE-ORG-03 | New note (starts in the current folder or as a favorite) | Done | |
| NOTE-ORG-04 | Favorite, pin, archive | Done | |
| NOTE-ORG-05 | Move to a folder (menu) | Done | No drag and drop. |
| NOTE-ORG-06 | Folders: new, rename, delete (notes kept) by long-press | Done | |
| NOTE-ORG-07 | Nested folders UI | Gap | Nested folders from Windows are kept. |
| NOTE-ORG-08 | Trash with Undo; restore; delete permanently (asks); empty (asks); 30-day retention | Done | Retention is not configurable. |
| NOTE-ORG-09 | Sort by last edited, created, title | Done | |
| NOTE-ORG-10 | List previews, dates, picture thumbnails | Done | |

### NOTE-EDIT: Editor

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-EDIT-01 | Same rich editor as Windows in the system WebView (touch layout, toolbar above the keyboard) | Done | All formatting of NOTE-EDIT/LIST/TBL on Windows. |
| NOTE-EDIT-08 | Unknown content kept; newer schema opens read-only | Done | |
| NOTE-EDIT-12 | Fallback plain-text editor without a working WebView; rich notes then read-only | Done | |
| NOTE-LIST-03 | Checklist item → task, linked | Done | |
| NOTE-TBL-04 | Column resizing by touch | Gap | Widths from Windows are kept. |

### NOTE-PIC: Pictures

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-PIC-01 | Add from the system photo picker, by paste, or the share sheet | Done | No drag and drop. |
| NOTE-PIC-02 | PNG, JPEG, GIF, WebP checked from the bytes; stored once by SHA-256 in `files/notes-assets` | Done | |
| NOTE-PIC-05 | Limits: 20 MB per picture, 100 MB per note | Done | |
| NOTE-PIC-06 | Loading web pictures pasted into a note | Gap | |

### NOTE-SAVE, NOTE-SRCH

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-SAVE-01 | Autosave about 0.45 s after typing stops | Done | |
| NOTE-SAVE-02 | Immediate save on back, leaving the note, app going to the background | Done | |
| NOTE-SAVE-04 | 2 MB limit for text and tables | Done | |
| NOTE-SRCH-01 | Full-text search (FTS4, prefix per word) | Done | Also in the global search. |

### NOTE-LINK and sharing

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-LINK-01 | Link a task or an event (**Link a task or event**) | Done | No contacts or mail on Android. |
| NOTE-LINK-02 | Link chips open the task or event; × removes the link | Done | |
| NOTE-LINK-04 | Meeting notes from an event | Done | See CAL-MEET-01. |
| NOTE-SHARE-01 | Share text or pictures from other apps → new note | Done | `ShareToNote.kt`, manifest `SEND` filters. |
| NOTE-SHARE-02 | Share a note as text | Done | |
| NOTE-IO-01 | Export as HTML or Markdown; import | Gap | Windows only. |

### NOTE-SYNC: Sync and conflicts

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| NOTE-SYNC-01 | Notes, folders and pictures sync | Done | |
| NOTE-SYNC-02 | Conflicts keep both; the sync issue remembers the conflict copy (`copyId`) | Done | Room schema 4. |
| NOTE-SYNC-03 | Banner above the note; **Compare** in Settings → Sync issues | Done | |
| NOTE-SYNC-04 | Compare screen: both versions with lines only in one version marked | Done | `NoteConflictScreen.kt`. |
| NOTE-SYNC-05 | Keep other device's, keep this device's, combine both, keep both | Done | Same rules as Windows (`NoteMerge`). |

## 4. Calendar (`CAL`)

Code: `features/calendar/CalendarScreens.kt` (`CalendarScreen`, `EventEditorScreen`, `EventEditorViewModel`), `data/repository/CalendarRepository.kt`, `domain/EventRules.kt`, `EventEntity`. Tests: `NoteAndEventRulesTest`, `RepositoriesTest`, `AppFlowsTest`, `DialogFlowsTest`. Contract: alondra1-backend `contract/schemas/calendar-v1.schema.json`.

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| CAL-VIEW-01 | Month grid with a day panel | Done | |
| CAL-VIEW-04 | Agenda of the next 60 days | Done | |
| CAL-VIEW-05 | Filter Events, Tasks or Both; task due dates in their own color, open the task | Done | Tasks are never copied as events. |
| CAL-VIEW-06 | Add a task or an event from the selected day | Done | |
| CAL-EDIT-01 | Create, edit, delete (Undo) events: title, description, all day, start, end, reminder | Done | |
| CAL-EDIT-03 | Timed events store UTC + time zone; all-day events store dates (end inclusive) | Done | |
| CAL-EDIT-04 | End must not be before start; moving the start keeps the duration | Done | |
| CAL-MEET-01 | **Meeting notes** on a saved event: opens the linked note or creates one from the meeting template | Done | Template shared with Windows. |
| CAL-SYS-01 | System calendar access | Gap | By design; no calendar permission. |
| CAL-REP-01 | Repeating events | Gap | |

## 5. Reminders and notifications (`REM`)

Code: `data/notifications/` (`Notifications.kt`, `WorkManagerReminderScheduler.kt`, `TimeChangeReceiver.kt`). Tests: `RepositoriesTest`, `TaskRulesTest`.

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| REM-CH-01 | Separate channels for task and event reminders | Done | |
| REM-SCH-01 | One unique pending job per task reminder and per event; edits replan them; complete or delete cancels them | Done | Jobs of schema 4 (one per task) are cancelled too. |
| REM-SCH-02 | Approximate delivery (WorkManager); survives reboots; re-planned on time zone or clock change | Done | No exact alarms. |
| REM-SCH-03 | All-day event reminders count from 09:00 on the start day | Done | |
| REM-PERM-01 | `POST_NOTIFICATIONS` asked only when a reminder is turned on; denial explained | Done | |
| REM-PRIV-01 | Lock-screen privacy (only "Alondra1 reminder") on by default | Done | Settings toggle. |

## 6. Alondra1 account and cloud (`CLOUD`)

Code: `auth/` (`ApiClient`, `SessionStore`, `AccountRepository`, `CloudAccountSection`, `AccountSection`). Tests: `SyncEngineTest`, `WireMapperTest`. Docs: [CLOUD.md](CLOUD.md).

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| CLOUD-AUTH-01 | Sign in with a 6-digit email code | Done | Shown only in builds with an API URL. |
| CLOUD-AUTH-02 | Tokens in EncryptedSharedPreferences | Done | |
| CLOUD-PLAN-01 | Plan and entitlements from the server | Done | |
| CLOUD-CFG-01 | Release: signed discovery of the flavor's environment; debug: local dev server `http://10.0.2.2:8080` (`-PapiBaseUrl` overrides) | Done | A fixed address must be loopback/emulator host or a host of the build's environment. |
| CLOUD-ENV-01 | Product flavors `prod` and `dev`: application id `com.alondra1.app(.dev)`, name *Alondra1 (Dev)*, API origin, discovery sources and trusted keys | Done | `BuildEnvironment`, `app/build.gradle.kts`. |
| CLOUD-DISC-01 | Signed discovery manifest: Ed25519 (own implementation, minSdk 29), environment, validity, monotonic version, host allowlist | Done | Passes the 32 shared fixture cases (`src/test/resources/discovery-v1`). |
| CLOUD-DISC-02 | Primary, mirror, verified cache in DataStore, bundled list until its expiry; no redirects, conditional GET; refresh every 15 min with jitter and after an offline sync | Done | `CloudEndpoints`, `SyncWorker`. |
| CLOUD-DISC-03 | Failover on transport errors, timeouts, 502/503/504 (Retry-After); never on 401/403/404/429; same body; no refresh replay | Done | `FailoverInterceptor`, `DiscoveryTest`. |
| CLOUD-SPACE-01 | Choose among several spaces; per-space data | Gap | Personal space (spec v1.1 §8.3, P0 personal / P1 shared). |
| MAIL-ARCH-01 | Mail Archive | Gap | Windows only (no mail on Android). |

## 7. Cloud Sync (`SYNC`)

Code: `data/sync/` (`SyncEngine`, `WireMapper`, `OutboxRecorder`, `ChangeRecorder`, `SyncWorker`), `data/local/SyncEntities.kt`. Tests: `SyncEngineTest`, `WireMapperTest`, `RichNotesTest`.

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| SYNC-KIND-01 | Kinds: list groups, task lists, tasks (steps, repeat, start date, reminders), note folders, notes (+ pictures), calendar events; completion records | Done | Groups before lists in a push. |
| SYNC-ON-01 | Merge preview, then download and merge (nothing replaced or deleted) | Done | |
| SYNC-ON-02 | Never-synced default list hands its tasks to the cloud default list | Done | |
| SYNC-OUT-01 | Local changes queued in `sync_outbox` in the same Room transaction | Done | |
| SYNC-RUN-01 | Push first, then pull; unknown server fields kept (`remoteJson`) | Done | |
| SYNC-RUN-02 | A pull never overwrites unsent edits | Done | |
| SYNC-RUN-04 | WorkManager with a network constraint: after changes, at start, when the network returns | Done | |
| SYNC-CONF-01 | Conflicts keep both; copy sent in the same run | Done | |
| SYNC-CONF-02 | Sync issues list (OK to dismiss; Compare for notes) | Done | |
| SYNC-ERR-01 | Plan ended or offline never deletes local data; `TENANT_NOT_FOUND` turns sync off | Done | |

## 8. Settings, appearance and data (`SET`)

Code: `features/settings/SettingsScreen.kt`, `data/settings/SettingsRepository.kt` (DataStore), `design/Theme.kt`, `res/xml/data_extraction_rules.xml`. Tests: `AppFlowsTest`, `RepositoriesTest`.

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| SET-GEN-01 | Theme: dark (default), light, follow system | Done | |
| SET-GEN-02 | First day of week, time format, default list for new tasks | Done | |
| SET-PRIV-01 | Delete local data: shows counts, type DELETE to confirm | Done | |
| SET-PRIV-02 | No OS cloud backup; device-to-device transfer of data but not tokens | Done | |
| SET-PRIV-03 | No analytics, crash upload or ads; free builds make no network request | Done | |
| SET-ACC-01 | Accessibility: TalkBack labels, 200 % font tested | Done | Robolectric test at 200 %. |

## 9. Build and quality (`OPS`)

| ID | Atomic feature | Status | Notes |
| --- | --- | --- | --- |
| OPS-BUILD-01 | Flavors × build types (prodDebug, prodRelease, devDebug, devRelease); min SDK 29, target 35 | Done | Check the current Play target-API policy before publishing. |
| OPS-ENV-01 | `scripts/check-environment.sh`: a bundle of one environment contains no endpoint of the other, prod no debug server | Done | Run in CI and before publishing. |
| OPS-REL-01 | Dev APK (debug-signed) on every main push as a workflow artifact | Done | `release.yml`. |
| OPS-REL-02 | Prod bundle on `vX.Y.Z`, signed with the upload key and published only after approval in the `prod` environment; Play internal track when configured | Done | Secrets `ANDROID_UPLOAD_*`, `PLAY_SERVICE_ACCOUNT_JSON`. |
| OPS-SIGN-01 | Release signing only from CI environment variables | Done | |
| OPS-DB-01 | Room schema exported to `app/schemas`; `committedSchemaMatchesTheEntities` fails without a new version and migration | Done | Current version **5**. |
| OPS-CI-01 | CI: lint, JVM tests of both flavors, dev APK, both release bundles with the environment check, emulator tests (`connectedProdDebugAndroidTest`) | Done | Actions pinned to commit SHAs. |

---

## Data stores

| Store | Where | Notes |
| --- | --- | --- |
| Room `alondra1.db` | app private storage | Version 4. Migrations: 1→2 sync tables, 2→3 rich notes (+ FTS4), 3→4 `sync_issues.copyId`. |
| Note pictures | `files/notes-assets/<ab>/<sha256>` | Content-addressed. |
| Settings | DataStore | |
| Session tokens | EncryptedSharedPreferences | Excluded from device transfer. |

## Recipes: adding a feature

- **A task field** [`TODO-DATE-01`]: `TaskEntity` (+ Room migration and schema JSON; bump the version in `committedSchemaMatchesTheEntities`) → `TodoRepository` → `WireMapper` (both directions; keep `remoteJson` merging) → `TaskDetailScreen` → shared contract change first in alondra1-backend → `RepositoriesTest`, `WireMapperTest`.
- **A Room schema change** [`MIGRATION_3_4`]: entity change → `AppDatabase` version + `MIGRATION_x_y` appended to `MIGRATIONS` → build (exports `schemas/…/<n>.json`, commit it) → `MigrationTest` case → update the version in `RepositoriesTest.committedSchemaMatchesTheEntities`.
- **A note action** [`NOTE-ORG-04`]: `NotesRepository` method using `edit()` (records the change for sync) → `NotesViewModel` / `NoteEditorViewModel` → menu item in `NotesScreens.kt` → `RichNotesTest`.
- **A notes editor feature**: change the shared editor in alondra1-backend `contract/notes-editor`, run `npm run build` (copies the bundle into `app/src/main/assets/notes-editor`), commit the bundle here; new host messages go in `handleEditorMessage` (`NotesScreens.kt`).
- **A new screen** [`NoteConflictScreen`]: composable in `features/<area>` → route in `Routes` and `AppNavHost` (`navigation/AppNavigation.kt`) → navigation callbacks run on the main thread (`withContext(Dispatchers.Main)` after database work) → test in `DialogFlowsTest` (device) or `AppFlowsTest` (Robolectric).
- **A synced kind**: backend first (schema, OpenAPI, kind) → `EntityType` → `WireMapper` → `SyncEngine` (`applyRecord`, `keepBoth`, push and bootstrap) → `OutboxRecorder` hooks in the repository → `SyncEngineTest`.
- **A setting** [`SET-GEN-02`]: key in `SettingsRepository` → row in `SettingsScreen` → read it in the view model.

After any feature: update its row here, the README or module doc, and the backend parity table when the feature exists on more than one platform.
