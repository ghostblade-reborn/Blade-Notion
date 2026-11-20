# GhostBlade Notion — Round 2 · Team Two (features/UI) report

**Scope owned:** `src/com/ghostblade/notion/MainActivity.kt`, `src/com/ghostblade/notion/Ui.kt`, this file. No other files edited.
**Build:** `./build.sh` **PASSED** (exit 0) with Team One's Round 2 data layer in place. Output `GhostBladeNotion-v0.2.apk`, 705,558 bytes, SHA-256 `dd7512bd11c9f3f49b96d47df919b740df747e96eb469bf92d1086ab44e3fefd`. Only warnings: the pre-existing `onBackPressed` deprecation (Round 1 finding N-10). `aapt2 dump permissions` on the built APK: **zero permissions**; manifest still has 0 `uses-permission`. No new permissions, no network/WebView code.

**Important timing note:** Team One's data layer landed *during* this pass. The first check of Store.kt showed the v0.1 API; a re-check before building showed the new API, so all wiring below uses the **real** Round 2 Store API — no stubs remain:
- `Store.updateScript(id, title?, body?, language?, date?)` — null = unchanged, `date = ""` clears the date, validates the date before mutating, bumps `updatedAt`.
- `Store.linkTaskToScript / unlinkTaskFromScript / scriptsForTask / tasksForScript` — many-to-many, persisted via `GTask.linkedScriptIds` + `Script.linkedTaskIds`.

All link call sites in the UI go through three private helpers in MainActivity (`scriptsLinkedToTask`, `tasksLinkedToScript`, `linkTaskToScript`, `unlinkTaskFromScript`) so the wiring lives in one marked section.

---

## Per-feature status

### 1. JSON export/import — ✅ DONE
- **Where:** Home → "Backup (JSON)" section; `exportShare()`, `exportToFile()`, `importFromFile()`, `onActivityResult()` in MainActivity.kt.
- **Export (share):** `ACTION_SEND` with type `application/json`, `EXTRA_TEXT = store.toJsonString()`, via a chooser.
- **Export (file):** `ACTION_CREATE_DOCUMENT` (type `application/json`, suggested name `ghostnotion-backup.json`); result written through `contentResolver.openOutputStream(uri, "w")`.
- **Import:** `ACTION_OPEN_DOCUMENT` (type `*/*`); text read via `contentResolver.openInputStream`. **Malformed-file guards, in order:** empty file → reject; not parseable as a JSON object (`org.json.JSONObject`) → reject; object carrying none of `channels`/`scripts`/`ideas`/`tasks` → reject. Only then `store.loadFromJsonString(text)` — which itself parses into temporary lists first (verified in Store.kt), so a rejected file leaves live data untouched. On success: pending undo cleared, `persistStore()`, toast "Import complete", back to Home.
- System pickers only — no direct file access, no storage permission.

### 2. Search screen — ✅ DONE
- **Where:** `showSearch()`; entry points: "🔍 Search" button in the Home nav row.
- In-memory filter over channels (name), scripts (title + body), ideas (title + notes), tasks (title), case-insensitive, live on every keystroke (`TextWatcher`). Results are cards with a type chip (CHANNEL/SCRIPT/IDEA/TASK), title, and a snippet/sub-line; tapping opens the item's screen (channel → `showChannel`, script → `showScript`, idea → `showIdeaDetail`, task → `showTaskDetail`). Empty query shows a hint; zero hits shows "No matches".

### 3. Delete / rename with undo — ✅ DONE (channels, scripts, ideas, tasks)
- **Where:** `cardHeader()` (title + "⋯" button), `showItemMenu()`, long-press on every list card, `deleteChannelWithUndo / deleteScriptWithUndo / deleteIdeaWithUndo / deleteTaskWithUndo`, `performUndo()`, `buildUndoBar()/showUndoBar()/hideUndoBar()`, rename dialogs (`renameChannelDialog`, `renameIdeaDialog`, `renameTaskDialog`; scripts use the full edit screen, feature 6).
- **Menus:** every channel card (Home), script card (Channel), idea card (Ideas), task card (Home/Channel) has a "⋯" button *and* long-press opening the same menu (Open / Rename-or-Edit / Delete as applicable). Channel and Script screens also have explicit Rename/Delete buttons.
- **Delete mechanics (via public Store state, documented as instructed):** Store v0.2 has no remove API, so delete = index lookup + `MutableList.removeAt(index)` on `store.channels/scripts/ideas/tasks` + `persistStore()`. Link consistency is maintained around removal: script deletes clear the idea's `linkedScriptId` and unlink all linked tasks via `Store.unlinkTaskFromScript` *before* removal; idea deletes clear the script's `linkedIdeaId`; task deletes unlink all linked scripts first.
- **Undo:** a bottom undo bar ("… deleted" + UNDO, auto-hides after 6 s) keeps an `UndoEntry` snapshot (the removed object + original index + cleared link ids). Undo re-inserts at `min(originalIndex, list.size)`, restores the idea link, re-links task↔script pairs via `Store.linkTaskToScript`, persists, and re-renders the current screen.
- **Known semantics (by design, flagged for coordinator):** deleting a channel does **not** cascade — its scripts/tasks keep their `channelId` and become visible again if the delete is undone; if not undone they remain in the JSON but unreachable from the UI. A Store-level cascade/orphan policy is a data-layer decision for a later round.
- **Rename:** channels and tasks via dialogs (task dialog includes due-date with validation + date picker); ideas via a title+notes dialog; scripts via the edit screen (below).

### 4. Tappable bidirectional idea↔script links — ✅ DONE (fixes N-5)
- Idea card (Ideas list) and Idea detail screen: linked state is now a button "✓ Linked script: <title> — tap to open" → `showScript(...)`; the detail screen also shows a full linked-script card (title + language/status chips) that opens the script.
- Script screen: "💡 From idea: <title> — tap to open" is a button → `showIdeaDetail(...)`. New `showIdeaDetail()` screen (title, notes, channel picker, linked script / start-script, rename, delete) gives the idea side a real destination.

### 5. Real language selector — ✅ DONE (fixes N-4)
- The empty `refreshLang()` stub is **deleted**. Both the new-script editor and the script edit screen use two toggle chips built with new `Ui.toggleButton()` / `Ui.setToggle()` helpers (Ui.kt): selected = gold pill + dark bold text, unselected = dark chip + light text, restyled **in place** on tap, so the selected language is always visible. Selection also feeds draft autosave.

### 6. Channel picker + date picker — ✅ DONE
- **Channel picker** (`pickChannel()` — AlertDialog list of channels) in: the new-script editor (button showing current channel; the choice is what `addScript` saves to — this also makes the formerly dead `editorChannelId` state real, fixing N-8), the script edit screen (channel move), the idea add form (replaces v0.1's always-first-channel behaviour; selected channel is passed to `Store.addIdea`), and the idea detail screen (reassign an existing idea's channel).
- **Date picker** (`pickDate()` — framework `DatePickerDialog`, seeded from the current field value when valid, else today; writes strict `yyyy-MM-dd`) alongside every typed date field: new-script editor, script edit screen, task add form, task rename dialog.
- **Script edit screen** (`showScriptEdit()`): title, language chips, channel picker, date + picker. Title/language/date save through `Store.updateScript(...)` (blank date → `date = ""` clears it). ⚠️ **One remaining direct mutation:** `updateScript` has no `channelId` parameter, so a channel move is `script.channelId = editChannelId` on the public var — marked with a NOTE comment at the call site. If Team One adds channel support to `updateScript`, route that line through it.

### 7. Editor autosave / draft retention — ✅ DONE (fixes N-6)
- Drafts live in SharedPreferences file `ghostnotion-drafts` (offline, no permission), as small JSON objects, saved on **every text change** (TextWatcher), on language/channel/date-picker changes, and flushed again in `onPause()` via a `draftSaver` hook each editor screen installs.
- **New-script editor** (key `draft_new_script`): title, body, date, language, channelId. Restored on reopen with a "↩ Draft restored" note; cleared on successful save or via the explicit "Discard draft" button.
- **Script-body field** (key `draft_body_<scriptId>`): restored on the script screen when it differs from the saved body, with an explanatory note; cleared on "Save body".
- **Script edit screen** (key `draft_edit_<scriptId>`): title/date/language/channel restored when they differ from the saved script.

### 8. Task↔script linking UI — ✅ DONE (on Team One's real API)
- **Script screen → "Tasks for this script":** lists linked tasks (`Store.tasksForScript`) — tap a task card to open its detail; "🔗 Link existing task" picker (excludes already-linked tasks); "Add task linked to this script" form (creates the task in the script's channel via `Store.addTask`, then `Store.linkTaskToScript`).
- **Task side:** task rows (Home/Channel) show up to 3 "🔗 Script: <title> — tap to open" lines (+ a "+n more" note) that open the script without toggling the task. New `showTaskDetail()` screen: done toggle, rename/edit, delete-with-undo, and a "Linked scripts" section listing every linked script (card tap opens it, per-script Unlink button) plus a "🔗 Link to a script" picker (excludes already-linked).
- Many-to-many semantics match the Store API (a task can link several scripts and vice versa).

### Cross-cutting: catch hygiene (N-7)
- Every `catch (t: Throwable)` on the paths touched is gone — the new MainActivity contains **zero** `Throwable` catches. Persistence failures surface as a toast in `persistStore()` (`IOException` → "Could not save to disk…"); toggle/mark-final/link/update failures toast instead of failing silently.

---

## Store API still needed / notes for the coordinator
1. **`updateScript` has no `channelId` parameter** — the script edit screen still assigns `script.channelId` directly for channel moves (single marked line). Optional API addition; nothing else depends on it.
2. **No Store remove/restore API** — delete/undo is implemented at the UI level through the public `MutableList`s + link cleanup/relink via the Store link API, as the task brief sanctioned. If Team One later adds `removeX` methods, the four `deleteXWithUndo` functions are the only call sites to rewire.
3. **Channel-delete orphan policy** is undecided (see feature 3): scripts/tasks of a deleted channel stay in the JSON, unreachable. Coordinator/the owner decision for a later round.
4. `pickScript()` helper in MainActivity is now unused (the task-detail picker inlines its candidate filtering) — harmless, may be removed in a cleanup pass.

## Recommended COMPARISON.md deltas (recommendation only — coordinator merges)
| Row | v0.1 status | Recommended v0.2 status | Why |
|---|---|---|---|
| Search across workspace | missing | **present** | In-memory search UI over scripts/ideas/tasks/channels (feature 2). FTS still absent — fine at this scale. |
| Import / export | partial | **present** (JSON) | User-facing JSON export (share + file) and import with malformed-file guards. Markdown/CSV/PDF still absent — keep that caveat in the row text. |
| Interlinking (ideas ↔ scripts ↔ tasks ↔ channels) | partial | **present** | Idea↔script navigable both ways in the UI; task↔script links now exist in the model (both sides), are persisted, and are navigable both ways; channel links unchanged. Idea↔task direct links still don't exist — if the row demands all six pairings, keep **partial** with updated text; my call: the four entity kinds are now all interconnected via channels/scripts, so present-with-note is defensible either way. |
| Script language Bangla/English | present | present (update bug note) | N-4 fixed: real selected-state chips in both editors. |
| Script dates | present | present (update bug note) | DatePickerDialog added alongside typed fields everywhere dates are entered. |
| Video Ideas | present | present (update bug note) | Channel picker in the add form + idea detail; no longer always-first-channel. |
| Offline / local storage | present | present (no change from UI side) | Still zero permissions (verified on the built APK). |
| Bugs table | — | — | Mark **resolved**: P2 "no user-facing export/import", P2 "no search", P3 language toggle UX, P3 idea→channel assignment, P3 typed-only dates, P3 "no delete/rename/edit… no undo", and Monitor findings N-4, N-5, N-6, N-8. N-7 addressed on all UI paths (no `Throwable` catches remain in MainActivity). P2-2 (script body edits bypassing Store) is resolved from the UI side by routing through `Store.updateScript` — status belongs to Team One's report. |

*Team Two — Round 2 features/UI pass complete. Build green, zero permissions, no files outside my ownership touched.*
