# GhostBlade Notion — Round 3 · Team Two (features/UI) report

**Scope owned:** `src/com/ghostblade/notion/MainActivity.kt`, `src/com/ghostblade/notion/Ui.kt` (no changes needed — all new rendering uses existing helpers + framework typefaces), this file. No other files edited.
**Build:** `./build.sh` **PASSED** (exit 0), twice, byte-identical. Output `GhostBladeNotion-v0.3.apk`, **742,422 bytes**, SHA-256 `08d5a14edf9eb3caccdaf1affeb2d85c9c4a40e7eded2c50d7210c8c2a12cf7f` (versionCode 3 / versionName 0.3, manifest bumped by Team One). Only warnings: the pre-existing `onBackPressed` deprecation (carried finding N-10). `aapt2 dump permissions` on the built APK: **zero permissions**; manifest still has 0 `uses-permission`; no network/WebView/location code in my files. *Preliminary numbers — Monitoring rebuilds independently.*

**Timing:** Team One's data layer landed during this pass, exactly as briefed. I waited, re-read Models.kt/Store.kt/Logic.kt + `FEATURES-R3-TEAMONE.md`, and wired everything against the **real** landed signatures — no stubs, no invented Store APIs in MainActivity.

---

## Store APIs consumed (all Team One, Round 3 unless noted)

- **Pages:** `addPage(title, parentPageId?)`, `pageById`, `renamePage`, `rootPages`, `childPages`, `pageAncestors` (breadcrumb), `descendantPageIds` (delete-disclosure counts), `removePageCascade` / `restorePageCascade`.
- **Blocks:** `addBlock(pageId, type)`, `blocksForPage`, `blockById`, `updateBlockText`, `updateBlockType`, `setBlockChecked`, `setBlockCollapsed`, `moveBlockUp` / `moveBlockDown`, `moveBlock` (order re-normalisation after a block restore), `removeBlock`, `restoreBlock`.
- **Links:** `linkPageToScript` / `unlinkPageFromScript` / `scriptsForPage` / `pagesForScript`; `linkIdeaToTask` / `unlinkIdeaFromTask` / `tasksForIdea` / `ideasForTask` (plus the Round 2 task↔script helpers, unchanged).
- **Close-out rewires:** `renameChannel`, `updateIdea` (idea rename dialog, idea channel reassignment, and a no-op emit-flush — see below), `updateTask` (task rename dialog), `updateScript(..., channelId = ...)` (script edit screen), `removeScript` / `restoreScript`, `removeIdea` / `restoreIdea`, `removeTask` / `restoreTask`, `removeChannelCascade` / `restoreChannelCascade`, and `loadFromJsonString`'s new **Boolean** return in the import handler.

---

## Per-feature status

### 1. Pages section + page tree — ✅ DONE
- **Where:** Home nav gains a "📄 Pages" button → `showPages()`; `renderPageTree()`, `pageMenu()`, `addSubPage()`, `renamePageDialog()`.
- Tree lists root pages (`rootPages()`); child pages render indented under their parent (16dp per depth) behind a ▸/▾ expander (expanded set kept in `expandedPageIds`); each row shows block count + sub-page count, a ⋯ menu (Open / Rename / Add sub-page / Delete) and long-press. Depth cap 12 + ancestor-set cycle guard so cyclic imported data can't recurse forever.
- Create page at root ("＋ New page" → title dialog → opens the new page) or inside a page ("＋ Sub-page" on the page screen / menu). Rename via dialog → `Store.renamePage` (emits).
- **Page screen** (`showPage()`): title header, **breadcrumb** — "Pages › ancestor › … › this page" from `Store.pageAncestors` (root-first), every crumb tappable, horizontally scrollable; Rename / ＋ Sub-page / Delete page actions; Sub-pages section (cards with counts, tap to drill in, ⋯ menu); the block editor (feature 2); Linked scripts (feature 4). Back button and system Back walk up to the parent page, then the Pages list.

### 2. Block editor — ✅ DONE
- **Where:** `showPage()` blocks section, `renderBlockGroup()`, `blockRowView()`, `blockMenu()`, `addBlockFlow()`, `showBlockEdit()`, `deleteBlockWithUndo()`.
- Renders the page's blocks in `blocksForPage` order, type-appropriate:
  - **text** — plain 15sp (muted placeholder when empty)
  - **heading** — 20sp bold
  - **bulleted_list** — "• " prefix
  - **numbered_list** — running number over consecutive numbered siblings
  - **todo** — tappable ☐/☑ (`Store.setBlockChecked` in place, strikethrough + grey when checked)
  - **toggle** — header row with ▸/▾ (`Store.setBlockCollapsed` in place); first line of the block text is the title, the remaining lines are the body, shown only while expanded; child blocks whose `parentBlockId` points at the toggle (e.g. from imported data) also render indented under it while expanded
  - **quote** — gold left bar + italic text
  - **code** — monospace on a shaded panel
  - **divider** — hairline rule
- Interactions: **＋ Add block** with a type picker (all 9 types; divider inserts immediately, others open the editor); tap any block to edit it in `showBlockEdit()` — a draft-autosaved editor using the exact Round 2 pattern (SharedPreferences draft on every keystroke + `onPause` flush via `draftSaver`, restored when it differs from the saved text, cleared on Save/delete); ⋯ menu (and long-press) with **Edit / Move up / Move down / Turn into… (type picker → `updateBlockType`) / Delete**. Delete uses the undo bar (no confirm): undo = `restoreBlock` + `moveBlock` to re-normalise the page's orders. Move actions are offered for top-level blocks (child blocks get Edit / Turn into / Delete).

### 3. Pages in Search — ✅ DONE
- `showSearch()` now also matches **pages**: title, plus the text of any of the page's blocks (`blocksForPage`). Results carry a new **PAGE** type chip; the sub-line shows "Block: <snippet>" for a block-text hit, "Inside: <parent title>" for a nested page, or "Root page". Tapping a page result opens the page. Header subtitle updated to say pages + block text are searched.

### 4. Linking UI — ✅ DONE (all pairings now have UI both ways)
- **Page ↔ script:** page screen "Linked scripts" section — linked script cards (tap to open), per-script **Unlink**, and a "🔗 Link a script" picker (excludes already-linked). Script screen gains the mirror "Linked pages" section — tappable page cards, per-page Unlink, "🔗 Link a page" picker.
- **Idea ↔ task:** idea detail gains "Linked tasks" (cards → task detail, Unlink, "🔗 Link a task" picker); task detail gains "Linked ideas" (cards → idea detail, Unlink, "🔗 Link an idea" picker). Both follow the existing task↔script UI pattern exactly, through persist-on-success helper wrappers like Round 2's.

### 5. Close-out rewires (MONITOR-REPORT-R2 R2-N1) — ✅ DONE
Every UI mutation now routes through a Store API that emits to the Db sink:
- **Renames:** channel dialog → `renameChannel`; idea dialog → `updateIdea(title, notes)`; task dialog → `updateTask(title, dueDate)` (`""` clears the date, matching the blank field); page dialog → `renamePage`.
- **Deletes:** the four `deleteXWithUndo` functions now call `removeScript` / `removeIdea` / `removeTask` (+ cascade APIs below) instead of `MutableList.removeAt` + manual link-field clears. Undo uses `restoreX(entity, index)` and re-links the other sides through the Store link APIs (`linkTaskToScript`, `linkPageToScript`, `linkIdeaToTask`). The script delete also captures page links (new in v0.3) and clears the script's retained editor drafts (**R2-N7** fixed in passing).
  - *One documented exception:* the idea↔script single-link has no Store link API (it is created only by `startScriptFromIdea`), so on undo of a script/idea delete the one field (`idea.linkedScriptId` / `script.linkedIdeaId`) is set directly and immediately flushed to the sink with a no-op `updateIdea(id)` / `updateScript(id)` call (all-null params = unchanged, but it emits the current state). Two sites, both commented. Net effect: the mirror still receives every mutation.
- **Idea channel reassignment** (idea detail) → `updateIdea(channelId = …)`.
- **Script channel move:** the last direct `script.channelId =` assignment is **deleted**; the edit screen's Save now passes `channelId = editChannelId` into `updateScript` (validated by the Store before any mutation).
- **Channel delete — decided cascade policy:** `deleteChannelWithUndo` now opens a confirmation dialog first (see wording below), then `Store.removeChannelCascade`; Undo = `Store.restoreChannelCascade` — channel **and** cascaded scripts/tasks/ideas **and** survivor links fully restored.
- **Import guard (R2-N4):** two layers — the UI pre-check now also verifies every present known key (`channels`/`scripts`/`ideas`/`tasks`/`blocks`) is a JSON array via `optJSONArray`, AND the handler checks `loadFromJsonString`'s Boolean return, toasting failure instead of "Import complete" when the Store refuses. `{"channels": 5}` now shows an error and never reaches the store.
- **Bonus fix (R2-N6):** the script-edit draft-restore condition now also triggers on language-only or channel-only draft changes (Round 2 compared title/date only).
- Dead `pickScript()` helper removed (R2-N9).

**Delete/cascade disclosure wording used (verbatim):**
- Channel: title "Delete channel?", message — *"“\<name\>” will be deleted along with its \<s\> script(s), \<t\> task(s) and \<i\> idea(s). You can undo right after deleting."* Buttons: Delete / Cancel. Undo bar: *"Channel “\<name\>” and its contents deleted"*.
- Page: title "Delete page?", message — *"“\<title\>” will be deleted along with \<n\> sub-page(s) and \<m\> block(s) inside them. Scripts linked to these pages will be unlinked, not deleted. You can undo right after deleting."* Buttons: Delete / Cancel.

### Build — ✅ DONE
See header. Two consecutive `./build.sh` runs produced byte-identical APKs (742,422 bytes, SHA-256 `08d5a14e…f7f`), zero permissions verified on the built APK via `aapt2 dump permissions`. No GitHub push, no repo created, `~/MEMORY.md` untouched, no files outside my ownership edited.

---

## Known limitations / notes for Monitoring
- **Toggle body model:** a toggle's body is the text after its first line (plus any `parentBlockId` child blocks present in data). There is no Store API to *create* a child block (`addBlock` has no parent parameter), so the UI cannot create toggle children this round — the editor explains the first-line/body convention on the toggle edit screen.
- **Block move on child blocks** is not offered (move APIs operate on the page's whole order list); children keep Edit / Turn into / Delete.
- Undo remains single-slot and memory-only (R2-N5, unchanged — a trash screen is still future work).
- `moveBlockUp/Down` act on the page's full order-sorted list; on a page containing child blocks, a top-level move could in principle swap past a child in the global order. Pages created in the UI have no child blocks, so this is data-import-only cosmetic risk.

## Recommended COMPARISON.md deltas (recommendation only — Monitoring judges)
| Row | v0.2 status | Recommended v0.3 status | Why |
|---|---|---|---|
| Pages / nested pages | missing | **present** | Full page tree UI: create/rename/delete (cascade + undo), nesting via parentPageId, drill-in + expandable tree, breadcrumb from the ancestor chain. |
| Block editor | missing | **present** | Editor on the page screen renders/edits all 9 block types (text, heading, bulleted/numbered lists, todo with in-place checkbox, toggle with collapse, quote, code, divider), with add/type-picker, tap-to-edit with draft autosave, move up/down, turn-into, delete-with-undo. (Media blocks remain missing — separate row.) |
| Columns, breadcrumb, table of contents | missing | **partial** | Breadcrumb is now real on the page screen (tappable ancestor chain). Columns and TOC still absent — hence partial, not present. |
| Search across workspace | present | present (update row text) | Now also searches page titles and block text; PAGE results open the page. Still in-memory filtering, no FTS. |
| Interlinking (ideas ↔ scripts ↔ tasks ↔ channels) | partial | **present** | All six pairings now exist at the data layer AND in the UI, both directions: idea↔task and page↔script UIs landed this round, joining idea↔script, task↔script, channel↔script/task/idea. |
| Workspaces + teamspaces, sidebar page tree | missing | missing (update note) | Still single-user/no sidebar by design, but a real page tree now exists on the Pages screen — the row text should stop saying "no page tree". |
| Bugs table | — | — | Mark **resolved**: R2-P2-1 channel-delete orphans (decided cascade + disclosed confirm + full undo), R2-N1 Db divergence on UI mutations (all renames/deletes/moves route through emitting Store APIs; one commented emit-flush exception for the idea↔script undo relink), R2-N4 import type-blindness (UI per-key array check + Store Boolean), R2-N6 edit-draft restore condition, R2-N7 per-script draft cleanup, R2-N9 dead `pickScript()`, and the "script channel move bypasses updateScript" P3. |

*Team Two — Round 3 features/UI pass complete. Build green (byte-identical across two runs), zero permissions, no files outside my ownership touched.*
