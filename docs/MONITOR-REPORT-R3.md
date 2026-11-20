# GhostBlade Notion v0.3 — Monitoring Team Report (Round 3)

**Date:** 2026-11-20 (local time)
**Monitor role:** Independent verification only. No `.kt` source, test, manifest, or build.sh was modified by the Monitoring Team. Files written by Monitoring this round: this report, COMPARISON.md (status updates, §5), github-staging/README.md (v0.3 facts), WORKLOG.md (Round 3 entry appended). My rebuilds only regenerated the APK outputs via `./build.sh` as-is — byte-identical to the builders'.
**Package:** `com.ghostblade.notion` · versionCode 3 · versionName 0.3 · minSdk 24 · targetSdk 34
**Wait condition:** Verification started only after the round settled — `GhostBladeNotion-v0.3.apk`, `FEATURES-R3-TEAMONE.md`, `FEATURES-R3-TEAMTWO.md` all present and `tests/TEST-RESULTS.txt` updated for v0.3 (settled ~13:35 UTC; an intermediate 726 KB APK seen during polling was superseded by the final build).

## Verdict: **PASS-WITH-NOTES**

Every headline builder claim reproduces under my own runs: the build is **byte-reproducible** (my two clean rebuilds are byte-identical to each other *and* to the builders' APK, at the exact claimed hash), the JVM suite passes **305/305** on my own compile-and-run with PASS lines identical to `tests/TEST-RESULTS.txt`, and the zero-permission / fully-offline guarantee holds under all three checks with `allowBackup="false"` confirmed in the manifest **and** in my built APK. Every Round 2 assigned item is **truly fixed in code** (traced UI → Store → sink/persist, not just grepped): the Db mirror can no longer diverge on UI paths, `{"channels": 5}` is rejected end-to-end with the store untouched, and channel delete now cascades under a disclosed dialog with a full undo. The Notion engine is real: a nestable page tree and a 9-type block editor, wired into Search and into the link system — and with idea↔task landed, **all six interlinking pairings now exist with UI in both directions**, so I have moved that COMPARISON row to present.

The notes are all P3: `Store.movePage` has no UI (a page's parent is fixed at creation), toggle child blocks can't be created in the UI, and two small consistency nits. No P1, no open P2. Details below.

---

## 1. Independent verification table (Monitoring Team's own runs)

| # | Claim | Verified? | My evidence |
|---|---|---|---|
| 1 | Clean rebuild → `GhostBladeNotion-v0.3.apk`, 742,422 bytes, SHA-256 `08d5a14e…cf7f` | ✅ YES | Builders' on-disk APK already matched (742,422 bytes, SHA-256 `08d5a14edf9eb3caccdaf1affeb2d85c9c4a40e7eded2c50d7210c8c2a12cf7f`). My rebuild #1 and #2 both exit 0, both **742,422 bytes**, both the same SHA-256. |
| 2 | Byte-identical across builds (`cmp`) | ✅ YES | `cmp` on my two builds: **identical**. `cmp` of my build vs the builders' APK: **identical**. All **14 zip entries** stamped **1980-01-01 00:00:00**. Byte-reproducibility holds for v0.3. |
| 3 | `apksigner` verifies; badging | ✅ YES | `apksigner verify --verbose`: **Verifies**, v2 true, v3 true, 1 signer. `aapt2 dump badging`: package `com.ghostblade.notion`, versionCode **3**, versionName **0.3**, minSdk 24, targetSdk 34. Only build warning: the known `onBackPressed` deprecation (N-10, carried). |
| 4 | JVM tests: 305 checks, 0 failures (125 R2 + 180 new) | ✅ YES | My own compile (`kotlinc Models.kt Logic.kt Store.kt tests/TestMain.kt`, kotlinc 2.0.21, JDK 21, android.jar on cp, exit 0) and run (kotlin-stdlib jar on cp): **`305 checks, 0 failures`, exit 0**. The 305 PASS lines are **identical** (sorted diff) to `tests/TEST-RESULTS.txt`. The R2 set is preserved as the first block of checks; new R3 checks (channelId move, tombstones, import type rejection, ghost rows, channel + page cascades, block types, round-trips, v0.2 backward compat) all pass in my run. Db compile-only check also reproduced: exit 0. |
| 5 | Zero permissions (a) manifest | ✅ YES | `grep -c uses-permission AndroidManifest.xml` = **0**; no "permission" string anywhere in the manifest. |
| 6 | Zero permissions (b) built APK | ✅ YES | `aapt2 dump permissions` on **my** built APK prints only `package: com.ghostblade.notion`, no permission lines. |
| 7 | Zero permissions (c) source scan | ✅ YES | Grep over all `.kt` for `java.net`, `HttpURLConnection`, `Socket`, `URL(`, `LocationManager`, `Firebase`, `analytics`, `WebView`, permission constants: **no hits** except one comment mention of "no WebView" in MainActivity's class doc. Dex `strings`: the only "http" hit is the Kotlin stdlib's own youtrack.jetbrains.com URL in a stdlib message — not app code (same as Rounds 1–2). |
| 8 | `android:allowBackup="false"` | ✅ YES | Present in AndroidManifest.xml **and** confirmed in my built APK via `aapt2 dump xmltree`: `allowBackup=false`, `usesCleartextTraffic=false`. |
| 9 | No GitHub work | ✅ YES | Still no `.git` anywhere in the project tree; `github-staging/` still contains only `README.md` (updated to v0.3 facts by Monitoring this round, §5). No repo created, nothing pushed. |

---

## 2. Round 2 assigned items — per-item verdicts (code-traced)

### §3(a) R2-N1 — Store remove/rename APIs + tombstones; no UI direct-mutation path — ✅ FIXED
- **Store side:** `renameChannel` / `renameScript` (= `updateScript(title=)`) / `renameIdea` / `renameTask` / `renamePage`, new `updateIdea` and `updateTask` (null = unchanged, validated channel/date before mutation), `removeChannel/Script/Idea/Task/Page/Block` and `restoreX(entity, index)` all exist. Every remove cleans the other side of every link the entity takes part in (re-emitting each cleaned entity) and then emits a **tombstone**: `emitXTombstone()` builds the same Record shape with `alive=false`. I verified in Db.kt that `upsertRecord` persists `Record.alive` into the row's `alive` column (`cv.put("alive", if (r.alive) 1 else 0)`), so a sink-emitted tombstone lands exactly as `Db.softDelete`'s end state — Team One's "no Db change needed" claim is correct.
- **Import ghost rows:** `loadFromJsonString` captures the pre-load lists, and on success emits a tombstone for every pre-load id absent from the new dataset (all six types) before `syncToRecordSink()`. Test-verified in my run ("ghost channel tombstoned on load").
- **UI side — full sweep, not a spot check:** grep over MainActivity.kt for direct field assignments (`.channelId =`, `.name =`, `.title =`, `.notes =`, `.dueDate =`, `.body =`) → **zero hits**; for `removeAt` / `store.<list>.add/remove` → **zero hits** (only a SharedPreferences draft removal and the UI's own expanded-pages set); for direct link-list mutation (`linkedTaskIds.add`, etc.) → **zero hits**. All four `deleteXWithUndo` functions call `removeScript` / `removeIdea` / `removeTask` / `removeChannelCascade`; undo calls `restoreX` / `restoreChannelCascade` and re-links the other sides through the Store link APIs; renames call `renameChannel` / `updateIdea` / `updateTask` / `renamePage` / the script edit screen. Script delete also clears the script's retained drafts (R2-N7, fixed in passing).
- **One documented exception — verified as disclosed:** the idea↔script single link has no Store link API (it is created only by `startScriptFromIdea`), so the undo paths for script/idea deletes set that one field directly (MainActivity.kt:510, :527) and **immediately flush it to the sink** with a no-op `store.updateIdea(id)` / `store.updateScript(id)` call (all-null params = unchanged, but it emits the current state). Both sites are commented. Net effect: the mirror still receives every mutation. This is honest engineering, disclosed by the builder, and I accept it — R2-N1 is closed. (A real `linkIdeaToScript` API would remove the exception; noted for Round 4 hygiene, not a finding against this round.)

### §3(b) `updateScript` channelId + last direct assignment gone — ✅ FIXED
- `Store.updateScript(..., channelId: String? = null)`: null **or empty** = leave unchanged; a non-empty channelId must name an existing channel, validated **before any field is mutated** (same validate-first pattern as date) — a missing channel returns false and changes nothing. On success `updatedAt` bumps and the emitted Record's `parentId` is the new channel. Tests in my run: move works, emitted with new parent, missing-channel atomicity, null/empty = unchanged.
- The script edit screen's Save now passes `channelId = editChannelId` into that same `updateScript` call (MainActivity.kt:1284). Grep confirms **zero** `script.channelId =` sites remain in MainActivity (the R2 report's exactly-one marked site is deleted). The idea-reassign path likewise routes through `updateIdea(channelId = …)` (MainActivity.kt:1570).

### §3(c) R2-N4 — import per-key type validation; `{"channels": 5}` traced end-to-end — ✅ FIXED
Two independent layers, either of which alone leaves the store untouched:
1. **UI layer** (`onActivityResult`, REQ_IMPORT_FILE): after the pre-existing guards (unreadable → reject; blank → reject; not a JSON object → reject; none of channels/scripts/ideas/tasks → reject), a new per-key check rejects when any present known key fails `parsed.optJSONArray(key) != null`, toasting `Import failed — "<key>" is not a list in that file`. `{"channels": 5}` dies here and never reaches the Store.
2. **Store layer**: `loadFromJsonString` now returns **Boolean**. After parsing the top level, if any known key (`channels`, `scripts`, `ideas`, `tasks`, `pages`, `blocks`) is present but not a List, it returns false **before** anything is parsed into entities or mutated; malformed JSON / non-object top level also returns false. The import handler checks the return value and toasts failure instead of "Import complete" when the Store refuses.
- Test-verified in my run: `import {channels:5} rejected`, `store untouched after bad-type import (channels)`, `store untouched after bad-type import (script kept)`, plus string/object/null/bool variants per Team One's report.
- One inconsistency, harmless: the UI layer's key list omits `"pages"` (Store's list has all six). A wrong-typed `pages` value is still caught — by layer 2 — on every path where it can appear with a GBN key present, and a `{"pages": 5}`-only file is rejected by the has-GBN-keys guard. No unsafe path exists. Recorded as R3-N4 (P3, consistency only).

### §3(d) R2-P2-1 — cascade channel delete per Jervis's decision — ✅ FIXED
- **Disclosure:** `deleteChannelWithUndo` now opens a confirmation dialog *before* deleting: *"“\<name\>” will be deleted along with its \<s\> script(s), \<t\> task(s) and \<i\> idea(s). You can undo right after deleting."* (counts computed live; wording matches Team Two's quoted text verbatim). Delete / Cancel buttons.
- **Cascade:** the confirm calls `Store.removeChannelCascade`, which snapshots deep copies *before* any mutation, cleans survivor links (dead ids stripped from survivor scripts/tasks/ideas/pages, each re-emitted), removes the cascaded scripts/tasks/ideas and the channel, and emits a tombstone for **every** removal. The plain `removeChannel` is the same cascade without the snapshot — there is no orphan-leaving channel delete left in the Store.
- **Undo:** `performUndo` routes `"channel"` to `Store.restoreChannelCascade`, which refuses (false, nothing changed) if any id already exists, re-inserts channel + contents at their original indices with all their own link fields intact, then surgically re-adds survivor links to restored entities (links a survivor gained *after* the delete are kept), emitting alive records throughout. Test-verified in my run end-to-end: indices, both sides of internal task↔script and idea↔script links, and all four survivor-link directions come back consistent; `cascade tombstones for every removed entity` passes. **R2-P2-1 is closed** — the orphan behaviour is gone, not merely disclosed.

### Also closed this round (verified in passing)
- **R2-N6** — script-edit draft restore condition now also triggers on language-only or channel-only changes (MainActivity.kt:~1250, commented). ✅
- **R2-N7** — script delete clears `draft_body_<id>` / `draft_edit_<id>`; block delete clears `draft_block_<id>`. ✅
- **R2-N9** — dead `pickScript()` is gone (grep: no hits). ✅
- Carried, still open by design: **R2-N5** (single-slot memory-only undo — unchanged, now also covering page/block deletes), **R2-N8** (`persistenceAvailable` still written in 4 places, read nowhere), **N-9** (`Logic.addDays()` throws on invalid input; still no UI callers), **N-10** (deprecated `onBackPressed()` — the only warning in my builds).

---

## 3. The Notion engine (§4) — per-item verdicts (UI click → Store call → persist traced)

Every path below follows the same verified pattern: UI handler → Store API (which emits to the sink) → `persistStore()` → re-render.

### Page tree — ✅ WORKS
- **Create:** Home nav "📄 Pages" → `showPages()`; "＋ New page" → title dialog → `store.addPage(title)` → persist → opens the page. Sub-page via the page screen's "＋ Sub-page" or any row's ⋯ menu → `store.addPage(title, parent.id)`.
- **Nest / navigate:** `rootPages()` roots render through `renderPageTree` — indented 16dp per depth, ▸/▾ expander (state in `expandedPageIds`), block + sub-page counts per row, depth cap 12 + ancestor-set cycle guard. Tapping a row opens `showPage()`; the page screen lists sub-page cards (tap to drill in); the **breadcrumb** — "Pages › ancestors › this page" from `store.pageAncestors` (root-first), every crumb tappable, horizontally scrollable — is real; Back walks up to the parent page, then the Pages list.
- **Rename:** ⋯ menu / page-screen button → `renamePageDialog` → `store.renamePage` (emits) → persist.
- **Delete (disclose + undo):** `deletePageWithUndo` discloses sub-page and block counts computed from `descendantPageIds` (*"Scripts linked to these pages will be unlinked, not deleted"*), then `removePageCascade` (page + descendants + their blocks removed, tombstones for all, script `linkedPageIds` cleaned and re-emitted); Undo = `restorePageCascade`. Test-verified incl. block and script-link restoration.
- **Gap:** `movePage` exists in the Store (with cycle prevention, tested) but has **no UI caller** — see R3-N1.

### Block editor — ✅ WORKS, all required types
`showPage()` renders `blocksForPage` order through `renderBlockGroup` / `blockRowView`:
- **text** — plain 15sp; **heading** — 20sp bold; **bulleted_list** — "• " prefix; **numbered_list** — running number over consecutive numbered siblings; **todo** — tappable ☐/☑ calling `store.setBlockChecked` in place (strikethrough + grey when checked); **toggle** — ▸/▾ header calling `store.setBlockCollapsed` in place, body = lines after the first (shown only while expanded), imported `parentBlockId` children render indented under it; **quote** — gold left bar + italic; **code** — monospace on a shaded panel; **divider** — hairline (inserts immediately from the picker).
- **Add:** "＋ Add block" → type picker (all 9 types) → `store.addBlock` → persist → editor opens. **Edit:** tap a block (or ⋯ → Edit) → `showBlockEdit` — draft-autosaved with the Round 2 pattern (SharedPreferences on every keystroke + `onPause` flush, restored when it differs, cleared on Save/delete) → Save calls `store.updateBlockText`. **Reorder:** ⋯ menu Move up / Move down → `store.moveBlockUp/Down` (page orders re-normalised by the Store). **Turn into…:** → `store.updateBlockType`. **Delete:** ⋯ / editor button → `deleteBlockWithUndo` → `store.removeBlock` (tombstone + renormalisation); Undo = `restoreBlock` + `moveBlock` to re-normalise. Unknown stored types render as plain text (loading stays lenient; creation rejects unknown types — `addBlock` throws, `updateBlockType` returns false; both test-verified).

### Pages in Search — ✅ WORKS
`showSearch()` now also iterates `store.pages`: a page matches on its title **or the text of any of its blocks** (`blocksForPage`). Results carry a **PAGE** chip; the sub-line shows "Block: \<snippet\>" for a block-text hit, "Inside: \<parent title\>" for a nested page, else "Root page". Tapping opens the page. Header subtitle updated to say pages + block text are searched.

### Page ↔ script linking — ✅ WORKS, both sides
Page screen "Linked scripts": linked script cards (tap → `showScript`), per-script **Unlink** (`unlinkPageFromScript`), "🔗 Link a script" picker excluding already-linked. Script screen mirrors it with a "Linked pages" section (cards, per-page Unlink, "🔗 Link a page" picker). Data layer is the exact `linkTaskToScript` pattern (`Script.linkedPageIds` + `Page.linkedScriptIds`, duplicate/missing rejection, one-way repair, JSON + sink on both sides); round-trip tests preserve both directions in my run. Script delete captures page links and undo re-links them via `linkPageToScript`.

### Idea ↔ task linking — ✅ WORKS, both sides → interlinking may move to present
Idea detail gains "Linked tasks" (cards → task detail, Unlink, "🔗 Link a task" picker); task detail gains "Linked ideas" (cards → idea detail, Unlink, "🔗 Link an idea" picker) — same pattern as task↔script, through `linkIdeaToTask` / `unlinkIdeaFromTask` helper wrappers that persist on success. Data layer: `Idea.linkedTaskIds` + `GTask.linkedIdeaIds`, consistency helper, JSON round-trip verified in my run.
**Judgement on the COMPARISON row:** the row's own claim is the six pairings among ideas ↔ scripts ↔ tasks ↔ channels. All six are now real **with data + UI in both directions** (idea↔script, task↔script, idea↔task, channel↔script, channel↔task, channel↔idea), plus page↔script as a bonus. The R2 reason for holding partial — idea↔task missing — no longer exists. **I have moved interlinking partial → present** (§5).

---

## 4. New findings, by severity

### P1 — crash / data-loss in normal paths
**None found.** Force-unwrap sweep: 4 `!!` in MainActivity — two on `date` immediately guarded by `isNullOrBlank()` checks on the same value, one on `bodyDraft` and one on `draftText`, each guarded by its restored-draft boolean. All safe. MainActivity still contains **zero** `catch (t: Throwable)`; the only `Throwable` catch in the codebase remains `Store.emit()` swallowing sink failures (justified — mirror only, JSON authoritative). Empty lists, missing ids, stale references, and cyclic page data on the new paths are guarded (undo indices clamped, cascade restores refuse on id collision, tree render is depth- and cycle-capped).

### P2 — significant
**None found, and none carried open.** R2-P2-1 (channel-delete orphans) is closed (§2d).

### P3 — minor / documentation / robustness
| ID | Finding |
|---|---|
| R3-N1 | **No page move/reparent UI.** `Store.movePage` (cycle-safe, tested) has no caller in MainActivity: a page's parent is chosen at creation and cannot be changed. Nesting works; reorganising a tree does not. Cheap Round 4 add (a "Move to…" picker in the page menu, excluding self + descendants — the Store already rejects those). |
| R3-N2 | **Toggle child blocks cannot be created in the UI** (builder-disclosed). `Store.addBlock` has no parent parameter, so a toggle's body is the text after its first line; the edit screen explains the convention. Imported child blocks render correctly under an expanded toggle but can't be authored. Needs a Store `addBlock` parent parameter (or a dedicated child-add API) to close. |
| R3-N3 | **Block move acts on the page-global order** (builder-disclosed). `moveBlockUp/Down` operate on the page's full order-sorted list, so on a page containing imported child blocks a top-level move could swap past a child. Cosmetic, import-only; UI-created pages have no child blocks. |
| R3-N4 | **UI import pre-check key list omits `"pages"`** (see §2c). The Store layer's six-key validation + Boolean check covers every path, so this is a consistency nit, not a hole. One-line fix: add `"pages"` to the UI `knownKeys` list. |
| — | Carried from Round 2, still true: R2-N5 (single-slot, memory-only undo — no trash screen), R2-N8 (`persistenceAvailable` write-only), N-9 (`Logic.addDays()` latent throw), N-10 (deprecated `onBackPressed()`). Export-via-share still puts the whole JSON in `EXTRA_TEXT` (Save-to-file is the reliable path for very large libraries). |

---

## 5. COMPARISON.md deltas — applied by Monitoring (spot-checked against code first)

Team Two's recommended deltas were each verified against the traced code before applying. **I agree with all of them** — including interlinking → present, for the reasons in §3 (Team Two's own evidence holds up; the R2 blocker is gone).

| Row | v0.2 | v0.3 (applied) | Basis |
|---|---|---|---|
| Pages / nested pages | missing | **present** | §3: full tree UI, nesting, breadcrumb, cascade delete + undo |
| Block editor | missing | **present** | §3: 9 types rendered/edited through Store APIs, draft-autosaved editor |
| Columns, breadcrumb, TOC | missing | **partial** | Breadcrumb real and tappable; columns + TOC still absent — partial, not present |
| Search across workspace | present | present (text updated) | Now covers page titles + block text; PAGE results open the page |
| Interlinking | partial | **present** | §3: all six pairings real, data + UI both directions; page↔script bonus noted in the row |
| Workspaces / sidebar tree | missing | missing (note updated) | Still single-user, no sidebar — but the row no longer claims "no page tree" |
| Import / export | present | present (text updated) | R2-N4 guard gap recorded as fixed (both layers) |
| Offline / local storage | present | present (text updated) | "Every mutation" is now accurate for UI paths; the one emit-flush exception is named in the row |

**Mechanical recount after my edits** (counted from the table itself, not hand-typed): 31 rows — **14 present / 2 partial / 15 missing** (was 11 / 2 / 18). Bugs table refreshed: R2-P2-1, R2-N1, R2-N4, the channelId bypass, R2-N6, R2-N7 and R2-N9 moved to the resolved list; R3-N1–N4 added; no P1/P2 open. Header updated to v0.3. `github-staging/README.md` updated to v0.3 facts (version, `GhostBladeNotion-v0.3.apk`, 742,422 bytes, SHA-256 from my verified build, feature status, counts) and the Round 3 entry appended to WORKLOG.md.

---

## 6. Recommended Round 4 tasks

**The expected focus — confirmed as the right next step; the record model is ready:**
1. **Database / table views for collections.** The Record/Block model and the typed lists (scripts, tasks, ideas, pages) are all in place. Build a generic collection view: a table rendering a channel's scripts (title / language / status / date) and tasks (title / due / done) as rows with sortable columns first; board view (DRAFT/FINAL columns for scripts) is the natural second step. This moves the "Databases — table view" row and starts on "board/gallery/list/calendar/timeline views"; the "Database properties" partial can only move once properties become user-defined, which should be scoped explicitly before anyone claims it.
2. **Templates.** Page templates (a page + its blocks, instantiated in one tap) and script/idea templates. The block model makes page templates cheap: snapshot a page's blocks as a named template, "New page from template" re-creates them via `addBlock`. Seed one or two useful ones (e.g. a video-production page: hook / script / shot list / upload checklist as todo blocks).

**Close out Round 3 leftovers (small):**
3. Page move/reparent UI on top of the existing tested `movePage` (R3-N1), and a Store way to create toggle child blocks (R3-N2) so toggles become fully authorable.
4. Add `"pages"` to the UI import `knownKeys` list (R3-N4, one line).
5. A real `linkIdeaToScript` Store API to retire the last direct field assignment + emit-flush exception in the undo paths (§2a) — hygiene, makes "no UI direct mutation" absolute.

**Then:** a trash/bin screen to replace single-slot memory-only undo (R2-N5 — now covering six entity types, the case for it keeps growing), calendar view over script/task dates, and the `OnBackPressedDispatcher` migration (N-10) whenever MainActivity is next touched structurally.

---

*Monitoring Team — Round 3 complete. Verdict: PASS-WITH-NOTES. Builders' headline claims (byte-reproducible build at the claimed hash, 305/305 tests, zero permissions) all independently reproduced; every Round 2 assigned item traced as truly fixed; the Notion engine and the sixth interlinking pairing are real. The notes above are the Round 4 entry list.*
