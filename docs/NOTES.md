# Notes on Android

Rich-text notes, compatible with the Windows app.

- **Editor.** The shared [notes editor](https://github.com/nikita4sea/alondra1-backend/tree/main/contract/notes-editor) (alondra1-backend `contract/notes-editor`) bundle runs in the system WebView:
  - it is served from `assets/notes-editor` through `WebViewAssetLoader`;
  - pictures come from `files/notes-assets`;
  - every other request and every navigation is blocked.

  On a phone the toolbar sits above the keyboard and buttons are 44 px.
- **Data (Room schema 3).**
  - `notes` gains `docJson`, `folderId`, `favorite`, `archivedUtc`, `trashedUtc`, `assetsJson`, `linksJson` and `remoteJson`.
  - New tables: `note_folders`, `note_assets` and the FTS4 index `notes_fts`.
  - `MIGRATION_2_3` keeps every existing note; plain-text notes open as paragraphs.
- **Pictures.**
  - Accepted: PNG, JPEG, GIF and WebP, checked from their bytes; up to 20 MB each and 100 MB per note.
  - Each picture is stored once by its SHA-256, the same id the Windows app and the server use.
  - Sources: the system photo picker, paste, or Android's share sheet. Sharing text or pictures to Alondra1 creates a note.
- **Saving.** The editor reports changes about 0.45 s after typing stops. Leaving the note, the back button and the app going to the background save immediately.
- **Trash.** Notes in Trash are deleted for good after 30 days, checked at start. Deleting a note or emptying Trash asks first.
- **Sync.**
  - `note_folder` and `note` sync with Cloud Sync.
  - Pictures are uploaded before their note and downloaded after a pull.
  - Edits made on two devices keep both versions as a **(conflict copy)**.
- **Links.** Tapping a linked task or event chip (or a task link in the text) opens it.
- **Meeting notes.** An event's screen has **Meeting notes**: it opens the note linked to the event, or creates one from the same template as Windows (date and time, description, Agenda, Notes, Action items).
- **Sync conflicts.** A note changed on two devices keeps both versions (see Sync). A banner above the note, or **Compare** in Settings → Sync issues, opens the comparison, where you can keep either version, **Combine both** into the note, or keep both as separate notes. The conflict copy goes to Trash. Room schema 4 adds `sync_issues.copyId`.
- **Fallback.** Without a working system WebView, notes use the plain-text editor, and notes with formatting open read-only so nothing is lost.

Not on Android yet:
- Creating notes from email (the Android app has no mail).
- Linking contacts.
- Loading web pictures pasted into a note: save the picture first, then add it.
- Merged table cells.
- Column resizing by touch (column widths from Windows are kept).

## Tests

- `RichNotesTest` and `MigrationTest` (JVM).
- `DialogFlowsTest` (on a device or emulator) opens a Windows-format document in the real editor and checks it saves unchanged, and turns a checklist item into a task, opens linked tasks and events, and checks that permanent deletion asks first, combines a sync conflict from the compare screen, and creates meeting notes from an event.
