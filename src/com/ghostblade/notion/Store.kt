package com.ghostblade.notion

import java.util.Collections
import java.util.WeakHashMap

/** In-memory store for GhostBlade Notion + JSON persistence.
 *
 *  AUTHORITATIVE STORE (v0.2, honest wording): the JSON string produced
 *  by toJsonString() — which the UI layer keeps in a file in internal
 *  storage (`ghostnotion-state.json`) — is the authoritative copy of
 *  all data. MainActivity loads from it on launch and rewrites it
 *  after every mutation.
 *
 *  Db WRITE-THROUGH MIRROR (v0.2, completed in v0.3): in addition,
 *  every successful mutation emits a [Record] (Notion-style, see
 *  Db.kt) to an optional record sink. Db.init() installs that sink
 *  (Db.upsertRecord), forces creation of `ghostnotion.db`, and
 *  backfills the mirror by calling [syncRegisteredStoresToSink].
 *  Db.upsertRecord writes Record.alive into the row's alive column,
 *  so a Record with alive=false IS a soft delete in the mirror.
 *  Since v0.3 EVERY mutation path goes through this class and emits:
 *  adds, updates/renames, link changes — and removals, which emit a
 *  tombstone (alive=false) carrying the entity's last JSON. A load
 *  (import) also tombstones mirror rows whose ids are absent from the
 *  new dataset, so the mirror can no longer keep ghost rows (R2-N1).
 *  Db is still a MIRROR, not the read path: nothing loads from Db,
 *  and a sink failure can never lose data (the JSON file already has
 *  it; sink errors are swallowed in [emit]).
 *
 *  CHANNEL DELETE POLICY (decided by Jervis, Round 3): deleting a
 *  channel CASCADE-deletes its scripts, its tasks and its ideas
 *  (ideas carry a channelId too). [removeChannel] and
 *  [removeChannelCascade] both implement the cascade; the latter
 *  returns a [ChannelCascadeSnapshot] that [restoreChannelCascade]
 *  can fully undo (original ids, indices and ALL links). The same
 *  disclose+undo shape applies to pages: removing a page cascades to
 *  its descendant pages and to every block of every removed page
 *  ([removePageCascade] / [restorePageCascade]).
 *
 *  IMPORT VALIDATION (R2-N4, Round 3; extended Round 4): [loadFromJsonString]
 *  returns Boolean. If any known top-level key (channels / scripts /
 *  ideas / tasks / pages / blocks / templates / trash) is present but
 *  is NOT a JSON array, the whole load is rejected (false) and the
 *  live store is left completely untouched — `{"channels": 5}` can
 *  no longer wipe the store. Malformed JSON likewise returns false,
 *  untouched.
 *
 *  Persistence is string-based on purpose (Context-free): the UI layer
 *  decides where the string lives via toJsonString() /
 *  loadFromJsonString().
 *
 *  JSON is serialised/parsed by the small pure-Kotlin codec at the
 *  bottom of this file (same schema/keys as v0.1's org.json version,
 *  and it parses v0.1 and v0.2 files — every field added since has a
 *  default when absent). Store therefore has NO Android and NO
 *  org.json dependency at all: the constructor, every CRUD method and
 *  the JSON round-trip all run on a plain JVM, which is what the
 *  tests in tests/TestMain.kt exercise.
 *
 *  toggleTask() semantics: returns TRUE when the task existed and was
 *  toggled (success flag, same convention as markScriptFinal); the new
 *  state is in GTask.done.
 *
 *  ROUND 4 (v0.4) additions:
 *  - [linkIdeaToScript] / [unlinkIdeaFromScript]: the idea<->script
 *    single link finally has a Store API (it was previously created
 *    only by [startScriptFromIdea] and patched by hand in the UI's
 *    undo paths with an emit-flush workaround — now retired).
 *  - Toggle child blocks: [addBlock] takes a parentBlockId, and
 *    [moveBlock] / [moveBlockUp] / [moveBlockDown] are SIBLING-scoped
 *    (a block moves only within its own sibling group — see the
 *    moveBlock doc).
 *  - Templates ([templates]): page templates are plain Store data
 *    persisted in the authoritative JSON. They are deliberately NOT
 *    emitted to the Db sink: the mirror's contract covers only the
 *    six entity tables (channel/script/idea/task/page/block), and a
 *    template is none of those — it is not a page and must never
 *    appear as one in the mirror. Same reasoning for trash entries.
 *  - Trash ([trash]): a persisted bin fed ONLY by the deleteToTrash
 *    APIs ([deleteScriptToTrash] etc.). The plain removeX APIs do
 *    NOT create trash entries — they behave exactly as before.
 *    [restoreFromTrash] replays the existing restore machinery
 *    (restoreX for singles, the cascade restores for channel/page).
 */
class Store {

    val channels: MutableList<Channel> = mutableListOf()
    val scripts: MutableList<Script> = mutableListOf()
    val ideas: MutableList<Idea> = mutableListOf()
    val tasks: MutableList<GTask> = mutableListOf()
    val pages: MutableList<Page> = mutableListOf()
    val blocks: MutableList<Block> = mutableListOf()

    /** Page templates (v0.4). Plain data — JSON-persisted, NOT
     *  sink-emitted (see class doc). */
    val templates: MutableList<PageTemplate> = mutableListOf()

    /** Trash/bin entries (v0.4), oldest first — new entries are
     *  APPENDED, so the newest entry is last. Plain data —
     *  JSON-persisted, NOT sink-emitted (see class doc). */
    val trash: MutableList<TrashEntry> = mutableListOf()

    init {
        synchronized(liveStores) { liveStores.add(this) }
    }

    companion object {
        fun uid(): String = Logic.uid()

        /* ----- Db write-through sink (see class doc) ----- */

        /** The installed write-through sink, if any. Set only via
         *  [installRecordSink] (Db.init installs Db.upsertRecord). */
        @Volatile
        var recordSink: ((Record) -> Unit)? = null
            private set

        fun installRecordSink(sink: ((Record) -> Unit)?) {
            recordSink = sink
        }

        /** Live Store instances (weakly held, so tests/GC are not
         *  pinned), used only so Db.init can backfill the mirror with
         *  records that were created/loaded before the sink existed. */
        private val liveStores: MutableSet<Store> =
            Collections.newSetFromMap(WeakHashMap<Store, Boolean>())

        /** Emits every record of every live Store to the sink.
         *  Called by Db.init right after it installs the sink. */
        fun syncRegisteredStoresToSink() {
            val snapshot = synchronized(liveStores) { liveStores.toList() }
            snapshot.forEach { it.syncToRecordSink() }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun Set<String>.containsId(id: String?): Boolean = id != null && contains(id)

    /* ---------- deep copies (snapshots must not alias live lists) ---------- */

    private fun copyScript(s: Script): Script =
        s.copy(linkedTaskIds = s.linkedTaskIds.toMutableList(), linkedPageIds = s.linkedPageIds.toMutableList())

    private fun copyIdea(i: Idea): Idea =
        i.copy(linkedTaskIds = i.linkedTaskIds.toMutableList())

    private fun copyTask(t: GTask): GTask =
        t.copy(linkedScriptIds = t.linkedScriptIds.toMutableList(), linkedIdeaIds = t.linkedIdeaIds.toMutableList())

    private fun copyPage(p: Page): Page =
        p.copy(linkedScriptIds = p.linkedScriptIds.toMutableList())

    /* ---------- Db write-through emission ---------- */

    private fun emit(record: Record) {
        val sink = recordSink ?: return
        try { sink(record) } catch (t: Throwable) { /* mirror only — JSON is authoritative */ }
    }

    private fun emitChannel(c: Channel) =
        emit(Record(c.id, "channel", null, c.createdAt, c.createdAt, true, channelJson(c)))

    private fun emitScript(s: Script) =
        emit(Record(s.id, "script", s.channelId, s.createdAt, s.updatedAt, true, scriptJson(s)))

    private fun emitIdea(i: Idea) =
        emit(Record(i.id, "idea", i.channelId, i.createdAt, i.createdAt, true, ideaJson(i)))

    private fun emitTask(t: GTask) =
        emit(Record(t.id, "task", t.channelId, t.createdAt, t.createdAt, true, taskJson(t)))

    private fun emitBlock(b: Block) =
        emit(Record(b.id, "block", b.pageId, 0L, 0L, true, blockJson(b)))

    /** Pages emit as tableName "block" with "type":"page" in dataJson
     *  — a page is a block of type "page" (FINDINGS §7.1). */
    private fun emitPage(p: Page) =
        emit(Record(p.id, "block", p.parentPageId, p.createdAt, p.updatedAt, true, pageJson(p)))

    /* Tombstones: same record shape with alive=false. Db.upsertRecord
     * persists alive=0, which is Db.softDelete's end state — the row
     * stays in the mirror, marked dead, exactly like Notion's trash. */

    private fun emitChannelTombstone(c: Channel) =
        emit(Record(c.id, "channel", null, c.createdAt, now(), false, channelJson(c)))

    private fun emitScriptTombstone(s: Script) =
        emit(Record(s.id, "script", s.channelId, s.createdAt, now(), false, scriptJson(s)))

    private fun emitIdeaTombstone(i: Idea) =
        emit(Record(i.id, "idea", i.channelId, i.createdAt, now(), false, ideaJson(i)))

    private fun emitTaskTombstone(t: GTask) =
        emit(Record(t.id, "task", t.channelId, t.createdAt, now(), false, taskJson(t)))

    private fun emitBlockTombstone(b: Block) =
        emit(Record(b.id, "block", b.pageId, 0L, now(), false, blockJson(b)))

    private fun emitPageTombstone(p: Page) =
        emit(Record(p.id, "block", p.parentPageId, p.createdAt, now(), false, pageJson(p)))

    /** Emits ALL current records to the installed sink (no-op when no
     *  sink is installed). Used for the Db backfill and after a load. */
    fun syncToRecordSink() {
        if (recordSink == null) return
        channels.forEach { emitChannel(it) }
        scripts.forEach { emitScript(it) }
        ideas.forEach { emitIdea(it) }
        tasks.forEach { emitTask(it) }
        pages.forEach { emitPage(it) }
        blocks.forEach { emitBlock(it) }
    }

    /* ---------- seeding ---------- */

    /** Seeds ONE template channel when (and only when) there are no
     *  channels yet — no pre-named channels: the template simply says
     *  "Write your channel name"; the owner renames it and adds more
     *  channels from the Channels section. Calling it again never
     *  duplicates. */
    fun seedIfEmpty() {
        if (channels.isNotEmpty()) return
        addChannel("Write your channel name")
    }

    /* ---------- channels ---------- */

    fun addChannel(name: String): Channel {
        val c = Channel(id = uid(), name = name, createdAt = now())
        channels.add(c)
        emitChannel(c)
        return c
    }

    fun channelById(id: String?): Channel? = channels.find { it.id == id }

    /** Renames a channel and emits the updated record.
     *  False if the channel does not exist. */
    fun renameChannel(id: String, newName: String): Boolean {
        val c = channelById(id) ?: return false
        c.name = newName
        emitChannel(c)
        return true
    }

    /** Deletes a channel WITH the decided cascade (see class doc):
     *  its scripts, tasks and ideas are deleted too. Returns false if
     *  the channel does not exist. Callers that need undo should use
     *  [removeChannelCascade] + [restoreChannelCascade] instead —
     *  this is the same cascade, just without the snapshot. */
    fun removeChannel(id: String): Boolean = removeChannelCascade(id) != null

    /** Cascade-deletes a channel: the channel, every script/task/idea
     *  whose channelId == id, and every link from a SURVIVING entity
     *  to a removed one (survivor link fields are cleaned and the
     *  survivors re-emitted). Emits a tombstone for every removal.
     *  Returns a snapshot that [restoreChannelCascade] can fully
     *  undo, or null if the channel does not exist. */
    fun removeChannelCascade(id: String): ChannelCascadeSnapshot? {
        val channel = channelById(id) ?: return null
        val channelIndex = channels.indexOf(channel)
        val deadScripts = scripts.withIndex().filter { it.value.channelId == id }
        val deadTasks = tasks.withIndex().filter { it.value.channelId == id }
        val deadIdeas = ideas.withIndex().filter { it.value.channelId == id }
        val deadScriptIds = deadScripts.map { it.value.id }.toSet()
        val deadTaskIds = deadTasks.map { it.value.id }.toSet()
        val deadIdeaIds = deadIdeas.map { it.value.id }.toSet()
        val deadScriptObjs = deadScripts.map { it.value }
        val deadTaskObjs = deadTasks.map { it.value }
        val deadIdeaObjs = deadIdeas.map { it.value }

        // Survivors whose links point at something about to be removed.
        val survScripts = scripts.filter {
            it.channelId != id &&
                (it.linkedTaskIds.any { t -> deadTaskIds.contains(t) } || deadIdeaIds.containsId(it.linkedIdeaId))
        }
        val survTasks = tasks.filter {
            it.channelId != id &&
                (it.linkedScriptIds.any { s -> deadScriptIds.contains(s) } || it.linkedIdeaIds.any { i -> deadIdeaIds.contains(i) })
        }
        val survIdeas = ideas.filter {
            it.channelId != id &&
                (deadScriptIds.containsId(it.linkedScriptId) || it.linkedTaskIds.any { t -> deadTaskIds.contains(t) })
        }
        val survPages = pages.filter { p -> p.linkedScriptIds.any { s -> deadScriptIds.contains(s) } }

        // Snapshot BEFORE any mutation, deep copies throughout.
        val snapshot = ChannelCascadeSnapshot(
            channel = channel.copy(),
            channelIndex = channelIndex,
            scripts = deadScripts.map { it.index to copyScript(it.value) },
            tasks = deadTasks.map { it.index to copyTask(it.value) },
            ideas = deadIdeas.map { it.index to copyIdea(it.value) },
            survivorScripts = survScripts.map { copyScript(it) },
            survivorTasks = survTasks.map { copyTask(it) },
            survivorIdeas = survIdeas.map { copyIdea(it) },
            survivorPages = survPages.map { copyPage(it) }
        )

        // Clean survivor links to removed entities (and re-emit them).
        survScripts.forEach { s ->
            s.linkedTaskIds.removeAll(deadTaskIds)
            if (deadIdeaIds.containsId(s.linkedIdeaId)) s.linkedIdeaId = null
            emitScript(s)
        }
        survTasks.forEach { t ->
            t.linkedScriptIds.removeAll(deadScriptIds)
            t.linkedIdeaIds.removeAll(deadIdeaIds)
            emitTask(t)
        }
        survIdeas.forEach { i ->
            if (deadScriptIds.containsId(i.linkedScriptId)) i.linkedScriptId = null
            i.linkedTaskIds.removeAll(deadTaskIds)
            emitIdea(i)
        }
        survPages.forEach { p ->
            p.linkedScriptIds.removeAll(deadScriptIds)
            emitPage(p)
        }

        scripts.removeAll(deadScriptObjs)
        tasks.removeAll(deadTaskObjs)
        ideas.removeAll(deadIdeaObjs)
        channels.remove(channel)
        deadScriptObjs.forEach { emitScriptTombstone(it) }
        deadTaskObjs.forEach { emitTaskTombstone(it) }
        deadIdeaObjs.forEach { emitIdeaTombstone(it) }
        emitChannelTombstone(channel)
        return snapshot
    }

    /** Undoes [removeChannelCascade]: re-inserts the channel and all
     *  cascaded scripts/tasks/ideas at their original indices with
     *  their original ids and link fields, then surgically re-adds
     *  survivor links that pointed at the restored entities (links a
     *  survivor gained AFTER the delete are kept). Emits an alive
     *  record for every restore and every repaired survivor.
     *  Returns false — changing nothing — if the channel or any
     *  cascaded id already exists. */
    fun restoreChannelCascade(snapshot: ChannelCascadeSnapshot): Boolean {
        if (channelById(snapshot.channel.id) != null) return false
        if (snapshot.scripts.any { scriptById(it.second.id) != null }) return false
        if (snapshot.tasks.any { taskById(it.second.id) != null }) return false
        if (snapshot.ideas.any { ideaById(it.second.id) != null }) return false

        val ch = snapshot.channel.copy()
        channels.add(snapshot.channelIndex.coerceIn(0, channels.size), ch)
        emitChannel(ch)
        snapshot.scripts.sortedBy { it.first }.forEach { (idx, sc) ->
            val c = copyScript(sc); scripts.add(idx.coerceIn(0, scripts.size), c); emitScript(c)
        }
        snapshot.tasks.sortedBy { it.first }.forEach { (idx, tk) ->
            val c = copyTask(tk); tasks.add(idx.coerceIn(0, tasks.size), c); emitTask(c)
        }
        snapshot.ideas.sortedBy { it.first }.forEach { (idx, idn) ->
            val c = copyIdea(idn); ideas.add(idx.coerceIn(0, ideas.size), c); emitIdea(c)
        }

        val restoredScriptIds = snapshot.scripts.map { it.second.id }.toSet()
        val restoredTaskIds = snapshot.tasks.map { it.second.id }.toSet()
        val restoredIdeaIds = snapshot.ideas.map { it.second.id }.toSet()
        snapshot.survivorScripts.forEach { snap ->
            val live = scriptById(snap.id) ?: return@forEach
            var changed = false
            snap.linkedTaskIds.filter { restoredTaskIds.contains(it) && !live.linkedTaskIds.contains(it) }
                .forEach { live.linkedTaskIds.add(it); changed = true }
            if (snap.linkedIdeaId != null && restoredIdeaIds.contains(snap.linkedIdeaId) && live.linkedIdeaId == null) {
                live.linkedIdeaId = snap.linkedIdeaId; changed = true
            }
            if (changed) emitScript(live)
        }
        snapshot.survivorTasks.forEach { snap ->
            val live = taskById(snap.id) ?: return@forEach
            var changed = false
            snap.linkedScriptIds.filter { restoredScriptIds.contains(it) && !live.linkedScriptIds.contains(it) }
                .forEach { live.linkedScriptIds.add(it); changed = true }
            snap.linkedIdeaIds.filter { restoredIdeaIds.contains(it) && !live.linkedIdeaIds.contains(it) }
                .forEach { live.linkedIdeaIds.add(it); changed = true }
            if (changed) emitTask(live)
        }
        snapshot.survivorIdeas.forEach { snap ->
            val live = ideaById(snap.id) ?: return@forEach
            var changed = false
            if (snap.linkedScriptId != null && restoredScriptIds.contains(snap.linkedScriptId) && live.linkedScriptId == null) {
                live.linkedScriptId = snap.linkedScriptId; changed = true
            }
            snap.linkedTaskIds.filter { restoredTaskIds.contains(it) && !live.linkedTaskIds.contains(it) }
                .forEach { live.linkedTaskIds.add(it); changed = true }
            if (changed) emitIdea(live)
        }
        snapshot.survivorPages.forEach { snap ->
            val live = pageById(snap.id) ?: return@forEach
            var changed = false
            snap.linkedScriptIds.filter { restoredScriptIds.contains(it) && !live.linkedScriptIds.contains(it) }
                .forEach { live.linkedScriptIds.add(it); changed = true }
            if (changed) emitPage(live)
        }
        return true
    }

    /** Re-inserts a previously removed channel at [index] (clamped)
     *  with its original id and emits it. False if the id is taken.
     *  Single-entity restore: links that removeX cleaned on OTHER
     *  entities are not restored (cascade snapshots do that). */
    fun restoreChannel(channel: Channel, index: Int = channels.size): Boolean {
        if (channelById(channel.id) != null) return false
        val c = channel.copy()
        channels.add(index.coerceIn(0, channels.size), c)
        emitChannel(c)
        return true
    }

    /* ---------- scripts ---------- */

    /** Creates a DRAFT script. Returns null if the channel does not
     *  exist or the date is not a valid yyyy-MM-dd date. */
    fun addScript(
        channelId: String,
        title: String,
        body: String = "",
        language: Language = Language.BANGLA,
        date: String? = null
    ): Script? {
        if (channelById(channelId) == null) return null
        if (!Logic.isValidDate(date)) return null
        val t = now()
        val s = Script(
            id = uid(), channelId = channelId, title = title, body = body,
            language = language, status = ScriptStatus.DRAFT,
            date = date?.takeIf { it.isNotBlank() },
            createdAt = t, updatedAt = t
        )
        scripts.add(s)
        emitScript(s)
        return s
    }

    fun scriptById(id: String?): Script? = scripts.find { it.id == id }

    /** Edits an existing script — THE method body/title/language/date
     *  edits route through (v0.2; replaces direct field assignment).
     *
     *  A null parameter means "leave that field unchanged", with one
     *  exception: pass date = "" to CLEAR the script's date.
     *  channelId (v0.3): null OR empty means "leave the channel
     *  unchanged"; a non-empty channelId must name an existing channel.
     *  The date and the channel are validated BEFORE anything is
     *  mutated: an invalid date or a missing channel returns false and
     *  changes nothing. On success updatedAt is bumped and true is
     *  returned. Returns false if the script does not exist. */
    fun updateScript(
        id: String,
        title: String? = null,
        body: String? = null,
        language: Language? = null,
        date: String? = null,
        channelId: String? = null
    ): Boolean {
        val s = scriptById(id) ?: return false
        if (date != null && !Logic.isValidDate(date)) return false
        if (channelId != null && channelId.isNotEmpty() && channelById(channelId) == null) return false
        if (title != null) s.title = title
        if (body != null) s.body = body
        if (language != null) s.language = language
        if (date != null) s.date = date.takeIf { it.isNotBlank() }
        if (channelId != null && channelId.isNotEmpty()) s.channelId = channelId
        s.updatedAt = now()
        emitScript(s)
        return true
    }

    /** Renames a script (title only) via [updateScript]. */
    fun renameScript(id: String, newTitle: String): Boolean = updateScript(id, title = newTitle)

    /** Removes a script, cleaning the other side of every link it
     *  takes part in (its idea's linkedScriptId, every linked task's
     *  linkedScriptIds, every linked page's linkedScriptIds — each
     *  cleaned entity is re-emitted), then emits a tombstone for the
     *  script. False if the script does not exist. */
    fun removeScript(id: String): Boolean {
        val s = scriptById(id) ?: return false
        ideas.filter { it.linkedScriptId == id }.forEach {
            it.linkedScriptId = null; emitIdea(it)
        }
        tasks.filter { it.linkedScriptIds.contains(id) }.forEach {
            it.linkedScriptIds.remove(id); emitTask(it)
        }
        pages.filter { it.linkedScriptIds.contains(id) }.forEach {
            it.linkedScriptIds.remove(id); emitPage(it)
        }
        scripts.remove(s)
        emitScriptTombstone(s)
        return true
    }

    /** Re-inserts a previously removed script at [index] (clamped)
     *  with its original id and emits it. False if the id is taken.
     *  Single-entity restore: links cleaned on other entities by
     *  [removeScript] are not restored. */
    fun restoreScript(script: Script, index: Int = scripts.size): Boolean {
        if (scriptById(script.id) != null) return false
        val c = copyScript(script)
        scripts.add(index.coerceIn(0, scripts.size), c)
        emitScript(c)
        return true
    }

    /** DRAFT -> FINAL. False if the script is missing or already FINAL. */
    fun markScriptFinal(scriptId: String): Boolean {
        val s = scriptById(scriptId) ?: return false
        if (!Logic.canMarkFinal(s)) return false
        s.status = ScriptStatus.FINAL
        s.updatedAt = now()
        emitScript(s)
        return true
    }

    fun scriptsForChannel(channelId: String): List<Script> =
        Logic.scriptsForChannel(scripts, channelId)

    /* ---------- ideas ---------- */

    fun addIdea(title: String, notes: String = "", channelId: String? = null): Idea {
        val i = Idea(id = uid(), title = title, notes = notes, channelId = channelId, createdAt = now())
        ideas.add(i)
        emitIdea(i)
        return i
    }

    fun ideaById(id: String?): Idea? = ideas.find { it.id == id }

    /** Edits an idea (v0.3 — ideas previously had no Store update API,
     *  so the UI mutated fields directly and bypassed the sink).
     *  A null parameter means "leave that field unchanged".
     *  channelId: null = unchanged, "" = clear the channel, non-empty
     *  must name an existing channel (validated BEFORE any mutation;
     *  a missing channel returns false and changes nothing). */
    fun updateIdea(
        id: String,
        title: String? = null,
        notes: String? = null,
        channelId: String? = null
    ): Boolean {
        val idea = ideaById(id) ?: return false
        if (channelId != null && channelId.isNotEmpty() && channelById(channelId) == null) return false
        if (title != null) idea.title = title
        if (notes != null) idea.notes = notes
        if (channelId != null) idea.channelId = channelId.takeIf { it.isNotEmpty() }
        emitIdea(idea)
        return true
    }

    /** Renames an idea (title only) via [updateIdea]. */
    fun renameIdea(id: String, newTitle: String): Boolean = updateIdea(id, title = newTitle)

    /** Removes an idea, cleaning the other side of its links (its
     *  script's linkedIdeaId, every linked task's linkedIdeaIds —
     *  each re-emitted), then emits a tombstone for the idea.
     *  False if the idea does not exist. */
    fun removeIdea(id: String): Boolean {
        val idea = ideaById(id) ?: return false
        scripts.filter { it.linkedIdeaId == id }.forEach {
            it.linkedIdeaId = null; emitScript(it)
        }
        tasks.filter { it.linkedIdeaIds.contains(id) }.forEach {
            it.linkedIdeaIds.remove(id); emitTask(it)
        }
        ideas.remove(idea)
        emitIdeaTombstone(idea)
        return true
    }

    /** Re-inserts a previously removed idea at [index] (clamped).
     *  False if the id is taken. Single-entity restore: links cleaned
     *  on other entities by [removeIdea] are not restored. */
    fun restoreIdea(idea: Idea, index: Int = ideas.size): Boolean {
        if (ideaById(idea.id) != null) return false
        val c = copyIdea(idea)
        ideas.add(index.coerceIn(0, ideas.size), c)
        emitIdea(c)
        return true
    }

    /** Start writing from an idea: creates a DRAFT script titled after
     *  the idea, with the idea's notes as the starting body, in the
     *  idea's channel (or the first channel if the idea has none / its
     *  channel is gone), and links BOTH ways
     *  (script.linkedIdeaId and idea.linkedScriptId).
     *  Returns null if the idea is missing, already linked to a script,
     *  or there are no channels at all. */
    fun startScriptFromIdea(ideaId: String): Script? {
        val idea = ideaById(ideaId) ?: return null
        if (idea.linkedScriptId != null) return null
        val channel = channelById(idea.channelId) ?: channels.firstOrNull() ?: return null
        val t = now()
        val s = Script(
            id = uid(), channelId = channel.id, title = idea.title, body = idea.notes,
            language = Language.BANGLA, status = ScriptStatus.DRAFT,
            linkedIdeaId = idea.id, createdAt = t, updatedAt = t
        )
        scripts.add(s)
        idea.linkedScriptId = s.id
        emitScript(s)
        emitIdea(idea)
        return s
    }

    /* ---------- idea <-> script linking (v0.4) ----------
     * The single (one-to-one) link. Unlike the many-to-many link
     * APIs, each side can point at at most one partner, so a link
     * attempt can CONFLICT: it is refused when either side already
     * points at a DIFFERENT partner. */

    /** Links an idea and a script BOTH ways
     *  (idea.linkedScriptId = scriptId, script.linkedIdeaId = ideaId)
     *  and emits BOTH entities.
     *  Returns false — changing nothing — if either id is missing,
     *  or the idea is already linked to a DIFFERENT script, or the
     *  script is already linked to a DIFFERENT idea.
     *  IDEMPOTENCY CHOICE (v0.4): if the two are already linked to
     *  EACH OTHER, this returns TRUE (success, no state change, no
     *  re-emission) — callers such as undo-restore can call it
     *  unconditionally without first checking the current state.
     *  A one-way pre-existing link naming this pair (e.g. only
     *  idea.linkedScriptId set) is repaired to two-way and returns
     *  true, like the other link APIs. */
    fun linkIdeaToScript(ideaId: String, scriptId: String): Boolean {
        val idea = ideaById(ideaId) ?: return false
        val script = scriptById(scriptId) ?: return false
        if (Logic.ideaLinkConsistent(idea, script)) return true
        if (idea.linkedScriptId != null && idea.linkedScriptId != scriptId) return false
        if (script.linkedIdeaId != null && script.linkedIdeaId != ideaId) return false
        idea.linkedScriptId = scriptId
        script.linkedIdeaId = ideaId
        emitIdea(idea)
        emitScript(script)
        return true
    }

    /** Removes an idea<->script link. Clears idea.linkedScriptId if
     *  it names [scriptId] and script.linkedIdeaId if it names
     *  [ideaId] — so a consistent two-way link is fully cleared and
     *  a one-way pointer naming the pair is cleared too. Emits BOTH
     *  entities when anything was cleared.
     *  Returns false — changing nothing — if either id is missing
     *  or neither side names the pair. */
    fun unlinkIdeaFromScript(ideaId: String, scriptId: String): Boolean {
        val idea = ideaById(ideaId) ?: return false
        val script = scriptById(scriptId) ?: return false
        val ideaPoints = idea.linkedScriptId == scriptId
        val scriptPoints = script.linkedIdeaId == ideaId
        if (!ideaPoints && !scriptPoints) return false
        if (ideaPoints) idea.linkedScriptId = null
        if (scriptPoints) script.linkedIdeaId = null
        emitIdea(idea)
        emitScript(script)
        return true
    }

    /* ---------- tasks ---------- */

    /** Returns null on an invalid due date (null/blank date is fine). */
    fun addTask(channelId: String?, title: String, dueDate: String? = null): GTask? {
        if (!Logic.isValidDate(dueDate)) return null
        val t = GTask(
            id = uid(), channelId = channelId, title = title,
            dueDate = dueDate?.takeIf { it.isNotBlank() }, createdAt = now()
        )
        tasks.add(t)
        emitTask(t)
        return t
    }

    fun taskById(id: String?): GTask? = tasks.find { it.id == id }

    /** Edits a task (v0.3 — same conventions as [updateIdea]).
     *  A null parameter means "leave that field unchanged".
     *  dueDate: "" clears the date; a non-blank date is validated
     *  BEFORE any mutation (invalid -> false, nothing changes).
     *  channelId: null = unchanged, "" = clear (make global),
     *  non-empty must name an existing channel. */
    fun updateTask(
        id: String,
        title: String? = null,
        dueDate: String? = null,
        channelId: String? = null
    ): Boolean {
        val t = taskById(id) ?: return false
        if (dueDate != null && !Logic.isValidDate(dueDate)) return false
        if (channelId != null && channelId.isNotEmpty() && channelById(channelId) == null) return false
        if (title != null) t.title = title
        if (dueDate != null) t.dueDate = dueDate.takeIf { it.isNotBlank() }
        if (channelId != null) t.channelId = channelId.takeIf { it.isNotEmpty() }
        emitTask(t)
        return true
    }

    /** Renames a task (title only) via [updateTask]. */
    fun renameTask(id: String, newTitle: String): Boolean = updateTask(id, title = newTitle)

    /** Removes a task, cleaning the other side of its links (every
     *  linked script's linkedTaskIds, every linked idea's
     *  linkedTaskIds — each re-emitted), then emits a tombstone for
     *  the task. False if the task does not exist. */
    fun removeTask(id: String): Boolean {
        val t = taskById(id) ?: return false
        scripts.filter { it.linkedTaskIds.contains(id) }.forEach {
            it.linkedTaskIds.remove(id); emitScript(it)
        }
        ideas.filter { it.linkedTaskIds.contains(id) }.forEach {
            it.linkedTaskIds.remove(id); emitIdea(it)
        }
        tasks.remove(t)
        emitTaskTombstone(t)
        return true
    }

    /** Re-inserts a previously removed task at [index] (clamped).
     *  False if the id is taken. Single-entity restore: links cleaned
     *  on other entities by [removeTask] are not restored. */
    fun restoreTask(task: GTask, index: Int = tasks.size): Boolean {
        if (taskById(task.id) != null) return false
        val c = copyTask(task)
        tasks.add(index.coerceIn(0, tasks.size), c)
        emitTask(c)
        return true
    }

    /** Flips GTask.done. Returns true if the task existed (success
     *  flag); false if missing. */
    fun toggleTask(taskId: String): Boolean {
        val t = taskById(taskId) ?: return false
        t.done = !t.done
        emitTask(t)
        return true
    }

    /* ---------- task <-> script linking ---------- */

    /** Links a task and a script BOTH ways
     *  (task.linkedScriptIds += scriptId, script.linkedTaskIds += taskId).
     *  Returns false — changing nothing — if either id is missing or
     *  the link already exists (no duplicate entries are ever added).
     *  A one-way (inconsistent) pre-existing link is repaired and
     *  returns true. */
    fun linkTaskToScript(taskId: String, scriptId: String): Boolean {
        val t = taskById(taskId) ?: return false
        val s = scriptById(scriptId) ?: return false
        if (Logic.taskLinkConsistent(t, s)) return false
        if (!t.linkedScriptIds.contains(scriptId)) t.linkedScriptIds.add(scriptId)
        if (!s.linkedTaskIds.contains(taskId)) s.linkedTaskIds.add(taskId)
        emitTask(t)
        emitScript(s)
        return true
    }

    /** Removes a task<->script link from BOTH sides.
     *  Returns false — changing nothing — if either id is missing or
     *  the two are not linked on either side. */
    fun unlinkTaskFromScript(taskId: String, scriptId: String): Boolean {
        val t = taskById(taskId) ?: return false
        val s = scriptById(scriptId) ?: return false
        val wasLinked = t.linkedScriptIds.contains(scriptId) || s.linkedTaskIds.contains(taskId)
        if (!wasLinked) return false
        t.linkedScriptIds.remove(scriptId)
        s.linkedTaskIds.remove(taskId)
        emitTask(t)
        emitScript(s)
        return true
    }

    /** Scripts linked to a task (empty if the task is missing). */
    fun scriptsForTask(taskId: String): List<Script> {
        val t = taskById(taskId) ?: return emptyList()
        return t.linkedScriptIds.mapNotNull { scriptById(it) }
    }

    /** Tasks linked to a script (empty if the script is missing). */
    fun tasksForScript(scriptId: String): List<GTask> {
        val s = scriptById(scriptId) ?: return emptyList()
        return s.linkedTaskIds.mapNotNull { taskById(it) }
    }

    /* ---------- idea <-> task linking (v0.3 — last missing pairing) ---------- */

    /** Links an idea and a task BOTH ways
     *  (idea.linkedTaskIds += taskId, task.linkedIdeaIds += ideaId).
     *  Same contract as [linkTaskToScript]: false — changing nothing —
     *  if either id is missing or the link already exists; a one-way
     *  pre-existing link is repaired and returns true. */
    fun linkIdeaToTask(ideaId: String, taskId: String): Boolean {
        val i = ideaById(ideaId) ?: return false
        val t = taskById(taskId) ?: return false
        if (Logic.ideaTaskLinkConsistent(i, t)) return false
        if (!i.linkedTaskIds.contains(taskId)) i.linkedTaskIds.add(taskId)
        if (!t.linkedIdeaIds.contains(ideaId)) t.linkedIdeaIds.add(ideaId)
        emitIdea(i)
        emitTask(t)
        return true
    }

    /** Removes an idea<->task link from BOTH sides.
     *  Returns false — changing nothing — if either id is missing or
     *  the two are not linked on either side. */
    fun unlinkIdeaFromTask(ideaId: String, taskId: String): Boolean {
        val i = ideaById(ideaId) ?: return false
        val t = taskById(taskId) ?: return false
        val wasLinked = i.linkedTaskIds.contains(taskId) || t.linkedIdeaIds.contains(ideaId)
        if (!wasLinked) return false
        i.linkedTaskIds.remove(taskId)
        t.linkedIdeaIds.remove(ideaId)
        emitIdea(i)
        emitTask(t)
        return true
    }

    /** Tasks linked to an idea (empty if the idea is missing). */
    fun tasksForIdea(ideaId: String): List<GTask> {
        val i = ideaById(ideaId) ?: return emptyList()
        return i.linkedTaskIds.mapNotNull { taskById(it) }
    }

    /** Ideas linked to a task (empty if the task is missing). */
    fun ideasForTask(taskId: String): List<Idea> {
        val t = taskById(taskId) ?: return emptyList()
        return t.linkedIdeaIds.mapNotNull { ideaById(it) }
    }

    /* ---------- pages (Notion engine, v0.3) ----------
     * A page is a record of type "page" (FINDINGS §7.1): a Page here,
     * a "block"-table Record with "type":"page" in the Db mirror.
     * Pages nest via parentPageId; a page's content is its Blocks. */

    /** Creates a page. parentPageId null = a root page; a non-null
     *  parent must exist, else null is returned (nothing created). */
    fun addPage(title: String, parentPageId: String? = null): Page? {
        if (parentPageId != null && pageById(parentPageId) == null) return null
        val t = now()
        val p = Page(id = uid(), title = title, parentPageId = parentPageId, createdAt = t, updatedAt = t)
        pages.add(p)
        emitPage(p)
        return p
    }

    fun pageById(id: String?): Page? = pages.find { it.id == id }

    /** Renames a page and emits the updated record (updatedAt bumped).
     *  False if the page does not exist. */
    fun renamePage(id: String, newTitle: String): Boolean {
        val p = pageById(id) ?: return false
        p.title = newTitle
        p.updatedAt = now()
        emitPage(p)
        return true
    }

    /** Root pages (parentPageId == null), in list order. */
    fun rootPages(): List<Page> = pages.filter { it.parentPageId == null }

    /** Direct children of a page, in list order. */
    fun childPages(id: String): List<Page> = pages.filter { it.parentPageId == id }

    /** The page's id plus every descendant page id (breadth-first).
     *  Cycle-safe: a corrupt parent loop cannot spin forever. */
    fun descendantPageIds(id: String): Set<String> {
        val out = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (out.add(cur)) childPages(cur).forEach { queue.add(it.id) }
        }
        return out
    }

    /** Breadcrumb chain for a page: ancestors root-first, excluding
     *  the page itself. Empty for a root page or a missing page. */
    fun pageAncestors(id: String): List<Page> {
        val chain = mutableListOf<Page>()
        val seen = mutableSetOf<String>()
        var cur = pageById(id)?.parentPageId
        while (cur != null && seen.add(cur)) {
            val p = pageById(cur) ?: break
            chain.add(p)
            cur = p.parentPageId
        }
        return chain.reversed()
    }

    /** Reparents a page. newParentId null = move to root. Returns
     *  false — changing nothing — if the page is missing, the new
     *  parent is missing, the new parent is the page itself or one of
     *  its descendants (that would create a cycle), or the page is
     *  already under newParentId (no-op). */
    fun movePage(pageId: String, newParentId: String?): Boolean {
        val p = pageById(pageId) ?: return false
        if (newParentId != null) {
            if (newParentId == pageId) return false
            if (pageById(newParentId) == null) return false
            if (descendantPageIds(pageId).contains(newParentId)) return false
        }
        if (p.parentPageId == newParentId) return false
        p.parentPageId = newParentId
        p.updatedAt = now()
        emitPage(p)
        return true
    }

    /** Deletes a page WITH cascade (see [removePageCascade]).
     *  False if the page does not exist. Callers that need undo
     *  should use [removePageCascade] + [restorePageCascade]. */
    fun removePage(id: String): Boolean = removePageCascade(id) != null

    /** Cascade-deletes a page: the page, ALL its descendant pages,
     *  and every block belonging to any removed page. Scripts that
     *  linked to a removed page have the dead id removed from
     *  linkedPageIds (and are re-emitted). Emits a tombstone for
     *  every removed page and block. Returns a snapshot that
     *  [restorePageCascade] can fully undo, or null if the page
     *  does not exist. */
    fun removePageCascade(id: String): PageCascadeSnapshot? {
        pageById(id) ?: return null
        val deadIds = descendantPageIds(id)
        val deadPages = pages.withIndex().filter { deadIds.contains(it.value.id) }
        val deadBlocks = blocks.withIndex().filter { deadIds.contains(it.value.pageId) }
        val deadPageObjs = deadPages.map { it.value }
        val deadBlockObjs = deadBlocks.map { it.value }
        val survScripts = scripts.filter { s -> s.linkedPageIds.any { deadIds.contains(it) } }

        val snapshot = PageCascadeSnapshot(
            pages = deadPages.map { it.index to copyPage(it.value) },
            blocks = deadBlocks.map { it.index to it.value.copy() },
            survivorScripts = survScripts.map { copyScript(it) }
        )

        survScripts.forEach { s ->
            s.linkedPageIds.removeAll(deadIds)
            emitScript(s)
        }
        blocks.removeAll(deadBlockObjs)
        pages.removeAll(deadPageObjs)
        deadBlockObjs.forEach { emitBlockTombstone(it) }
        deadPageObjs.forEach { emitPageTombstone(it) }
        return snapshot
    }

    /** Undoes [removePageCascade]: re-inserts every removed page and
     *  block at its original index with its original id and fields,
     *  then surgically re-adds script->page links that pointed at the
     *  restored pages. Emits an alive record for every restore and
     *  every repaired script. Returns false — changing nothing — if
     *  any removed page/block id already exists. */
    fun restorePageCascade(snapshot: PageCascadeSnapshot): Boolean {
        if (snapshot.pages.any { pageById(it.second.id) != null }) return false
        if (snapshot.blocks.any { blockById(it.second.id) != null }) return false
        snapshot.pages.sortedBy { it.first }.forEach { (idx, pg) ->
            val c = copyPage(pg); pages.add(idx.coerceIn(0, pages.size), c); emitPage(c)
        }
        snapshot.blocks.sortedBy { it.first }.forEach { (idx, bl) ->
            val c = bl.copy(); blocks.add(idx.coerceIn(0, blocks.size), c); emitBlock(c)
        }
        val restoredPageIds = snapshot.pages.map { it.second.id }.toSet()
        snapshot.survivorScripts.forEach { snap ->
            val live = scriptById(snap.id) ?: return@forEach
            var changed = false
            snap.linkedPageIds.filter { restoredPageIds.contains(it) && !live.linkedPageIds.contains(it) }
                .forEach { live.linkedPageIds.add(it); changed = true }
            if (changed) emitScript(live)
        }
        return true
    }

    /** Re-inserts a previously removed page at [index] (clamped).
     *  False if the id is taken. Single-entity restore: the page's
     *  blocks and cleaned script links are not restored (the cascade
     *  snapshot restores those). */
    fun restorePage(page: Page, index: Int = pages.size): Boolean {
        if (pageById(page.id) != null) return false
        val c = copyPage(page)
        pages.add(index.coerceIn(0, pages.size), c)
        emitPage(c)
        return true
    }

    /* ---------- page <-> script linking (v0.3) ---------- */

    /** Links a page and a script BOTH ways
     *  (page.linkedScriptIds += scriptId, script.linkedPageIds += pageId).
     *  Same contract as [linkTaskToScript]: false — changing nothing —
     *  if either id is missing or the link already exists; a one-way
     *  pre-existing link is repaired and returns true. */
    fun linkPageToScript(pageId: String, scriptId: String): Boolean {
        val p = pageById(pageId) ?: return false
        val s = scriptById(scriptId) ?: return false
        if (Logic.pageLinkConsistent(p, s)) return false
        if (!p.linkedScriptIds.contains(scriptId)) p.linkedScriptIds.add(scriptId)
        if (!s.linkedPageIds.contains(pageId)) s.linkedPageIds.add(pageId)
        emitPage(p)
        emitScript(s)
        return true
    }

    /** Removes a page<->script link from BOTH sides.
     *  Returns false — changing nothing — if either id is missing or
     *  the two are not linked on either side. */
    fun unlinkPageFromScript(pageId: String, scriptId: String): Boolean {
        val p = pageById(pageId) ?: return false
        val s = scriptById(scriptId) ?: return false
        val wasLinked = p.linkedScriptIds.contains(scriptId) || s.linkedPageIds.contains(pageId)
        if (!wasLinked) return false
        p.linkedScriptIds.remove(scriptId)
        s.linkedPageIds.remove(pageId)
        emitPage(p)
        emitScript(s)
        return true
    }

    /** Scripts linked to a page (empty if the page is missing). */
    fun scriptsForPage(pageId: String): List<Script> {
        val p = pageById(pageId) ?: return emptyList()
        return p.linkedScriptIds.mapNotNull { scriptById(it) }
    }

    /** Pages linked to a script (empty if the script is missing). */
    fun pagesForScript(scriptId: String): List<Page> {
        val s = scriptById(scriptId) ?: return emptyList()
        return s.linkedPageIds.mapNotNull { pageById(it) }
    }

    /* ---------- blocks ---------- */

    /** Appends a block at the end of a page: its order is one past the
     *  page's current maximum order (0 for an empty page) — this holds
     *  for child blocks too (v0.4): a new child is appended at the
     *  page end order-wise; the UI indents it under its parent, and
     *  the sibling-scoped moves (see [moveBlock]) can then position
     *  it among its siblings without disturbing other groups.
     *  [type] must be one of Logic.BLOCK_TYPES (text, heading,
     *  bulleted_list, numbered_list, todo, toggle, quote, code,
     *  divider); an unknown type throws IllegalArgumentException and
     *  adds nothing. checked/collapsed only matter for todo/toggle.
     *  [parentBlockId] (v0.4, appended parameter — existing callers
     *  are unaffected): when non-null, the parent block must exist
     *  AND belong to the same page, else IllegalArgumentException is
     *  thrown and nothing is added. This is how toggle child blocks
     *  are authored (R3-N2). */
    fun addBlock(
        pageId: String,
        type: String = "text",
        text: String = "",
        checked: Boolean = false,
        collapsed: Boolean = false,
        parentBlockId: String? = null
    ): Block {
        require(Logic.isValidBlockType(type)) { "Unknown block type: $type" }
        if (parentBlockId != null) {
            val parent = blockById(parentBlockId)
            require(parent != null && parent.pageId == pageId) {
                "Parent block $parentBlockId does not exist on page $pageId"
            }
        }
        val nextOrder = (blocks.filter { it.pageId == pageId }.maxOfOrNull { it.order } ?: -1) + 1
        val b = Block(
            id = uid(), pageId = pageId, type = type, text = text,
            order = nextOrder, parentBlockId = parentBlockId,
            checked = checked, collapsed = collapsed
        )
        blocks.add(b)
        emitBlock(b)
        return b
    }

    fun blockById(id: String?): Block? = blocks.find { it.id == id }

    /** The page's blocks sorted by order (stable). */
    fun blocksForPage(pageId: String): List<Block> =
        Logic.sortBlocks(blocks.filter { it.pageId == pageId })

    /** Direct children of a block (blocks whose parentBlockId ==
     *  [blockId]), sorted by order. Empty if the block is missing
     *  or has no children. (v0.4) */
    fun childBlocks(blockId: String): List<Block> =
        Logic.sortBlocks(blocks.filter { it.parentBlockId == blockId })

    /** Replaces a block's text and emits it. This is also the block
     *  "rename". False if the block does not exist. */
    fun updateBlockText(blockId: String, text: String): Boolean {
        val b = blockById(blockId) ?: return false
        b.text = text
        emitBlock(b)
        return true
    }

    /** Changes a block's type ("turn into"). False — changing
     *  nothing — if the block is missing or [type] is not in
     *  Logic.BLOCK_TYPES. */
    fun updateBlockType(blockId: String, type: String): Boolean {
        if (!Logic.isValidBlockType(type)) return false
        val b = blockById(blockId) ?: return false
        b.type = type
        emitBlock(b)
        return true
    }

    /** Combined text/type edit; a null parameter leaves that field
     *  unchanged. An unknown non-null type returns false and changes
     *  nothing (validated before any mutation). */
    fun updateBlock(blockId: String, text: String? = null, type: String? = null): Boolean {
        if (type != null && !Logic.isValidBlockType(type)) return false
        val b = blockById(blockId) ?: return false
        if (text != null) b.text = text
        if (type != null) b.type = type
        emitBlock(b)
        return true
    }

    /** Sets a to-do block's checkbox state and emits it.
     *  False if the block does not exist. */
    fun setBlockChecked(blockId: String, checked: Boolean): Boolean {
        val b = blockById(blockId) ?: return false
        b.checked = checked
        emitBlock(b)
        return true
    }

    /** Sets a toggle block's collapsed state and emits it.
     *  False if the block does not exist. */
    fun setBlockCollapsed(blockId: String, collapsed: Boolean): Boolean {
        val b = blockById(blockId) ?: return false
        b.collapsed = collapsed
        emitBlock(b)
        return true
    }

    /** Moves a block to [targetIndex] WITHIN ITS SIBLING GROUP
     *  (v0.4, sibling-scoped — closes R3-N3).
     *
     *  Semantics: a block's siblings are the blocks sharing its
     *  pageId AND its parentBlockId (top-level blocks are siblings
     *  of each other; a toggle's children are siblings of each
     *  other). [targetIndex] is the 0-based position within that
     *  sibling group (in page order), clamped to the group's range.
     *  The block is repositioned only inside the sibling subsequence
     *  of the page's full order-sorted list — blocks of other groups
     *  keep their slots in the full sequence — then the WHOLE page's
     *  order values are normalised to 0,1,2,… in the resulting
     *  sequence and every block of the page is emitted.
     *
     *  Consequences: a top-level move can never leapfrog a child
     *  block into a different relative position, and a child move
     *  never escapes its parent's group. On a FLAT page (no child
     *  blocks) the sibling group is the whole page, so behaviour is
     *  exactly the v0.3 behaviour and all v0.3 move tests hold.
     *  Returns false if the block does not exist; true if it does
     *  (including when it was already at targetIndex — orders are
     *  still normalised, which also repairs scrambled orders). */
    fun moveBlock(blockId: String, targetIndex: Int): Boolean {
        val b = blockById(blockId) ?: return false
        val full = blocksForPage(b.pageId)
        val siblings = full.filter { it.parentBlockId == b.parentBlockId }
        val from = siblings.indexOfFirst { it.id == blockId }
        if (from < 0) return false
        val to = targetIndex.coerceIn(0, siblings.size - 1)
        val result: List<Block>
        if (from == to) {
            result = full
        } else {
            val newSiblings = siblings.toMutableList()
            newSiblings.removeAt(from)
            newSiblings.add(to, b)
            // Rebuild the full sequence: sibling slots are refilled
            // in the new sibling order; every other block stays put.
            val iter = newSiblings.iterator()
            result = full.map { if (it.parentBlockId == b.parentBlockId) iter.next() else it }
        }
        result.forEachIndexed { idx, blk -> blk.order = idx }
        result.forEach { emitBlock(it) }
        return true
    }

    /** Moves a block one position earlier WITHIN ITS SIBLING GROUP.
     *  False if the block is missing or already the first sibling. */
    fun moveBlockUp(blockId: String): Boolean {
        val b = blockById(blockId) ?: return false
        val siblings = blocksForPage(b.pageId).filter { it.parentBlockId == b.parentBlockId }
        val idx = siblings.indexOfFirst { it.id == blockId }
        if (idx <= 0) return false
        return moveBlock(blockId, idx - 1)
    }

    /** Moves a block one position later WITHIN ITS SIBLING GROUP.
     *  False if the block is missing or already the last sibling. */
    fun moveBlockDown(blockId: String): Boolean {
        val b = blockById(blockId) ?: return false
        val siblings = blocksForPage(b.pageId).filter { it.parentBlockId == b.parentBlockId }
        val idx = siblings.indexOfFirst { it.id == blockId }
        if (idx < 0 || idx >= siblings.size - 1) return false
        return moveBlock(blockId, idx + 1)
    }

    /** Removes a block, emits its tombstone, and normalises the
     *  remaining blocks of its page to orders 0,1,2,… (re-emitting
     *  any whose order changed). False if the block does not exist. */
    fun removeBlock(blockId: String): Boolean {
        val b = blockById(blockId) ?: return false
        val pid = b.pageId
        blocks.remove(b)
        emitBlockTombstone(b)
        normaliseBlockOrders(pid)
        return true
    }

    /** Re-inserts a previously removed block at [index] (clamped).
     *  False if the id is taken. Note the block keeps its stored
     *  order value; call [moveBlock] if strict normalisation is
     *  needed after a manual restore. */
    fun restoreBlock(block: Block, index: Int = blocks.size): Boolean {
        if (blockById(block.id) != null) return false
        val c = block.copy()
        blocks.add(index.coerceIn(0, blocks.size), c)
        emitBlock(c)
        return true
    }

    /** Rewrites a page's order values to 0,1,2,… in sorted sequence,
     *  emitting any block whose order actually changed. */
    private fun normaliseBlockOrders(pageId: String) {
        blocksForPage(pageId).forEachIndexed { idx, blk ->
            if (blk.order != idx) {
                blk.order = idx
                emitBlock(blk)
            }
        }
    }

    /* ---------- templates (v0.4) ----------
     * Page templates: named snapshots of a page's blocks. Plain
     * Store data — JSON-persisted, deliberately NOT emitted to the
     * Db sink (the mirror covers only the six entity tables; a
     * template is not a page and must never appear as one there). */

    fun templateById(id: String?): PageTemplate? = templates.find { it.id == id }

    /** Snapshots a page's blocks as a new template named [name].
     *  The snapshot is the page's blocks in blocksForPage order;
     *  each TemplateBlock.parentIndex is the index IN THAT ORDERED
     *  LIST of the block's parent (when the parent is itself a
     *  block of the same page), else null. Returns null — creating
     *  nothing — if the page does not exist or [name] is blank.
     *  The snapshot is a deep copy: later edits to the page do not
     *  change the template. */
    fun savePageAsTemplate(pageId: String, name: String): PageTemplate? {
        if (pageById(pageId) == null) return null
        if (name.isBlank()) return null
        val ordered = blocksForPage(pageId)
        val indexById = ordered.withIndex().associate { it.value.id to it.index }
        val snapshot = ordered.map { b ->
            TemplateBlock(
                type = b.type, text = b.text,
                checked = b.checked, collapsed = b.collapsed,
                parentIndex = b.parentBlockId?.let { indexById[it] }
            )
        }
        val t = PageTemplate(id = uid(), name = name, blocks = snapshot, createdAt = now())
        templates.add(t)
        return t
    }

    /** Creates a NEW page from a template: the page (title = [title]
     *  when non-blank, else the template's name), then the
     *  template's blocks re-created IN ORDER via [addBlock] with
     *  fresh ids, mapping each parentIndex to the corresponding new
     *  block id. A parentIndex pointing at the block itself, a later
     *  block, or outside the list is treated as null (top-level) —
     *  a block can only parent to an already-created block. A stored
     *  block type that is no longer valid falls back to "text"
     *  rather than failing the whole creation. Returns null if the
     *  template does not exist. */
    fun createPageFromTemplate(templateId: String, title: String? = null): Page? {
        val template = templateById(templateId) ?: return null
        val page = addPage(title?.takeIf { it.isNotBlank() } ?: template.name) ?: return null
        val createdIds = mutableListOf<String>()
        for (tb in template.blocks) {
            val parentId = tb.parentIndex
                ?.takeIf { it in createdIds.indices }
                ?.let { createdIds[it] }
            val type = if (Logic.isValidBlockType(tb.type)) tb.type else "text"
            val created = addBlock(page.id, type, tb.text, tb.checked, tb.collapsed, parentId)
            createdIds.add(created.id)
        }
        return page
    }

    /** Removes a template. False if it does not exist. (Pages
     *  already created from it are unaffected.) */
    fun removeTemplate(id: String): Boolean {
        val t = templateById(id) ?: return false
        templates.remove(t)
        return true
    }

    /** Renames a template. False if it does not exist or [newName]
     *  is blank (nothing changed). */
    fun renameTemplate(id: String, newName: String): Boolean {
        if (newName.isBlank()) return false
        val t = templateById(id) ?: return false
        t.name = newName
        return true
    }

    /* ---------- trash (v0.4) ----------
     * A persisted bin. ONLY the deleteXToTrash APIs below create
     * entries — the plain removeX APIs behave exactly as before and
     * create NONE. Each ToTrash API performs the SAME removal as its
     * plain counterpart (same link cleanup, same tombstones) and, on
     * success, appends a TrashEntry (newest LAST) whose payloadJson
     * fully encodes the restore: singles store
     * {"index":Int,"entity":{...}} (the entity copy captured BEFORE
     * removal, at its original list index); channel/page store a
     * serialised cascade snapshot. [restoreFromTrash] replays the
     * existing restore machinery — restoreX for singles (entity
     * only; links cleaned on OTHER entities are not restored, the
     * documented single-restore semantics) and
     * restoreChannelCascade / restorePageCascade for cascades (full
     * restore incl. survivor links). Trash entries are plain data:
     * JSON-persisted, NOT sink-emitted. */

    fun trashById(id: String?): TrashEntry? = trash.find { it.id == id }

    private fun addTrashEntry(kind: String, label: String, payloadJson: String): TrashEntry {
        val e = TrashEntry(id = uid(), kind = kind, label = label, deletedAt = now(), payloadJson = payloadJson)
        trash.add(e)
        return e
    }

    /** [removeScript] + a trash entry. Null if the script is missing. */
    fun deleteScriptToTrash(id: String): TrashEntry? {
        val s = scriptById(id) ?: return null
        val idx = scripts.indexOf(s)
        val copy = copyScript(s)
        if (!removeScript(id)) return null
        return addTrashEntry("script", copy.title, "{\"index\":$idx,\"entity\":${scriptJson(copy)}}")
    }

    /** [removeIdea] + a trash entry. Null if the idea is missing. */
    fun deleteIdeaToTrash(id: String): TrashEntry? {
        val i = ideaById(id) ?: return null
        val idx = ideas.indexOf(i)
        val copy = copyIdea(i)
        if (!removeIdea(id)) return null
        return addTrashEntry("idea", copy.title, "{\"index\":$idx,\"entity\":${ideaJson(copy)}}")
    }

    /** [removeTask] + a trash entry. Null if the task is missing. */
    fun deleteTaskToTrash(id: String): TrashEntry? {
        val t = taskById(id) ?: return null
        val idx = tasks.indexOf(t)
        val copy = copyTask(t)
        if (!removeTask(id)) return null
        return addTrashEntry("task", copy.title, "{\"index\":$idx,\"entity\":${taskJson(copy)}}")
    }

    /** [removeBlock] + a trash entry. The label is the block's text,
     *  or its type when the text is blank (e.g. a divider).
     *  Null if the block is missing. */
    fun deleteBlockToTrash(id: String): TrashEntry? {
        val b = blockById(id) ?: return null
        val idx = blocks.indexOf(b)
        val copy = b.copy()
        if (!removeBlock(id)) return null
        val label = copy.text.takeIf { it.isNotBlank() } ?: copy.type
        return addTrashEntry("block", label, "{\"index\":$idx,\"entity\":${blockJson(copy)}}")
    }

    /** [removeChannelCascade] + a trash entry holding the full
     *  snapshot. Null if the channel is missing. */
    fun deleteChannelToTrash(id: String): TrashEntry? {
        val snap = removeChannelCascade(id) ?: return null
        return addTrashEntry("channel", snap.channel.name, serializeChannelSnapshot(snap))
    }

    /** [removePageCascade] + a trash entry holding the full
     *  snapshot. Null if the page is missing. */
    fun deletePageToTrash(id: String): TrashEntry? {
        val snap = removePageCascade(id) ?: return null
        val label = snap.pages.firstOrNull { it.second.id == id }?.second?.title ?: "Page"
        return addTrashEntry("page", label, serializePageSnapshot(snap))
    }

    /** Restores a trash entry, dispatching on its kind (singles via
     *  restoreX at the stored index; channel/page via the cascade
     *  restores). On SUCCESS the entry is removed from the trash and
     *  true is returned. On FAILURE (unknown kind, corrupt payload,
     *  or an id collision — e.g. an entity with the same id now
     *  exists) the entry is KEPT and false is returned. */
    fun restoreFromTrash(entryId: String): Boolean {
        val entry = trashById(entryId) ?: return false
        val ok = when (entry.kind) {
            "script" -> parseIndexedEntity(entry.payloadJson)
                ?.let { (idx, m) -> restoreScript(scriptFromMap(m), idx) } ?: false
            "idea" -> parseIndexedEntity(entry.payloadJson)
                ?.let { (idx, m) -> restoreIdea(ideaFromMap(m), idx) } ?: false
            "task" -> parseIndexedEntity(entry.payloadJson)
                ?.let { (idx, m) -> restoreTask(taskFromMap(m), idx) } ?: false
            "block" -> parseIndexedEntity(entry.payloadJson)
                ?.let { (idx, m) -> restoreBlock(blockFromMap(m), idx) } ?: false
            "channel" -> parseChannelSnapshot(entry.payloadJson)
                ?.let { restoreChannelCascade(it) } ?: false
            "page" -> parsePageSnapshot(entry.payloadJson)
                ?.let { restorePageCascade(it) } ?: false
            else -> false
        }
        if (ok) trash.remove(entry)
        return ok
    }

    /** Drops a trash entry WITHOUT restoring anything (the entities
     *  were already deleted and tombstoned at delete time). False
     *  if the entry does not exist. */
    fun permanentlyDeleteTrash(entryId: String): Boolean {
        val e = trashById(entryId) ?: return false
        trash.remove(e)
        return true
    }

    /** Drops every trash entry (no restores). */
    fun emptyTrash() {
        trash.clear()
    }

    /* ----- trash payload (de)serialisation -----
     * Reuses the per-entity JSON writers above and the JsonParser /
     * Map helpers below. Singles: {"index":Int,"entity":{...}}.
     * Cascades: the snapshot's fields, with indexed pairs encoded
     * as {"index":Int,"entity":{...}} objects. */

    private fun indexedJson(index: Int, entityJson: String): String =
        "{\"index\":$index,\"entity\":$entityJson}"

    private fun parseIndexedEntity(payloadJson: String): Pair<Int, Map<*, *>>? {
        val root = JsonParser(payloadJson).parseTop() as? Map<*, *> ?: return null
        val entity = root["entity"] as? Map<*, *> ?: return null
        return root.long("index").toInt() to entity
    }

    private fun parseIndexedList(root: Map<*, *>, key: String): List<Pair<Int, Map<*, *>>> =
        root.arr(key).mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val e = m["entity"] as? Map<*, *> ?: return@mapNotNull null
            m.long("index").toInt() to e
        }

    private fun parseEntityList(root: Map<*, *>, key: String): List<Map<*, *>> =
        root.arr(key).mapNotNull { it as? Map<*, *> }

    private fun serializeChannelSnapshot(snap: ChannelCascadeSnapshot): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"channel\":").append(channelJson(snap.channel)).append(',')
        sb.append("\"channelIndex\":").append(snap.channelIndex).append(',')
        sb.append("\"scripts\":[").append(snap.scripts.joinToString(",") { indexedJson(it.first, scriptJson(it.second)) }).append("],")
        sb.append("\"tasks\":[").append(snap.tasks.joinToString(",") { indexedJson(it.first, taskJson(it.second)) }).append("],")
        sb.append("\"ideas\":[").append(snap.ideas.joinToString(",") { indexedJson(it.first, ideaJson(it.second)) }).append("],")
        sb.append("\"survivorScripts\":[").append(snap.survivorScripts.joinToString(",") { scriptJson(it) }).append("],")
        sb.append("\"survivorTasks\":[").append(snap.survivorTasks.joinToString(",") { taskJson(it) }).append("],")
        sb.append("\"survivorIdeas\":[").append(snap.survivorIdeas.joinToString(",") { ideaJson(it) }).append("],")
        sb.append("\"survivorPages\":[").append(snap.survivorPages.joinToString(",") { pageJson(it) }).append(']')
        sb.append('}')
        return sb.toString()
    }

    private fun parseChannelSnapshot(payloadJson: String): ChannelCascadeSnapshot? {
        val root = JsonParser(payloadJson).parseTop() as? Map<*, *> ?: return null
        val chMap = root["channel"] as? Map<*, *> ?: return null
        return ChannelCascadeSnapshot(
            channel = channelFromMap(chMap),
            channelIndex = root.long("channelIndex").toInt(),
            scripts = parseIndexedList(root, "scripts").map { it.first to scriptFromMap(it.second) },
            tasks = parseIndexedList(root, "tasks").map { it.first to taskFromMap(it.second) },
            ideas = parseIndexedList(root, "ideas").map { it.first to ideaFromMap(it.second) },
            survivorScripts = parseEntityList(root, "survivorScripts").map { scriptFromMap(it) },
            survivorTasks = parseEntityList(root, "survivorTasks").map { taskFromMap(it) },
            survivorIdeas = parseEntityList(root, "survivorIdeas").map { ideaFromMap(it) },
            survivorPages = parseEntityList(root, "survivorPages").map { pageFromMap(it) }
        )
    }

    private fun serializePageSnapshot(snap: PageCascadeSnapshot): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"pages\":[").append(snap.pages.joinToString(",") { indexedJson(it.first, pageJson(it.second)) }).append("],")
        sb.append("\"blocks\":[").append(snap.blocks.joinToString(",") { indexedJson(it.first, blockJson(it.second)) }).append("],")
        sb.append("\"survivorScripts\":[").append(snap.survivorScripts.joinToString(",") { scriptJson(it) }).append(']')
        sb.append('}')
        return sb.toString()
    }

    private fun parsePageSnapshot(payloadJson: String): PageCascadeSnapshot? {
        val root = JsonParser(payloadJson).parseTop() as? Map<*, *> ?: return null
        return PageCascadeSnapshot(
            pages = parseIndexedList(root, "pages").map { it.first to pageFromMap(it.second) },
            blocks = parseIndexedList(root, "blocks").map { it.first to blockFromMap(it.second) },
            survivorScripts = parseEntityList(root, "survivorScripts").map { scriptFromMap(it) }
        )
    }

    /* ----- entity reconstruction from a parsed JSON map -----
     * Same field/default rules as loadFromJsonString. */

    private fun channelFromMap(j: Map<*, *>): Channel =
        Channel(id = j.str("id"), name = j.str("name"), createdAt = j.long("createdAt"))

    private fun scriptFromMap(j: Map<*, *>): Script {
        val dateStr = j.nullableStr("date")
        return Script(
            id = j.str("id"),
            channelId = j.str("channelId"),
            title = j.str("title"),
            body = j.str("body"),
            language = try { Language.valueOf(j.str("language", "BANGLA")) } catch (e: Exception) { Language.BANGLA },
            status = try { ScriptStatus.valueOf(j.str("status", "DRAFT")) } catch (e: Exception) { ScriptStatus.DRAFT },
            date = dateStr?.takeIf { Logic.isValidDate(it) && it.isNotBlank() },
            linkedIdeaId = j.nullableStr("linkedIdeaId"),
            linkedTaskIds = j.strList("linkedTaskIds"),
            linkedPageIds = j.strList("linkedPageIds"),
            createdAt = j.long("createdAt"),
            updatedAt = j.long("updatedAt")
        )
    }

    private fun ideaFromMap(j: Map<*, *>): Idea =
        Idea(
            id = j.str("id"), title = j.str("title"), notes = j.str("notes"),
            channelId = j.nullableStr("channelId"),
            linkedScriptId = j.nullableStr("linkedScriptId"),
            linkedTaskIds = j.strList("linkedTaskIds"),
            createdAt = j.long("createdAt")
        )

    private fun taskFromMap(j: Map<*, *>): GTask {
        val dueStr = j.nullableStr("dueDate")
        return GTask(
            id = j.str("id"), channelId = j.nullableStr("channelId"), title = j.str("title"),
            dueDate = dueStr?.takeIf { Logic.isValidDate(it) && it.isNotBlank() },
            done = j.bool("done"),
            linkedScriptIds = j.strList("linkedScriptIds"),
            linkedIdeaIds = j.strList("linkedIdeaIds"),
            createdAt = j.long("createdAt")
        )
    }

    private fun blockFromMap(j: Map<*, *>): Block =
        Block(
            id = j.str("id"), pageId = j.str("pageId"),
            type = j.str("type", "text"), text = j.str("text"),
            order = j.long("order").toInt(),
            parentBlockId = j.nullableStr("parentBlockId"),
            checked = j.bool("checked"), collapsed = j.bool("collapsed")
        )

    private fun pageFromMap(j: Map<*, *>): Page =
        Page(
            id = j.str("id"), title = j.str("title"),
            parentPageId = j.nullableStr("parentPageId"),
            linkedScriptIds = j.strList("linkedScriptIds"),
            createdAt = j.long("createdAt"), updatedAt = j.long("updatedAt")
        )

    /* ---------- JSON persistence (pure-Kotlin codec — runs anywhere) ---------- */

    fun toJsonString(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"channels\":[").append(channels.joinToString(",") { channelJson(it) }).append("],")
        sb.append("\"scripts\":[").append(scripts.joinToString(",") { scriptJson(it) }).append("],")
        sb.append("\"ideas\":[").append(ideas.joinToString(",") { ideaJson(it) }).append("],")
        sb.append("\"tasks\":[").append(tasks.joinToString(",") { taskJson(it) }).append("],")
        sb.append("\"pages\":[").append(pages.joinToString(",") { pageJson(it) }).append("],")
        sb.append("\"blocks\":[").append(blocks.joinToString(",") { blockJson(it) }).append("],")
        sb.append("\"templates\":[").append(templates.joinToString(",") { templateJson(it) }).append("],")
        sb.append("\"trash\":[").append(trash.joinToString(",") { trashJson(it) }).append(']')
        sb.append('}')
        return sb.toString()
    }

    /** Replaces all in-memory content with the JSON string's content.
     *
     *  Returns TRUE on success. Returns FALSE — leaving the live store
     *  COMPLETELY untouched — when the string is not a JSON object, or
     *  when any known top-level key (channels / scripts / ideas /
     *  tasks / pages / blocks / templates / trash) is present but its
     *  value is NOT an array (R2-N4: `{"channels": 5}` must not wipe
     *  the store; v0.4 adds templates/trash to the validated keys).
     *  Parsing otherwise happens into temporary lists first, so a
     *  malformed payload can never half-clear the store.
     *  (v0.2 returned Unit; existing callers that ignore the result
     *  still compile — but the import UI SHOULD check it and only
     *  toast success on true.)
     *
     *  On success the Db mirror is reconciled: tombstones are emitted
     *  for every pre-load id that is absent from the new dataset
     *  (R2-N1 ghost rows), then the new state is synced. */
    fun loadFromJsonString(s: String): Boolean {
        val root = JsonParser(s).parseTop() as? Map<*, *> ?: return false

        // Per-key type validation (R2-N4) — before anything is parsed
        // or mutated. A present key holding a non-array (number,
        // string, object, null, …) rejects the whole load.
        for (key in listOf("channels", "scripts", "ideas", "tasks", "pages", "blocks", "templates", "trash")) {
            if (root.containsKey(key) && root[key] !is List<*>) return false
        }

        val newChannels = mutableListOf<Channel>()
        val newScripts = mutableListOf<Script>()
        val newIdeas = mutableListOf<Idea>()
        val newTasks = mutableListOf<GTask>()
        val newPages = mutableListOf<Page>()
        val newBlocks = mutableListOf<Block>()
        val newTemplates = mutableListOf<PageTemplate>()
        val newTrash = mutableListOf<TrashEntry>()

        for (item in root.arr("channels")) {
            val j = item as? Map<*, *> ?: continue
            newChannels.add(Channel(j.str("id"), j.str("name"), j.long("createdAt")))
        }
        for (item in root.arr("scripts")) {
            val j = item as? Map<*, *> ?: continue
            val dateStr = j.nullableStr("date")
            newScripts.add(
                Script(
                    id = j.str("id"),
                    channelId = j.str("channelId"),
                    title = j.str("title"),
                    body = j.str("body"),
                    language = try { Language.valueOf(j.str("language", "BANGLA")) } catch (e: Exception) { Language.BANGLA },
                    status = try { ScriptStatus.valueOf(j.str("status", "DRAFT")) } catch (e: Exception) { ScriptStatus.DRAFT },
                    date = dateStr?.takeIf { Logic.isValidDate(it) && it.isNotBlank() },
                    linkedIdeaId = j.nullableStr("linkedIdeaId"),
                    linkedTaskIds = j.strList("linkedTaskIds"),
                    linkedPageIds = j.strList("linkedPageIds"),
                    createdAt = j.long("createdAt"),
                    updatedAt = j.long("updatedAt")
                )
            )
        }
        for (item in root.arr("ideas")) {
            val j = item as? Map<*, *> ?: continue
            newIdeas.add(
                Idea(
                    id = j.str("id"),
                    title = j.str("title"),
                    notes = j.str("notes"),
                    channelId = j.nullableStr("channelId"),
                    linkedScriptId = j.nullableStr("linkedScriptId"),
                    linkedTaskIds = j.strList("linkedTaskIds"),
                    createdAt = j.long("createdAt")
                )
            )
        }
        for (item in root.arr("tasks")) {
            val j = item as? Map<*, *> ?: continue
            val dueStr = j.nullableStr("dueDate")
            newTasks.add(
                GTask(
                    id = j.str("id"),
                    channelId = j.nullableStr("channelId"),
                    title = j.str("title"),
                    dueDate = dueStr?.takeIf { Logic.isValidDate(it) && it.isNotBlank() },
                    done = j.bool("done"),
                    linkedScriptIds = j.strList("linkedScriptIds"),
                    linkedIdeaIds = j.strList("linkedIdeaIds"),
                    createdAt = j.long("createdAt")
                )
            )
        }
        for (item in root.arr("pages")) {
            val j = item as? Map<*, *> ?: continue
            newPages.add(
                Page(
                    id = j.str("id"),
                    title = j.str("title"),
                    parentPageId = j.nullableStr("parentPageId"),
                    linkedScriptIds = j.strList("linkedScriptIds"),
                    createdAt = j.long("createdAt"),
                    updatedAt = j.long("updatedAt")
                )
            )
        }
        for (item in root.arr("blocks")) {
            val j = item as? Map<*, *> ?: continue
            newBlocks.add(
                Block(
                    id = j.str("id"),
                    pageId = j.str("pageId"),
                    type = j.str("type", "text"),
                    text = j.str("text"),
                    order = j.long("order").toInt(),
                    parentBlockId = j.nullableStr("parentBlockId"),
                    checked = j.bool("checked"),
                    collapsed = j.bool("collapsed")
                )
            )
        }
        // Templates + trash (v0.4). Absent keys (v0.3 and older files)
        // load as empty lists — backward compatible by construction.
        for (item in root.arr("templates")) {
            val j = item as? Map<*, *> ?: continue
            val tBlocks = j.arr("blocks").mapNotNull { bi ->
                val bj = bi as? Map<*, *> ?: return@mapNotNull null
                TemplateBlock(
                    type = bj.str("type", "text"),
                    text = bj.str("text"),
                    checked = bj.bool("checked"),
                    collapsed = bj.bool("collapsed"),
                    parentIndex = (bj["parentIndex"] as? Number)?.toInt()
                )
            }
            newTemplates.add(
                PageTemplate(
                    id = j.str("id"),
                    name = j.str("name"),
                    blocks = tBlocks,
                    createdAt = j.long("createdAt")
                )
            )
        }
        for (item in root.arr("trash")) {
            val j = item as? Map<*, *> ?: continue
            newTrash.add(
                TrashEntry(
                    id = j.str("id"),
                    kind = j.str("kind"),
                    label = j.str("label"),
                    deletedAt = j.long("deletedAt"),
                    payloadJson = j.str("payloadJson", "{}")
                )
            )
        }

        // Capture the pre-load state so the mirror can be reconciled.
        val oldChannels = channels.toList()
        val oldScripts = scripts.toList()
        val oldIdeas = ideas.toList()
        val oldTasks = tasks.toList()
        val oldPages = pages.toList()
        val oldBlocks = blocks.toList()

        channels.clear(); channels.addAll(newChannels)
        scripts.clear(); scripts.addAll(newScripts)
        ideas.clear(); ideas.addAll(newIdeas)
        tasks.clear(); tasks.addAll(newTasks)
        pages.clear(); pages.addAll(newPages)
        blocks.clear(); blocks.addAll(newBlocks)
        // Templates/trash are plain data (no sink emission — see the
        // class doc): a load simply replaces both lists.
        templates.clear(); templates.addAll(newTemplates)
        trash.clear(); trash.addAll(newTrash)

        // A load is a wholesale mutation: tombstone mirror rows whose
        // ids did not survive the load (R2-N1 ghost rows), then mirror
        // the new state.
        val newChannelIds = newChannels.map { it.id }.toSet()
        val newScriptIds = newScripts.map { it.id }.toSet()
        val newIdeaIds = newIdeas.map { it.id }.toSet()
        val newTaskIds = newTasks.map { it.id }.toSet()
        val newPageIds = newPages.map { it.id }.toSet()
        val newBlockIds = newBlocks.map { it.id }.toSet()
        oldChannels.filter { !newChannelIds.contains(it.id) }.forEach { emitChannelTombstone(it) }
        oldScripts.filter { !newScriptIds.contains(it.id) }.forEach { emitScriptTombstone(it) }
        oldIdeas.filter { !newIdeaIds.contains(it.id) }.forEach { emitIdeaTombstone(it) }
        oldTasks.filter { !newTaskIds.contains(it.id) }.forEach { emitTaskTombstone(it) }
        oldPages.filter { !newPageIds.contains(it.id) }.forEach { emitPageTombstone(it) }
        oldBlocks.filter { !newBlockIds.contains(it.id) }.forEach { emitBlockTombstone(it) }
        syncToRecordSink()
        return true
    }
}

/* ================================================================== */
/* Per-entity JSON writers (single source for the full-store JSON     */
/* and for Record.dataJson in the Db write-through mirror).           */
/* ================================================================== */

private fun jsonEsc(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
    }
    return sb.toString()
}

private fun q(s: String?): String = if (s == null) "null" else "\"" + jsonEsc(s) + "\""

private fun qList(list: List<String>): String =
    list.joinToString(",", "[", "]") { q(it) }

private fun channelJson(c: Channel): String =
    "{\"id\":${q(c.id)},\"name\":${q(c.name)},\"createdAt\":${c.createdAt}}"

private fun scriptJson(s: Script): String =
    "{\"id\":${q(s.id)},\"channelId\":${q(s.channelId)},\"title\":${q(s.title)}," +
        "\"body\":${q(s.body)},\"language\":${q(s.language.name)},\"status\":${q(s.status.name)}," +
        "\"date\":${q(s.date)},\"linkedIdeaId\":${q(s.linkedIdeaId)}," +
        "\"linkedTaskIds\":${qList(s.linkedTaskIds)},\"linkedPageIds\":${qList(s.linkedPageIds)}," +
        "\"createdAt\":${s.createdAt},\"updatedAt\":${s.updatedAt}}"

private fun ideaJson(i: Idea): String =
    "{\"id\":${q(i.id)},\"title\":${q(i.title)},\"notes\":${q(i.notes)}," +
        "\"channelId\":${q(i.channelId)},\"linkedScriptId\":${q(i.linkedScriptId)}," +
        "\"linkedTaskIds\":${qList(i.linkedTaskIds)},\"createdAt\":${i.createdAt}}"

private fun taskJson(t: GTask): String =
    "{\"id\":${q(t.id)},\"channelId\":${q(t.channelId)},\"title\":${q(t.title)}," +
        "\"dueDate\":${q(t.dueDate)},\"done\":${t.done}," +
        "\"linkedScriptIds\":${qList(t.linkedScriptIds)},\"linkedIdeaIds\":${qList(t.linkedIdeaIds)}," +
        "\"createdAt\":${t.createdAt}}"

private fun blockJson(b: Block): String =
    "{\"id\":${q(b.id)},\"pageId\":${q(b.pageId)},\"type\":${q(b.type)}," +
        "\"text\":${q(b.text)},\"order\":${b.order},\"parentBlockId\":${q(b.parentBlockId)}," +
        "\"checked\":${b.checked},\"collapsed\":${b.collapsed}}"

private fun pageJson(p: Page): String =
    "{\"id\":${q(p.id)},\"type\":\"page\",\"title\":${q(p.title)}," +
        "\"parentPageId\":${q(p.parentPageId)},\"linkedScriptIds\":${qList(p.linkedScriptIds)}," +
        "\"createdAt\":${p.createdAt},\"updatedAt\":${p.updatedAt}}"

/* Template / trash writers (v0.4) — used ONLY by Store.toJsonString;
 * templates and trash entries are not Db records, so there is no
 * Record.dataJson form of these. parentIndex is a bare number or
 * null; payloadJson is a string CONTAINING JSON, escaped as a whole. */

private fun templateBlockJson(tb: TemplateBlock): String =
    "{\"type\":${q(tb.type)},\"text\":${q(tb.text)}," +
        "\"checked\":${tb.checked},\"collapsed\":${tb.collapsed}," +
        "\"parentIndex\":${tb.parentIndex?.toString() ?: "null"}}"

private fun templateJson(t: PageTemplate): String =
    "{\"id\":${q(t.id)},\"name\":${q(t.name)},\"createdAt\":${t.createdAt}," +
        "\"blocks\":[" + t.blocks.joinToString(",") { templateBlockJson(it) } + "]}"

private fun trashJson(e: TrashEntry): String =
    "{\"id\":${q(e.id)},\"kind\":${q(e.kind)},\"label\":${q(e.label)}," +
        "\"deletedAt\":${e.deletedAt},\"payloadJson\":${q(e.payloadJson)}}"

/* ================================================================== */
/* Minimal JSON parser (objects, arrays, strings with escapes,        */
/* numbers, booleans, null) — enough for the schema above and for     */
/* v0.1 files written by org.json. Any failure yields null overall,   */
/* which loadFromJsonString treats as "leave the store untouched".    */
/* ================================================================== */

private class JsonParser(private val s: String) {
    private var pos = 0
    private var failed = false

    fun parseTop(): Any? {
        val v = parseValue()
        skipWs()
        if (pos != s.length) failed = true
        return if (failed) null else v
    }

    private fun skipWs() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
    }

    private fun parseValue(): Any? {
        skipWs()
        if (pos >= s.length) { failed = true; return null }
        return when (s[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun parseLiteral(word: String, value: Any?): Any? {
        if (s.startsWith(word, pos)) { pos += word.length; return value }
        failed = true; return null
    }

    private fun parseObject(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        pos++ // '{'
        skipWs()
        if (pos < s.length && s[pos] == '}') { pos++; return out }
        while (true) {
            skipWs()
            if (pos >= s.length || s[pos] != '"') { failed = true; return out }
            val key = parseString()
            skipWs()
            if (pos >= s.length || s[pos] != ':') { failed = true; return out }
            pos++
            out[key] = parseValue()
            if (failed) return out
            skipWs()
            if (pos >= s.length) { failed = true; return out }
            when (s[pos]) {
                ',' -> pos++
                '}' -> { pos++; return out }
                else -> { failed = true; return out }
            }
        }
    }

    private fun parseArray(): List<Any?> {
        val out = mutableListOf<Any?>()
        pos++ // '['
        skipWs()
        if (pos < s.length && s[pos] == ']') { pos++; return out }
        while (true) {
            out.add(parseValue())
            if (failed) return out
            skipWs()
            if (pos >= s.length) { failed = true; return out }
            when (s[pos]) {
                ',' -> pos++
                ']' -> { pos++; return out }
                else -> { failed = true; return out }
            }
        }
    }

    private fun parseString(): String {
        val sb = StringBuilder()
        pos++ // opening quote
        while (pos < s.length) {
            val c = s[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= s.length) { failed = true; return sb.toString() }
                    when (s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) { failed = true; return sb.toString() }
                            val hex = s.substring(pos, pos + 4)
                            pos += 4
                            val code = hex.toIntOrNull(16)
                            if (code == null) { failed = true; return sb.toString() }
                            sb.append(code.toChar())
                        }
                        else -> { failed = true; return sb.toString() }
                    }
                }
                else -> sb.append(c)
            }
        }
        failed = true
        return sb.toString()
    }

    private fun parseNumber(): Any? {
        val start = pos
        while (pos < s.length && s[pos] in "-+0123456789.eE") pos++
        if (pos == start) { failed = true; return null }
        val text = s.substring(start, pos)
        return text.toLongOrNull() ?: text.toDoubleOrNull() ?: run { failed = true; null }
    }
}

/* Map accessors mimicking the org.json opt* semantics v0.1 used. */
private fun Map<*, *>.str(key: String, default: String = ""): String =
    when (val v = this[key]) {
        null -> default
        is String -> v
        else -> v.toString()
    }

private fun Map<*, *>.nullableStr(key: String): String? =
    when (val v = this[key]) {
        null -> null
        is String -> v
        else -> v.toString()
    }

private fun Map<*, *>.long(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L

private fun Map<*, *>.bool(key: String): Boolean = this[key] as? Boolean ?: false

private fun Map<*, *>.arr(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any?>()

private fun Map<*, *>.strList(key: String): MutableList<String> =
    arr(key).mapNotNull { (it as? String) ?: it?.toString() }.toMutableList()
