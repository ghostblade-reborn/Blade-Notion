# GhostBlade Notion

**Your own Notion, rebuilt in Kotlin for Android — combined with a to-do engine, made for content creation.**

GhostBlade Notion is a native Android app that re-implements the core idea of Notion (everything is a record; pages, databases and content all live in one local model) and fuses it with the task logic of [GhostBlade Task](https://github.com/ghostblade-reborn/Gbostblade-Todo-App): channels, scripts, video ideas and tasks — all interlinked, all on your phone.

> Status: **v0.4** — database views (tables + board), page templates and a persisted Trash landed, Monitoring-verified (492 JVM logic tests passing, byte-reproducible APK build, zero permissions). See "v0.4 status" below for exactly what works today.

## Vision

One app for a faceless content creator's whole workflow:

- **Channels** — every YouTube channel gets its own homepage tab (Channel One, Channel Two, and "+ add new channel" for whatever comes next).
- **Scripts** — inside each channel, all its scripts in **Bangla or English**, each with a date and a **draft → final** flow: drafts become final scripts when you mark them done.
- **Video Ideas** — a place to capture ideas the moment they strike. Hit **"Start script"** on an idea and a script is created and **auto-linked both ways** — the script knows which idea it came from, and the idea knows which script it became.
- **Tasks** — channel tasks and daily to-dos live next to the scripts they belong to, not in a separate app.
- **Everything interlinked** — ideas ↔ scripts ↔ tasks ↔ channels are connected by record IDs, the same way Notion links pages: jump from an idea to its script, from a script to its channel, from a channel to everything inside it.

## The offline guarantee

This is a hard rule, not a feature toggle:

- **NO INTERNET permission** in the Android manifest — zero `uses-permission` lines at all. No network, no location, no notifications, nothing.
- **No trackers, no analytics, no ads, no accounts, no servers, no sync.**
- **All data lives only on your phone** (app-private storage: the JSON state file is the authoritative store; a local SQLite database, `ghostnotion.db`, mirrors every Store mutation write-through). Android backup of the app data is disabled (`allowBackup="false"`), so nothing is copied to a cloud device backup either.
- Everything is unlocked — there are no Pro gates in our own app.

For contrast: the official Notion Android app ships with 40 permissions and a cloud-first, ~120 MB hybrid (native shell + embedded web bundle). GhostBlade Notion is a small, 100% native Kotlin app that never phones home.

## v0.4 status

**Works today:**

- Channels homepages (seeded with Channel One + Channel Two; add your own)
- Scripts per channel: create drafts, edit body (routed through a validated Store update that timestamps edits), optional date (typed or via date picker, validated), language chips with a persistent selected state (BANGLA / ENGLISH), status chip (DRAFT / FINAL), **Mark FINAL**; editors autosave drafts, so a process kill doesn't lose writing
- **Pages** — a Notion-style page tree: pages nest inside pages, with an expandable Pages screen, tappable breadcrumb, sub-pages, rename, cascade delete with a disclosure dialog + undo — and since v0.4 a "Move to…" picker to reparent a page
- **Block editor** on every page: text, heading, bulleted/numbered lists, to-do blocks with a real checkbox, toggles that collapse (with real child blocks, authorable since v0.4), quote, code and divider blocks — add, edit (draft-autosaved), reorder, turn-into and delete, all stored as records
- **Database views (v0.4)** — each channel's scripts can be viewed as a List, a sortable **Table** (Title / Lang / Status / Date / Updated) or a **Board** (Draft / Final columns); tasks get a List / Table switcher too. Scoped honestly: fixed-column views over a channel's own scripts/tasks, not generic user-defined databases
- **Page templates (v0.4)** — save any page (blocks and their structure included) as a named template, then create a fresh page from it in one tap; rename/delete templates on the Pages screen
- **Trash (v0.4)** — every delete (channels, pages, scripts, ideas, tasks, blocks) lands in a persisted Trash screen: restore it later, delete it forever, or empty the trash. The quick undo bar still appears right after a delete
- Video Ideas: capture title + notes (with a channel picker), linked badge, **Start script from idea** with bidirectional auto-linking — and the links are tappable both ways
- Tasks: add (global or per-channel, optional due date), tap to toggle done; tasks ↔ scripts link both ways from either screen
- **Everything interlinked, all six pairings, both directions:** ideas ↔ scripts ↔ tasks ↔ channels — plus pages ↔ scripts
- **Search** across channels, scripts, ideas, tasks and pages (titles + bodies/notes/block text)
- **Delete / rename with undo** for channels, scripts, ideas, tasks, pages and blocks — deleting a channel cascades to its contents (disclosed up front) and undo restores everything, links included
- **Export / import (JSON)** via the system file picker and share sheet — back up before an uninstall, move data between devices by hand; import is type-validated, so a malformed file can never wipe your data; still zero network
- Local persistence across restarts: the JSON state file is authoritative; `ghostnotion.db` mirrors every Store mutation write-through (deletes included, as tombstones)

**Known v0.4 limitations (honest list):** the undo bar itself is still a single 6-second slot (the persisted Trash behind it is the real safety net); restoring a single item from Trash restores the item itself — links that were cleaned on other items aren't re-linked from the Trash screen (the undo bar does re-link them); template deletes are confirmation-only, with no undo.

**Not yet:** generic databases with user-defined properties, gallery/calendar/timeline views, comments, version history, Markdown/CSV/PDF export. A full feature-by-feature comparison against real Notion ships in the repo as `COMPARISON.md` (v0.4: 31 features compared — 16 present, 3 partial, 12 missing).

## Build

No Gradle. No AndroidX. No Compose. Framework-only Kotlin, built with a single shell script — the GhostBlade recipe:

```bash
./build.sh
```

Pipeline: `aapt2` (compile + link resources) → `javac` (R.java) → `kotlinc` (app sources) → `d8` (dex + kotlin-stdlib) → `zipalign` → `apksigner`.

Output: `GhostBladeNotion-v0.4.apk` (plus a `GhostBladeNotion.apk` copy), signed with a debug keystore. The build is byte-reproducible: two clean builds produce an identical SHA-256 (v0.4: `b10656223f39bceb7a87664d504c5379b5e3e9b67f8c2e1029b13a1bf5fa4191`, 762,902 bytes, independently rebuilt and matched by the Monitoring Team).

**Requirements:**

- JDK 21
- Kotlin compiler 2.0.21 (`kotlinc`)
- Android SDK platform 34, build-tools 35.0.0
- A debug keystore (the script expects the GhostBlade Task debug keystore path by default — edit `KEYSTORE` in `build.sh` to use your own)

## Tech facts

- **Package:** `com.ghostblade.notion`
- **Version:** 0.4 (versionCode 4), minSdk 24, targetSdk 34
- **Language:** Kotlin only (plus Android-required XML: manifest, launcher icons, strings)
- **UI:** built entirely in Kotlin code (LinearLayout/ScrollView) — no XML layouts, no WebView
- **Permissions:** none

## Roadmap sketch

- **Next (Round 5):** a calendar view over script/task dates; Trash-restore link repair
- **Then:** gallery view, script/idea templates, and a scoped decision on generic collections with user-defined properties
- **Combined-app phase:** GhostBlade Task's full task engine (daily Must tasks, local reminders), a combined Today dashboard

## License / notes

Personal project by GhostBlade, built with an AI agent team (Team One / Team Two / Monitoring) under Jervis. Not affiliated with Notion Labs, Inc. "Notion" is used descriptively — this is a clean-room, learning-driven re-implementation of a data model, not a copy of Notion's code or assets.
