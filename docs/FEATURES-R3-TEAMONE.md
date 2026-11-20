# GhostBlade Notion v0.3 — Team One (build/test A) Report, Round 3

**Date:** 2026-11-20 (local time) · **Package:** `com.ghostblade.notion` · **versionCode 3 / versionName 0.3** (manifest bumped; build.sh derives `GhostBladeNotion-v0.3.apk` from it — no build.sh change needed)
**Files owned & changed:** Models.kt, Logic.kt, Store.kt, Db.kt (checked, no change needed — see §1), tests/TestMain.kt, tests/TEST-RESULTS.txt, AndroidManifest.xml (version only). MainActivity.kt / Ui.kt / COMPARISON.md / WORKLOG.md untouched.
**Tests: 305 checks, 0 failures** (125 prior, all still passing + 180 new), from my real run — full output in `tests/TEST-RESULTS.txt`. Db compile-only check: OK. Full-app compile check (all sources incl. Team Two's in-progress MainActivity): OK, exit 0.
**APK build: NOT run by me** — Team Two's page UI had not landed when I checked (MainActivity is being actively edited — mtime moved 13:07 → 13:20 during my run — but there is still no `showPages`). Per my orders, the final v0.3 build is left to Monitoring once the UI lands. Zero permissions preserved (manifest still has 0 `uses-permission`; no network/WebView/location code anywhere in my files).

---

## 1. Remove/rename APIs for ALL entity types + tombstones (R2-N1) — Task 1

**Db check first:** `Db.upsertRecord` already persists `Record.alive` into the row's `alive` column, so a sink-emitted `Record(alive = false)` lands in the mirror exactly as `Db.softDelete`'s end state (row kept, `alive=0`). No Db.kt change was needed; the fix is entirely on the Store side: every removal now emits such a tombstone (carrying the entity's last JSON as `dataJson`), and every rename/update emits the updated alive record. **After this change no Store mutation path can diverge the mirror** — the remaining divergence risk is only UI code that still mutates Store lists/fields directly (Team Two's rewire list is in §7).

New/changed Store APIs (all return `Boolean` = success; `false` = missing id / invalid input, nothing changed):

| Entity | Rename / update | Remove | Single restore |
|---|---|---|---|
| Channel | `renameChannel(id: String, newName: String)` | `removeChannel(id)` — **cascades**, see §4 | `restoreChannel(channel: Channel, index: Int = channels.size)` |
| Script | `renameScript(id, newTitle)` (= `updateScript(title=)`); `updateScript` extended, see §2 | `removeScript(id)` | `restoreScript(script, index = scripts.size)` |
| Idea | `renameIdea(id, newTitle)`; **`updateIdea(id, title: String? = null, notes: String? = null, channelId: String? = null)`** — NEW (ideas had no update API; the UI's direct `idea.channelId =` at MainActivity:1314 can now route here) | `removeIdea(id)` | `restoreIdea(idea, index = ideas.size)` |
| Task | `renameTask(id, newTitle)`; **`updateTask(id, title: String? = null, dueDate: String? = null, channelId: String? = null)`** — NEW | `removeTask(id)` | `restoreTask(task, index = tasks.size)` |
| Page | `renamePage(id, newTitle)` | `removePage(id)` — **cascades**, see §5 | `restorePage(page, index = pages.size)` |
| Block | `updateBlockText(blockId, text)` is the block rename (also `updateBlock`, §5) | `removeBlock(blockId)` | `restoreBlock(block, index = blocks.size)` |

Conventions for `updateIdea`/`updateTask` (same as `updateScript`): null param = unchanged; `channelId = ""` clears the channel (task becomes global / idea unassigned), a non-empty channelId must exist or the call returns false changing nothing; `updateTask` validates `dueDate` before mutating (`""` clears it).

**Remove semantics (all types):** the other side of every link the entity takes part in is cleaned and that entity re-emitted, then the tombstone is emitted. E.g. `removeScript` clears its idea's `linkedScriptId`, every linked task's `linkedScriptIds`, every linked page's `linkedScriptIds`. `removeBlock` additionally renormalises the page's remaining block orders.

**Single-restore semantics:** `restoreX(entity, index)` re-inserts a copy with the original id at the clamped index and emits it; returns false if the id is already taken. It restores the entity only — links that a remove cleaned on *other* entities are **not** restored by these (that is what the cascade snapshots in §4/§5 are for).

**Import ghost rows (rest of R2-N1):** `loadFromJsonString` now captures the pre-load id sets and, on a successful load, emits a tombstone for every pre-load id absent from the new dataset (all six types) before syncing the new state. A stale mirror row from a previous dataset can no longer survive an import as `alive=1`.

## 2. `updateScript` gains `channelId` — Task 2

```kotlin
fun updateScript(
    id: String,
    title: String? = null,
    body: String? = null,
    language: Language? = null,
    date: String? = null,
    channelId: String? = null   // NEW, appended last — existing named/positional callers unaffected
): Boolean
```

Semantics: `channelId == null` **or empty** = leave the channel unchanged; a non-empty `channelId` must name an existing channel, validated **before any field is mutated** (same validate-first pattern as `date`) — a missing channel returns false and changes nothing, title included. On success `updatedAt` bumps and the emitted Record's `parentId` is the new channel.

**⚠ For Team Two (do not edit MainActivity myself, per file ownership):** the last direct `script.channelId =` site is **MainActivity.kt:1063**, in the script edit screen's Save handler:

```kotlin
if (script.channelId != editChannelId) script.channelId = editChannelId
```

It can now be deleted and folded into the `updateScript(...)` call directly above it (line ~1066) as `channelId = editChannelId`. I verified there is exactly one such site (`script.channelId =` assignment; the `idea.channelId =` at line 1314 is the idea-reassign path, now covered by `updateIdea(channelId = …)`).

## 3. Import per-key type validation (R2-N4) — Task 3

**Design (least invasive):** `Store.loadFromJsonString(s: String)` now returns **`Boolean`** (was `Unit`). Both existing MainActivity call sites use it as a statement, so they still compile unchanged — verified by my full-app compile check. `true` = loaded; `false` = rejected, **live store completely untouched**.

Rejection rule: after parsing, if any known top-level key — `channels`, `scripts`, `ideas`, `tasks`, `pages`, `blocks` — is present but its value is **not a JSON array** (number, string, object, boolean, or `null`), the whole load returns false before anything is parsed into entities or mutated. Malformed JSON / non-object top level also returns false (previously silent no-op). Test-proven: `{"channels": 5}` (and the string/object/null/bool variants for other keys) leave channel count, script count and script contents exactly as they were.

**⚠ For Team Two:** the UI import guard in MainActivity (~line 684) still only checks `JSONObject.has(...)` and ignores the return value, so a wrong-type file would still toast "Import complete" even though the store (correctly) refused it. Rewire: either check each present known key with `parsed.optJSONArray(key) != null`, or simplest — use the new return value: `if (!store.loadFromJsonString(text)) { toast("Import failed — …"); return }` and only persist/toast success on `true`. Same one-line check applies to `restoreStore()` if desired (not required; a corrupt state file returning false just leaves the seeded/empty store, same as before).

## 4. CASCADE channel delete + restore (Jervis's decided policy) — Task 4

**Policy as implemented:** deleting a channel deletes its **scripts, its tasks, and its ideas** (ideas carry a `channelId` too — cascaded consistently, as the task anticipated). Both entry points cascade; there is no orphan-leaving channel delete left in the Store.

```kotlin
fun removeChannel(id: String): Boolean                       // cascade, no snapshot (for callers that don't need undo)
fun removeChannelCascade(id: String): ChannelCascadeSnapshot? // null if channel missing
fun restoreChannelCascade(snapshot: ChannelCascadeSnapshot): Boolean
```

**`ChannelCascadeSnapshot`** (Models.kt) holds deep copies captured *before* any mutation: the channel + its index; `(originalIndex, copy)` pairs for every cascaded script/task/idea; and pre-delete copies of every **survivor** whose links pointed at a removed entity (survivor scripts/tasks/ideas/pages).

**Removal does, in order:** clean survivor links (dead ids removed from `linkedTaskIds`/`linkedScriptIds`/`linkedIdeaIds`/`linkedScriptIds` lists, `linkedIdeaId`/`linkedScriptId` nulled when they pointed at a removed entity) and re-emit each changed survivor → remove the cascaded entities and the channel → emit a tombstone for every single removal.

**Restore does:** refuse (false, nothing changed) if the channel id or any cascaded id already exists → re-insert channel/scripts/tasks/ideas at their original indices (clamped) with original ids and *all* their own link fields intact → surgically re-add survivor links to restored entities (only ids that were in the snapshot and are currently missing are re-added, and single-links only if currently null — links a survivor gained *after* the delete are kept) → emit alive records for every restore and every repaired survivor. Test-proven end-to-end: indices, both sides of internal task↔script and idea↔script links, and all four survivor-link directions (task→script, script→task, task→idea, page→script) come back consistent (`Logic.taskLinkConsistent` / `ideaLinkConsistent` true).

## 5. NOTION ENGINE data layer — Task 5 (Team Two: this is your contract)

### 5.1 Page model — design decision

A page **is** a record of type `"page"` per FINDINGS §7.1. Implementation: a first-class `Page` entity in `Store.pages`, JSON-persisted under a new top-level **`"pages"`** array, and sink-emitted with **`tableName = "block"`** and `"type":"page"` inside `dataJson` (parent_id = `parentPageId`) — the Notion-faithful choice of the two the task offered. Page ids and block ids share the global uid space, so the mirror's `block` table never collides.

```kotlin
data class Page(
    var id: String,
    var title: String,
    var parentPageId: String? = null,                 // null = root page
    var linkedScriptIds: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0,
    var updatedAt: Long = 0
)
// Store.pages: MutableList<Page>
```

Page APIs:

```kotlin
fun addPage(title: String, parentPageId: String? = null): Page?   // null if a non-null parent doesn't exist
fun pageById(id: String?): Page?
fun renamePage(id: String, newTitle: String): Boolean             // bumps updatedAt, emits
fun rootPages(): List<Page>                                       // parentPageId == null, list order
fun childPages(id: String): List<Page>                            // direct children, list order
fun descendantPageIds(id: String): Set<String>                    // id + all descendants, cycle-safe
fun pageAncestors(id: String): List<Page>                         // breadcrumb, root-first, excludes self
fun movePage(pageId: String, newParentId: String?): Boolean      // null parent = move to root
fun removePage(id: String): Boolean                               // cascades (below)
fun removePageCascade(id: String): PageCascadeSnapshot?
fun restorePageCascade(snapshot: PageCascadeSnapshot): Boolean
fun restorePage(page: Page, index: Int = pages.size): Boolean
```

`movePage` returns false (nothing changed) when: page missing, new parent missing, new parent is the page itself **or one of its descendants** (cycle prevention), or the page is already under `newParentId` (no-op).

**Page delete semantics (cascade, disclose+undo like channels):** removing a page removes **the page, all descendant pages, and every block belonging to any removed page**; scripts linked to a removed page have the dead id stripped from `linkedPageIds` (re-emitted); tombstones are emitted for every removed page and block. `PageCascadeSnapshot` (pages + blocks as `(originalIndex, copy)` pairs + survivor-script copies) restores all of it via `restorePageCascade` with the same refuse-if-id-exists and surgical-survivor-relink rules as §4.

### 5.2 Blocks — extended model + editor APIs

```kotlin
data class Block(
    var id: String, var pageId: String,
    var type: String = "text", var text: String = "",
    var order: Int = 0, var parentBlockId: String? = null,
    var checked: Boolean = false,      // NEW: to-do checkbox (type "todo")
    var collapsed: Boolean = false     // NEW: toggle state (type "toggle")
)
```

Valid types (`Logic.BLOCK_TYPES`, `Logic.isValidBlockType(type)`): **`text`, `heading`, `bulleted_list`, `numbered_list`, `todo`, `toggle`, `quote`, `code`, `divider`** — the seven required types plus numbered_list/divider. (`"page"` is deliberately not a content-block type; pages are Page records.) Unknown types are **rejected**: `addBlock` throws `IllegalArgumentException` (signature kept non-null for compatibility — see below), `updateBlockType`/`updateBlock` return false changing nothing. JSON *loading* stays lenient (an unknown stored type is preserved, not rewritten).

```kotlin
fun addBlock(pageId: String, type: String = "text", text: String = "",
             checked: Boolean = false, collapsed: Boolean = false): Block  // signature-compatible: new params defaulted; appends at max order + 1; THROWS on unknown type
fun blockById(id: String?): Block?
fun blocksForPage(pageId: String): List<Block>                 // unchanged, order-sorted (works with Page ids directly)
fun updateBlockText(blockId: String, text: String): Boolean    // the block rename; emits
fun updateBlockType(blockId: String, type: String): Boolean    // "turn into"; false on unknown type/missing block
fun updateBlock(blockId: String, text: String? = null, type: String? = null): Boolean  // null = unchanged; unknown type -> false, nothing changed
fun setBlockChecked(blockId: String, checked: Boolean): Boolean
fun setBlockCollapsed(blockId: String, collapsed: Boolean): Boolean
fun moveBlock(blockId: String, targetIndex: Int): Boolean      // see below
fun moveBlockUp(blockId: String): Boolean                      // false if missing or already first
fun moveBlockDown(blockId: String): Boolean                    // false if missing or already last
fun removeBlock(blockId: String): Boolean                      // tombstone + renormalise page orders
fun restoreBlock(block: Block, index: Int = blocks.size): Boolean
```

`moveBlock(blockId, targetIndex)`: targetIndex is the 0-based position in the page's order-sorted list, clamped to range; after the move the page's `order` values are **normalised to 0,1,2,…** and every block of the page is emitted. Returns true whenever the block exists (even if already at the target — orders are still normalised, which also repairs scrambled/imported orders); false only for a missing block.

**Backward compatibility (test-proven):** v0.2 JSON without `checked`/`collapsed` loads them as `false`; the full v0.3 round-trip preserves checked/collapsed, page tree, and all new link fields.

### 5.3 Page ↔ script linking (many-to-many, both sides — exact `linkTaskToScript` pattern)

Model: `Script.linkedPageIds: MutableList<String>` (NEW) + `Page.linkedScriptIds`. JSON-persisted on both sides, sink-emitted on both sides. Helper: `Logic.pageLinkConsistent(page, script)`.

```kotlin
fun linkPageToScript(pageId: String, scriptId: String): Boolean    // false if either missing or already linked; repairs one-way links
fun unlinkPageFromScript(pageId: String, scriptId: String): Boolean
fun scriptsForPage(pageId: String): List<Script>
fun pagesForScript(scriptId: String): List<Page>
```

### 5.4 Idea ↔ task linking (last missing pairing — all six pairings now exist)

Model: `Idea.linkedTaskIds: MutableList<String>` (NEW) + `GTask.linkedIdeaIds: MutableList<String>` (NEW). Same contract, JSON + sink. Helper: `Logic.ideaTaskLinkConsistent(idea, task)`.

```kotlin
fun linkIdeaToTask(ideaId: String, taskId: String): Boolean
fun unlinkIdeaFromTask(ideaId: String, taskId: String): Boolean
fun tasksForIdea(ideaId: String): List<GTask>
fun ideasForTask(taskId: String): List<Idea>
```

## 6. Tests — Task 6

`tests/TestMain.kt` extended in place; **all 125 prior checks pass unchanged** (one internal variable rename in my own new section during development; no prior check was edited). 180 new checks cover: rename/remove sink emission + tombstones for every type; link cleanup on remove; `updateScript` channelId (move, missing-channel atomicity, null/empty = unchanged, emitted parentId); import validation (`{"channels": 5}` + string/object/null/bool variants — store provably untouched — plus valid loads returning true); ghost-row tombstoning on load; channel cascade + full restore (indices + every link direction); page tree (create/nest/ancestors/reparent/cycle rejection); page cascade + restore (blocks, script links); all 9 block types + unknown-type rejection; block edit/turn-into; move/reorder with normalised, stable orders; remove renormalisation; todo checked + toggle collapsed persistence; v0.3 JSON round-trip; **v0.2-format backward compatibility** (no `pages` key, no new link keys, blocks without checked/collapsed). Results written from the real run to `tests/TEST-RESULTS.txt`: **305 checks, 0 failures, RUN_EXIT=0**.

## 7. What Team Two / Monitoring must know

1. **Rewire points for the UI (R2-N1 completion):** channel/script/idea/task/page/block delete + rename dialogs and the four `deleteXWithUndo` functions should now call the Store APIs in §1/§4/§5 instead of mutating `store.channels`/`scripts`/… lists or entity fields directly. For undoable deletes use `removeChannelCascade`/`restoreChannelCascade` and `removePageCascade`/`restorePageCascade`; for single entities, hold the removed object + its index and use `restoreX(entity, index)` (links cleaned on *other* entities are not restored by the single form — documented in §1).
2. **MainActivity:1063** — fold the direct `script.channelId =` into `updateScript(channelId = editChannelId)` (§2). **MainActivity:1314** — `idea.channelId =` can route through `updateIdea(channelId = …)` (§1).
3. **Import UI guard** — check `loadFromJsonString`'s new Boolean return (§3), otherwise the UI still toasts success on a refused import.
4. **Build:** manifest is at versionCode 3 / 0.3; `build.sh` needs no change (it derives `GhostBladeNotion-v0.3.apk` from the manifest). I did **not** run build.sh — no `showPages` in MainActivity yet, so per orders the final build is Monitoring's, after Team Two's page UI lands. My full-source compile check (exit 0) says the integration will compile when it does.
5. **COMPARISON.md deltas Monitoring may want** (not mine to edit): interlinking is now **6 of 6 pairings** (idea↔task landed, data layer + tests; UI is Team Two's call) — the "partial" verdict from R2 §3 can be revisited once UI exists; pages/blocks rows now have a complete data layer (UI pending Team Two).
6. Nothing pushed to GitHub; no repo created; no permissions added; no WebView/network/location code.
