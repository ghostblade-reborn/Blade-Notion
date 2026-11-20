# GhostBlade Notion v0.4 — Monitoring Team Report (Round 4)

**Date:** 2026-11-20 (local time)
**Monitor role:** Independent verification only. No `.kt` source, test, manifest, or build.sh was modified by the Monitoring Team. Files written by Monitoring this round: this report, COMPARISON.md (status updates), github-staging/README.md (v0.4 facts), WORKLOG.md (Round 4 entry appended). My rebuilds only regenerated the APK outputs via `./build.sh` as-is — byte-identical to the builders'.
**Package:** `com.ghostblade.notion` · versionCode 4 · versionName 0.4 · minSdk 24 · targetSdk 34
**Wait condition:** Verification started only after the round settled — `GhostBladeNotion-v0.4.apk`, `FEATURES-R4-TEAMONE.md` and `FEATURES-R4-TEAMTWO.md` all present and `tests/TEST-RESULTS.txt` updated for v0.4 (492 checks). Team One's report landed first (~13:53 UTC), the APK at ~14:01 UTC and Team Two's report at ~14:02 UTC; I confirmed the APK hash, both reports, TEST-RESULTS.txt and all six sources were **unchanged across two polls** before trusting them. One honesty note on the evidence chain: Team One's TEST-RESULTS.txt full-app compile note says it ran against the settled v0.3 UI (Team Two's UI had not landed yet) — that note is accurate as written, and my own full six-source compile below covers the final v0.4 UI.

## Verdict: **PASS-WITH-NOTES**

Every headline builder claim reproduces under my own runs: the build is **byte-reproducible** (my two clean rebuilds are byte-identical to each other *and* to the builders' APK, at the exact claimed hash), the JVM suite passes **492/492** on my own compile-and-run with PASS lines identical to `tests/TEST-RESULTS.txt`, and the zero-permission / fully-offline guarantee holds under all three checks with `allowBackup="false"` confirmed in the manifest **and** in my built APK. Every Round 3 close-out is **truly fixed in code** (traced UI → Store → persist, not just grepped): page move UI on the tested `movePage`, toggle child blocks authorable, sibling-scoped block moves, the eight-key import list, and the last direct link-field assignment retired onto `Store.linkIdeaToScript` — MainActivity now contains **zero** direct assignments to `linkedScriptId`/`linkedIdeaId`. Templates and the persisted Trash are real end-to-end, and Back is migrated to `OnBackInvokedCallback` on API 33+ with sound no-double-handling reasoning.

The notes are all P3: a Trash-screen restore of a single entity doesn't re-link the other sides (the undo-bar path does), template delete has no trash/undo, the import "has data" guard still checks only the four original keys, and there is a second — justified, disclosed — `catch (Throwable)` in the back-callback registration. No P1, no P2. Details below.

---

## 1. Independent verification table (Monitoring Team's own runs)

| # | Claim | Verified? | My evidence |
|---|---|---|---|
| 1 | Clean rebuild → `GhostBladeNotion-v0.4.apk`, 762,902 bytes, SHA-256 `b1065622…4191` | ✅ YES | Builders' on-disk APK already matched (762,902 bytes, SHA-256 `b10656223f39bceb7a87664d504c5379b5e3e9b67f8c2e1029b13a1bf5fa4191`). My rebuild #1 and #2 both exit 0, both **762,902 bytes**, both the same SHA-256. |
| 2 | Byte-identical across builds (`cmp`) | ✅ YES | `cmp` on my two builds: **identical**. `cmp` of my build vs the builders' APK: **identical**. All **14 zip entries** stamped **1980-01-01 00:00:00**. Byte-reproducibility holds for v0.4. |
| 3 | `apksigner` verifies; badging | ✅ YES | `apksigner verify --verbose`: **Verifies**, v2 true, v3 true, 1 signer. `aapt2 dump badging`: package `com.ghostblade.notion`, versionCode **4**, versionName **0.4**, minSdk 24, targetSdk 34. Only build warning: the `super.onBackPressed()` deprecation inside the retained API <33 fallback (expected — see §4e). |
| 4 | JVM tests: 492 checks, 0 failures (305 prior + 187 new) | ✅ YES | My own compile (`kotlinc Models.kt Logic.kt Store.kt tests/TestMain.kt`, kotlinc 2.0.21, JDK 21, android.jar on cp, exit 0) and run (kotlin-stdlib jar on cp): **`492 checks, 0 failures`, exit 0**. The 492 PASS lines are **identical** (sorted diff) to `tests/TEST-RESULTS.txt`. Db compile-only check reproduced: exit 0. Full six-source compile (incl. the final v0.4 MainActivity/Ui) reproduced: exit 0, only the fallback deprecation warning. (One false start in my own run — a `~` in the middle of a `java -cp` argument doesn't expand, so the stdlib wasn't found; fixed by using `$HOME`. My harness error, not a code issue.) |
| 5 | Zero permissions (a) manifest | ✅ YES | `grep -c uses-permission AndroidManifest.xml` = **0**; no "permission" string anywhere in the manifest. |
| 6 | Zero permissions (b) built APK | ✅ YES | `aapt2 dump permissions` on **my** built APK prints only `package: com.ghostblade.notion`, no permission lines. |
| 7 | Zero permissions (c) source scan | ✅ YES | Grep over all `.kt` for `java.net`, `HttpURLConnection`, `Socket`, `URL(`, `LocationManager`, `Firebase`, `analytics`, `WebView`, permission constants: **no hits** except one comment mention of "no WebView" in MainActivity's class doc. Dex `strings`: the only "http" hit is the Kotlin stdlib's own youtrack.jetbrains.com URL in a stdlib message — not app code (same as Rounds 1–3). |
| 8 | `android:allowBackup="false"` | ✅ YES | Present in AndroidManifest.xml **and** confirmed in my built APK via `aapt2 dump xmltree`: `allowBackup=false`, `usesCleartextTraffic=false`. |
| 9 | No GitHub work | ✅ YES | Still no `.git` anywhere in the project tree; `github-staging/` still contains only `README.md` (updated to v0.4 facts by Monitoring this round). No repo created, nothing pushed. |

---

## 2. Per-item verdicts (code-traced: UI → Store → persist)

### §4(a) Table + board views — ✅ WORKS
- **Scripts table** (`renderScriptTable`, reached from `showChannel` via the List | Table | Board switcher): header cells Title / Lang / Status / Date / Updated, each wired to `onScriptSort(ScriptSort.…)` — new key sorts ascending, tapping the active key toggles direction, active header shows ▲/▼ in accent. Sorting is `Logic.sortScripts` at render time. I read Logic.kt in full: TITLE is case-insensitive, LANGUAGE by enum name (BANGLA first ascending), STATUS by ordinal (DRAFT first), UPDATED numeric with stable ties, and DATE goes through `compareDatesNullsLast`, which returns the blank side as "greater" **before** any direction flip — null/blank dates are last in **both** directions, exactly as specified (and test-verified in my run: "DATE desc nulls STILL last" for both scripts and tasks). Row tap → `showScript(s.id)`. Updated renders as `yyyy-MM-dd` via `fmtDay` ("—" when 0).
- **Tasks table** (`renderTaskTable`, inside the shared `renderTasks`, so it appears on the channel screen and for global tasks on Home): headers Done / Title / Date via `Logic.sortTasks` (DONE ascending = open first). The ☐/☑ cell toggles via `Store.toggleTask` + persist + re-render; the rest of the row opens the task detail; done titles are struck through and muted.
- **Board** (`renderScriptBoard`): two equal-weight columns from `Logic.groupScriptsByStatus` — I read the implementation: a `linkedMapOf` with **both keys always present** (DRAFT then FINAL, empty column = empty list), input order preserved per group. Column headers show counts; card tap → `showScript`. Honest scoping in code and report: views over the channel's own collections only, nothing claims a generic database.

### §4(b) Templates — ✅ WORKS
- **Store (read in full):** `savePageAsTemplate` returns null on missing page / blank name; the snapshot is `blocksForPage` order, deep-copied, with `parentIndex` = the index in that ordered list of the block's parent when the parent is a block of the same page, else null. `createPageFromTemplate` creates the page via `addPage` (title param if non-blank, else the template name), then re-creates blocks **in order via `addBlock`**, mapping `parentIndex` → the new block's id; a self/later/out-of-range `parentIndex` degrades to top-level, and an invalid stored type falls back to `"text"` instead of failing creation. New page and blocks get fresh ids. `removeTemplate` / `renameTemplate` (blank → false) are as specified.
- **UI:** page screen button + page ⋯ menu "Save as template…" → name dialog → `savePageAsTemplate` → persist + toast; Pages screen Templates section lists `store.templates` with per-template "＋ New page from template" (→ `createPageFromTemplate` → persist → opens the new page), Rename and Delete (confirmation dialog — no undo, disclosed; see R4-N3).
- **Persistence:** `"templates"` is a top-level key in `toJsonString` and parsed back in `loadFromJsonString`; export and import both go through that same `toJsonString`, so templates are inside the export/import payload. The per-key validation list in `loadFromJsonString` covers all eight keys — `{"templates": 5}` returns false **before any mutation** (test-verified in my run: "import {templates:5} rejected", "templates untouched after bad-type import", plus the full template round-trip and create-from-round-tripped-template checks).

### §4(c) Close-outs — ✅ ALL CLOSED
- **R3-N1 page Move UI:** `movePageFlow` (page ⋯ menu + page-screen "Move to…" button) calls `Store.movePage`. The picker lists "Root (no parent)" plus pages in depth-first tree order, indented by `pageAncestors` depth, **excluding** `descendantPageIds(page.id)` — which I verified in Store.kt includes the page itself (the BFS seeds the queue with the id) — and excluding the current parent. A false return surfaces as a toast ("that would create a loop, or it is already there"); success persists and re-opens the page under its new breadcrumb.
- **R3-N2 toggle child blocks:** toggle ⋯ menu "＋ Add child block" → `addChildBlockFlow` → type picker → `store.addBlock(page.id, type, parentBlockId = toggle.id)` in try/catch → toast on failure; a collapsed toggle is expanded via `setBlockCollapsed` so the child is visible; the editor opens for the new block (divider inserts immediately). Rendering obtains children via `store.childBlocks(b.id)` in `renderBlockGroup` (with the old parentBlockId filter as an exception fallback) — newly authored and imported children flow through the same path. Store-side, `addBlock`'s new `parentBlockId` parameter validates that the parent exists **and belongs to the same page**, throwing `IllegalArgumentException` and adding nothing otherwise (test-verified).
- **R3-N4 import keys:** the UI `knownKeys` list in `onActivityResult` is now all eight keys — `channels, scripts, ideas, tasks, pages, blocks, templates, trash` — matching the Store's validation list exactly (both read). (A neighbouring guard still only checks four keys for "has any GhostBlade data" — safe direction, recorded as R4-N4.)
- **Link exception retired:** `Store.linkIdeaToScript` exists with the documented semantics (both fields set, both emitted; false on missing/conflicting links; **idempotent-true** on an already-consistent pair; one-way links repaired). Both `performUndo` relink sites call it. My own grep for direct assignments (`\.linked(Script|Idea)Id\s*=[^=]`) over MainActivity.kt: **zero matches** — the remaining mentions are reads (`==` captures before delete, navigation lookups) and comments. "No UI direct mutation" is now absolute for links.
- **R3-N3 sibling-scoped block move:** I read the new `moveBlock` in full. Siblings = same `pageId` **and** same `parentBlockId`; `targetIndex` is clamped to the sibling group's range; the full page sequence is rebuilt by refilling **only the sibling slots** in the new sibling order — blocks of other groups keep their exact slots — then the whole page's orders are normalised to 0,1,2,… and every block is emitted. A top-level move therefore cannot cross into or past a child group, and `moveBlockUp/Down` step within the group and return false at its ends. On flat pages the sibling group is the whole page, so v0.3 behaviour is preserved (all prior move tests pass unchanged in my run, alongside the new sibling-scope checks). The UI side is consistent: block ⋯ menus now offer Move for child blocks too, and `deleteBlockWithUndo` stores the **sibling index** in `UndoEntry.index` for the `moveBlock` repair in `performUndo`.

### §4(d) Trash — ✅ WORKS; R2-N5 closed as scoped (residuals are P3)
- **Trash screen** (`showTrash`, Home nav "🗑 Trash", back → Home): lists `store.trash` **newest first** (Store appends newest last; UI reverses), each card showing label, kind chip and "Deleted: yyyy-MM-dd HH:mm". Restore → `store.restoreFromTrash(id)` → persist; on false the entry stays and a toast explains (e.g. same-id item exists). "Delete forever" → confirm dialog → `store.permanentlyDeleteTrash(id)` (drops only the entry — verified in Store: no restore, entities were already deleted/tombstoned at delete time). "Empty trash" → confirm dialog stating the count → `store.emptyTrash()`. Empty-state note when there are no entries.
- **Delete flows — all six, not just the required three:** I read every `deleteXWithUndo`. Channel and page deletes keep their Round 3 disclosure dialogs (wording now adds "or restore it later from Trash") and call `deleteChannelToTrash` / `deletePageToTrash`; script/idea/task/block call `deleteScriptToTrash` / `deleteIdeaToTrash` / `deleteTaskToTrash` / `deleteBlockToTrash`. On a null ToTrash result each flow falls back to exactly the Round 3 path (`removeChannelCascade`/`removePageCascade` snapshot, or plain `removeX`). Script/block draft-clearing (R2-N7) is unchanged.
- **Restore semantics (Store, read in full):** singles store `{"index","entity"}` captured **before** removal and restore via `restoreX(entity, index)`; channel/page store the full cascade snapshot and restore via `restoreChannelCascade` / `restorePageCascade` — **contents + survivor links** included. On success the entry is removed from `trash`; on failure (unknown kind, corrupt payload, id collision) the entry is **kept** and false returned. All of this is test-verified in my run (per-kind kind/label checks, cascade restores incl. survivor task/page links, collision-keeps-entry, permanent-delete-does-not-restore, newest-last accumulation, plain `removeScript` creates NO entry).
- **Persistence:** `"trash"` is in `toJsonString`/`loadFromJsonString` with the same eight-key type validation (`{"trash": "x"}` rejected untouched — tested), so trash survives a JSON round-trip **including payloads** (restore-from-round-tripped-entry tested) and is inside the export/import payload (same `toJsonString`).
- **Undo bar:** still functions — it now rides on the trash path via `restoreViaTrash` (channel/page = the full cascade restore; singles = entity restore **plus** the Round 3 `clearedLinkIds` relink loops and the `linkIdeaToScript` relink, per Team One's wiring contract), with the legacy snapshot/entity paths as the documented fallback when no trash entry exists.
- **Judgement on R2-N5:** the substance of R2-N5 — a second delete or a process death in the 6-second window making a delete permanent, with no trash screen — is **gone**: deletes are persisted in a real bin, recoverable at any time, for all six entity types. What remains is smaller and different in kind: the undo *bar* itself is still single-slot (now just a shortcut into the trash), a Trash-screen restore of a single doesn't re-link other sides (R4-N2), and template deletes aren't trashed (R4-N3). **I close R2-N5 as scoped**, with the residuals tracked as new P3s.

### §4(e) onBackPressed migration (N-10) — ✅ MIGRATED, soundly
- The Round 3 back logic is extracted **verbatim** into `handleBack(): Boolean` (true = handled; false = let the system finish), with identical screen coverage — script/editor → channel or Home, scriptEdit → script, idea → Ideas, pages → Home, page → parent page else Pages list, blockEdit → page, task → its channel or Home — plus `"trash" -> Home` for the new screen.
- **API 33+:** `onCreate` calls `registerPredictiveBackCallback()` **only** when `SDK_INT >= 33`. It registers an `android.window.OnBackInvokedCallback` via `onBackInvokedDispatcher` (PRIORITY_DEFAULT) running `if (!handleBack()) finish()`, and sets `backCallbackRegistered` only on success. The API-33 types live inside that one method, which is never invoked below 33, so they are never touched on older devices — the correct framework-only pattern.
- **No double-handling:** when a callback is registered, the system delivers Back to it and does not invoke the override; the retained deprecated override additionally returns immediately if `backCallbackRegistered` is set (belt-and-braces), otherwise delegates to the same `handleBack()`. One logic path, two dispatchers, mutually exclusive.
- The registration is wrapped in `catch (Throwable)` with the flag left false on failure, so a registration failure degrades to the legacy override instead of crashing — disclosed by the builder and judged justified (see R4-N1). The single remaining deprecation warning in my builds points at the unavoidable `super.onBackPressed()` call inside the <33 fallback. **N-10 is closed.** Runtime dispatch on a real device can't be exercised from this VM (same limitation as prior rounds); the logic itself is unchanged from the verified Round 3 override.

---

## 3. Force-unwrap / crash sweep (§5)

- **`!!` sites: 5 total, all guarded.** MainActivity: `s.date!!` (×2 — each inside `if (!s.date.isNullOrBlank())`), `bodyDraft!!` (guarded by `restoredBody`, which requires the draft non-null/non-empty), `draftText!!` (guarded by `restored`, same pattern). Logic.kt: `a!!.compareTo(b!!)` inside `compareDatesNullsLast`, reached only after both-blank and either-blank returns — both values are provably non-blank there. No new unguarded force-unwrap on any R4 path.
- **`catch (Throwable)`: exactly two in the codebase.** `Store.emit()` swallowing sink failures (justified since Round 2 — mirror only, JSON authoritative) and the new one in `registerPredictiveBackCallback()` (see R4-N1). MainActivity's many other catches are `Exception`-typed, narrow, and follow the established best-effort pattern; none swallows a data-path failure silently — Store calls that matter check their Boolean/return value and toast on failure.
- New-path null safety: table/board renderers fall back to the unsorted list / empty map on exception and return early on empty input; template and trash UI null-check every Store result; `movePageFlow` handles orphaned/cyclic imported pages (unreached pages appended, ancestor-guarded); block rendering keeps its depth cap. No P1 found.

---

## 4. New findings, by severity

### P1 — crash / data-loss in normal paths
**None found.**

### P2 — significant
**None found, and none carried open.** R2-N5 is closed as scoped (§2d); N-10 is closed (§2e).

### P3 — minor / documentation / robustness
| ID | Finding |
|---|---|
| R4-N1 | **Second `catch (Throwable)` in the codebase** — `registerPredictiveBackCallback()` catches Throwable around the API 33+ dispatcher registration and falls back to the legacy override. Judged **justified** (framework-registration failure fallback, flag-scoped, builder-disclosed, no app logic inside the try). Recorded so earlier rounds' "only one Throwable catch" statement is not repeated unqualified. |
| R4-N2 | **Trash-screen restore of a single entity restores the entity only.** Links cleaned on *other* entities at delete time are not re-linked from the Trash screen (the undo-bar path does re-link them; channel/page cascade restores include survivor links). E.g. a script restored from Trash keeps its own `linkedTaskIds`, but the tasks' `linkedScriptIds` were cleaned — a one-way link until re-linked manually. Documented Store single-restore semantics, inherited from Round 3; the Trash screen makes the path user-visible for the first time, so it's worth a Round 5 fix (run the same relink repair after a Trash restore). |
| R4-N3 | **Template delete has no undo and creates no trash entry** (builder-disclosed). Confirmation dialog only. Defensible — a template is a snapshot and its source page is untouched — but it is now the only delete in the app with no recovery path. |
| R4-N4 | **UI import "has GhostBlade data" guard still checks only channels/scripts/ideas/tasks.** A file containing only templates/trash (or only pages/blocks) is rejected at the UI guard although the Store would load it. Same class of nit as the closed R3-N4, in the neighbouring guard; failure direction is safe (reject, never wipe). One-line fix: accept any of the eight keys. |
| — | Carried, still true: the undo bar is single-slot by design (mitigated by Trash — see §2d); `persistenceAvailable` still written, read nowhere; `Logic.addDays()` still throws on invalid input (latent, no UI callers). Export-via-share still puts the whole JSON in `EXTRA_TEXT` (Save-to-file is the reliable path for very large libraries). |

---

## 5. COMPARISON.md deltas — applied by Monitoring (each traced against code first)

I applied Team Two's recommended deltas where my tracing supports them, with the scoping stated honestly in the row text:

| Row | v0.3 | v0.4 (applied) | Basis |
|---|---|---|---|
| Templates / template buttons | missing | **present** | §2b: save/instantiate/rename/delete end-to-end through tested Store APIs; persists + export/import. Row notes the limits (page templates only, no template buttons, delete is confirm-only) |
| Databases — table view | missing | **present (scoped)** | §2a: real sortable tables — but the row text states plainly these are fixed-column tables over a channel's scripts/tasks, NOT generic user-defined databases |
| Databases — board/gallery/list/calendar/timeline views | missing | **partial** | §2a: list + board (scripts, DRAFT/FINAL) are real switchable views; gallery/calendar/timeline still absent — partial, not present |
| Database properties | partial | partial (unchanged, note added) | The table's sort keys are fixed schema fields; the row now says so explicitly. No user-defined properties appeared |
| Pages / nested pages | present | present (text updated) | Move/reparent UI landed (R3-N1 closed) |
| Block editor | present | present (text updated) | Toggle child blocks authorable; sibling-scoped moves (R3-N2/N3 closed) |
| Columns, breadcrumb, TOC | partial | partial (unchanged) | Nothing in this round touches columns or TOC; breadcrumb unchanged |
| Offline / local storage | present | present (text updated) | The v0.3 emit-flush exception is retired; templates/trash deliberately JSON-only (not Db-mirrored) — named in the row |
| Import / export (JSON) | present | present (text updated) | Eight-key validation in both layers; templates + trash inside the payload |

**Mechanical recount after my edits** (counted from the table itself, not hand-typed): 31 rows — **16 present / 3 partial / 12 missing** (was 14 / 2 / 15). Bugs table refreshed: R3-N1, R3-N2, R3-N3, R3-N4, the link emit-flush exception, R2-N5 (closed as scoped) and N-10 moved to the resolved list; R4-N1–N4 added. Header updated to v0.4. `github-staging/README.md` updated to v0.4 facts (version, `GhostBladeNotion-v0.4.apk`, 762,902 bytes, SHA-256 from my verified build, feature status, counts) and the Round 4 entry appended to WORKLOG.md.

---

## 6. Recommended Round 5 tasks

**The next feature frontier — pick one headline, not three:**
1. **Calendar view over script/task dates.** The dates, validation and day formatting all exist; a month grid over script dates + task due dates is the cheapest remaining database view and directly serves the owner's production schedule. This starts moving the board/gallery/list/calendar/timeline row beyond partial honestly (calendar would make it list+board+calendar of five).
2. **Generic collections — scoped decision first.** The "table view" row is present only in its scoped form. Before anyone claims more, Jervis should scope what a user-defined collection means here (custom title + rows of blocks? user-defined properties on scripts/tasks?) — the properties row cannot move until properties are user-defined, and that is a data-model change, not a view change.

**Close out Round 4 residuals (small):**
3. Run the relink repair after a Trash-screen restore of a single entity (R4-N2) — reuse the `performUndo` relink loops so Trash restore and undo restore converge.
4. Accept any of the eight keys in the UI import "has data" guard (R4-N4, one line).
5. Route template deletes through the trash or give them an undo (R4-N3) — or explicitly document the confirm-only choice in the UI copy.

**Then:** gallery view (needs a visual identity for scripts — could reuse language/status chips in a card grid), script/idea templates (the page-template machinery generalises), and the combined-app phase (GhostBlade Task's Must-task engine) whenever Jervis prioritises it over Notion parity.

---

*Monitoring Team — Round 4 complete. Verdict: PASS-WITH-NOTES. Builders' headline claims (byte-reproducible build at the claimed hash, 492/492 tests, zero permissions) all independently reproduced; every Round 3 close-out traced as truly fixed; templates, scoped table/board views and the persisted Trash are real. The notes above are the Round 5 entry list.*
