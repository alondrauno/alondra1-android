# Alondra1 cloud on Android (optional)

The app works fully without an account. **Settings → Account & Cloud** is shown only when the build has an API address.

- **Debug builds** use `http://10.0.2.2:8080`, which is the host PC as seen from the emulator. Cleartext is allowed only to 10.0.2.2, 127.0.0.1 and localhost.
- **Release builds** need `-PapiBaseUrl=https://…`.

## What it does

- **Sign-in.** You sign in with a 6-digit code sent by email; there is no password. Tokens are kept in EncryptedSharedPreferences.
- **Plans.** The server's entitlements decide what is allowed. Payment is a placeholder; see alondra1-backend `docs/BILLING_SPEC.md`.
- **Cloud Sync:**
  - **What syncs:** tasks, lists, notes and calendar events, with the same protocol as the Windows app.
  - **Turning it on.** You see a merge preview first. Nothing is replaced or deleted, and the device's never-synced default list hands its tasks over to the cloud default list.
  - **Recording.** Once sync is set up, local changes are queued in `sync_outbox`, in the same Room transaction as the change.
  - **Order.** Each sync run pushes first, then pulls. A pull never overwrites unsent edits.
  - **When it runs.** WorkManager runs sync with a network constraint, after local changes, when the app starts, and when the connection comes back.
  - **Conflicts keep both versions.** The cloud version keeps the id, the local version becomes "… (conflict copy)", and the conflict is listed as a sync issue. The copy is sent in the same run.
  - **Errors.** A plan that ended (`read_only`) or offline status never deletes local data. `TENANT_NOT_FOUND` turns sync off and keeps the data.
- **Mail Archive** is available in the Windows app only.

## Code

| Part | Where |
| --- | --- |
| Sync engine, wire format, outbox recording, worker | `app/src/main/java/com/alondra1/app/data/sync/` (`SyncEngine`, `WireMapper`, `OutboxRecorder`, `SyncWorker`) |
| API client, session, account and settings UI | `auth/` (`ApiClient`, `SessionStore`, `AccountRepository`, `CloudAccountSection`) |
| Sync tables | `data/local/SyncEntities.kt`; Room schema 2 adds them (`MIGRATION_1_2`) |

## Tests

- `SyncEngineTest` and `WireMapperTest` (JVM, MockWebServer) cover the contract behaviour.
- To check against a real backend, run the devserver (`go run ./cmd/devserver -addr 127.0.0.1:8080 -dir .dev -grant-paid` in alondra1-backend), install the debug build on an emulator, and sign in. The codes are written to `.dev/codes.json` instead of being emailed.
