# GhostBlade Notion v0.4 — Team One (build/test A) Report, Round 4

**Date:** 2026-11-20 (local time) · **Package:** `com.ghostblade.notion` · **versionCode 4 / versionName 0.4** (manifest bumped by me; build.sh derives `GhostBladeNotion-v0.4.apk` from it — no build.sh change needed)
**Files owned & changed:** Models.kt, Logic.kt, Store.kt, tests/TestMain.kt, tests/TEST-RESULTS.txt, AndroidManifest.xml (version only). Db.kt checked — **no change needed** (nothing in this round touches the mirror contract; see §5). MainActivity.kt / Ui.kt / COMPARISON.md / WORKLOG.md untouched.
**Tests: 492 checks, 0 failures** (305 prior — verified byte-identical as the first 305 PASS lines of my run — + 187 new), from my real run; full output in `tests/TEST-RESULTS.txt`. Db compile-only check: OK (exit 0). Full-source compile check (all six `.kt` files incl. MainActivity.kt as it exists now — still the settled v0.3 UI, mtime 13:31 UTC; Team Two's R4 UI had not landed when I finished): **OK, exit 0**, only the pre-existing `onBackPressed` deprecation warnings.
**APK build: NOT run by me** — per orders, Team Two builds after the UI lands and Monitoring rebuilds independently. Zero permissions preserved (manifest still has 0 `uses-permission`; no network/WebView/location code anywhere in my files).

---

## 1. Sorting + board logic (Logic.kt) — Task 2

New top-level enums in Logic.kt (same file, package `com.ghostblade.notion`):

```kotlin
enum class ScriptSort { TITLE, LANGUAGE, STATUS, DATE, UPDATED }
enum class TaskSort { TITLE, DONE, DATE }
```

New `Logic` functions (all pure, all stable — equal elements keep input relative order):

```kotlin
fun sortScripts(scripts: List<Script>, by: ScriptSort, ascending: Boolean = true): List<Script>
fun sortTasks(tasks: List<GTask>, by: TaskSort, ascending: Boolean = true): List<GTask>
fun groupScriptsByStatus(scripts: List<Script>): Map<ScriptStatus, List<Script>>
```

Rules, exactly as implemented and tested:

| Key | Rule |
|---|---|
| `TITLE` | Case-insensitive (`String.CASE_INSENSITIVE_ORDER`). |
| `LANGUAGE` | By enum name — BANGLA before ENGLISH ascending. |
| `STATUS` | Enum ordinal — DRAFT before FINAL ascending; descending puts FINAL first. |
| `DATE` | Lexicographic on the `yyyy-MM-dd` string (== chronological). **Null/blank dates sort LAST in BOTH directions** — flipping the sort never floats undated items to the top. Among dated items, descending reverses normally; among undated items, input order is kept. |
| `UPDATED` | Numeric `updatedAt`; ties keep input order in both directions (tested). |
| `DONE` (tasks) | Ascending = open tasks (`done=false`) first; descending = done first. |

`groupScriptsByStatus` returns a `LinkedHashMap` with **both keys always present** (DRAFT first, then FINAL; an empty column is an empty list, never a missing key), each group preserving input order. Sorting never mutates the input list.

## 2. Toggle child blocks + sibling-scoped move (Store.kt) — Task 3, closes R3-N2 (data side) + R3-N3

```kotlin
fun addBlock(
    pageId: String,
    type: String = "text",
    text: String = "",
    checked: Boolean = false,
    collapsed: Boolean = false,
    parentBlockId: String? = null        // NEW, appended — existing callers unaffected
): Block
fun childBlocks(blockId: String): List<Block>   // direct children, order-sorted; empty if missing/leaf
```

- `parentBlockId != null`: the parent block must exist **and belong to the same page**, else `IllegalArgumentException` is thrown and nothing is added (same failure style as the unknown-type throw). The new block's `order` is still the page max + 1 (it is appended at the page end order-wise; sibling moves then position it — see below).
- **Sibling-scoped moves** — `moveBlock` / `moveBlockUp` / `moveBlockDown` signatures are UNCHANGED, semantics are now:

> A block's siblings = same `pageId` AND same `parentBlockId`. `targetIndex` is the 0-based index **within the sibling group**, clamped to the group's range. The block is repositioned only inside the sibling subsequence of the page's full order-sorted list (blocks of other groups keep their slots), then the whole page's orders are normalised to 0,1,2,… and every block of the page is emitted. `moveBlockUp/Down` step within the group and return false at the group's ends (a first child cannot move up even though it is not the page's first block — tested).

On a **flat page** the sibling group is the whole page, so v0.3 behaviour is bit-for-bit the same — all prior move tests pass unchanged (verified in the run).

## 3. `linkIdeaToScript` API (Store.kt) — Task 4, retires the last emit-flush exception

```kotlin
fun linkIdeaToScript(ideaId: String, scriptId: String): Boolean
fun unlinkIdeaFromScript(ideaId: String, scriptId: String): Boolean
```

- `linkIdeaToScript` sets BOTH fields (`idea.linkedScriptId`, `script.linkedIdeaId`) and emits BOTH entities. Returns false — changing nothing — if either id is missing, the idea already points at a DIFFERENT script, or the script already points at a DIFFERENT idea. A one-way pre-existing link naming the pair is repaired to two-way (returns true), like the other link APIs.
- **Idempotency choice (documented, tested):** if the two are already linked to EACH OTHER, it returns **TRUE** (success, no state change, no re-emission). Undo-restore callers can call it unconditionally. (This deliberately differs from the many-to-many link APIs, which return false on an already-consistent link — a one-to-one relink is a restore operation, not a duplicate-add.)
- `unlinkIdeaFromScript` clears each side that names the pair (a consistent two-way link is fully cleared; a one-way pointer naming the pair is cleared too), emits both, returns true. False if either id is missing or neither side names the pair.

## 4. Templates (Models.kt + Store.kt) — Task 5

```kotlin
data class TemplateBlock(var type: String, var text: String, var checked: Boolean = false,
                         var collapsed: Boolean = false, var parentIndex: Int? = null)
data class PageTemplate(var id: String, var name: String,
                        var blocks: List<TemplateBlock> = emptyList(), var createdAt: Long = 0)

// Store:
val templates: MutableList<PageTemplate>
fun templateById(id: String?): PageTemplate?
fun savePageAsTemplate(pageId: String, name: String): PageTemplate?
fun createPageFromTemplate(templateId: String, title: String? = null): Page?
fun removeTemplate(id: String): Boolean
fun renameTemplate(id: String, newName: String): Boolean   // blank name -> false
```

- `savePageAsTemplate`: null if the page is missing or the name is blank. Snapshot = the page's blocks in `blocksForPage` order, deep-copied (later page edits don't touch the template — tested); `parentIndex` = the index **in that ordered list** of the block's parent, when the parent is a block of the same page, else null.
- `createPageFromTemplate`: null if the template is missing. Creates the page via `addPage` (title = the `title` param if non-blank, else the template name), then re-creates the blocks **in order via `addBlock`**, mapping `parentIndex` → the new block id. A `parentIndex` pointing at the block itself, a later block, or outside the list is treated as null (top-level) — a block can only parent to an already-created block. A stored type that is no longer in `Logic.BLOCK_TYPES` falls back to `"text"` instead of failing the whole creation. New page and blocks get fresh ids; texts/types/checked/collapsed/parent structure are reproduced (tested, including through a JSON round-trip).
- Persistence: top-level `"templates"` key in `toJsonString`/`loadFromJsonString`, and `"templates"` is in the per-key type-validation list — `{"templates": 5}` rejects the load with the store untouched (tested). v0.3 JSON without the key loads fine with an empty template list (tested).

## 5. Trash (Models.kt + Store.kt) — Task 6

```kotlin
data class TrashEntry(var id: String, var kind: String, var label: String,
                      var deletedAt: Long = 0, var payloadJson: String = "{}")
// kind ∈ {"channel","page","script","idea","task","block"}

// Store:
val trash: MutableList<TrashEntry>          // oldest first — new entries are APPENDED (newest LAST)
fun trashById(id: String?): TrashEntry?
fun deleteScriptToTrash(id: String): TrashEntry?
fun deleteIdeaToTrash(id: String): TrashEntry?
fun deleteTaskToTrash(id: String): TrashEntry?
fun deleteBlockToTrash(id: String): TrashEntry?
fun deleteChannelToTrash(id: String): TrashEntry?
fun deletePageToTrash(id: String): TrashEntry?
fun restoreFromTrash(entryId: String): Boolean
fun permanentlyDeleteTrash(entryId: String): Boolean
fun emptyTrash()
```

- Each ToTrash API performs the **same removal** as its plain counterpart (same link cleanup, same tombstones) and, on success only, appends a `TrashEntry` and returns it (null on failure — missing entity). Label = channel name / page title (the page the delete was called on) / script title / idea title / task title / block text, falling back to the block **type** when its text is blank (e.g. a divider — tested).
- Payloads (private serialize/parse helpers in Store.kt, reusing the per-entity JSON writers + `JsonParser`/Map helpers): singles store `{"index":Int,"entity":{...}}` — the entity copy captured BEFORE removal, at its original list index (for blocks, the index in the global `blocks` list, matching `restoreBlock`'s index semantics). Channel stores the full serialised `ChannelCascadeSnapshot`; page the full `PageCascadeSnapshot`.
- `restoreFromTrash` dispatches on kind: singles via `restoreX(entity, index)`; channel/page via `restoreChannelCascade`/`restorePageCascade`. **On success the entry is removed from the trash; on failure (unknown kind, corrupt payload, id collision) the entry is KEPT and false is returned** — both halves tested, including a same-id collision that succeeds after the impostor is removed.
- Restore semantics note (inherited, deliberate): single restores bring back the entity only — links that the delete cleaned on OTHER entities are not restored (exactly the documented `restoreX` semantics). Cascade restores bring back everything, survivor links included (tested for both channel and page trash restores).
- `permanentlyDeleteTrash` just drops the entry (entities are already deleted + tombstoned); `emptyTrash()` drops all entries. Neither restores anything.
- **Plain `removeX` APIs create NO trash entries** — stated in the Store class doc and tested (`removeScript` leaves `trash` empty).
- Persistence: top-level `"trash"` key, validated like the others (`{"trash": "x"}` rejected untouched — tested); a load replaces the trash list. Trash survives a JSON round-trip **including payloads** — restoring from a round-tripped entry brings the entity back (tested).

**Sink treatment of templates/trash (design choice, applies to §4 too):** templates and trash entries are **NOT emitted to the Db sink**. The mirror's contract covers only the six entity tables (channel/script/idea/task/page-as-block/block); a template is not a page and a trash entry is not an entity, and emitting either (e.g. templates as `"collection"` records) would put rows in the mirror that no entity list can reconcile on load. They live only in the authoritative JSON, like the store's own structure. No Db.kt change was needed or made.

## 6. Tests — Task 7

`tests/TestMain.kt` extended in place (imports for `ScriptSort`/`TaskSort` added; **no prior check edited or deleted** — the 305 R1–R3 PASS lines are an identical prefix of the new output). 187 new checks cover: every sort key asc/desc incl. null-date-last in both directions and UPDATED tie stability; `sortTasks` every key; `groupScriptsByStatus` (both keys, order, empty input); `addBlock` parent validation (missing parent throws, foreign-page parent throws, nothing added) + `childBlocks`; sibling-scoped moves (top-level move never crosses the child group, child move stays within children, group-end moves return false, orders normalised); `linkIdeaToScript`/`unlinkIdeaFromScript` (both sides set, both entities emitted — captured via `installRecordSink`, idempotent-true, both conflict directions, one-way repair, one-way unlink, `startScriptFromIdea` interplay); templates (snapshot incl. parent mapping + checked/collapsed, snapshot independence, create-from-template reproducing texts/types/checked/collapsed/parent structure on a new page with new ids, title default/override/blank-fallback, remove/rename, JSON round-trip, v0.3-format backward compat, `{"templates": 5}` + `{"trash": "x"}` rejected untouched); trash (every ToTrash kind: entry kind/label, entity gone, restore at original index / cascades fully restored incl. survivor links, entry removed on restore, collision keeps entry, permanent delete drops without restoring, `emptyTrash`, newest-last order, JSON round-trip preserves trash + payload restore, plain `removeScript` creates NO entry).

**Real numbers from my run: 492 checks, 0 failures, RUN_EXIT=0** (compile: kotlinc 2.0.21, JDK 21, android.jar on cp; run: `TestMainKt` with the kotlin-stdlib jar). Full output in `tests/TEST-RESULTS.txt`, together with the Db compile-only check (exit 0) and the full-app compile check (exit 0).

## 7. Wiring contract for Team Two (precise — please follow exactly)

Line numbers refer to MainActivity.kt as it stands now (settled v0.3 file).

1. **`performUndo` idea↔script relink sites → `linkIdeaToScript`** (retires the R2-N1 exception Monitoring noted):
   - Script branch (~lines 505–515): delete the direct assignment block
     `idea.linkedScriptId = s.id` + the no-op `store.updateIdea(idea.id)` flush, and replace the whole `if (e.linkedOtherId != null) { … }` body with a single call: `try { store.linkIdeaToScript(e.linkedOtherId, s.id) } catch (ex: Exception) { }` — valid because the API is idempotent-true and emits both sides itself.
   - Idea branch (~lines 522–532): same replacement: `try { store.linkIdeaToScript(i.id, e.linkedOtherId) } catch (ex: Exception) { }`, deleting `sc.linkedIdeaId = i.id` + the no-op `store.updateScript(sc.id)` flush.
   - After this, MainActivity contains **zero** direct link-field assignments — "no UI direct mutation" becomes absolute.
2. **Delete flows → ToTrash APIs + `restoreFromTrash`** (when wiring the trash/bin screen; the undo bar can also ride on it):
   - `deleteScriptWithUndo` → `store.deleteScriptToTrash(s.id)`; `deleteIdeaWithUndo` → `deleteIdeaToTrash`; `deleteTaskWithUndo` → `deleteTaskToTrash`; `deleteBlockWithUndo` → `deleteBlockToTrash`; channel confirm → `deleteChannelToTrash(ch.id)` (replaces `removeChannelCascade` — same cascade, plus the entry); page confirm → `deletePageToTrash(page.id)` (replaces `removePageCascade`).
   - Undo for a ToTrash delete = `store.restoreFromTrash(entry.id)` — hold the returned `TrashEntry` in the `UndoEntry` instead of the entity/snapshot. For **channel/page** this restores strictly more than today's path (full cascade incl. survivor links, identical to the snapshot restores). For **singles** it restores the entity only — so KEEP today's `clearedLinkIds`/`clearedLinkIds2` relink loops in `performUndo` for task/page/idea-task links (they call the Store link APIs and compose fine with `restoreFromTrash`); the idea↔script relink is item 1 above.
   - A trash screen reads `store.trash` (oldest first; display reversed for newest-first if desired), and wires each row to `restoreFromTrash(entry.id)` / `permanentlyDeleteTrash(entry.id)`, plus `emptyTrash()` for "Empty trash". Call `persistStore()` after any of these (trash lives in the JSON). Draft-clearing on script/block delete (R2-N7) stays in the UI functions as today.
3. **Block menu → child add + sibling move** (closes R3-N2 in the UI):
   - On a **toggle** block's menu (and/or the page's add-block flow when a toggle is the context), add "＋ Add child block": pick a type as in `addBlockFlow`, then `store.addBlock(pageId, type, "", parentBlockId = toggleBlock.id)` — it throws `IllegalArgumentException` if the parent is stale, so keep the existing try/catch → toast pattern used at the current `addBlock` call sites (~lines 2143/2147).
   - Rendering: children of a toggle = `store.childBlocks(toggle.id)` (order-sorted), shown indented while the toggle is expanded — this replaces reliance on the first-line-body convention for NEW child blocks (keep rendering imported `parentBlockId` children the same way; they already flow through this).
   - Move up/down menu items keep calling `store.moveBlockUp/Down(block.id)` unchanged — they are now sibling-scoped server-side, so a child's Move up/down steps among its siblings and a top-level block's steps among top-level blocks. No call-site change needed **except the undo repair below**.
   - ⚠ **Block undo index change:** `performUndo`'s `"block"` branch calls `store.moveBlock(b.id, e.index)` to re-normalise after `restoreBlock`. `targetIndex` is now the index **within the sibling group**, not the full-page index. `deleteBlockWithUndo(block, pageIndex)` currently receives the full-page index — for top-level blocks on flat pages the two are identical, but for a child block Team Two must pass the block's index within `store.childBlocks(parentId)` (or within the top-level subsequence for a top-level block on a page that has children). Please compute the sibling index at delete time and store THAT in `UndoEntry.index` for blocks.
4. **Import knownKeys → add `"pages"`, `"templates"`, `"trash"`** (closes R3-N4 and its R4 extension): at ~line 903 the UI list is `listOf("channels", "scripts", "ideas", "tasks", "blocks")`. The full correct list is all eight keys the Store validates:
   `listOf("channels", "scripts", "ideas", "tasks", "pages", "blocks", "templates", "trash")`.
   (The Store layer already rejects wrong-typed values for all eight and the import handler already checks `loadFromJsonString`'s Boolean — this just makes the UI pre-check consistent, with the friendly per-key toast.)
5. **Templates UI** (new, no existing sites): page screen ⋯ menu → "Save as template…" (name dialog → `store.savePageAsTemplate(page.id, name)`, toast on null); Pages screen / new-page flow → "New page from template" picker over `store.templates` → `store.createPageFromTemplate(t.id)` (optionally pass a title) → open the returned page; a templates manager (list from `store.templates`, `store.templateById`) with rename (`renameTemplate` — blank returns false) and delete (`removeTemplate`). `persistStore()` after every template mutation and after `createPageFromTemplate`.
6. **Table/board views** (Monitoring's R4 headline): channel script table columns sort via `Logic.sortScripts(scripts, ScriptSort.TITLE/LANGUAGE/STATUS/DATE/UPDATED, ascending)`; task table via `Logic.sortTasks(tasks, TaskSort.TITLE/DONE/DATE, ascending)`; the script board's DRAFT/FINAL columns are exactly `Logic.groupScriptsByStatus(scripts)` (both keys always present). All pure — call them on `store.scripts`/`scriptsForChannel(...)` results at render time; no persistence involved.

## 8. What Monitoring should know

1. Manifest is at versionCode 4 / 0.4; I did **not** run build.sh (per orders — Team Two builds after the UI lands; Monitoring rebuilds independently).
2. My full-source compile check ran against the settled v0.3 MainActivity (mtime 13:31 UTC, before Team Two's R4 edits). It passed. If Team Two's wiring (§7) introduces a mismatch, it will be against the signatures quoted verbatim in §1–§5 — those are copied from the landed code.
3. COMPARISON.md deltas I suggest (not mine to edit): "Databases — table view" and "board/gallery/list/calendar/timeline views" now have their data-layer sort/group primitives (§1); "Templates" row can move once Team Two wires §7.5 (data layer complete + tested); the trash/bin gap behind R2-N5 now has a persisted data layer (§5) — the single-slot memory-only undo can be retired when the UI switches to §7.2.
4. Nothing pushed to GitHub; no repo created; no permissions added; no WebView/network/location code.
