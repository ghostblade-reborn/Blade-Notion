# GhostBlade Notion v0.2 — Monitoring Team Report (Round 2)

**Date:** 2026-11-20 (local time)
**Monitor role:** Independent verification only. No source file, manifest, build.sh, test, or COMPARISON.md was modified by the Monitoring Team. This report is the only file written. (build.sh does not touch `tests/`; my rebuilds only regenerated the APK outputs, byte-identical to the builders'.)
**Package:** `com.ghostblade.notion` · versionCode 2 · versionName 0.2 · minSdk 24 · targetSdk 34

## Verdict: **PASS-WITH-NOTES**

Every headline builder claim reproduces under my own runs: the build is now **fully byte-reproducible** (the Round 1 hash-instability problem is genuinely fixed), the JVM suite passes **125/125** on my own compile-and-run, and the zero-permission / fully-offline guarantee holds under all three checks, with `allowBackup="false"` now confirmed in the manifest **and** in the built APK. All four Round 1 P2s and findings N-1, N-4, N-5, N-6, N-8 are truly fixed in code (traced, not just grepped); N-7 is substantially fixed. No P1 (crash / data-loss in normal paths) was found.

The notes: COMPARISON.md's *arithmetic* was fixed but its *statuses* were not updated for Round 2 (it still calls search missing and import/export partial, and its bugs table lists fixed P2s as open); the Db write-through mirror silently diverges on UI-level direct mutations (renames, deletes, channel moves) because those bypass the Store sink; and channel deletion leaves orphaned scripts/tasks in the JSON with no user-visible disclosure. Details and severities below.

---

## 1. Independent verification table (Monitoring Team's own runs)

| # | Claim | Verified? | My evidence |
|---|---|---|---|
| 1 | Clean rebuild → `GhostBladeNotion-v0.2.apk`, 705,558 bytes, SHA-256 `dd7512bd…fefd` | ✅ YES | Pre-existing on-disk APK already matched. My rebuild #1 and #2 both exit 0, both **705,558 bytes**, both SHA-256 `dd7512bd11c9f3f49b96d47df919b740df747e96eb469bf92d1086ab44e3fefd` — exactly the builders' claimed hash. |
| 2 | Byte-identical across builds (`cmp`) | ✅ YES | `cmp` on my two builds: **identical**. All **14 zip entries** in both APKs are stamped **1980-01-01 00:00:00** with zero extra-field bytes (build.sh's `touch -d 1980` + `zip -X` on classes.dex, plus deterministic signing, works). Round 1 finding on reproducibility: **closed**. |
| 3 | `apksigner` verifies | ✅ YES | `apksigner verify --verbose`: **Verifies**, v2 true, v3 true, 1 signer. `aapt2 dump badging`: package `com.ghostblade.notion`, versionCode **2**, versionName **0.2**, minSdk 24, targetSdk 34. Only build warnings: javac source-8 obsolescence + the known `onBackPressed` deprecation (N-10, carried). |
| 4 | JVM tests: 125 checks, 0 failures (73 R1 + 52 new) | ✅ YES | My own compile (`kotlinc Models.kt Logic.kt Store.kt tests/TestMain.kt`, android.jar on cp, exit 0) and run (kotlin-stdlib jar on cp): **`125 checks, 0 failures`, exit 0**. PASS/FAIL lines are **identical** to `tests/TEST-RESULTS.txt` (125 PASS lines there too). Db compile-only check also reproduced: exit 0. |
| 5 | Zero permissions (a) manifest | ✅ YES | `grep -c uses-permission AndroidManifest.xml` = **0**; no "permission" string anywhere in the manifest. |
| 6 | Zero permissions (b) built APK | ✅ YES | `aapt2 dump permissions` on **my** built APK prints only `package: com.ghostblade.notion`, no permission lines. |
| 7 | Zero permissions (c) source scan | ✅ YES | Grep over all `.kt` for `java.net`, `HttpURLConnection`, `Socket`, `URL(`, `LocationManager`, `Firebase`, `analytics`, `WebView`, permission constants: **no hits** (one comment mention of "no WebView"). Full import list is Android framework UI/SQLite/content, `java.io`, `java.time`, `java.util`, and `org.json.JSONObject` (MainActivity only — drafts + import guard). Dex `strings`: the only "http" hit is the Kotlin stdlib's own youtrack.jetbrains.com URL in a stdlib message — not app code (same as Round 1). |
| 8 | `android:allowBackup="false"` | ✅ YES | Present in AndroidManifest.xml **and** confirmed in the built APK via `aapt2 dump xmltree`: `allowBackup=false`, `usesCleartextTraffic=false`. Round 1 finding N-3: **closed**. |
| 9 | No GitHub work | ✅ YES | Still no `.git` anywhere in the project tree; `github-staging/` still contains only `README.md` (but see finding R2-N3: that README is now stale). |

---

## 2. Round 1 findings — per-finding verdicts (code-traced)

### P2-1 Db honesty — ✅ FIXED, with one overstatement (see R2-N1)
- `Db.init()` now does `val opened = db` where `db` is `helper.writableDatabase`, then `check(opened.isOpen)`. The lazy-helper trap from v0.1 is gone: the DB file **is** created at runtime. It then installs `Db.upsertRecord` as `Store.recordSink` and calls `Store.syncRegisteredStoresToSink()` to backfill.
- Call order in `MainActivity.onCreate` is correct: `restoreStore()` → `seedIfEmpty()` → `initDbIfPresent()` → `persistStore()`. Anything created/loaded before the sink existed is covered by the backfill; `loadFromJsonString()` also re-syncs to the sink after a load.
- Store mutations genuinely write through: every Store CRUD/link/update method calls its `emitX()`, and the JVM tests verify the sink contract (emission on add/update/link, backfill, silence after uninstall) — all reproduced in my run.
- JSON remains the authoritative and only read path, and the COMPARISON offline row + Store/Db class docs now say exactly that. **Wording matches reality for Store-API mutations.** The overstatement — "every mutation" — is that UI-level direct mutations bypass the sink entirely (R2-N1).

### P2-2 `updateScript` — ✅ FIXED (Team One's specific question: YES)
- `Store.updateScript(id, title?, body?, language?, date?)` exists: returns false for a missing id; validates the date (`Logic.isValidDate`) **before any field is mutated** (invalid date → false, nothing changes — test-verified); `date = ""` clears the date; bumps `updatedAt` and emits to the sink on success.
- **Body-edit path confirmed:** `MainActivity.showScript()`'s "Save body" button calls `store.updateScript(script.id, body = bodyInput.text.toString())` (MainActivity.kt:1144). The old `setScriptBody()` direct assignment no longer exists anywhere.
- The edit screen also routes title/language/date through `updateScript(...)` (MainActivity.kt:1056). The one exception is the channel move — see §3.

### P2-3 Export / import — ✅ FIXED
- Home → "Backup (JSON)": **Share export** (`ACTION_SEND`, `application/json`, full state in `EXTRA_TEXT`), **Save to file** (`ACTION_CREATE_DOCUMENT`), **Import** (`ACTION_OPEN_DOCUMENT`). System pickers only; no storage permission.
- Import guards, traced in order: unreadable stream → toast, abort; empty/blank → reject; not parseable as a JSON object (`org.json.JSONObject`) → reject; object with none of `channels`/`scripts`/`ideas`/`tasks` → reject. Only then `store.loadFromJsonString(text)`, which itself parses into temporary lists first (a malformed payload leaves the live store untouched — test-verified). On success the pending undo is cleared (`hideUndoBar()`), the store is persisted, and the UI states up front that "Import replaces ALL current data". One guard gap remains — see R2-N4.

### P2-4 Search — ✅ FIXED
- `showSearch()` (Home → "🔍 Search"): live in-memory filter on every keystroke. Scripts match **title + body**, ideas **title + notes**, channels name, tasks title (tasks have no body field — title-only is complete for them). Case-insensitive. Empty query shows a hint (no crash), zero hits shows "No matches". Result cards are tappable to the item's screen (channel/script/idea-detail/task-detail).

### N-1 COMPARISON counts — ✅ FIXED (arithmetic); statuses now stale (see §4)
- My mechanical count of the table: **31 data rows — 9 present / 3 partial / 19 missing**, and the Counts section now states exactly that (its missing list also enumerates 19). The Round 1 arithmetic error is closed. However, the Counts header admits the table is still the "v0.1 table; statuses unchanged" — for a v0.2 build that is now the stale part (§4).

### N-4 Language selector — ✅ FIXED
- `refreshLang()` is gone (only a comment references it). Both the new-script editor and the script edit screen use `Ui.toggleButton()` / `Ui.setToggle()`: selected = gold pill + dark bold text, unselected = dark chip + light text, restyled **in place** on tap. The selected language is persistently visible and also feeds draft autosave.

### N-5 Tappable links — ✅ FIXED
- Idea side: idea cards and the new `showIdeaDetail()` screen show "✓ Linked script: \<title\> — tap to open" → `showScript(...)`; the detail screen also renders a full linked-script card that opens the script.
- Script side: "💡 From idea: \<title\> — tap to open" is a button → `showIdeaDetail(...)`. Both directions traced end-to-end.

### N-6 Draft retention — ✅ FIXED, with two minor gaps (R2-N6, R2-N7)
- Drafts live in SharedPreferences `ghostnotion-drafts` as JSON, saved on **every text change** (TextWatcher), on language/channel/date-picker changes, and flushed again in `onPause()` via the `draftSaver` hook. Three draft kinds: `draft_new_script` (title/body/date/language/channelId, restored with a "Draft restored" note, cleared on save or explicit Discard), `draft_body_<scriptId>` (restored when it differs from the saved body, cleared on "Save body"), `draft_edit_<scriptId>` (cleared on "Save changes"). Stale-channel restore is guarded (`findChannel` null-check). Process death no longer loses editor text.

### N-7 Blanket `catch (Throwable)` — ✅ SUBSTANTIALLY FIXED
- MainActivity now contains **zero** `catch (t: Throwable)` (grep-verified). The only remaining `Throwable` catch in the codebase is `Store.emit()` swallowing sink failures — justified and documented (mirror only; JSON is authoritative).
- `persistStore()` catches `IOException` specifically and toasts "Could not save to disk…"; toggle / mark-final / link / update failures toast instead of failing silently. Residual: many helpers still catch broad `Exception` and return empty/null silently, and `persistStore()`'s generic-`Exception` branch sets a `persistenceAvailable` flag that is **never read anywhere** (R2-N8). Crash-safe; no longer fully silent on the paths that matter.

### N-8 Dead `editorChannelId` — ✅ FIXED
- Now genuinely load-bearing: set when the editor opens, updated by the channel picker, read by the save path (`editorChannelId ?: channelId`), by draft save/restore, and by `onBackPressed`. (New dead code in exchange: `pickScript()` is now unused — disclosed by Team Two; R2-N9.)

### Also verified new in Round 2

**Delete / rename with undo (all four types)** — ✅ works as claimed. Traced one full delete + undo per type:
- *Channel:* `deleteChannelWithUndo` removes from `store.channels`, persists, undo re-inserts at `min(index, size)`. Rename via dialog.
- *Script:* before removal it clears the linked idea's `linkedScriptId` and unlinks every linked task **via the Store API** (which needs the script still present); undo re-inserts, restores the idea link, and re-links tasks from the snapshot's `clearedLinkIds`. (The removed Script object's own `linkedTaskIds` is correctly emptied by the unlink, so the snapshot list is the restore source — sound.)
- *Idea:* clears the script's `linkedIdeaId` before removal; undo restores it.
- *Task:* unlinks all linked scripts first; undo re-links from the snapshot.
All deletes/renames are reachable via "⋯" menus **and** long-press on every list card. Undo limitations: R2-N5.

**Channel-delete orphan behaviour** — ⚠️ real, and **not disclosed to the user**. Deleting a channel does not cascade: its scripts/tasks keep the dead `channelId` in the JSON. This is disclosed **only** in Team Two's report — not in any dialog (delete is immediate, no warning), not in COMPARISON's bugs table. It is also not quite "unreachable": orphaned **tasks still appear** in Home's "All tasks" list (it renders all tasks), and orphaned **scripts still appear in Search** (search iterates all scripts) and via task links; opening one renders fine with a fallback channel name. Only the channel screens lose them. Rated **P2** (R2-P2-1).

**Channel picker + DatePickerDialog** — ✅ present everywhere claimed: channel picker in the new-script editor, script edit screen, idea add form (no longer always-first-channel), and idea detail (reassign). `DatePickerDialog` (framework, seeded from the current valid value else today, writes strict `yyyy-MM-dd`) sits beside every typed date field: new-script, script edit, task add, task rename dialog.

**Task↔script linking, both directions** — ✅ present and consistent. Data layer: `Store.linkTaskToScript` / `unlinkTaskFromScript` maintain **both** fields (`GTask.linkedScriptIds` + `Script.linkedTaskIds`), reject duplicates/missing ids, repair one-way links; tests verify consistency and JSON round-trip preservation. UI both ways: script screen → "Tasks for this script" (linked list, "Link existing task" picker, add-and-link form); task side → task rows show tappable "🔗 Script:" lines, and `showTaskDetail()` lists linked scripts with per-script Unlink + a "Link to a script" picker. Minor asymmetry: the script screen's linked-task cards have no Unlink button (unlink lives on the task detail screen) — R2-N10.

**`updateScript` channelId gap** — ✅ exactly as described. There is **exactly one** direct `script.channelId =` assignment in MainActivity (line 1063, inside the edit screen's save), marked with the NOTE comment explaining `updateScript` has no `channelId` parameter. It persists correctly: `persistStore()` runs immediately after in the same save handler, and `updateScript` (called just before) already bumped `updatedAt`. Caveat: like all direct mutations it does not emit to the Db sink (R2-N1). (The `idea.channelId =` assignment in idea detail is a different entity — ideas have no Store update API at all.)

**Store JSON codec change** — ✅ verified. Store/Models/Logic contain **no** `org.json` (comments only); the pure-Kotlin codec in Store.kt serialises/parses the same schema. My test run confirms: a legacy v0.1-format JSON (no `linkedTaskIds`/`linkedScriptIds` keys, as written by the old org.json code) loads with empty link lists; round-trip preserves idea↔script and task↔script links and special characters (quotes, newline, backslash, and Bengali বাংলা unicode in the test fixture). MainActivity still uses `org.json.JSONObject` for drafts and the import guard — that's the Android framework's bundled copy, not a Store dependency, and does not affect the JVM-testability claim.

---

## 3. COMPARISON.md spot-check + delta verdicts

Rows whose wording changed in Round 2, verified against code:
- **Offline / local storage** — wording updated (write-through mirror, JSON authoritative). ✅ Accurate for Store-API mutations; "every mutation" overstates the UI direct-mutation paths (R2-N1). Status **present** remains correct.
- **Interlinking** — wording updated to add task↔script "at the data layer since v0.2 … but have no UI yet". The data-layer part was true when written, but the "no UI yet" clause is **already stale**: Team Two's UI shipped in this same round (§2). Status **partial** — see verdict below.
- Everything else in the table is unchanged v0.1 text — including rows that Round 2 has overtaken (Search still reads "No search UI… missing"; Import/export still reads "No user-facing export/import screen… partial") and a bugs table that still lists as open: "Script body editing: UI wiring pending" (wired — §2/P2-2), "No user-facing export/import" (fixed), "No search" (fixed), plus P3 rows for the language toggle, idea→channel assignment, typed-only dates, and "no delete/rename… no undo" (all fixed). The document's own title/header still says v0.1.

**My verdicts on Team Two's recommended deltas:**
| Row | Team Two recommends | Monitor verdict |
|---|---|---|
| Search across workspace | missing → **present** | ✅ **Agree — present.** Real search UI over channels/scripts/ideas/tasks, titles + bodies/notes where a body exists. Absence of FTS is fine at this scale and should be noted in the row text, not held against the status. |
| Import / export | partial → **present** (JSON only) | ✅ **Agree — present (JSON only)**, *provided* the row text is rewritten to say exactly that: user-facing JSON export (share + file) and guarded JSON import; Markdown/CSV/HTML/PDF still absent. The v0.1 "partial" meant "no user-facing flow at all", and that blocker is gone. |
| Interlinking | partial → **present** (Team Two itself hedges) | ❌ **Disagree — stay partial.** Task↔script is now fully present (data + UI, both directions) and idea↔script is navigable both ways, but **direct idea↔task pairing still does not exist** — one of the six pairings in the row's own "ideas ↔ scripts ↔ tasks ↔ channels" claim. Present-with-note would overclaim against the owner's end-goal design. Update the row *text* (task↔script now has UI; only idea↔task missing), keep the status. |

**Honest final counts if the coordinator applies my verdicts:** 31 rows — **11 present / 2 partial / 18 missing** (search: missing→present; import/export: partial→present; interlinking stays partial; database properties stays partial).

---

## 4. New findings, by severity

### P1 — crash / data-loss in normal paths
**None found.** Force-unwrap sweep: 3 `!!` in MainActivity — two on `date` immediately guarded by `isNullOrBlank()` checks on the same value, one on `bodyDraft` guarded by the `restoredBody` non-empty check. All safe. Empty lists, missing ids, and stale references on the new paths are null-guarded throughout (undo re-insertion clamps its index; undo relinking tolerates since-deleted partners).

### P2 — significant
| ID | Finding |
|---|---|
| R2-P2-1 | **Channel-delete orphans are undisclosed to the user and only half-unreachable.** Deleting a channel silently retains its scripts/tasks in the JSON under a dead `channelId`; no warning dialog, no COMPARISON bugs-table entry — disclosure exists only in Team Two's internal report. The orphans still surface via Home's all-tasks list and via Search, which contradicts both "deleted" and "unreachable". Needs a policy decision (cascade / orphan-inbox / block delete while non-empty) and, until then, at minimum a delete-confirmation that states what happens to the contents. No crash, no data loss — the data is retained, which is why this is P2 and not P1. |

### P3 — minor / documentation / robustness
| ID | Finding |
|---|---|
| R2-N1 | **Db mirror diverges on UI direct mutations.** Renames (`ch.name =`, idea/task dialog edits), all four deletes (`MutableList.removeAt` — the mirror row is never soft-deleted via `Db.softDelete`, so deleted records stay `alive=1` in `ghostnotion.db` forever), the script channel move, idea channel reassignment, and the direct link-field clears in the delete/undo paths never emit to the sink. Only Store-API calls write through. After an import, the mirror also keeps ghost rows from the previous dataset (upsert-only, never cleared). Harmless today because JSON is authoritative and Db is never read — but COMPARISON's "every mutation is written through" is not literally true, and the divergence will matter the day Db becomes the read path. Clean fix: Store-level `removeX`/`renameX` methods that emit (or emit a tombstone), which Team Two already flagged as the rewire points. |
| R2-N2 | **COMPARISON.md is materially stale for v0.2** (see §3): two statuses and at least six bugs-table rows describe v0.1, not the shipped build — mostly *under*claiming, plus the false "UI wiring pending" for `updateScript`. Apply §3's verdicts (→ 11/2/18) and refresh the bugs table. |
| R2-N3 | **`github-staging/README.md` is entirely v0.1**: status line, version facts (0.1 / versionCode 1 / `GhostBladeNotion-v0.1.apk` output), and a "Not yet" list that now wrongly includes search, export/import, and delete/rename. Staging only, nothing published — but it must be refreshed before any repo creation or it will publish false statements. Its "ghostnotion.db" wording is, however, now true (P2-1 fix). |
| R2-N4 | **Import guard is type-blind.** The pre-check only tests `JSONObject.has(...)`. A file like `{"channels": 5}` or `{"channels": "x"}` passes the guard, then `loadFromJsonString`'s `arr()` treats the non-array as empty and the import "succeeds", wiping the store to empty with an "Import complete" toast. (A *valid* single-key GBN file wiping everything is by design and is disclosed in the UI text; the wrong-type case is not.) Validate that present keys are arrays before loading. Data-loss only via a hand-crafted/wrong file, hence P3. |
| R2-N5 | **Undo is single-slot and memory-only.** `pendingUndo` holds one entry: deleting a second item within the 6-second window makes the first deletion permanently un-undoable, and a process death inside the window does the same (the delete is already persisted; the snapshot is not). Acceptable for an undo-bar pattern, but it is undisclosed and worth a trash/bin in a later round. |
| R2-N6 | **Edit-screen draft restore can drop language/channel-only changes.** The restore block runs only when the draft title differs or the date differs; a draft in which *only* the language or channel was changed is silently not restored (the saved draft itself is correct — the restore condition is too narrow). |
| R2-N7 | **Orphaned drafts are never cleaned up.** Deleting a script leaves its `draft_body_<id>` / `draft_edit_<id>` SharedPreferences entries behind forever. Harmless (keyed by dead ids, never loaded), but they accumulate. Clear them in the delete paths. |
| R2-N8 | **`persistenceAvailable` is write-only state** (set in `restoreStore`/`persistStore`, read nowhere), and `persistStore()`'s generic-`Exception` branch fails silently where the `IOException` branch toasts. Either surface it or drop the flag. |
| R2-N9 | **Dead code:** `pickScript()` in MainActivity is unused (disclosed by Team Two). One-line removal. |
| R2-N10 | **Link-management asymmetry:** a script's linked tasks can only be unlinked from the task detail screen, not from the script screen where they are listed. UX nit only. |
| — | Carried from Round 1, still true: N-9 (`Logic.addDays()` throws on invalid input; still no UI callers — latent), N-10 (deprecated `onBackPressed()`, compiler warnings only). Export-via-share puts the whole JSON in `EXTRA_TEXT`; very large libraries could be truncated by some share targets — the Save-to-file path is the reliable one (worth a one-line hint in the Backup section eventually). |

---

## 5. Recommended Round 3 tasks

**The Notion engine (expected focus — confirmed as the right next step; the model is ready):**
1. **Page tree + nested pages.** The Record/Block model already supports it (a page is a block of type "page"; `Block.parentBlockId` exists). Build: a Pages section with a tree/list of pages, pages nestable inside pages (parent page id), create/rename/delete with the Round 2 undo pattern, breadcrumb from the parent chain (COMPARISON's "columns/breadcrumb/TOC" row starts here).
2. **Block editor on the existing tested API.** `Store.addBlock` / `blocksForPage` are already unit-tested and waiting — Round 1 noted MainActivity never called them; it still doesn't. Build the editor UI: text blocks first, then heading / to-do / quote / divider types (Block.type already carries the string), reorder via `Block.order`, edit-in-place with the Round 2 draft-autosave pattern so the block editor inherits N-6 protection from day one. Scripts could later migrate onto blocks, but don't force it this round.
3. Wire pages into Search (page titles + block text) and into the link system (page ↔ script/task links reuse the Round 2 many-to-many pattern) so the new surface isn't an island.

**Close out Round 2 leftovers first (small, high-trust-value):**
4. Decide and implement the **channel-delete orphan policy** (R2-P2-1) and disclose it in the delete confirmation until then.
5. Add Store-level `removeX` / rename / channel-move APIs that emit to the sink (incl. tombstones via `Db.softDelete`) and rewire the four `deleteXWithUndo` functions + rename dialogs + the one marked `script.channelId =` site through them (R2-N1); add a `channelId` parameter to `updateScript` in the same pass.
6. Refresh **COMPARISON.md** per §3 (statuses → 11 present / 2 partial / 18 missing, bugs table, v0.2 header) and **github-staging/README.md** to v0.2 facts (R2-N2, R2-N3) — before any repo work.
7. Harden import: require present top-level keys to be JSON arrays before loading (R2-N4). Fix the edit-draft restore condition (R2-N6) and clear per-script drafts on script delete (R2-N7).

**Then:** generic table view for collections (per FINDINGS §7.5 order), trash/bin to replace single-slot undo (R2-N5), and the `OnBackPressedDispatcher` migration (N-10) whenever MainActivity is next touched structurally.

---

*Monitoring Team — Round 2 complete. Verdict: PASS-WITH-NOTES. Builders' headline claims (reproducible build at the claimed hash, 125/125 tests, zero permissions) all independently reproduced; all Round 1 P2s fixed; the notes above are the Round 3 entry list.*
