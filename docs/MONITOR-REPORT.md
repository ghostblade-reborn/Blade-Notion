# GhostBlade Notion v0.1 — Monitoring Team Report (Round 1)

**Date:** 2026-11-20 (local time)
**Monitor role:** Independent verification only. No source file, manifest, COMPARISON.md, or README was modified by the Monitoring Team. This report is the only file written.
**Package:** `com.ghostblade.notion` · versionCode 1 · versionName 0.1 · minSdk 24 · targetSdk 34

## Verdict: **PASS-WITH-NOTES**

Every functional claim made by Team One and Team Two was independently reproduced: the build succeeds from clean, the APK is byte-size identical across builds, the JVM tests pass 73/73 on a fresh compile-and-run, and the zero-permission / fully-offline guarantee holds under three separate checks (manifest, built APK, source + dex scan). No P1 (crash / data-loss) bugs were found, and no *undisclosed* P2 bugs were found — all P2 items below were already honestly self-reported in COMPARISON.md.

The "notes" are documentation-precision problems the build teams did **not** disclose (wrong feature counts in COMPARISON.md, a `ghostnotion.db` "created" claim that does not happen at runtime, an `allowBackup` tension with the offline guarantee) plus a set of P3 UX/robustness items. None of them block v0.1, but per the owner's rule they are recorded here and should be fixed in Round 2.

---

## 1. Independent verification table

| # | Claim (source) | Verified? | Evidence (Monitoring Team's own runs) |
|---|---|---|---|
| 1 | APK builds from source via `./build.sh` (Team Two) | ✅ YES | Two consecutive clean rebuilds (`rm -rf build` is inside build.sh), both exit 0. Only warnings: javac source-8 obsolescence + `onBackPressed` deprecation in MainActivity.kt:67/74. |
| 2 | APK size 664,622 bytes | ✅ YES | Both of my rebuilds: exactly **664,622 bytes**. Pre-existing on-disk APK before my rebuild was also 664,622 bytes. |
| 3 | APK identity: package / version / SDK levels | ✅ YES | `aapt2 dump badging` on my build: `com.ghostblade.notion`, versionCode **1**, versionName **0.1**, minSdk **24**, targetSdk **34**, label "GhostBlade Notion", launchable activity `com.ghostblade.notion.MainActivity`. All 6 app classes present in dex (Models/Logic/Store/Db/Ui/MainActivity + data classes). |
| 4 | Coordinator SHA-256 `0d005842…` vs Team Two's different SHA — "zip timestamps" explanation | ✅ EXPLANATION HOLDS — proven | The on-disk APK before my rebuild hashed to the coordinator's `0d0058426b39ae45c36bfe332b806c3f856f6cf221276cc3864faedef285466e`. My build #1: `f3c1991f17094128a887baee6ab2ecdbd6479809f075a34877ec5ec520fca364`. My build #2: `f2c2f9eb1e7a7d92373ce43a231058daa5c824839131a90d15e7fb94d705d569`. Three different APK hashes, **identical size**, and — decisive — the extracted contents are bit-identical across my two builds: `classes.dex` SHA-256 `2578fa5a959e417facc24c16e84baa905fb0275319d948b10463c6e21b55bb16`, `AndroidManifest.xml` `1177a858…`, `resources.arsc` `9f75e81e…`. The only variance is container metadata: aapt2 entries carry the deterministic 1980-01-01 stamp, while the `classes.dex` entry (added by `zip -q` in build.sh) and the META-INF signing entries carry build-time stamps, and the v2/v3 signing block follows. Content is reproducible; the file hash is not. |
| 5 | APK signature valid | ✅ YES | `apksigner verify --verbose` (with JDK 21 on PATH): **Verifies**, v2 scheme true, v3 scheme true, 1 signer. |
| 6 | Zero permissions — manifest | ✅ YES | `grep -c uses-permission AndroidManifest.xml` = **0**; no "permission" string anywhere in the manifest. |
| 7 | Zero permissions — built APK | ✅ YES | `aapt2 dump permissions GhostBladeNotion-v0.1.apk` prints only `package: com.ghostblade.notion` and **no permission lines**. |
| 8 | No internet / location / tracking APIs in code | ✅ YES | Grep over all `.kt` sources for `java.net`, `HttpURLConnection`, `Socket`, `URL(`, `LocationManager`, `Firebase`, `analytics`, `WebView`, permission constants: **no hits**. Full import list is only Android framework UI/SQLite/content classes, `java.io.File`, `java.time.LocalDate`, `org.json`. Dex `strings` scan: the single "http" hit is the Kotlin stdlib's own youtrack.jetbrains.com URL inside a stdlib error message — not app code. |
| 9 | JVM tests 73/73 passed (Team One) | ✅ YES | My own compile (`kotlinc Models.kt Logic.kt Store.kt tests/TestMain.kt`, exit 0) and run: **`73 checks, 0 failures`, exit 0** — identical result to Team One, the coordinator, and `tests/TEST-RESULTS.txt`. |
| 10 | Db.kt + Ui.kt + MainActivity.kt compile together | ✅ YES | Proven by build.sh step 1: kotlinc compiles all six `src/com/ghostblade/notion/*.kt` files together in every clean rebuild. (TEST-RESULTS.txt's separate Db compile note is corroborated.) |
| 11 | `tests/TEST-RESULTS.txt` matches reality | ✅ YES | File exists, contains exactly 73 `PASS` lines, footer `73 checks, 0 failures`, `RUN_EXIT=0` — matches my independent run. |
| 12 | COMPARISON.md: 29 rows — 9 present / 3 partial / 17 missing | ❌ **NO — counts are wrong** | The feature table actually contains **31 data rows: 9 present / 3 partial / 19 missing**. Every individual row's status is correct (see §3), and the Counts section's own missing-features list enumerates 19 items while labelling them "17". Documentation error only; see Finding N-1. |
| 13 | No GitHub repo created / nothing pushed | ✅ YES | No `.git` directory or git metadata anywhere in the project tree; `git remote` fails with "not a git repository". `github-staging/` contains only `README.md`, as intended. |

---

## 2. Code audit — do the "present" features actually work in MainActivity?

All nine were traced from UI click → Store call → persistence (`persistStore()` writes `store.toJsonString()` to `ghostnotion-state.json` in filesDir after every mutation). All nine are real:

| Feature | Where verified | Result |
|---|---|---|
| Channels homepages | `MainActivity.showHome()` lists channels with script count + draft/final counts; card tap → `showChannel(id)`; "+ Add channel" → `store.addChannel()` + persist | ✅ Works as claimed |
| Scripts draft → final | `showScript()` shows "✓ Mark FINAL" only for DRAFT; calls `Store.markScriptFinal()` which is DRAFT-only via `Logic.canMarkFinal()`, returns false if already FINAL, bumps `updatedAt` | ✅ Works as claimed |
| Bangla / English language | `Language` enum; editor buttons set `editorLanguage` (default BANGLA); passed to `Store.addScript()`; language chip rendered on channel script lists and the script screen | ✅ Works (UX caveat, Finding N-4) |
| Script dates | Editor date field → `Store.addScript()` validates via `Logic.isValidDate()` (strict `yyyy-MM-dd` + real calendar date via `LocalDate.of`, leap years honoured); invalid date → null → toast, save rejected; date chip shown when present | ✅ Works as claimed |
| Video Ideas | `showIdeas()`: add (title + notes), list, linked badge | ✅ Works as claimed |
| Idea → script auto-link, BOTH ways | `Store.startScriptFromIdea()`: creates DRAFT script (title = idea title, body = idea notes, channel = idea's channel or first-channel fallback), sets **both** `script.linkedIdeaId` and `idea.linkedScriptId`; second call returns null (already-linked guard); null if idea missing or zero channels. Script screen shows "💡 From idea: \<title\>" | ✅ Works as claimed at the data level (navigation caveat, Finding N-5) |
| Tasks add / toggle | `renderTasks(null)` on Home (all tasks) and `renderTasks(channelId)` on Channel screen (filtered); add validates due date via Store; tap → `Store.toggleTask()` + persist + re-render, with ✓ / strikethrough / grey when done | ✅ Works as claimed |
| Seeding | `Store.seedIfEmpty()`: returns early if any channel exists; otherwise adds exactly **"Channel One"** then **"Channel Two"**. `MainActivity.onCreate`: restore → seed → persist. Idempotent — verified by tests ("seed twice does not duplicate") and by code reading | ✅ Exactly as claimed |
| Offline / local storage | Store ⇄ JSON file in app-private storage is the live path; app works with zero permissions | ✅ Works (Db wording caveat, Finding N-2) |

### Crash-risk sweep (no P1 found)

- Only two force-unwraps (`!!`) in app code — `MainActivity.kt:228` (`s.date!!`) and `:353` (`script.date!!`) — both immediately guarded by `if (!…isNullOrBlank())` on the same value. Safe.
- `Logic.isValidDate()` indexes `p[0..2]` only after a strict regex guarantees the shape, inside try/catch. Safe.
- Empty lists are handled everywhere: Home/Channel/Ideas render "No … yet" notes; `startScriptFromIdea` uses `firstOrNull()`; `Store.addScript` returns null for a missing channel; `loadFromJsonString` parses into temporary lists first, so a malformed file leaves the live store untouched.
- Invalid-date paths are rejected at the Store layer for both scripts and tasks, and surfaced as toasts in the UI.

---

## 3. COMPARISON.md accuracy audit

- **9 "present" rows — all 9 accurate.** Spot-checked each against code (§2). No overclaiming in the rows themselves.
- **3 "partial" rows — all 3 accurate.** (a) Database properties: scripts/tasks really do have only the fixed fields listed. (b) Import/export: only the internal JSON persistence methods exist; no user-facing screen (grep confirms no export/import UI). (c) Interlinking: idea↔script bidirectional links are real; channel↔script and channel↔task via `channelId` are real; task↔script and idea↔task links genuinely do not exist.
- **"Missing" rows — all genuinely absent.** Verified by grep over all sources: no search UI and no FTS in Db.kt; no template code; no comments/sharing/calendar-view code; MainActivity **never calls** `Store.addBlock()` / `blocksForPage()` (block model exists, no editor UI — exactly as the block-editor row states); `Db.softDelete()` exists but is unreachable from the UI, so "no delete in UI" is also accurate.
- **One error: the totals.** The table has **31 rows, 19 missing** — not "29 rows, 17 missing" as the Counts section (and the teams' summary) states. The missing list in that same section itself names 19 features. See Finding N-1.

---

## 4. github-staging/README.md audit

- **Feature description: accurate.** The "v0.1 status — Works today" list matches the code exactly, and the "Not yet" list is honest (page tree, block editor, databases/views, search, templates, comments, version history, export/import UI, delete/rename).
- **Build instructions: accurate.** `./build.sh` and the pipeline description (aapt2 → javac → kotlinc → d8 → zipalign → apksigner) match build.sh step for step; stated requirements (JDK 21, kotlinc 2.0.21 — verified installed, SDK platform 34, build-tools 35.0.0, debug keystore path note) all match reality. Output names (`GhostBladeNotion-v0.1.apk` + `GhostBladeNotion.apk` copy) match build.sh.
- **Offline guarantee: substantively true, two wording notes.** Zero permissions is verified (§1 #6–8). But: (a) the README says data lives in "a JSON state file **plus a local SQLite database, `ghostnotion.db`**" — the database file is never actually created in v0.1 (Finding N-2); (b) the manifest sets `android:allowBackup="true"`, so Android Auto Backup could copy the app-private JSON to the user's Google-account device backup if that is enabled on the phone — a small but real tension with "all data lives only on your phone" (Finding N-3).
- **Vision section:** "jump from an idea to its script" is only true at the moment of creation; a linked idea is not tappable afterwards (Finding N-5). The section is labelled Vision, so this is aspiration slightly ahead of the v0.1 UI — noted, not counted as a false status claim.
- **No publication happened:** staging README only; no repo, no remote, no push (§1 #13). Correct per the owner's instruction.

---

## 5. Findings (bugs / issues), by severity

### P1 — crash / data-loss
**None found.** Empty lists, null dates, empty titles, invalid dates, already-final scripts, already-linked ideas, and malformed persistence files are all guarded in Store and/or MainActivity.

### P2 — significant, all already self-disclosed in COMPARISON.md (confirmed by Monitoring)
| ID | Finding | Evidence |
|---|---|---|
| P2-1 | **Db is not the live store.** `Db.init(this)` is the *only* Db call in the app (`MainActivity.kt:102`); Store persists to the JSON file only. Two storage layers, one authoritative — confusion risk in later rounds. | grep: no `Db.upsertRecord` / `recordById` / `recordsByTable` call sites anywhere |
| P2-2 | **Script body editing bypasses Store.** `MainActivity.setScriptBody()` assigns `script.body = newBody` directly (Script.body is a `var` in Models.kt) and persists; there is no Store update method, and `updatedAt` is **not** bumped on body edits — the record's timestamp silently goes stale. Data itself is persisted correctly via the JSON file. | MainActivity.kt `setScriptBody()`; Store.kt has no update/edit method |
| P2-3 | **No user-facing export / import / backup.** All data lives in one app-private JSON file; uninstalling the app loses everything. | No export/import UI exists (grep-verified) |
| P2-4 | **No search.** Content is findable only by scrolling lists; will hurt as script/idea counts grow. | No search UI, no FTS (grep-verified) |

### P3 — minor / documentation / UX (includes Monitoring's new findings, marked ★)
| ID | Finding |
|---|---|
| ★ N-1 | **COMPARISON.md totals are wrong:** 31 feature rows and **19 missing**, not "29 rows / 17 missing". Every row's status is individually correct; only the arithmetic in the Counts section (and the headline summary repeated from it) understates the missing count by 2. |
| ★ N-2 | **"`ghostnotion.db` created via Db.init" overstates reality** (COMPARISON offline row; README echoes it). `Db.init()` only constructs the `SQLiteOpenHelper`; Android creates the DB file lazily on the first `writableDatabase` access, which never happens in v0.1 because no Db read/write method is ever called. The SQLite *layer* is present and compiles; the database *file* is not created at runtime. |
| ★ N-3 | **`android:allowBackup="true"`** in AndroidManifest.xml weakens the "data lives only on your phone" guarantee: with device backup enabled, Android may copy app-private files (including the JSON state file) into the user's Google-account backup. One-line fix (`false`) aligns the manifest with the owner's hard offline rule. |
| ★ N-4 | **Language selector visual state is misleading** (extends COMPARISON's P3): `refreshLang()` in `showScriptEditor()` is an empty stub; the BANGLA button is always rendered as the gold "primary" button and ENGLISH never is, even after ENGLISH is selected — only a transient toast confirms the choice. Easy to save a script with the wrong language. |
| ★ N-5 | **Linked idea is a dead end in the UI:** once linked, the idea card shows static text "✓ Linked to a script" and is not tappable, so there is no idea→script navigation after creation (the link is bidirectional in data only). The script screen likewise shows the idea title as plain text, not a link. |
| ★ N-6 | **Unsaved editor text is lost on process death:** title/body/date fields in the script editor and the script-body field persist only when a Save button is tapped; nothing is autosaved. (Rotation is safe — the manifest declares `configChanges` — but an Android process kill while editing loses the draft text.) Not listed in COMPARISON. |
| ★ N-7 | **Blanket `catch (t: Throwable)` around nearly every Store call in MainActivity** silently swallows failures (e.g. a failed `toggleTask` or `persistStore` looks identical to success). Crash-safe, but it can mask real bugs and silent data non-persistence in future rounds. |
| ★ N-8 | **Dead state:** `MainActivity.editorChannelId` is written in `showScriptEditor()` and never read (the save path uses the captured `channelId` parameter). Harmless; remove or use it. |
| ★ N-9 | **`Logic.addDays()` throws on invalid input** (`LocalDate.parse` without a guard). Currently unreachable from the UI (no callers outside tests), so latent only — guard it before any UI use. |
| ★ N-10 | **Deprecated `onBackPressed()` override** (compiler warnings at MainActivity.kt:67/74). Works on targetSdk 34; migrate to `OnBackPressedDispatcher` in a future round. |
| — | Self-reported P3s in COMPARISON, all confirmed accurate: dates are typed text with no picker; Ideas screen always assigns the first channel (no picker — the UI itself discloses this); no delete/rename/edit for channels and no delete for scripts/ideas/tasks, no undo/trash in the UI; no per-screen scroll state; tasks have no reminders/recurrence/priorities; launcher icon inherited from GhostBlade Task. |

---

## 6. Recommended Round 2 tasks

**Data layer first (clears the P2s):**
1. Pick ONE authoritative store: make Store write through to Db (records table already designed for this), or remove `Db.init` until Db is ready. Then correct the "db created" wording to match whichever is true.
2. Add `Store.updateScript(scriptId, title, body, language, date)` (validating the date like `addScript`) that bumps `updatedAt`, and route `MainActivity.setScriptBody()` and the script screen through it. Add JVM tests for it.
3. User-facing export/import: write/read the existing JSON state file via a share sheet / file picker so the owner can back up before uninstalls and move data between devices manually — still zero network.
4. Set `android:allowBackup="false"` in the manifest to harden the offline guarantee.

**Findability & completeness:**
5. Search: a simple filter field over scripts/ideas/tasks titles+bodies first (in-memory is fine at this scale); FTS only if counts demand it.
6. Delete / rename / edit for channels, scripts, ideas, tasks, with an undo or trash step (the `Db.softDelete` model already sketches the semantics).
7. Make links navigable both ways: tapping a linked idea opens its script; tapping "From idea:" on a script opens the idea.

**UX fixes (cheap, high value):**
8. Real language selector: two chips with a persistent selected state (delete the empty `refreshLang()` stub).
9. Channel picker when adding an idea; a date picker (or at minimum a "today" shortcut) instead of typed-only dates.
10. Autosave editor drafts (persist on pause / on text change, debounced) so process death can't lose writing.

**Docs & build hygiene:**
11. Fix COMPARISON.md Counts: **31 rows — 9 present / 3 partial / 19 missing**, and re-audit totals mechanically (count the table, don't hand-type).
12. Make the build byte-reproducible — normalise the `classes.dex` zip entry timestamp (e.g. fixed `zip -X` date or `SOURCE_DATE_EPOCH`) so the APK SHA-256 is stable across builds and future monitoring rounds can verify by hash alone.
13. Reduce blanket `catch (Throwable)` in MainActivity to targeted catches with at least a toast/log on persistence failure.

**Then the Notion engine (per FINDINGS §7.5 order):** pages + block editor on the existing Record/Block model (Store's block API is already tested and waiting), then a generic table view for collections.

---

*Monitoring Team — Round 1 complete. Verdict: PASS-WITH-NOTES. No P1s, no hidden P2s; the notes above are the Round 2 entry list.*
