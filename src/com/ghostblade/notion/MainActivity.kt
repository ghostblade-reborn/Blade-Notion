package com.ghostblade.notion

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * GhostBlade Notion — single-Activity, view-switching UI built in code
 * (framework-only, no XML layouts), in GhostBlade Task style.
 *
 * Round 2 (Team Two, features/UI): user-facing JSON export/import via
 * system picker/share intents, in-memory search, rename/delete with undo
 * for channels/scripts/ideas/tasks, tappable bidirectional idea<->script
 * links, a real language selector, channel + date pickers, editor draft
 * autosave (SharedPreferences), and task<->script linking UI.
 *
 * Round 3 (Team Two, features/UI): the Notion engine UI — a Pages
 * section with a nestable page tree and breadcrumb, a block editor on
 * the page screen (text/heading/lists/todo/toggle/quote/code/divider,
 * draft-autosaved editing, move up/down, turn-into, delete-with-undo),
 * pages wired into Search and into page<->script linking, idea<->task
 * linking UI, and the R2-N1 close-out: every rename/delete now routes
 * through Team One's Store remove/rename/cascade APIs so each
 * mutation emits to the Db write-through sink; channel delete uses the
 * decided cascade policy with a disclosing confirmation + full undo.
 *
 * Round 4 (Team Two, features/UI): channel database views (scripts
 * List/Table/Board + tasks List/Table with sortable columns, wired to
 * Team One's Logic.sortScripts / sortTasks / groupScriptsByStatus),
 * page templates UI (save a page as a template, instantiate / rename /
 * delete templates from the Pages screen), a page Move-to… picker on
 * Store.movePage (closes R3-N1), toggle child-block authoring via
 * Store.addBlock(parentBlockId = …) (closes R3-N2), a persisted Trash
 * screen with every delete flow rewired through the Store
 * deleteXToTrash / restoreFromTrash APIs, the idea<->script undo
 * relink retired onto Store.linkIdeaToScript (zero direct link-field
 * assignments remain), and the onBackPressed logic extracted into
 * handleBack() with an API 33+ OnBackInvokedCallback (N-10).
 *
 * Data goes through Team One's Store API. Persistence is
 * Store's JSON string in filesDir ("ghostnotion-state.json"). Fully
 * offline: no new permissions, no network, no WebView.
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private var persistenceAvailable: Boolean = false

    private lateinit var rootCol: LinearLayout
    private lateinit var contentCol: LinearLayout
    private lateinit var undoBar: LinearLayout
    private lateinit var undoLabel: TextView

    private var screen: String = "home"
    private var currentChannelId: String? = null
    private var currentScriptId: String? = null
    private var currentIdeaId: String? = null
    private var currentTaskId: String? = null
    private var currentPageId: String? = null

    /** Page-tree nodes currently expanded on the Pages screen. */
    private val expandedPageIds: MutableSet<String> = mutableSetOf()

    /* Round 4 database-view state (channel screen): which view the
     * Scripts/Tasks sections show and how the table views are sorted.
     * Kept in activity state so re-renders preserve the user's choice. */
    private var scriptViewMode: String = "list" // "list" | "table" | "board"
    private var scriptSort: ScriptSort = ScriptSort.TITLE
    private var scriptSortAsc: Boolean = true
    private var taskViewMode: String = "list" // "list" | "table"
    private var taskSort: TaskSort = TaskSort.TITLE
    private var taskSortAsc: Boolean = true

    /** True once the API 33+ OnBackInvokedCallback is registered
     *  (N-10 migration); on <33 the deprecated override handles Back. */
    private var backCallbackRegistered: Boolean = false

    private var editorLanguage: Language = Language.BANGLA
    private var editorChannelId: String? = null
    private var ideaEditorChannelId: String? = null

    /** Set by editor screens so onPause can flush the current draft. */
    private var draftSaver: (() -> Unit)? = null

    private var pendingUndo: UndoEntry? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideUndoRunnable = Runnable { hideUndoBar() }

    private val stateFileName = "ghostnotion-state.json"

    companion object {
        private const val REQ_EXPORT_FILE = 2001
        private const val REQ_IMPORT_FILE = 2002
        private const val DRAFTS_PREFS = "ghostnotion-drafts"
        private const val DRAFT_NEW_SCRIPT = "draft_new_script"
    }

    /** Snapshot of a deleted item so Undo can restore it at its index.
     *  Round 3: channel/page deletes hold Team One's cascade
     *  snapshots (full restore incl. links); single-entity deletes hold
     *  the removed object (its own link fields survive removal — the
     *  Store remove APIs only clean the OTHER side) plus the ids whose
     *  other-side links were cleaned, so undo can re-link via the
     *  Store link APIs. */
    private class UndoEntry(
        val kind: String, // "channel" | "script" | "idea" | "task" | "page" | "block"
        val index: Int,
        val channel: Channel? = null,
        val script: Script? = null,
        val idea: Idea? = null,
        val task: GTask? = null,
        val block: Block? = null,
        val channelSnapshot: ChannelCascadeSnapshot? = null,
        val pageSnapshot: PageCascadeSnapshot? = null,
        /** For script deletes: the idea whose linkedScriptId was cleared.
         *  For idea deletes: the script whose linkedIdeaId was cleared. */
        val linkedOtherId: String? = null,
        /** Task<->script / idea<->task links removed by the delete, to
         *  re-link on undo: task ids for a script delete, script ids
         *  for a task delete, task ids for an idea delete. */
        val clearedLinkIds: List<String> = emptyList(),
        /** Page<->script links removed by a script delete (page ids),
         *  or idea<->task links removed by a task delete (idea ids). */
        val clearedLinkIds2: List<String> = emptyList(),
        /** Round 4: when the delete went through a Store
         *  deleteXToTrash API, the trash entry it created. Undo then
         *  restores via Store.restoreFromTrash(entry.id) (the
         *  clearedLinkIds relink loops still run for single entities,
         *  exactly as Team One's wiring contract prescribes); the
         *  legacy entity/snapshot fields are the fallback for when a
         *  ToTrash call returned null and the old remove path ran. */
        val trashEntry: TrashEntry? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = Store()
        restoreStore()
        try { store.seedIfEmpty() } catch (e: Exception) { /* seeding is best-effort */ }
        initDbIfPresent()
        persistStore()

        rootCol = Ui.column(this)
        rootCol.setBackgroundColor(Ui.BG)
        val scroll = ScrollView(this)
        contentCol = Ui.column(this)
        contentCol.setPadding(Ui.dp(this, 16f), Ui.dp(this, 16f), Ui.dp(this, 16f), Ui.dp(this, 24f))
        scroll.addView(contentCol)
        rootCol.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootCol.addView(buildUndoBar())
        setContentView(rootCol)

        // N-10: on API 33+ Back is handled by an OnBackInvokedCallback
        // registered against the same handleBack() logic; below 33 the
        // deprecated onBackPressed() override below handles it. The
        // registration lives in its own method that is only invoked on
        // 33+, so the API-33 types are never touched on older devices.
        if (Build.VERSION.SDK_INT >= 33) registerPredictiveBackCallback()

        showHome()
    }

    /** API 33+ only — never called on older devices (see onCreate). */
    private fun registerPredictiveBackCallback() {
        try {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                android.window.OnBackInvokedCallback {
                    if (!handleBack()) finish()
                }
            )
            backCallbackRegistered = true
        } catch (t: Throwable) {
            // Registration failed: fall back to the override below.
            backCallbackRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        try { draftSaver?.invoke() } catch (e: Exception) { /* draft flush is best-effort */ }
    }

    /** The single Back-navigation implementation (N-10, Round 4).
     *  Returns true when Back was handled inside the app; false means
     *  "let the system do its default" (finish the activity). Called
     *  by the API 33+ OnBackInvokedCallback when one is registered,
     *  and by the deprecated override on older devices. */
    private fun handleBack(): Boolean {
        when (screen) {
            "script" -> {
                val cid = findScript(currentScriptId)?.channelId ?: currentChannelId
                if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            }
            "editor" -> {
                val cid = editorChannelId ?: currentChannelId
                if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            }
            "scriptEdit" -> {
                val sid = currentScriptId
                if (sid != null && findScript(sid) != null) showScript(sid) else showHome()
            }
            "idea" -> showIdeas()
            "pages" -> showHome()
            "page" -> {
                val parentId = currentPageId?.let { findPage(it)?.parentPageId }
                if (parentId != null && findPage(parentId) != null) showPage(parentId) else showPages()
            }
            "blockEdit" -> {
                val pid = currentPageId
                if (pid != null && findPage(pid) != null) showPage(pid) else showPages()
            }
            "task" -> {
                val t = currentTaskId?.let { findTask(it) }
                val cid = t?.channelId
                if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            }
            "channel", "ideas", "search", "trash" -> showHome()
            else -> return false
        }
        return true
    }

    @Deprecated("Kept only for API < 33; API 33+ uses the OnBackInvokedCallback registered in onCreate (N-10)")
    override fun onBackPressed() {
        // When the API 33+ callback is registered the system delivers
        // Back to it and never calls this override; the early return
        // is belt-and-braces against any double-handling.
        if (backCallbackRegistered) return
        if (!handleBack()) super.onBackPressed()
    }

    // ---------- persistence (Store JSON-string methods, verified in Store.kt) ----------

    private fun restoreStore() {
        try {
            val f = File(filesDir, stateFileName)
            if (!f.exists()) return
            val json = f.readText()
            if (json.isBlank()) return
            store.loadFromJsonString(json)
            persistenceAvailable = true
        } catch (e: Exception) { /* malformed file: start fresh, seedIfEmpty covers it */ }
    }

    private fun persistStore() {
        try {
            val json = store.toJsonString()
            File(filesDir, stateFileName).writeText(json)
            persistenceAvailable = true
        } catch (e: IOException) {
            persistenceAvailable = false
            toast("Could not save to disk — changes are kept for this session only")
        } catch (e: Exception) {
            persistenceAvailable = false
        }
    }

    private fun initDbIfPresent() {
        try { Db.init(this) } catch (e: Exception) { /* Db is not on the live data path */ }
    }

    // ---------- small helpers ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun clearContent() {
        draftSaver = null
        contentCol.removeAllViews()
    }

    private fun header(title: String, subtitle: String? = null, backLabel: String? = null, back: (() -> Unit)? = null) {
        if (backLabel != null && back != null) {
            val b = Ui.button(this, "← $backLabel") { back() }
            contentCol.addView(b, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, b = 10f))
        }
        contentCol.addView(Ui.tv(this, title, 26f, Ui.TEXT, true), Ui.lp(this, b = 4f))
        if (!subtitle.isNullOrBlank()) {
            contentCol.addView(Ui.tv(this, subtitle, 13f, Ui.MUTED), Ui.lp(this, b = 12f))
        } else {
            contentCol.addView(View(this), Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8f)))
        }
    }

    private fun sectionTitle(text: String) {
        contentCol.addView(Ui.tv(this, text, 17f, Ui.ACCENT, true), Ui.lp(this, t = 14f, b = 8f))
    }

    private fun emptyNote(text: String) {
        contentCol.addView(Ui.tv(this, text, 14f, Ui.MUTED), Ui.lp(this, b = 8f))
    }

    private fun scriptsOf(channelId: String): List<Script> =
        try { store.scriptsForChannel(channelId) } catch (e: Exception) { emptyList() }

    private fun ideasList(): List<Idea> =
        try { store.ideas } catch (e: Exception) { emptyList() }

    private fun tasksList(): List<GTask> =
        try { store.tasks } catch (e: Exception) { emptyList() }

    private fun findScript(id: String?): Script? =
        try { store.scriptById(id) } catch (e: Exception) { null }

    private fun findChannel(id: String?): Channel? =
        try { store.channelById(id) } catch (e: Exception) { null }

    private fun findIdea(id: String?): Idea? =
        try { store.ideaById(id) } catch (e: Exception) { null }

    private fun findTask(id: String?): GTask? =
        try { store.taskById(id) } catch (e: Exception) { null }

    private fun findPage(id: String?): Page? =
        try { store.pageById(id) } catch (e: Exception) { null }

    private fun findBlock(id: String?): Block? =
        try { store.blockById(id) } catch (e: Exception) { null }

    private fun onTextChanged(field: EditText, action: () -> Unit) {
        field.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { action() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun refreshCurrent() {
        when (screen) {
            "home" -> showHome()
            "channel" -> {
                val cid = currentChannelId
                if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            }
            "ideas" -> showIdeas()
            "script" -> {
                val sid = currentScriptId
                if (sid != null && findScript(sid) != null) showScript(sid) else showHome()
            }
            "idea" -> {
                val iid = currentIdeaId
                if (iid != null && findIdea(iid) != null) showIdeaDetail(iid) else showIdeas()
            }
            "task" -> {
                val tid = currentTaskId
                if (tid != null && findTask(tid) != null) showTaskDetail(tid) else showHome()
            }
            "pages" -> showPages()
            "page", "blockEdit" -> {
                val pid = currentPageId
                if (pid != null && findPage(pid) != null) showPage(pid) else showPages()
            }
            "trash" -> showTrash()
            "search" -> showSearch()
            else -> showHome()
        }
    }

    // ---------- dates ----------

    private fun formatDate(year: Int, monthZeroBased: Int, day: Int): String =
        String.format("%04d-%02d-%02d", year, monthZeroBased + 1, day)

    /** yyyy-MM-dd for a stored epoch-millis value (Round 4 table view:
     *  a script's updatedAt). "—" when there is no timestamp. */
    private fun fmtDay(millis: Long): String {
        if (millis <= 0L) return "—"
        return try { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis)) } catch (e: Exception) { "—" }
    }

    /** yyyy-MM-dd HH:mm for a stored epoch-millis value (Trash screen). */
    private fun fmtDayTime(millis: Long): String {
        if (millis <= 0L) return "—"
        return try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis)) } catch (e: Exception) { "—" }
    }

    /** Framework DatePickerDialog seeded from the current yyyy-MM-dd
     *  value when valid, otherwise from today. */
    private fun pickDate(current: String?, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        if (!current.isNullOrBlank() && Logic.isValidDate(current)) {
            try {
                val p = current.split("-")
                cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            } catch (e: Exception) { /* keep today */ }
        }
        DatePickerDialog(this, { _, y, m, d -> onPicked(formatDate(y, m, d)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---------- channel / script pickers ----------

    private fun pickChannel(onPicked: (Channel) -> Unit) {
        val chans = store.channels
        if (chans.isEmpty()) { toast("Add a channel first"); return }
        val names = Array<CharSequence>(chans.size) { chans[it].name }
        AlertDialog.Builder(this).setTitle("Choose channel").setItems(names) { _, which ->
            onPicked(chans[which])
        }.show()
    }

    // ---------- item menus (rename / delete / …) ----------

    /** Small "⋯" menu dialog shared by channels, scripts, ideas, tasks. */
    private fun showItemMenu(itemLabel: String, actions: List<Pair<String, () -> Unit>>) {
        if (actions.isEmpty()) return
        val names = Array<CharSequence>(actions.size) { actions[it].first }
        AlertDialog.Builder(this).setTitle(itemLabel).setItems(names) { _, which ->
            actions[which].second()
        }.show()
    }

    /** Card header row: bold title (weight 1) + a small "⋯" menu button. */
    private fun cardHeader(title: String, size: Float = 16f, menu: () -> Unit): LinearLayout {
        val row = Ui.row(this)
        val tv = Ui.tv(this, title, size, Ui.TEXT, true)
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dots = Ui.button(this, "⋯") { menu() }
        row.addView(dots, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        return row
    }

    private fun textDialog(title: String, hint: String, initial: String, saveLabel: String = "Save", onSave: (String) -> Unit) {
        val input = Ui.input(this, hint, value = initial)
        val box = Ui.column(this)
        val pad = Ui.dp(this, 16f)
        box.setPadding(pad, pad, pad, 0)
        box.addView(input)
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(saveLabel) { _, _ ->
                val v = input.text.toString().trim()
                if (v.isEmpty()) toast("Cannot be empty") else onSave(v)
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- drafts (SharedPreferences, offline) ----------

    private fun saveDraftJson(key: String, obj: JSONObject) {
        try {
            getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE).edit().putString(key, obj.toString()).apply()
        } catch (e: Exception) { /* drafts are best-effort */ }
    }

    private fun loadDraftJson(key: String): JSONObject? {
        val s = try { getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE).getString(key, null) }
            catch (e: Exception) { null } ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    private fun clearDraft(key: String) {
        try { getSharedPreferences(DRAFTS_PREFS, MODE_PRIVATE).edit().remove(key).apply() }
        catch (e: Exception) { /* ignore */ }
    }

    // =====================================================================
    // Task<->script links — wired to Team One's Round 2 Store API
    // (Store.linkTaskToScript / unlinkTaskFromScript / scriptsForTask /
    // tasksForScript; many-to-many, persisted in the Store JSON via
    // GTask.linkedScriptIds + Script.linkedTaskIds). All UI call sites
    // go through these three helpers so the wiring lives in one place.
    // =====================================================================

    private fun scriptsLinkedToTask(taskId: String): List<Script> =
        try { store.scriptsForTask(taskId) } catch (e: Exception) { emptyList() }

    private fun tasksLinkedToScript(scriptId: String): List<GTask> =
        try { store.tasksForScript(scriptId) } catch (e: Exception) { emptyList() }

    private fun linkTaskToScript(taskId: String, scriptId: String): Boolean {
        val ok = try { store.linkTaskToScript(taskId, scriptId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    private fun unlinkTaskFromScript(taskId: String, scriptId: String): Boolean {
        val ok = try { store.unlinkTaskFromScript(taskId, scriptId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    // =====================================================================
    // Page<->script and idea<->task links — Round 3, wired to Team
    // One's Store APIs (linkPageToScript / scriptsForPage / pagesForScript
    // and linkIdeaToTask / tasksForIdea / ideasForTask; many-to-many,
    // both sides maintained by the Store, JSON-persisted, sink-emitted).
    // =====================================================================

    private fun scriptsLinkedToPage(pageId: String): List<Script> =
        try { store.scriptsForPage(pageId) } catch (e: Exception) { emptyList() }

    private fun pagesLinkedToScript(scriptId: String): List<Page> =
        try { store.pagesForScript(scriptId) } catch (e: Exception) { emptyList() }

    private fun linkPageToScript(pageId: String, scriptId: String): Boolean {
        val ok = try { store.linkPageToScript(pageId, scriptId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    private fun unlinkPageFromScript(pageId: String, scriptId: String): Boolean {
        val ok = try { store.unlinkPageFromScript(pageId, scriptId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    private fun tasksLinkedToIdea(ideaId: String): List<GTask> =
        try { store.tasksForIdea(ideaId) } catch (e: Exception) { emptyList() }

    private fun ideasLinkedToTask(taskId: String): List<Idea> =
        try { store.ideasForTask(taskId) } catch (e: Exception) { emptyList() }

    private fun linkIdeaToTask(ideaId: String, taskId: String): Boolean {
        val ok = try { store.linkIdeaToTask(ideaId, taskId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    private fun unlinkIdeaFromTask(ideaId: String, taskId: String): Boolean {
        val ok = try { store.unlinkIdeaFromTask(ideaId, taskId) } catch (e: Exception) { false }
        if (ok) persistStore()
        return ok
    }

    // ---------- undo bar + delete/undo ----------

    private fun buildUndoBar(): LinearLayout {
        undoBar = Ui.row(this)
        undoBar.background = Ui.panelBg(this, 0f)
        val pad = Ui.dp(this, 14f)
        undoBar.setPadding(pad, Ui.dp(this, 10f), pad, Ui.dp(this, 10f))
        undoLabel = Ui.tv(this, "", 14f, Ui.TEXT)
        undoBar.addView(undoLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBar.addView(Ui.button(this, "UNDO", primary = true) { performUndo() },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 10f))
        undoBar.visibility = View.GONE
        return undoBar
    }

    private fun showUndoBar(message: String, entry: UndoEntry) {
        pendingUndo = entry
        undoLabel.text = message
        undoBar.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideUndoRunnable)
        uiHandler.postDelayed(hideUndoRunnable, 6000)
    }

    private fun hideUndoBar() {
        if (::undoBar.isInitialized) undoBar.visibility = View.GONE
        pendingUndo = null
    }

    /** Round 4 helper: restores via the trash entry when the delete
     *  created one. Returns true when restoreFromTrash did the
     *  restore (the caller then skips its legacy restore path but —
     *  for single entities — still runs the cleared-link relink
     *  loops, per Team One's wiring contract). */
    private fun restoreViaTrash(e: UndoEntry): Boolean {
        val te = e.trashEntry ?: return false
        return try { store.restoreFromTrash(te.id) } catch (ex: Exception) { false }
    }

    private fun performUndo() {
        val e = pendingUndo ?: return
        uiHandler.removeCallbacks(hideUndoRunnable)
        try {
            when (e.kind) {
                // Round 4: deletes are trash-backed — undo restores
                // via Store.restoreFromTrash when a trash entry was
                // created (for channel/page that IS the full cascade
                // restore, survivor links included). The Round 3
                // snapshot paths remain as the fallback for a delete
                // whose ToTrash call returned null.
                "channel" -> {
                    if (!restoreViaTrash(e)) {
                        val snap = e.channelSnapshot
                        if (snap != null) store.restoreChannelCascade(snap)
                        else e.channel?.let { store.restoreChannel(it, e.index) }
                    }
                }
                "page" -> {
                    if (!restoreViaTrash(e)) {
                        e.pageSnapshot?.let { store.restorePageCascade(it) }
                    }
                }
                "script" -> e.script?.let { s ->
                    if (!restoreViaTrash(e)) store.restoreScript(s, e.index)
                    // The script's own link fields survived removal;
                    // re-link the OTHER sides via the Store link APIs.
                    // Round 4: the idea<->script relink now goes
                    // through Store.linkIdeaToScript (idempotent-true,
                    // emits both sides) — the old direct field
                    // assignment + no-op-update emit-flush exception is
                    // retired; no direct link-field assignments remain
                    // in this file.
                    if (e.linkedOtherId != null) {
                        try { store.linkIdeaToScript(e.linkedOtherId, s.id) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                    e.clearedLinkIds.forEach { taskId ->
                        try { store.linkTaskToScript(taskId, s.id) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                    e.clearedLinkIds2.forEach { pageId ->
                        try { store.linkPageToScript(pageId, s.id) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                }
                "idea" -> e.idea?.let { i ->
                    if (!restoreViaTrash(e)) store.restoreIdea(i, e.index)
                    if (e.linkedOtherId != null) {
                        try { store.linkIdeaToScript(i.id, e.linkedOtherId) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                    e.clearedLinkIds.forEach { taskId ->
                        try { store.linkIdeaToTask(i.id, taskId) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                }
                "task" -> e.task?.let { t ->
                    if (!restoreViaTrash(e)) store.restoreTask(t, e.index)
                    e.clearedLinkIds.forEach { scriptId ->
                        try { store.linkTaskToScript(t.id, scriptId) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                    e.clearedLinkIds2.forEach { ideaId ->
                        try { store.linkIdeaToTask(ideaId, t.id) } catch (ex: Exception) { /* best-effort relink */ }
                    }
                }
                "block" -> e.block?.let { b ->
                    // e.index is the block's index WITHIN its sibling
                    // group (Round 4: moveBlock is sibling-scoped, and
                    // deleteBlockWithUndo now stores the sibling index).
                    // restoreBlock / restoreFromTrash re-insert the
                    // block; moveBlock then re-normalises its group's
                    // order values around it.
                    val restored = if (restoreViaTrash(e)) true else store.restoreBlock(b)
                    if (restored) {
                        try { store.moveBlock(b.id, e.index) } catch (ex: Exception) { /* order repair best-effort */ }
                    }
                }
            }
            persistStore()
            toast("Restored")
        } catch (ex: Exception) {
            toast("Undo failed")
        }
        hideUndoBar()
        refreshCurrent()
    }

    /** Channel delete — DECIDED cascade policy (Round 3): the delete
     *  confirmation DISCLOSES that the channel's scripts, tasks and
     *  ideas will also be deleted. Round 4: the delete itself is
     *  Store.deleteChannelToTrash (the same cascade, plus a persisted
     *  trash entry) and Undo restores everything via
     *  Store.restoreFromTrash. If the ToTrash call returns null the
     *  Round 3 snapshot path runs as the fallback. */
    private fun deleteChannelWithUndo(ch: Channel, afterDelete: () -> Unit = { refreshCurrent() }) {
        val nScripts = scriptsOf(ch.id).size
        val nTasks = tasksList().count { it.channelId == ch.id }
        val nIdeas = ideasList().count { it.channelId == ch.id }
        AlertDialog.Builder(this)
            .setTitle("Delete channel?")
            .setMessage(
                "“${ch.name}” will be deleted along with its $nScripts script(s), " +
                    "$nTasks task(s) and $nIdeas idea(s). " +
                    "You can undo right after deleting, or restore it later from Trash."
            )
            .setPositiveButton("Delete") { _, _ ->
                val entry = try { store.deleteChannelToTrash(ch.id) } catch (e: Exception) { null }
                if (entry != null) {
                    persistStore()
                    showUndoBar("Channel \"${ch.name}\" and its contents deleted",
                        UndoEntry("channel", 0, trashEntry = entry))
                    afterDelete()
                    return@setPositiveButton
                }
                // Fallback (ToTrash returned null): Round 3 snapshot path.
                val snapshot = try { store.removeChannelCascade(ch.id) } catch (e: Exception) { null }
                if (snapshot == null) { toast("Could not delete the channel"); return@setPositiveButton }
                persistStore()
                showUndoBar("Channel \"${ch.name}\" and its contents deleted",
                    UndoEntry("channel", snapshot.channelIndex, channelSnapshot = snapshot))
                afterDelete()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteScriptWithUndo(s: Script, afterDelete: () -> Unit = { refreshCurrent() }) {
        val idx = store.scripts.indexOfFirst { it.id == s.id }
        if (idx < 0) return
        // Capture the other-side link partners BEFORE removal:
        // the Store removal cleans their fields (and emits), so undo
        // needs these ids to re-link. The script object itself keeps
        // its own link fields, which the restore brings back.
        val linkedIdeaId = ideasList().firstOrNull { it.linkedScriptId == s.id }?.id
        val linkedTaskIds = tasksLinkedToScript(s.id).map { it.id }
        val linkedPageIds = pagesLinkedToScript(s.id).map { it.id }
        // Round 4: trash-backed delete; the plain removeScript path is
        // the fallback if the ToTrash call returns null.
        val entry = try { store.deleteScriptToTrash(s.id) } catch (e: Exception) { null }
        if (entry == null) {
            val ok = try { store.removeScript(s.id) } catch (e: Exception) { false }
            if (!ok) { toast("Could not delete the script"); return }
        }
        // Clear this script's retained editor drafts (R2-N7).
        clearDraft("draft_body_${s.id}")
        clearDraft("draft_edit_${s.id}")
        persistStore()
        showUndoBar("Script \"${s.title}\" deleted",
            UndoEntry("script", idx, script = s, linkedOtherId = linkedIdeaId,
                clearedLinkIds = linkedTaskIds, clearedLinkIds2 = linkedPageIds,
                trashEntry = entry))
        afterDelete()
    }

    private fun deleteIdeaWithUndo(idea: Idea, afterDelete: () -> Unit = { refreshCurrent() }) {
        val idx = store.ideas.indexOfFirst { it.id == idea.id }
        if (idx < 0) return
        val linkedScriptId = store.scripts.firstOrNull { it.linkedIdeaId == idea.id }?.id
        val linkedTaskIds = tasksLinkedToIdea(idea.id).map { it.id }
        val entry = try { store.deleteIdeaToTrash(idea.id) } catch (e: Exception) { null }
        if (entry == null) {
            val ok = try { store.removeIdea(idea.id) } catch (e: Exception) { false }
            if (!ok) { toast("Could not delete the idea"); return }
        }
        persistStore()
        showUndoBar("Idea \"${idea.title}\" deleted",
            UndoEntry("idea", idx, idea = idea, linkedOtherId = linkedScriptId,
                clearedLinkIds = linkedTaskIds, trashEntry = entry))
        afterDelete()
    }

    private fun deleteTaskWithUndo(task: GTask, afterDelete: () -> Unit = { refreshCurrent() }) {
        val idx = store.tasks.indexOfFirst { it.id == task.id }
        if (idx < 0) return
        val linkedScriptIds = scriptsLinkedToTask(task.id).map { it.id }
        val linkedIdeaIds = ideasLinkedToTask(task.id).map { it.id }
        val entry = try { store.deleteTaskToTrash(task.id) } catch (e: Exception) { null }
        if (entry == null) {
            val ok = try { store.removeTask(task.id) } catch (e: Exception) { false }
            if (!ok) { toast("Could not delete the task"); return }
        }
        persistStore()
        showUndoBar("Task \"${task.title}\" deleted",
            UndoEntry("task", idx, task = task, clearedLinkIds = linkedScriptIds,
                clearedLinkIds2 = linkedIdeaIds, trashEntry = entry))
        afterDelete()
    }

    /** Page delete — disclose + undo, same pattern as channels:
     *  the confirmation states that sub-pages and blocks will also be
     *  deleted. Round 4: Store.deletePageToTrash does the cascade and
     *  records a trash entry; Undo (and the Trash screen) restore via
     *  Store.restoreFromTrash. The Round 3 snapshot path is the
     *  fallback if the ToTrash call returns null. */
    private fun deletePageWithUndo(page: Page, afterDelete: () -> Unit = { refreshCurrent() }) {
        val deadIds = try { store.descendantPageIds(page.id) } catch (e: Exception) { setOf(page.id) }
        val nSub = (deadIds.size - 1).coerceAtLeast(0)
        val nBlocks = try { store.blocks.count { deadIds.contains(it.pageId) } } catch (e: Exception) { 0 }
        AlertDialog.Builder(this)
            .setTitle("Delete page?")
            .setMessage(
                "“${page.title}” will be deleted along with $nSub sub-page(s) and " +
                    "$nBlocks block(s) inside them. Scripts linked to these pages will be " +
                    "unlinked, not deleted. You can undo right after deleting, or restore it later from Trash."
            )
            .setPositiveButton("Delete") { _, _ ->
                val entry = try { store.deletePageToTrash(page.id) } catch (e: Exception) { null }
                if (entry != null) {
                    persistStore()
                    showUndoBar("Page \"${page.title}\" deleted",
                        UndoEntry("page", 0, trashEntry = entry))
                    afterDelete()
                    return@setPositiveButton
                }
                // Fallback (ToTrash returned null): Round 3 snapshot path.
                val snapshot = try { store.removePageCascade(page.id) } catch (e: Exception) { null }
                if (snapshot == null) { toast("Could not delete the page"); return@setPositiveButton }
                persistStore()
                showUndoBar("Page \"${page.title}\" deleted",
                    UndoEntry("page", 0, pageSnapshot = snapshot))
                afterDelete()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Block delete with undo (no confirmation — the undo bar is the
     *  safety net, same as the Round 2 entity deletes). Round 4: the
     *  delete is trash-backed (deleteBlockToTrash, removeBlock as the
     *  fallback), and the index stored for undo is the block's index
     *  WITHIN ITS SIBLING GROUP (same pageId + same parentBlockId) —
     *  Store.moveBlock's targetIndex is sibling-scoped since v0.4, so
     *  a child block's undo must not use the full-page index. The
     *  [pageIndex] parameter is kept for call-site compatibility but
     *  the sibling index computed here is authoritative. */
    private fun deleteBlockWithUndo(block: Block, pageIndex: Int) {
        val storeIndex = store.blocks.indexOfFirst { it.id == block.id }
        if (storeIndex < 0) return
        val siblingIndex = try {
            store.blocksForPage(block.pageId)
                .filter { it.parentBlockId == block.parentBlockId }
                .indexOfFirst { it.id == block.id }
                .coerceAtLeast(0)
        } catch (e: Exception) { pageIndex }
        val entry = try { store.deleteBlockToTrash(block.id) } catch (e: Exception) { null }
        if (entry == null) {
            val ok = try { store.removeBlock(block.id) } catch (e: Exception) { false }
            if (!ok) { toast("Could not delete the block"); return }
        }
        clearDraft("draft_block_${block.id}")
        persistStore()
        showUndoBar("Block deleted", UndoEntry("block", siblingIndex, block = block, trashEntry = entry))
        refreshCurrent()
    }

    // ---------- rename dialogs ----------

    private fun renameChannelDialog(ch: Channel) {
        textDialog("Rename channel", "Channel name", ch.name) { newName ->
            // Round 3 (R2-N1): renames route through the Store so they
            // emit to the Db write-through sink.
            val ok = try { store.renameChannel(ch.id, newName) } catch (e: Exception) { false }
            if (!ok) { toast("Could not rename the channel"); return@textDialog }
            persistStore()
            toast("Renamed")
            refreshCurrent()
        }
    }

    private fun renamePageDialog(page: Page) {
        textDialog("Rename page", "Page title", page.title) { newName ->
            val ok = try { store.renamePage(page.id, newName) } catch (e: Exception) { false }
            if (!ok) { toast("Could not rename the page"); return@textDialog }
            persistStore()
            toast("Renamed")
            refreshCurrent()
        }
    }

    private fun renameIdeaDialog(idea: Idea) {
        val titleInput = Ui.input(this, "Idea title", value = idea.title)
        val notesInput = Ui.input(this, "Notes (optional)", value = idea.notes, multi = true)
        notesInput.minLines = 3
        val box = Ui.column(this)
        val pad = Ui.dp(this, 16f)
        box.setPadding(pad, pad, pad, 0)
        box.addView(titleInput, Ui.lp(this, b = 8f))
        box.addView(notesInput)
        AlertDialog.Builder(this).setTitle("Edit idea").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = titleInput.text.toString().trim()
                if (t.isEmpty()) { toast("Idea title cannot be empty"); return@setPositiveButton }
                // Round 3 (R2-N1): via Store.updateIdea so it emits.
                val ok = try {
                    store.updateIdea(idea.id, title = t, notes = notesInput.text.toString().trim())
                } catch (e: Exception) { false }
                if (!ok) { toast("Could not save the idea"); return@setPositiveButton }
                persistStore()
                toast("Saved")
                refreshCurrent()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun renameTaskDialog(task: GTask) {
        val titleInput = Ui.input(this, "Task title", value = task.title)
        val dateInput = Ui.input(this, "Due date (optional, yyyy-MM-dd)", value = task.dueDate ?: "")
        val box = Ui.column(this)
        val pad = Ui.dp(this, 16f)
        box.setPadding(pad, pad, pad, 0)
        box.addView(titleInput, Ui.lp(this, b = 8f))
        val dateRow = Ui.row(this)
        dateRow.addView(dateInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pickBtn = Ui.button(this, "📅 Pick") { }
        pickBtn.setOnClickListener { pickDate(dateInput.text.toString().trim()) { picked -> dateInput.setText(picked) } }
        dateRow.addView(pickBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        box.addView(dateRow)
        AlertDialog.Builder(this).setTitle("Edit task").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = titleInput.text.toString().trim()
                if (t.isEmpty()) { toast("Task title cannot be empty"); return@setPositiveButton }
                val due = dateInput.text.toString().trim().ifEmpty { null }
                if (!Logic.isValidDate(due)) { toast("Check the date is yyyy-MM-dd"); return@setPositiveButton }
                // Round 3 (R2-N1): via Store.updateTask so it emits
                // (dueDate = "" clears the date, matching the blank field).
                val ok = try {
                    store.updateTask(task.id, title = t, dueDate = due ?: "")
                } catch (e: Exception) { false }
                if (!ok) { toast("Could not save the task"); return@setPositiveButton }
                persistStore()
                toast("Saved")
                refreshCurrent()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- Screen 1: Home ----------

    private fun showHome() {
        screen = "home"
        currentScriptId = null
        clearContent()
        header("GhostBlade Notion", "Channels · Scripts · Ideas · Tasks — fully offline")

        val nav = Ui.row(this)
        nav.addView(Ui.button(this, "📄 Pages") { showPages() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        nav.addView(Ui.button(this, "💡 Video Ideas") { showIdeas() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        nav.addView(Ui.button(this, "🔍 Search") { showSearch() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        nav.addView(Ui.button(this, "🗑 Trash") { showTrash() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(nav, Ui.lp(this, b = 6f))

        sectionTitle("Channels")
        val channels = store.channels
        if (channels.isEmpty()) {
            emptyNote("No channels yet. Add your first channel below.")
        }
        for (ch in channels) {
            val scripts = scriptsOf(ch.id)
            val drafts = scripts.count { it.status == ScriptStatus.DRAFT }
            val finals = scripts.count { it.status == ScriptStatus.FINAL }
            val card = Ui.card(this)
            val menu: () -> Unit = {
                showItemMenu(ch.name, listOf(
                    "Rename" to { renameChannelDialog(ch) },
                    "Delete" to { deleteChannelWithUndo(ch) }
                ))
            }
            card.addView(cardHeader(ch.name, 18f, menu))
            card.addView(Ui.tv(this, "${scripts.size} scripts · $drafts draft · $finals final", 13f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showChannel(ch.id) }
            card.setOnLongClickListener { menu(); true }
            contentCol.addView(card, Ui.lp(this, b = 10f))
        }

        sectionTitle("+ Add channel")
        val nameInput = Ui.input(this, "Write your channel name")
        contentCol.addView(nameInput, Ui.lp(this, b = 8f))
        contentCol.addView(Ui.button(this, "Add channel", primary = true) {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) { toast("Channel name cannot be empty"); return@button }
            try { store.addChannel(name) } catch (e: Exception) { toast("Could not add channel"); return@button }
            persistStore()
            showHome()
        })

        sectionTitle("All tasks")
        renderTasks(null)

        // Backup: user-facing JSON export / import (Round 2 feature 1)
        sectionTitle("Backup (JSON)")
        contentCol.addView(Ui.tv(this,
            "Export saves the full app state as JSON. Import replaces ALL current data with the file's data.",
            12f, Ui.MUTED), Ui.lp(this, b = 8f))
        val backupRow = Ui.row(this)
        backupRow.addView(Ui.button(this, "Share export") { exportShare() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        backupRow.addView(Ui.button(this, "Save to file…") { exportToFile() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        backupRow.addView(Ui.button(this, "Import…") { importFromFile() }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(backupRow, Ui.lp(this, b = 6f))
    }

    // ---------- Screen: Trash (Round 4, closes R2-N5 as scoped) ----------

    /** The persisted trash/bin: every deleteXToTrash delete lands
     *  here (Store.trash, oldest first — displayed newest first).
     *  Restore goes through Store.restoreFromTrash (on false the
     *  entry stays and the user is told — e.g. an item with the same
     *  id exists again). "Delete forever" and "Empty trash" only drop
     *  the entry; the entity itself is already deleted. */
    private fun showTrash() {
        screen = "trash"
        clearContent()
        header("Trash", "Deleted items — restore them, or delete them forever", backLabel = "Home") { showHome() }

        val entries = try { store.trash } catch (e: Exception) { emptyList() }
        if (entries.isEmpty()) {
            emptyNote("Trash is empty. Deleted channels, pages, scripts, ideas, tasks and blocks will appear here.")
            return
        }

        contentCol.addView(Ui.button(this, "Empty trash") {
            AlertDialog.Builder(this)
                .setTitle("Empty trash?")
                .setMessage("All ${entries.size} item(s) in the trash will be permanently deleted. This cannot be undone.")
                .setPositiveButton("Empty trash") { _, _ ->
                    try { store.emptyTrash() } catch (e: Exception) { /* ignore */ }
                    hideUndoBar()
                    persistStore()
                    toast("Trash emptied")
                    showTrash()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, Ui.lp(this, b = 10f))

        for (entry in entries.reversed()) {
            val card = Ui.card(this)
            val headRow = Ui.row(this)
            headRow.addView(Ui.tv(this, entry.label.ifBlank { "(untitled)" }, 15f, Ui.TEXT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headRow.addView(Ui.chip(this, entry.kind.uppercase(Locale.US), accent = true),
                Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
            card.addView(headRow)
            card.addView(Ui.tv(this, "Deleted: ${fmtDayTime(entry.deletedAt)}", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            val btnRow = Ui.row(this)
            btnRow.addView(Ui.button(this, "Restore") {
                val ok = try { store.restoreFromTrash(entry.id) } catch (e: Exception) { false }
                if (ok) {
                    persistStore()
                    toast("Restored \"${entry.label}\"")
                } else {
                    toast("Could not restore — an item with the same id may already exist")
                }
                showTrash()
            }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
            btnRow.addView(Ui.button(this, "Delete forever") {
                AlertDialog.Builder(this)
                    .setTitle("Delete forever?")
                    .setMessage("“${entry.label}” will be permanently deleted. This cannot be undone.")
                    .setPositiveButton("Delete forever") { _, _ ->
                        try { store.permanentlyDeleteTrash(entry.id) } catch (e: Exception) { /* ignore */ }
                        persistStore()
                        toast("Deleted forever")
                        showTrash()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(btnRow, Ui.lp(this, t = 8f))
            contentCol.addView(card, Ui.lp(this, b = 8f))
        }
    }

    // ---------- export / import ----------

    private fun exportShare() {
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "application/json"
            intent.putExtra(Intent.EXTRA_SUBJECT, "GhostBlade Notion backup")
            intent.putExtra(Intent.EXTRA_TEXT, store.toJsonString())
            startActivity(Intent.createChooser(intent, "Export GhostBlade Notion JSON"))
        } catch (e: Exception) {
            toast("No app available to share the backup")
        }
    }

    private fun exportToFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/json"
        intent.putExtra(Intent.EXTRA_TITLE, "ghostnotion-backup.json")
        try {
            startActivityForResult(intent, REQ_EXPORT_FILE)
        } catch (e: Exception) {
            toast("File picker not available — use Share export instead")
        }
    }

    private fun importFromFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        try {
            startActivityForResult(intent, REQ_IMPORT_FILE)
        } catch (e: Exception) {
            toast("File picker not available")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_FILE -> {
                try {
                    val out = contentResolver.openOutputStream(uri, "w")
                    if (out == null) { toast("Export failed — could not open the file"); return }
                    out.use { it.write(store.toJsonString().toByteArray(Charsets.UTF_8)) }
                    toast("Backup saved")
                } catch (e: IOException) {
                    toast("Export failed — could not write the file")
                } catch (e: Exception) {
                    toast("Export failed")
                }
            }
            REQ_IMPORT_FILE -> {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: IOException) {
                    toast("Import failed — could not read the file"); return
                } catch (e: Exception) {
                    toast("Import failed"); return
                }
                if (text.isNullOrBlank()) { toast("Import failed — the file is empty"); return }
                // Guard malformed files BEFORE touching the live store:
                // the file must be a JSON object carrying at least one of
                // our top-level arrays. Store.loadFromJsonString itself
                // parses into temporary lists first (verified in Store.kt),
                // so a rejected file leaves current data untouched.
                val parsed = try { JSONObject(text) } catch (e: Exception) {
                    toast("Import failed — not a GhostBlade Notion JSON file"); return
                }
                if (!parsed.has("channels") && !parsed.has("scripts") &&
                    !parsed.has("ideas") && !parsed.has("tasks")) {
                    toast("Import failed — no GhostBlade data found in that file"); return
                }
                // Per-key TYPE check (Round 3, fixes R2-N4): every known
                // top-level key that is present must hold a JSON array.
                // A file like {"channels": 5} used to pass the has() check
                // and then wipe the store to empty; reject it here so it
                // never reaches the store.
                // Round 4: all eight keys the Store validates —
                // "pages" added (closes R3-N4) plus the new
                // "templates" and "trash" keys.
                val knownKeys = listOf("channels", "scripts", "ideas", "tasks", "pages", "blocks", "templates", "trash")
                val badKey = knownKeys.firstOrNull { parsed.has(it) && parsed.optJSONArray(it) == null }
                if (badKey != null) {
                    toast("Import failed — \"$badKey\" is not a list in that file"); return
                }
                // Round 3: loadFromJsonString returns Boolean — false
                // means the Store rejected the file (e.g. a known key
                // holding a non-array value) and left current data
                // untouched; only toast success on a real load.
                val loaded = try { store.loadFromJsonString(text) } catch (e: Exception) { false }
                if (!loaded) {
                    toast("Import failed — that file is not valid GhostBlade Notion data"); return
                }
                hideUndoBar()
                persistStore()
                toast("Import complete")
                showHome()
            }
        }
    }

    // ---------- Screen 2: Channel ----------

    private fun showChannel(channelId: String) {
        val ch = findChannel(channelId) ?: run { showHome(); return }
        screen = "channel"
        currentChannelId = channelId
        clearContent()
        header(ch.name, "Scripts and tasks for this channel", backLabel = "Home") { showHome() }

        val chRow = Ui.row(this)
        chRow.addView(Ui.button(this, "✍ Write script", primary = true) {
            showScriptEditor(channelId)
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        chRow.addView(Ui.button(this, "Rename") { renameChannelDialog(ch) }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        chRow.addView(Ui.button(this, "Delete") {
            deleteChannelWithUndo(ch) { showHome() }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(chRow, Ui.lp(this, b = 6f))

        sectionTitle("Scripts")
        val scripts = scriptsOf(channelId)
        // Round 4: view switcher — List (the original cards, default)
        // | Table (sortable columns) | Board (DRAFT/FINAL columns).
        // These are views over this channel's scripts only.
        contentCol.addView(viewSwitcher(
            listOf("list" to "List", "table" to "Table", "board" to "Board"), scriptViewMode
        ) { mode -> scriptViewMode = mode; showChannel(channelId) }, Ui.lp(this, b = 8f))
        if (scripts.isEmpty()) emptyNote("No scripts yet for this channel.")
        when (scriptViewMode) {
            "table" -> renderScriptTable(scripts)
            "board" -> renderScriptBoard(scripts)
            else -> renderScriptList(scripts)
        }

        sectionTitle("Tasks")
        renderTasks(channelId)
    }

    // =====================================================================
    // Round 4 — channel database views. List | Table | Board are three
    // renderings of the SAME channel script/task lists (sorting and
    // grouping are Team One's pure Logic functions, applied at
    // render time; nothing extra is persisted).
    // =====================================================================

    /** Segmented List/Table/Board switcher built from toggle chips. */
    private fun viewSwitcher(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): LinearLayout {
        val row = Ui.row(this)
        options.forEachIndexed { i, opt ->
            val btn = Ui.toggleButton(this, opt.second, current == opt.first) { onPick(opt.first) }
            row.addView(btn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                r = if (i < options.size - 1) 8f else 0f))
        }
        return row
    }

    /** Tappable table column header: tapping sorts by that key;
     *  tapping the active key toggles ascending/descending; the
     *  active header shows ▲ (ascending) or ▼ (descending). */
    private fun tableHeaderCell(label: String, weight: Float, active: Boolean, asc: Boolean, onClick: () -> Unit): TextView {
        val t = Ui.tv(this, label + if (active) (if (asc) " ▲" else " ▼") else "", 12f,
            if (active) Ui.ACCENT else Ui.MUTED, true)
        t.setPadding(Ui.dp(this, 4f), Ui.dp(this, 8f), Ui.dp(this, 4f), Ui.dp(this, 8f))
        t.setOnClickListener { onClick() }
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        return t
    }

    /** One compact, single-line, end-ellipsised table body cell. */
    private fun tableCell(text: String, weight: Float, color: Int = Ui.TEXT, bold: Boolean = false): TextView {
        val t = Ui.tv(this, text, 12.5f, color, bold)
        t.setSingleLine(true)
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(Ui.dp(this, 4f), Ui.dp(this, 7f), Ui.dp(this, 4f), Ui.dp(this, 7f))
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        return t
    }

    private fun onScriptSort(key: ScriptSort) {
        if (scriptSort == key) scriptSortAsc = !scriptSortAsc
        else { scriptSort = key; scriptSortAsc = true }
        refreshCurrent()
    }

    private fun onTaskSort(key: TaskSort) {
        if (taskSort == key) taskSortAsc = !taskSortAsc
        else { taskSort = key; taskSortAsc = true }
        refreshCurrent()
    }

    /** The original (Round 1–3) script card list — unchanged. */
    private fun renderScriptList(scripts: List<Script>) {
        for (s in scripts) {
            val card = Ui.card(this)
            val menu: () -> Unit = {
                showItemMenu(s.title, listOf(
                    "Open" to { showScript(s.id) },
                    "Edit (title / date / language / channel)" to { showScriptEdit(s.id) },
                    "Delete" to { deleteScriptWithUndo(s) }
                ))
            }
            card.addView(cardHeader(s.title, 16f, menu))
            val chips = Ui.row(this)
            chips.addView(Ui.chip(this, if (s.language == Language.BANGLA) "BANGLA" else "ENGLISH", accent = true))
            val statusChip = Ui.chip(this, if (s.status == ScriptStatus.FINAL) "FINAL" else "DRAFT", accent = s.status == ScriptStatus.FINAL)
            val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp2.setMargins(Ui.dp(this, 6f), 0, 0, 0)
            chips.addView(statusChip, lp2)
            if (!s.date.isNullOrBlank()) {
                val lp3 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp3.setMargins(Ui.dp(this, 6f), 0, 0, 0)
                chips.addView(Ui.chip(this, s.date!!), lp3)
            }
            card.addView(chips, Ui.lp(this, t = 6f))
            card.setOnClickListener { showScript(s.id) }
            card.setOnLongClickListener { menu(); true }
            contentCol.addView(card, Ui.lp(this, b = 10f))
        }
    }

    /** Scripts as a table: Title / Lang / Status / Date / Updated
     *  columns with tappable, sortable headers (Logic.sortScripts).
     *  Tapping a row opens the script. */
    private fun renderScriptTable(scripts: List<Script>) {
        if (scripts.isEmpty()) return
        val sorted = try { Logic.sortScripts(scripts, scriptSort, scriptSortAsc) } catch (e: Exception) { scripts }
        val panel = Ui.card(this)
        panel.setPadding(Ui.dp(this, 8f), Ui.dp(this, 4f), Ui.dp(this, 8f), Ui.dp(this, 4f))
        val head = Ui.row(this)
        head.addView(tableHeaderCell("Title", 3f, scriptSort == ScriptSort.TITLE, scriptSortAsc) { onScriptSort(ScriptSort.TITLE) })
        head.addView(tableHeaderCell("Lang", 1.1f, scriptSort == ScriptSort.LANGUAGE, scriptSortAsc) { onScriptSort(ScriptSort.LANGUAGE) })
        head.addView(tableHeaderCell("Status", 1.3f, scriptSort == ScriptSort.STATUS, scriptSortAsc) { onScriptSort(ScriptSort.STATUS) })
        head.addView(tableHeaderCell("Date", 1.6f, scriptSort == ScriptSort.DATE, scriptSortAsc) { onScriptSort(ScriptSort.DATE) })
        head.addView(tableHeaderCell("Updated", 1.6f, scriptSort == ScriptSort.UPDATED, scriptSortAsc) { onScriptSort(ScriptSort.UPDATED) })
        panel.addView(head)
        for (s in sorted) {
            val row = Ui.row(this)
            row.addView(tableCell(s.title, 3f, Ui.TEXT, true))
            row.addView(tableCell(if (s.language == Language.BANGLA) "BANGLA" else "ENGLISH", 1.1f, Ui.MUTED))
            row.addView(tableCell(if (s.status == ScriptStatus.FINAL) "FINAL" else "DRAFT", 1.3f,
                if (s.status == ScriptStatus.FINAL) Ui.GREEN else Ui.MUTED))
            row.addView(tableCell(s.date?.takeIf { it.isNotBlank() } ?: "—", 1.6f, Ui.MUTED))
            row.addView(tableCell(fmtDay(s.updatedAt), 1.6f, Ui.MUTED))
            row.setOnClickListener { showScript(s.id) }
            panel.addView(row)
        }
        contentCol.addView(panel, Ui.lp(this, b = 10f))
    }

    /** Scripts as a board: DRAFT and FINAL columns side by side
     *  (Logic.groupScriptsByStatus — both keys always present), each
     *  column listing compact cards; tapping a card opens the script. */
    private fun renderScriptBoard(scripts: List<Script>) {
        if (scripts.isEmpty()) return
        val groups = try { Logic.groupScriptsByStatus(scripts) } catch (e: Exception) { emptyMap() }
        val board = Ui.row(this)
        board.gravity = Gravity.TOP
        for (status in listOf(ScriptStatus.DRAFT, ScriptStatus.FINAL)) {
            val colScripts = groups[status] ?: emptyList()
            val col = Ui.column(this)
            col.addView(Ui.tv(this, "${status.name} (${colScripts.size})", 14f, Ui.ACCENT, true), Ui.lp(this, b = 8f))
            if (colScripts.isEmpty()) {
                col.addView(Ui.tv(this, "None yet.", 12f, Ui.MUTED))
            }
            for (s in colScripts) {
                val card = Ui.card(this)
                card.addView(Ui.tv(this, s.title, 14f, Ui.TEXT, true))
                card.addView(Ui.tv(this,
                    (if (s.language == Language.BANGLA) "BANGLA" else "ENGLISH") +
                        " · " + (s.date?.takeIf { it.isNotBlank() } ?: "—"),
                    11f, Ui.MUTED), Ui.lp(this, t = 4f))
                card.setOnClickListener { showScript(s.id) }
                col.addView(card, Ui.lp(this, b = 8f))
            }
            val lpCol = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (status == ScriptStatus.DRAFT) lpCol.setMargins(0, 0, Ui.dp(this, 8f), 0)
            board.addView(col, lpCol)
        }
        contentCol.addView(board, Ui.lp(this, b = 10f))
    }

    /** Tasks as a table: Title / Done / Date columns with tappable,
     *  sortable headers (Logic.sortTasks). Tapping the ☐/☑ cell
     *  toggles the task via the Store (same as the list view's card
     *  tap); tapping the rest of the row opens the task detail. */
    private fun renderTaskTable(tasks: List<GTask>) {
        if (tasks.isEmpty()) return
        val sorted = try { Logic.sortTasks(tasks, taskSort, taskSortAsc) } catch (e: Exception) { tasks }
        val panel = Ui.card(this)
        panel.setPadding(Ui.dp(this, 8f), Ui.dp(this, 4f), Ui.dp(this, 8f), Ui.dp(this, 4f))
        val head = Ui.row(this)
        head.addView(tableHeaderCell("Done", 0.9f, taskSort == TaskSort.DONE, taskSortAsc) { onTaskSort(TaskSort.DONE) })
        head.addView(tableHeaderCell("Title", 3f, taskSort == TaskSort.TITLE, taskSortAsc) { onTaskSort(TaskSort.TITLE) })
        head.addView(tableHeaderCell("Date", 1.6f, taskSort == TaskSort.DATE, taskSortAsc) { onTaskSort(TaskSort.DATE) })
        panel.addView(head)
        for (task in sorted) {
            val row = Ui.row(this)
            val box = Ui.tv(this, if (task.done) "☑" else "☐", 17f,
                if (task.done) Ui.GREEN else Ui.ACCENT, true)
            box.setPadding(Ui.dp(this, 4f), Ui.dp(this, 5f), Ui.dp(this, 4f), Ui.dp(this, 5f))
            box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            box.setOnClickListener {
                val ok = try { store.toggleTask(task.id) } catch (e: Exception) { false }
                if (!ok) { toast("Could not update the task"); return@setOnClickListener }
                persistStore()
                refreshCurrent()
            }
            row.addView(box)
            val titleCell = tableCell(task.title, 3f, if (task.done) Ui.MUTED else Ui.TEXT, true)
            if (task.done) titleCell.paintFlags = titleCell.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            row.addView(titleCell)
            row.addView(tableCell(task.dueDate?.takeIf { it.isNotBlank() } ?: "—", 1.6f, Ui.MUTED))
            row.setOnClickListener { showTaskDetail(task.id) }
            panel.addView(row)
        }
        contentCol.addView(panel, Ui.lp(this, b = 8f))
    }

    /** Tasks list + add form. channelId == null renders all tasks (Home).
     *  Round 4: a List | Table view switcher sits above the tasks —
     *  List is the original card rendering (card tap toggles done);
     *  Table is the sortable-column rendering (see renderTaskTable).
     *  Because this renderer is shared, the switcher appears both on
     *  the channel screen and for the global tasks on Home. */
    private fun renderTasks(channelId: String?) {
        val all = tasksList()
        val tasks = if (channelId == null) all else all.filter { it.channelId == channelId }
        contentCol.addView(viewSwitcher(
            listOf("list" to "List", "table" to "Table"), taskViewMode
        ) { mode -> taskViewMode = mode; refreshCurrent() }, Ui.lp(this, b = 8f))
        if (tasks.isEmpty()) emptyNote("No tasks yet.")
        if (taskViewMode == "table") renderTaskTable(tasks)
        if (taskViewMode != "table") for (task in tasks) {
            val card = Ui.card(this)
            val menu: () -> Unit = {
                showItemMenu(task.title, listOf(
                    "Details / link script" to { showTaskDetail(task.id) },
                    "Rename / edit" to { renameTaskDialog(task) },
                    "Delete" to { deleteTaskWithUndo(task) }
                ))
            }
            val titleTv = Ui.tv(this, if (task.done) "✓ ${task.title}" else task.title, 15f,
                if (task.done) Ui.MUTED else Ui.TEXT, true)
            if (task.done) titleTv.paintFlags = titleTv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            val headRow = Ui.row(this)
            headRow.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headRow.addView(Ui.button(this, "⋯") { menu() },
                Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
            card.addView(headRow)
            if (!task.dueDate.isNullOrBlank()) {
                card.addView(Ui.tv(this, "Due: ${task.dueDate}", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            val linkedScripts = scriptsLinkedToTask(task.id)
            for (linked in linkedScripts.take(3)) {
                val linkTv = Ui.tv(this, "🔗 Script: ${linked.title} — tap to open", 12f, Ui.ACCENT)
                linkTv.setOnClickListener { showScript(linked.id) }
                card.addView(linkTv, Ui.lp(this, t = 4f))
            }
            if (linkedScripts.size > 3) {
                card.addView(Ui.tv(this, "…and ${linkedScripts.size - 3} more linked script(s) — open Details", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            card.setOnClickListener {
                val ok = try { store.toggleTask(task.id) } catch (e: Exception) { false }
                if (!ok) { toast("Could not update the task"); return@setOnClickListener }
                persistStore()
                refreshCurrent()
            }
            card.setOnLongClickListener { menu(); true }
            contentCol.addView(card, Ui.lp(this, b = 8f))
        }

        val titleInput = Ui.input(this, "New task title")
        contentCol.addView(titleInput, Ui.lp(this, t = 4f, b = 6f))
        val dateInput = Ui.input(this, "Due date (optional, yyyy-MM-dd)")
        val dateRow = Ui.row(this)
        dateRow.addView(dateInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pickBtn = Ui.button(this, "📅 Pick") { }
        pickBtn.setOnClickListener { pickDate(dateInput.text.toString().trim()) { picked -> dateInput.setText(picked) } }
        dateRow.addView(pickBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        contentCol.addView(dateRow, Ui.lp(this, b = 8f))
        contentCol.addView(Ui.button(this, "Add task") {
            val title = titleInput.text.toString().trim()
            if (title.isEmpty()) { toast("Task title cannot be empty"); return@button }
            val due = dateInput.text.toString().trim().ifEmpty { null }
            val createdTask = try { store.addTask(channelId, title, due) } catch (e: Exception) { null }
            if (createdTask == null) { toast("Could not add task — check the date is yyyy-MM-dd"); return@button }
            persistStore()
            refreshCurrent()
        })
    }

    // ---------- Screen 2b: Script editor (new script) ----------

    private fun showScriptEditor(channelId: String) {
        val ch = findChannel(channelId) ?: run { showHome(); return }
        screen = "editor"
        currentChannelId = channelId
        editorChannelId = channelId
        editorLanguage = Language.BANGLA
        clearContent()
        header("Write script", "New draft in ${ch.name}", backLabel = ch.name) { showChannel(channelId) }

        val titleInput = Ui.input(this, "Script title")
        contentCol.addView(titleInput, Ui.lp(this, b = 8f))

        // Language selector — real toggle chips with persistent selected
        // state (fixes Round 1 finding N-4; the old refreshLang() stub
        // is gone, selection restyles both chips in place).
        contentCol.addView(Ui.tv(this, "Language:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val banglaBtn = Ui.toggleButton(this, "BANGLA", editorLanguage == Language.BANGLA) { }
        val englishBtn = Ui.toggleButton(this, "ENGLISH", editorLanguage == Language.ENGLISH) { }
        val langRow = Ui.row(this)
        langRow.addView(banglaBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        langRow.addView(englishBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(langRow, Ui.lp(this, b = 10f))

        // Channel picker (the editor can target any channel, not just
        // the one the editor was opened from).
        contentCol.addView(Ui.tv(this, "Channel:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val channelBtn = Ui.button(this, "Channel: ${ch.name}  ▾") { }
        contentCol.addView(channelBtn, Ui.lp(this, b = 10f))

        // Date field + framework date picker.
        val dateInput = Ui.input(this, "Date (optional, yyyy-MM-dd)")
        val dateRow = Ui.row(this)
        dateRow.addView(dateInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val datePickBtn = Ui.button(this, "📅 Pick") { }
        dateRow.addView(datePickBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        contentCol.addView(dateRow, Ui.lp(this, b = 8f))

        val bodyInput = Ui.input(this, "Write your script…", multi = true)
        bodyInput.minLines = 10
        contentCol.addView(bodyInput, Ui.lp(this, b = 10f))

        // Draft autosave (fixes Round 1 finding N-6): every change is
        // written to SharedPreferences; restored on reopen, cleared on save.
        fun saveDraft() {
            val obj = JSONObject()
            obj.put("title", titleInput.text.toString())
            obj.put("body", bodyInput.text.toString())
            obj.put("date", dateInput.text.toString())
            obj.put("language", editorLanguage.name)
            obj.put("channelId", editorChannelId ?: "")
            saveDraftJson(DRAFT_NEW_SCRIPT, obj)
        }
        draftSaver = { saveDraft() }
        onTextChanged(titleInput) { saveDraft() }
        onTextChanged(bodyInput) { saveDraft() }
        onTextChanged(dateInput) { saveDraft() }

        banglaBtn.setOnClickListener {
            editorLanguage = Language.BANGLA
            Ui.setToggle(this, banglaBtn, true)
            Ui.setToggle(this, englishBtn, false)
            saveDraft()
        }
        englishBtn.setOnClickListener {
            editorLanguage = Language.ENGLISH
            Ui.setToggle(this, banglaBtn, false)
            Ui.setToggle(this, englishBtn, true)
            saveDraft()
        }
        channelBtn.setOnClickListener {
            pickChannel { picked ->
                editorChannelId = picked.id
                channelBtn.text = "Channel: ${picked.name}  ▾"
                saveDraft()
            }
        }
        datePickBtn.setOnClickListener {
            pickDate(dateInput.text.toString().trim()) { picked -> dateInput.setText(picked) }
        }

        // Restore a retained draft, if any (e.g. after a process kill).
        val draft = loadDraftJson(DRAFT_NEW_SCRIPT)
        if (draft != null) {
            val dTitle = draft.optString("title", "")
            val dBody = draft.optString("body", "")
            val dDate = draft.optString("date", "")
            val dChannel = draft.optString("channelId", "")
            val dLang = try { Language.valueOf(draft.optString("language", "BANGLA")) }
                catch (e: Exception) { Language.BANGLA }
            if (dTitle.isNotBlank() || dBody.isNotBlank() || dDate.isNotBlank()) {
                titleInput.setText(dTitle)
                bodyInput.setText(dBody)
                dateInput.setText(dDate)
                editorLanguage = dLang
                Ui.setToggle(this, banglaBtn, dLang == Language.BANGLA)
                Ui.setToggle(this, englishBtn, dLang == Language.ENGLISH)
                val dc = findChannel(dChannel)
                if (dc != null) {
                    editorChannelId = dc.id
                    channelBtn.text = "Channel: ${dc.name}  ▾"
                }
                contentCol.addView(Ui.tv(this, "↩ Draft restored from your last session", 12f, Ui.ACCENT), Ui.lp(this, b = 8f))
            }
        }

        contentCol.addView(Ui.button(this, "Save draft", primary = true) {
            val title = titleInput.text.toString().trim()
            if (title.isEmpty()) { toast("Script title cannot be empty"); return@button }
            val cid = editorChannelId ?: channelId
            if (findChannel(cid) == null) { toast("Pick a channel first"); return@button }
            val body = bodyInput.text.toString()
            val date = dateInput.text.toString().trim().ifEmpty { null }
            val created = try {
                store.addScript(cid, title, body, editorLanguage, date)
            } catch (e: Exception) { null }
            if (created == null) { toast("Could not save script — check the date is yyyy-MM-dd"); return@button }
            clearDraft(DRAFT_NEW_SCRIPT)
            draftSaver = null
            persistStore()
            showScript(created.id)
        }, Ui.lp(this, b = 8f))

        contentCol.addView(Ui.button(this, "Discard draft") {
            clearDraft(DRAFT_NEW_SCRIPT)
            draftSaver = null
            val cid = editorChannelId ?: channelId
            if (findChannel(cid) != null) showChannel(cid) else showHome()
        })
    }

    // ---------- Screen 2c: Script edit (existing script metadata) ----------

    private fun showScriptEdit(scriptId: String) {
        val script = findScript(scriptId) ?: run { showHome(); return }
        screen = "scriptEdit"
        currentScriptId = scriptId
        currentChannelId = script.channelId
        clearContent()
        header("Edit script", script.title, backLabel = "Script") { showScript(scriptId) }

        var editLanguage: Language = script.language
        var editChannelId: String = script.channelId

        val titleInput = Ui.input(this, "Script title", value = script.title)
        contentCol.addView(titleInput, Ui.lp(this, b = 8f))

        contentCol.addView(Ui.tv(this, "Language:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val banglaBtn = Ui.toggleButton(this, "BANGLA", editLanguage == Language.BANGLA) { }
        val englishBtn = Ui.toggleButton(this, "ENGLISH", editLanguage == Language.ENGLISH) { }
        val langRow = Ui.row(this)
        langRow.addView(banglaBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        langRow.addView(englishBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(langRow, Ui.lp(this, b = 10f))

        contentCol.addView(Ui.tv(this, "Channel:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val channelBtn = Ui.button(this, "Channel: ${findChannel(editChannelId)?.name ?: "?"}  ▾") { }
        contentCol.addView(channelBtn, Ui.lp(this, b = 10f))

        val dateInput = Ui.input(this, "Date (optional, yyyy-MM-dd)", value = script.date ?: "")
        val dateRow = Ui.row(this)
        dateRow.addView(dateInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val datePickBtn = Ui.button(this, "📅 Pick") { }
        dateRow.addView(datePickBtn, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        contentCol.addView(dateRow, Ui.lp(this, b = 8f))

        contentCol.addView(Ui.tv(this, "Body text is edited on the script screen.", 12f, Ui.MUTED), Ui.lp(this, b = 10f))

        val draftKey = "draft_edit_$scriptId"
        fun saveDraft() {
            val obj = JSONObject()
            obj.put("title", titleInput.text.toString())
            obj.put("date", dateInput.text.toString())
            obj.put("language", editLanguage.name)
            obj.put("channelId", editChannelId)
            saveDraftJson(draftKey, obj)
        }
        draftSaver = { saveDraft() }
        onTextChanged(titleInput) { saveDraft() }
        onTextChanged(dateInput) { saveDraft() }
        banglaBtn.setOnClickListener {
            editLanguage = Language.BANGLA
            Ui.setToggle(this, banglaBtn, true); Ui.setToggle(this, englishBtn, false)
            saveDraft()
        }
        englishBtn.setOnClickListener {
            editLanguage = Language.ENGLISH
            Ui.setToggle(this, banglaBtn, false); Ui.setToggle(this, englishBtn, true)
            saveDraft()
        }
        channelBtn.setOnClickListener {
            pickChannel { picked ->
                editChannelId = picked.id
                channelBtn.text = "Channel: ${picked.name}  ▾"
                saveDraft()
            }
        }
        datePickBtn.setOnClickListener {
            pickDate(dateInput.text.toString().trim()) { picked -> dateInput.setText(picked) }
        }

        val draft = loadDraftJson(draftKey)
        if (draft != null) {
            val dTitle = draft.optString("title", "")
            val dDate = draft.optString("date", "")
            // Round 3 (fixes R2-N6): restore also when ONLY the
            // language or channel changed — Round 2's condition looked
            // at title/date only and silently dropped those drafts.
            val dLangName = draft.optString("language", "")
            val dChannelId = draft.optString("channelId", "")
            if ((dTitle.isNotBlank() && dTitle != script.title) || dDate != (script.date ?: "") ||
                (dLangName.isNotBlank() && dLangName != script.language.name) ||
                (dChannelId.isNotBlank() && dChannelId != script.channelId)) {
                titleInput.setText(dTitle)
                dateInput.setText(dDate)
                editLanguage = try { Language.valueOf(draft.optString("language", editLanguage.name)) }
                    catch (e: Exception) { editLanguage }
                Ui.setToggle(this, banglaBtn, editLanguage == Language.BANGLA)
                Ui.setToggle(this, englishBtn, editLanguage == Language.ENGLISH)
                val dc = findChannel(draft.optString("channelId", ""))
                if (dc != null) {
                    editChannelId = dc.id
                    channelBtn.text = "Channel: ${dc.name}  ▾"
                }
                contentCol.addView(Ui.tv(this, "↩ Unsaved edits restored", 12f, Ui.ACCENT), Ui.lp(this, b = 8f))
            }
        }

        contentCol.addView(Ui.button(this, "Save changes", primary = true) {
            val title = titleInput.text.toString().trim()
            if (title.isEmpty()) { toast("Script title cannot be empty"); return@button }
            val date = dateInput.text.toString().trim().ifEmpty { null }
            if (!Logic.isValidDate(date)) { toast("Check the date is yyyy-MM-dd"); return@button }
            if (findChannel(editChannelId) == null) { toast("Pick a channel first"); return@button }
            // Title/language/date/channel all route through Team
            // One's Store.updateScript (null = unchanged, "" clears
            // the date — our blank field means clear, so pass
            // date ?: ""). Round 3: updateScript gained a channelId
            // parameter, so the channel move goes through it too —
            // the last direct `script.channelId =` assignment is gone,
            // and the move now emits to the Db sink (R2-N1 closed).
            val ok = try {
                store.updateScript(script.id, title = title, language = editLanguage,
                    date = date ?: "", channelId = editChannelId)
            } catch (e: Exception) { false }
            if (!ok) { toast("Could not save — check the date is yyyy-MM-dd"); return@button }
            clearDraft(draftKey)
            draftSaver = null
            persistStore()
            toast("Saved")
            showScript(script.id)
        })
    }

    // ---------- Screen 3: Script ----------

    private fun showScript(scriptId: String) {
        val script = findScript(scriptId) ?: run {
            val cid = currentChannelId
            if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            return
        }
        screen = "script"
        currentScriptId = scriptId
        currentChannelId = script.channelId
        clearContent()
        val chName = findChannel(script.channelId)?.name ?: "Channel"
        header(script.title, null, backLabel = chName) { showChannel(script.channelId) }

        val chips = Ui.row(this)
        chips.addView(Ui.chip(this, if (script.language == Language.BANGLA) "BANGLA" else "ENGLISH", accent = true))
        val statusChip = Ui.chip(this, if (script.status == ScriptStatus.FINAL) "FINAL" else "DRAFT", accent = script.status == ScriptStatus.FINAL)
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.setMargins(Ui.dp(this, 6f), 0, 0, 0)
        chips.addView(statusChip, lp2)
        if (!script.date.isNullOrBlank()) {
            val lp3 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp3.setMargins(Ui.dp(this, 6f), 0, 0, 0)
            chips.addView(Ui.chip(this, script.date!!), lp3)
        }
        contentCol.addView(chips, Ui.lp(this, b = 10f))

        // Tappable idea link (fixes Round 1 finding N-5).
        if (!script.linkedIdeaId.isNullOrBlank()) {
            val idea = findIdea(script.linkedIdeaId)
            if (idea != null) {
                contentCol.addView(Ui.button(this, "💡 From idea: ${idea.title} — tap to open") {
                    showIdeaDetail(idea.id)
                }, Ui.lp(this, b = 10f))
            } else {
                contentCol.addView(Ui.tv(this, "💡 From idea (linked)", 13f, Ui.ACCENT), Ui.lp(this, b = 10f))
            }
        }

        val actionRow = Ui.row(this)
        actionRow.addView(Ui.button(this, "✎ Edit details") { showScriptEdit(script.id) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        actionRow.addView(Ui.button(this, "Delete script") {
            val cid = script.channelId
            deleteScriptWithUndo(script) {
                if (findChannel(cid) != null) showChannel(cid) else showHome()
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(actionRow, Ui.lp(this, b = 6f))

        sectionTitle("Body (editable)")
        val bodyDraftKey = "draft_body_$scriptId"
        val bodyDraft = loadDraftJson(bodyDraftKey)?.optString("body", "")
        val restoredBody = !bodyDraft.isNullOrEmpty() && bodyDraft != script.body
        val bodyInput = Ui.input(this, "Script body…", value = if (restoredBody) bodyDraft!! else script.body, multi = true)
        bodyInput.minLines = 12
        contentCol.addView(bodyInput, Ui.lp(this, b = 6f))
        if (restoredBody) {
            contentCol.addView(Ui.tv(this, "↩ Unsaved body draft restored — tap Save body to keep it", 12f, Ui.ACCENT), Ui.lp(this, b = 8f))
        }
        fun saveBodyDraft() {
            val obj = JSONObject()
            obj.put("body", bodyInput.text.toString())
            saveDraftJson(bodyDraftKey, obj)
        }
        draftSaver = { saveBodyDraft() }
        onTextChanged(bodyInput) { saveBodyDraft() }

        contentCol.addView(Ui.button(this, "Save body") {
            // Routed through Team One's Store.updateScript (Round 2) —
            // validates and bumps updatedAt; fixes Round 1 finding P2-2.
            val ok = try { store.updateScript(script.id, body = bodyInput.text.toString()) }
                catch (e: Exception) { false }
            if (!ok) { toast("Could not update the script"); return@button }
            clearDraft(bodyDraftKey)
            draftSaver = null
            persistStore()
            toast("Saved")
            showScript(script.id)
        }, Ui.lp(this, b = 8f))

        if (script.status == ScriptStatus.DRAFT) {
            contentCol.addView(Ui.button(this, "✓ Mark FINAL", primary = true) {
                val ok = try { store.markScriptFinal(script.id) } catch (e: Exception) { false }
                if (ok) {
                    persistStore()
                    toast("Marked FINAL")
                } else {
                    toast("Already final or not found")
                }
                showScript(script.id)
            })
        } else {
            contentCol.addView(Ui.tv(this, "✓ This script is FINAL", 14f, Ui.GREEN), Ui.lp(this, t = 4f))
        }

        // Tasks linked to this script (Round 2 feature 8).
        sectionTitle("Tasks for this script")
        val linkedTasks = tasksLinkedToScript(script.id)
        if (linkedTasks.isEmpty()) emptyNote("No tasks linked yet.")
        for (t in linkedTasks) {
            val card = Ui.card(this)
            val titleTv = Ui.tv(this, if (t.done) "✓ ${t.title}" else t.title, 15f,
                if (t.done) Ui.MUTED else Ui.TEXT, true)
            if (t.done) titleTv.paintFlags = titleTv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            card.addView(titleTv)
            if (!t.dueDate.isNullOrBlank()) {
                card.addView(Ui.tv(this, "Due: ${t.dueDate}", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            card.setOnClickListener { showTaskDetail(t.id) }
            contentCol.addView(card, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link existing task") {
            val alreadyLinked = tasksLinkedToScript(script.id).map { it.id }.toSet()
            val candidates = tasksList().filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No other tasks to link"); return@button }
            val names = Array<CharSequence>(candidates.size) { candidates[it].title }
            AlertDialog.Builder(this).setTitle("Link a task to this script").setItems(names) { _, which ->
                if (linkTaskToScript(candidates[which].id, script.id)) toast("Task linked") else toast("Could not link the task")
                showScript(script.id)
            }.show()
        }, Ui.lp(this, b = 8f))
        val newTaskInput = Ui.input(this, "New linked task title")
        contentCol.addView(newTaskInput, Ui.lp(this, b = 8f))
        contentCol.addView(Ui.button(this, "Add task linked to this script") {
            val title = newTaskInput.text.toString().trim()
            if (title.isEmpty()) { toast("Task title cannot be empty"); return@button }
            val created = try { store.addTask(script.channelId, title, null) } catch (e: Exception) { null }
            if (created == null) { toast("Could not add task"); return@button }
            persistStore()
            linkTaskToScript(created.id, script.id)
            toast("Task added and linked")
            showScript(script.id)
        })

        // Pages linked to this script (Round 3 — script side of
        // page<->script; the page screen carries the same UI).
        sectionTitle("Linked pages")
        val linkedPages = pagesLinkedToScript(script.id)
        if (linkedPages.isEmpty()) emptyNote("Not linked to any page.")
        for (linked in linkedPages) {
            val card = Ui.card(this)
            card.addView(Ui.tv(this, "📄 ${linked.title}", 16f, Ui.TEXT, true))
            card.addView(Ui.tv(this, "Tap to open the page", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showPage(linked.id) }
            contentCol.addView(card, Ui.lp(this, b = 6f))
            contentCol.addView(Ui.button(this, "Unlink \"${linked.title}\"") {
                if (unlinkPageFromScript(linked.id, script.id)) toast("Unlinked") else toast("Could not unlink")
                showScript(script.id)
            }, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link a page") {
            val alreadyLinked = linkedPages.map { it.id }.toSet()
            val candidates = store.pages.filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No pages to link"); return@button }
            val names = Array<CharSequence>(candidates.size) { candidates[it].title }
            AlertDialog.Builder(this).setTitle("Link a page to this script").setItems(names) { _, which ->
                if (linkPageToScript(candidates[which].id, script.id)) {
                    toast("Linked to \"${candidates[which].title}\"")
                } else {
                    toast("Could not link")
                }
                showScript(script.id)
            }.show()
        })
    }

    // ---------- Screen 4: Ideas ----------

    private fun showIdeas() {
        screen = "ideas"
        clearContent()
        header("Video Ideas", "Capture ideas — start a script from any idea", backLabel = "Home") { showHome() }

        val ideas = ideasList()
        if (ideas.isEmpty()) emptyNote("No ideas yet. Add your first idea below.")
        for (idea in ideas) {
            val card = Ui.card(this)
            val menu: () -> Unit = {
                showItemMenu(idea.title, listOf(
                    "Open" to { showIdeaDetail(idea.id) },
                    "Rename / edit" to { renameIdeaDialog(idea) },
                    "Delete" to { deleteIdeaWithUndo(idea) }
                ))
            }
            card.addView(cardHeader(idea.title, 16f, menu))
            if (idea.notes.isNotBlank()) {
                card.addView(Ui.tv(this, idea.notes, 13f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            val chName = findChannel(idea.channelId)?.name
            if (chName != null) {
                card.addView(Ui.tv(this, "Channel: $chName", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            if (!idea.linkedScriptId.isNullOrBlank()) {
                val linked = findScript(idea.linkedScriptId)
                if (linked != null) {
                    // Tappable link to the script (fixes N-5, idea side).
                    card.addView(Ui.button(this, "✓ Linked script: ${linked.title} — tap to open") {
                        showScript(linked.id)
                    }, Ui.lp(this, t = 8f))
                } else {
                    card.addView(Ui.tv(this, "✓ Linked to a script", 12f, Ui.GREEN), Ui.lp(this, t = 6f))
                }
            } else {
                val btnHolder = Ui.column(this)
                btnHolder.addView(Ui.button(this, "▶ Start script from this idea", primary = true) {
                    val created = try { store.startScriptFromIdea(idea.id) } catch (e: Exception) { null }
                    if (created == null) { toast("Could not start script"); return@button }
                    persistStore()
                    currentChannelId = created.channelId
                    showScript(created.id)
                }, Ui.lp(this, t = 8f))
                card.addView(btnHolder)
            }
            card.setOnClickListener { showIdeaDetail(idea.id) }
            card.setOnLongClickListener { menu(); true }
            contentCol.addView(card, Ui.lp(this, b = 10f))
        }

        sectionTitle("+ Add idea")
        val titleInput = Ui.input(this, "Idea title")
        contentCol.addView(titleInput, Ui.lp(this, b = 8f))
        val notesInput = Ui.input(this, "Notes (optional)", multi = true)
        notesInput.minLines = 3
        contentCol.addView(notesInput, Ui.lp(this, b = 8f))

        // Channel picker for the new idea (Round 2 feature 6 — v0.1
        // always used the first channel).
        ideaEditorChannelId = ideaEditorChannelId?.takeIf { findChannel(it) != null }
            ?: store.channels.firstOrNull()?.id
        contentCol.addView(Ui.tv(this, "Channel:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val ideaChannelBtn = Ui.button(this,
            "Channel: ${findChannel(ideaEditorChannelId)?.name ?: "(add a channel first)"}  ▾") { }
        ideaChannelBtn.setOnClickListener {
            pickChannel { picked ->
                ideaEditorChannelId = picked.id
                ideaChannelBtn.text = "Channel: ${picked.name}  ▾"
            }
        }
        contentCol.addView(ideaChannelBtn, Ui.lp(this, b = 8f))

        contentCol.addView(Ui.button(this, "Add idea", primary = true) {
            val title = titleInput.text.toString().trim()
            if (title.isEmpty()) { toast("Idea title cannot be empty"); return@button }
            val notes = notesInput.text.toString().trim()
            try { store.addIdea(title, notes, ideaEditorChannelId) } catch (e: Exception) {
                toast("Could not add idea"); return@button
            }
            persistStore()
            showIdeas()
        })
    }

    // ---------- Screen 4b: Idea detail ----------

    private fun showIdeaDetail(ideaId: String) {
        val idea = findIdea(ideaId) ?: run { showIdeas(); return }
        screen = "idea"
        currentIdeaId = ideaId
        clearContent()
        header(idea.title, "Video idea", backLabel = "Ideas") { showIdeas() }

        if (idea.notes.isNotBlank()) {
            contentCol.addView(Ui.tv(this, idea.notes, 15f, Ui.TEXT), Ui.lp(this, b = 10f))
        } else {
            emptyNote("No notes for this idea.")
        }

        contentCol.addView(Ui.tv(this, "Channel:", 13f, Ui.MUTED), Ui.lp(this, b = 6f))
        val chBtn = Ui.button(this, "Channel: ${findChannel(idea.channelId)?.name ?: "(none)"}  ▾") { }
        chBtn.setOnClickListener {
            pickChannel { picked ->
                // Round 3 (R2-N1): via Store.updateIdea so it emits.
                val ok = try { store.updateIdea(idea.id, channelId = picked.id) } catch (e: Exception) { false }
                if (!ok) { toast("Could not update the channel"); return@pickChannel }
                persistStore()
                toast("Channel updated")
                showIdeaDetail(idea.id)
            }
        }
        contentCol.addView(chBtn, Ui.lp(this, b = 10f))

        sectionTitle("Linked script")
        if (!idea.linkedScriptId.isNullOrBlank()) {
            val linked = findScript(idea.linkedScriptId)
            if (linked != null) {
                val card = Ui.card(this)
                card.addView(Ui.tv(this, linked.title, 16f, Ui.TEXT, true))
                val chips = Ui.row(this)
                chips.addView(Ui.chip(this, if (linked.language == Language.BANGLA) "BANGLA" else "ENGLISH", accent = true))
                val sc = Ui.chip(this, if (linked.status == ScriptStatus.FINAL) "FINAL" else "DRAFT", accent = linked.status == ScriptStatus.FINAL)
                val lpS = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lpS.setMargins(Ui.dp(this, 6f), 0, 0, 0)
                chips.addView(sc, lpS)
                card.addView(chips, Ui.lp(this, t = 6f))
                card.setOnClickListener { showScript(linked.id) }
                contentCol.addView(card, Ui.lp(this, b = 10f))
            } else {
                emptyNote("The linked script no longer exists.")
            }
        } else {
            emptyNote("Not linked to a script yet.")
            contentCol.addView(Ui.button(this, "▶ Start script from this idea", primary = true) {
                val created = try { store.startScriptFromIdea(idea.id) } catch (e: Exception) { null }
                if (created == null) { toast("Could not start script"); return@button }
                persistStore()
                currentChannelId = created.channelId
                showScript(created.id)
            }, Ui.lp(this, b = 8f))
        }

        // Linked tasks (Round 3 — idea side of idea<->task, the last
        // of the six link pairings; follows the task<->script pattern).
        sectionTitle("Linked tasks")
        val linkedTasks = tasksLinkedToIdea(idea.id)
        if (linkedTasks.isEmpty()) emptyNote("Not linked to any task.")
        for (linked in linkedTasks) {
            val card = Ui.card(this)
            val titleTv = Ui.tv(this, if (linked.done) "✓ ${linked.title}" else linked.title, 15f,
                if (linked.done) Ui.MUTED else Ui.TEXT, true)
            if (linked.done) titleTv.paintFlags = titleTv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            card.addView(titleTv)
            card.addView(Ui.tv(this, "Tap to open the task", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showTaskDetail(linked.id) }
            contentCol.addView(card, Ui.lp(this, b = 6f))
            contentCol.addView(Ui.button(this, "Unlink \"${linked.title}\"") {
                if (unlinkIdeaFromTask(idea.id, linked.id)) toast("Unlinked") else toast("Could not unlink")
                showIdeaDetail(idea.id)
            }, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link a task") {
            val alreadyLinked = linkedTasks.map { it.id }.toSet()
            val candidates = tasksList().filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No tasks to link"); return@button }
            val names = Array<CharSequence>(candidates.size) { candidates[it].title }
            AlertDialog.Builder(this).setTitle("Link a task to this idea").setItems(names) { _, which ->
                if (linkIdeaToTask(idea.id, candidates[which].id)) {
                    toast("Linked to \"${candidates[which].title}\"")
                } else {
                    toast("Could not link")
                }
                showIdeaDetail(idea.id)
            }.show()
        }, Ui.lp(this, b = 8f))

        val row = Ui.row(this)
        row.addView(Ui.button(this, "Rename / edit") { renameIdeaDialog(idea) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        row.addView(Ui.button(this, "Delete idea") {
            deleteIdeaWithUndo(idea) { showIdeas() }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(row, Ui.lp(this, t = 8f))
    }

    // ---------- Screen 5: Task detail ----------

    private fun showTaskDetail(taskId: String) {
        val task = findTask(taskId) ?: run { showHome(); return }
        screen = "task"
        currentTaskId = taskId
        currentChannelId = task.channelId
        clearContent()
        val chName = findChannel(task.channelId)?.name
        header(task.title, if (chName != null) "Task · $chName" else "Task", backLabel = "Back") {
            val cid = task.channelId
            if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
        }

        val statusTv = Ui.tv(this,
            if (task.done) "✓ Done" else "○ Open",
            15f, if (task.done) Ui.GREEN else Ui.TEXT, true)
        if (task.done) statusTv.paintFlags = statusTv.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        contentCol.addView(statusTv, Ui.lp(this, b = 6f))
        if (!task.dueDate.isNullOrBlank()) {
            contentCol.addView(Ui.tv(this, "Due: ${task.dueDate}", 13f, Ui.MUTED), Ui.lp(this, b = 10f))
        }

        contentCol.addView(Ui.button(this, if (task.done) "Mark not done" else "✓ Mark done") {
            val ok = try { store.toggleTask(task.id) } catch (e: Exception) { false }
            if (!ok) { toast("Could not update the task"); return@button }
            persistStore()
            showTaskDetail(task.id)
        }, Ui.lp(this, b = 8f))

        val row = Ui.row(this)
        row.addView(Ui.button(this, "Rename / edit") { renameTaskDialog(task) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        row.addView(Ui.button(this, "Delete task") {
            val cid = task.channelId
            deleteTaskWithUndo(task) {
                if (cid != null && findChannel(cid) != null) showChannel(cid) else showHome()
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(row, Ui.lp(this, b = 6f))

        // Linked scripts (Round 2 feature 8, task side — many-to-many
        // via Store.scriptsForTask / linkTaskToScript).
        sectionTitle("Linked scripts")
        val linkedScripts = scriptsLinkedToTask(task.id)
        if (linkedScripts.isEmpty()) emptyNote("Not linked to any script.")
        for (linked in linkedScripts) {
            val card = Ui.card(this)
            card.addView(Ui.tv(this, linked.title, 16f, Ui.TEXT, true))
            card.addView(Ui.tv(this, "Tap to open the script", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showScript(linked.id) }
            contentCol.addView(card, Ui.lp(this, b = 6f))
            contentCol.addView(Ui.button(this, "Unlink \"${linked.title}\"") {
                if (unlinkTaskFromScript(task.id, linked.id)) toast("Unlinked") else toast("Could not unlink")
                showTaskDetail(task.id)
            }, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link to a script") {
            val alreadyLinked = linkedScripts.map { it.id }.toSet()
            val candidates = store.scripts.filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No scripts to link"); return@button }
            val names = Array<CharSequence>(candidates.size) {
                val s = candidates[it]
                "${s.title}  ·  ${findChannel(s.channelId)?.name ?: "?"}"
            }
            AlertDialog.Builder(this).setTitle("Link to a script").setItems(names) { _, which ->
                if (linkTaskToScript(task.id, candidates[which].id)) {
                    toast("Linked to \"${candidates[which].title}\"")
                } else {
                    toast("Could not link")
                }
                showTaskDetail(task.id)
            }.show()
        })

        // Linked ideas (Round 3 — task side of idea<->task).
        sectionTitle("Linked ideas")
        val linkedIdeas = ideasLinkedToTask(task.id)
        if (linkedIdeas.isEmpty()) emptyNote("Not linked to any idea.")
        for (linked in linkedIdeas) {
            val card = Ui.card(this)
            card.addView(Ui.tv(this, linked.title, 16f, Ui.TEXT, true))
            card.addView(Ui.tv(this, "Tap to open the idea", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showIdeaDetail(linked.id) }
            contentCol.addView(card, Ui.lp(this, b = 6f))
            contentCol.addView(Ui.button(this, "Unlink \"${linked.title}\"") {
                if (unlinkIdeaFromTask(linked.id, task.id)) toast("Unlinked") else toast("Could not unlink")
                showTaskDetail(task.id)
            }, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link an idea") {
            val alreadyLinked = linkedIdeas.map { it.id }.toSet()
            val candidates = ideasList().filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No ideas to link"); return@button }
            val names = Array<CharSequence>(candidates.size) { candidates[it].title }
            AlertDialog.Builder(this).setTitle("Link an idea to this task").setItems(names) { _, which ->
                if (linkIdeaToTask(candidates[which].id, task.id)) {
                    toast("Linked to \"${candidates[which].title}\"")
                } else {
                    toast("Could not link")
                }
                showTaskDetail(task.id)
            }.show()
        })
    }

    // =====================================================================
    // Round 3 — the Notion engine UI: Pages + block editor.
    // Pages are Team One's first-class Page records (Store.pages):
    // a page nests via parentPageId and owns the Blocks whose
    // pageId == page.id (Store.blocksForPage, order-sorted). All
    // mutations go through the Store API so they emit to the Db sink.
    // =====================================================================

    /** Block types offered by the editor (labels for Logic.BLOCK_TYPES). */
    private val blockTypes: List<Pair<String, String>> = listOf(
        "Text" to "text",
        "Heading" to "heading",
        "Bulleted list" to "bulleted_list",
        "Numbered list" to "numbered_list",
        "To-do" to "todo",
        "Toggle" to "toggle",
        "Quote" to "quote",
        "Code" to "code",
        "Divider" to "divider"
    )

    private fun blockTypeLabel(type: String): String =
        blockTypes.firstOrNull { it.second == type }?.first ?: type

    private fun pickBlockType(dialogTitle: String, onPicked: (String) -> Unit) {
        val names = Array<CharSequence>(blockTypes.size) { blockTypes[it].first }
        AlertDialog.Builder(this).setTitle(dialogTitle).setItems(names) { _, which ->
            onPicked(blockTypes[which].second)
        }.show()
    }

    private fun pageMenu(page: Page) {
        showItemMenu(page.title, listOf(
            "Open" to { showPage(page.id) },
            "Rename" to { renamePageDialog(page) },
            "Add sub-page" to { addSubPage(page) },
            "Move to…" to { movePageFlow(page) },
            "Save as template…" to { savePageAsTemplateFlow(page) },
            "Delete" to { deletePageWithUndo(page) }
        ))
    }

    /** Page move/reparent (Round 4, closes R3-N1): a picker listing
     *  "Root (no parent)" plus every page EXCEPT the page itself, its
     *  descendants (Store.descendantPageIds) and its current parent
     *  (already there — Store.movePage would no-op), in tree order
     *  and indented by depth. Store.movePage re-validates the cycle
     *  rules; a false result is explained with a toast. On success the
     *  user stays on the page, whose breadcrumb now reflects the new
     *  parent. */
    private fun movePageFlow(page: Page) {
        val excluded = try { store.descendantPageIds(page.id) } catch (e: Exception) { setOf(page.id) }
        // Tree order (depth-first from the roots), then any pages the
        // walk could not reach (orphaned/cyclic imported data).
        val ordered = mutableListOf<Page>()
        val seen = mutableSetOf<String>()
        fun walk(p: Page) {
            if (!seen.add(p.id)) return
            ordered.add(p)
            val kids = try { store.childPages(p.id) } catch (e: Exception) { emptyList() }
            for (k in kids) walk(k)
        }
        val roots = try { store.rootPages() } catch (e: Exception) { emptyList() }
        for (r in roots) walk(r)
        for (p in store.pages) if (!seen.contains(p.id)) { seen.add(p.id); ordered.add(p) }

        val targets = mutableListOf<Page?>() // null = Root
        targets.add(null)
        for (p in ordered) {
            if (excluded.contains(p.id)) continue
            if (p.id == page.parentPageId) continue
            targets.add(p)
        }
        val names = Array<CharSequence>(targets.size) { i ->
            val t = targets[i]
            if (t == null) "Root (no parent)"
            else {
                val depth = try { store.pageAncestors(t.id).size } catch (e: Exception) { 0 }
                "  ".repeat(depth) + "📄 " + t.title
            }
        }
        AlertDialog.Builder(this).setTitle("Move \"${page.title}\" to…").setItems(names) { _, which ->
            val targetId = targets[which]?.id
            val ok = try { store.movePage(page.id, targetId) } catch (e: Exception) { false }
            if (!ok) {
                toast("Can't move the page there — that would create a loop, or it is already there")
                return@setItems
            }
            persistStore()
            toast("Page moved")
            showPage(page.id)
        }.show()
    }

    /** "Save as template" (Round 4): name dialog →
     *  Store.savePageAsTemplate snapshots the page's blocks (in page
     *  order, parent structure included) under that name. */
    private fun savePageAsTemplateFlow(page: Page) {
        textDialog("Save as template", "Template name", page.title, saveLabel = "Save template") { name ->
            val t = try { store.savePageAsTemplate(page.id, name) } catch (e: Exception) { null }
            if (t == null) { toast("Could not save the template"); return@textDialog }
            persistStore()
            toast("Saved as template \"${t.name}\" — find it on the Pages screen")
        }
    }

    private fun addSubPage(parent: Page) {
        textDialog("New sub-page", "Page title", "") { title ->
            val p = try { store.addPage(title, parent.id) } catch (e: Exception) { null }
            if (p == null) { toast("Could not create the page"); return@textDialog }
            persistStore()
            expandedPageIds.add(parent.id)
            showPage(p.id)
        }
    }

    // ---------- Screen 7: Pages (tree) ----------

    private fun showPages() {
        screen = "pages"
        clearContent()
        header("Pages", "Notion-style pages — pages nest inside pages", backLabel = "Home") { showHome() }

        contentCol.addView(Ui.button(this, "＋ New page", primary = true) {
            textDialog("New page", "Page title", "") { title ->
                val p = try { store.addPage(title) } catch (e: Exception) { null }
                if (p == null) { toast("Could not create the page"); return@textDialog }
                persistStore()
                showPage(p.id)
            }
        }, Ui.lp(this, b = 10f))

        val roots = try { store.rootPages() } catch (e: Exception) { emptyList() }
        if (roots.isEmpty()) emptyNote("No pages yet. Create your first page above.")
        val treeCol = Ui.column(this)
        contentCol.addView(treeCol)
        for (p in roots) renderPageTree(treeCol, p, 0, emptySet())

        // Templates (Round 4): pages saved via "Save as template" on
        // the page screen. Each template can instantiate a fresh page
        // (blocks included) in one tap, be renamed, or be deleted.
        // This per-template button IS the "new page from template"
        // flow — no separate picker is offered, to keep one clean path.
        sectionTitle("Templates")
        val templates = try { store.templates } catch (e: Exception) { emptyList() }
        if (templates.isEmpty()) {
            emptyNote("No templates yet. Open a page and tap \"Save as template\" to snapshot its blocks here.")
        }
        for (t in templates) {
            val card = Ui.card(this)
            card.addView(Ui.tv(this, t.name, 16f, Ui.TEXT, true))
            card.addView(Ui.tv(this, "${t.blocks.size} block(s)", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            val btnRow = Ui.row(this)
            btnRow.addView(Ui.button(this, "＋ New page from template", primary = true) {
                val created = try { store.createPageFromTemplate(t.id) } catch (e: Exception) { null }
                if (created == null) { toast("Could not create a page from that template"); return@button }
                persistStore()
                toast("Page created from \"${t.name}\"")
                showPage(created.id)
            }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
            btnRow.addView(Ui.button(this, "Rename") {
                textDialog("Rename template", "Template name", t.name) { newName ->
                    val ok = try { store.renameTemplate(t.id, newName) } catch (e: Exception) { false }
                    if (!ok) { toast("Could not rename the template"); return@textDialog }
                    persistStore()
                    toast("Renamed")
                    showPages()
                }
            }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
            btnRow.addView(Ui.button(this, "Delete") {
                // No undo for template deletes (a template is just a
                // snapshot — the source page is untouched), so this is
                // guarded by a confirmation dialog instead.
                AlertDialog.Builder(this)
                    .setTitle("Delete template?")
                    .setMessage("The template “${t.name}” will be deleted. Pages already created from it are not affected.")
                    .setPositiveButton("Delete") { _, _ ->
                        val ok = try { store.removeTemplate(t.id) } catch (e: Exception) { false }
                        if (!ok) { toast("Could not delete the template"); return@setPositiveButton }
                        persistStore()
                        toast("Template deleted")
                        showPages()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(btnRow, Ui.lp(this, t = 8f))
            contentCol.addView(card, Ui.lp(this, b = 8f))
        }
    }

    /** One tree row (indented by depth) + its expanded children,
     *  recursively. Depth/ancestor guards keep cyclic imported data
     *  from recursing forever. */
    private fun renderPageTree(col: LinearLayout, page: Page, depth: Int, ancestors: Set<String>) {
        if (depth > 12 || ancestors.contains(page.id)) return
        val children = try { store.childPages(page.id) } catch (e: Exception) { emptyList() }
        val nBlocks = try { store.blocksForPage(page.id).size } catch (e: Exception) { 0 }
        val row = Ui.row(this)
        row.setPadding(Ui.dp(this, depth * 16f), Ui.dp(this, 2f), 0, Ui.dp(this, 2f))
        if (children.isNotEmpty()) {
            val arrow = Ui.tv(this, if (expandedPageIds.contains(page.id)) "▾" else "▸", 15f, Ui.ACCENT, true)
            arrow.setPadding(Ui.dp(this, 4f), Ui.dp(this, 6f), Ui.dp(this, 8f), Ui.dp(this, 6f))
            arrow.setOnClickListener {
                if (expandedPageIds.contains(page.id)) expandedPageIds.remove(page.id) else expandedPageIds.add(page.id)
                showPages()
            }
            row.addView(arrow, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val spacer = View(this)
            row.addView(spacer, LinearLayout.LayoutParams(Ui.dp(this, 24f), 1))
        }
        val titleTv = Ui.tv(this, "📄 ${page.title}", 15f, Ui.TEXT, true)
        titleTv.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        titleTv.setOnClickListener { showPage(page.id) }
        row.addView(titleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val meta = Ui.tv(this,
            "$nBlocks block(s)" + if (children.isNotEmpty()) " · ${children.size} sub-page(s)" else "",
            11f, Ui.MUTED)
        row.addView(meta, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 4f))
        val menu: () -> Unit = { pageMenu(page) }
        row.addView(Ui.button(this, "⋯") { menu() },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnLongClickListener { menu(); true }
        col.addView(row, Ui.lp(this, b = 2f))
        if (expandedPageIds.contains(page.id)) {
            for (c in children) renderPageTree(col, c, depth + 1, ancestors + page.id)
        }
    }

    // ---------- Screen 8: Page (breadcrumb + sub-pages + block editor + linked scripts) ----------

    private fun showPage(pageId: String) {
        val page = findPage(pageId) ?: run { showPages(); return }
        screen = "page"
        currentPageId = pageId
        clearContent()
        val parent = findPage(page.parentPageId)
        header(page.title, null, backLabel = parent?.title ?: "Pages") {
            if (parent != null) showPage(parent.id) else showPages()
        }

        // Breadcrumb: Pages › ancestors (root-first) › this page.
        val ancestors = try { store.pageAncestors(pageId) } catch (e: Exception) { emptyList() }
        val crumbs = Ui.row(this)
        val pagesCrumb = Ui.tv(this, "Pages", 13f, Ui.ACCENT, true)
        pagesCrumb.setOnClickListener { showPages() }
        crumbs.addView(pagesCrumb)
        for (a in ancestors) {
            crumbs.addView(Ui.tv(this, "  ›  ", 13f, Ui.MUTED))
            val c = Ui.tv(this, a.title, 13f, Ui.ACCENT, true)
            c.setOnClickListener { showPage(a.id) }
            crumbs.addView(c)
        }
        crumbs.addView(Ui.tv(this, "  ›  ", 13f, Ui.MUTED))
        crumbs.addView(Ui.tv(this, page.title, 13f, Ui.MUTED, true))
        val crumbScroll = android.widget.HorizontalScrollView(this)
        crumbScroll.addView(crumbs)
        contentCol.addView(crumbScroll, Ui.lp(this, b = 10f))

        val actRow = Ui.row(this)
        actRow.addView(Ui.button(this, "Rename") { renamePageDialog(page) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        actRow.addView(Ui.button(this, "＋ Sub-page") { addSubPage(page) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        actRow.addView(Ui.button(this, "Delete page") {
            deletePageWithUndo(page) {
                val pid = page.parentPageId
                if (pid != null && findPage(pid) != null) showPage(pid) else showPages()
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(actRow, Ui.lp(this, b = 6f))

        // Round 4 actions: reparent this page, or snapshot it as a
        // reusable page template.
        val actRow2 = Ui.row(this)
        actRow2.addView(Ui.button(this, "Move to…") { movePageFlow(page) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        actRow2.addView(Ui.button(this, "Save as template") { savePageAsTemplateFlow(page) },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(actRow2, Ui.lp(this, b = 6f))

        // Sub-pages
        val children = try { store.childPages(pageId) } catch (e: Exception) { emptyList() }
        if (children.isNotEmpty()) {
            sectionTitle("Sub-pages")
            for (c in children) {
                val card = Ui.card(this)
                val menu: () -> Unit = { pageMenu(c) }
                card.addView(cardHeader(c.title, 16f, menu))
                val nB = try { store.blocksForPage(c.id).size } catch (e: Exception) { 0 }
                val nC = try { store.childPages(c.id).size } catch (e: Exception) { 0 }
                card.addView(Ui.tv(this, "$nB block(s)" + if (nC > 0) " · $nC sub-page(s)" else "", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
                card.setOnClickListener { showPage(c.id) }
                card.setOnLongClickListener { menu(); true }
                contentCol.addView(card, Ui.lp(this, b = 8f))
            }
        }

        // Block editor
        sectionTitle("Blocks")
        val blocks = try { store.blocksForPage(pageId) } catch (e: Exception) { emptyList() }
        if (blocks.isEmpty()) emptyNote("No blocks yet — add the first one below.")
        val blockCol = Ui.column(this)
        contentCol.addView(blockCol)
        val ids = blocks.map { it.id }.toSet()
        val topLevel = blocks.filter { it.parentBlockId == null || it.parentBlockId !in ids }
        renderBlockGroup(blockCol, topLevel, blocks, page, 0)
        contentCol.addView(Ui.button(this, "＋ Add block") { addBlockFlow(pageId) }, Ui.lp(this, t = 8f, b = 6f))

        // Linked scripts (page side of page<->script)
        sectionTitle("Linked scripts")
        val linkedScripts = scriptsLinkedToPage(page.id)
        if (linkedScripts.isEmpty()) emptyNote("No scripts linked to this page yet.")
        for (linked in linkedScripts) {
            val card = Ui.card(this)
            card.addView(Ui.tv(this, linked.title, 16f, Ui.TEXT, true))
            card.addView(Ui.tv(this, "Tap to open the script", 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            card.setOnClickListener { showScript(linked.id) }
            contentCol.addView(card, Ui.lp(this, b = 6f))
            contentCol.addView(Ui.button(this, "Unlink \"${linked.title}\"") {
                if (unlinkPageFromScript(page.id, linked.id)) toast("Unlinked") else toast("Could not unlink")
                showPage(page.id)
            }, Ui.lp(this, b = 8f))
        }
        contentCol.addView(Ui.button(this, "🔗 Link a script") {
            val alreadyLinked = linkedScripts.map { it.id }.toSet()
            val candidates = store.scripts.filter { it.id !in alreadyLinked }
            if (candidates.isEmpty()) { toast("No scripts to link"); return@button }
            val names = Array<CharSequence>(candidates.size) {
                val s = candidates[it]
                "${s.title}  ·  ${findChannel(s.channelId)?.name ?: "?"}"
            }
            AlertDialog.Builder(this).setTitle("Link a script to this page").setItems(names) { _, which ->
                if (linkPageToScript(page.id, candidates[which].id)) {
                    toast("Linked to \"${candidates[which].title}\"")
                } else {
                    toast("Could not link")
                }
                showPage(page.id)
            }.show()
        })
    }

    /** Renders one sibling group of blocks; consecutive numbered_list
     *  blocks get a running number. Toggle children (blocks whose
     *  parentBlockId points at the toggle, e.g. from imported data)
     *  render indented under the toggle while it is expanded. */
    private fun renderBlockGroup(container: LinearLayout, siblings: List<Block>, allBlocks: List<Block>, page: Page, depth: Int) {
        var num = 0
        for (b in siblings) {
            num = if (b.type == "numbered_list") num + 1 else 0
            container.addView(blockRowView(b, page, allBlocks, depth, num))
            if (b.type == "toggle" && !b.collapsed && depth < 6) {
                // Children via Store.childBlocks (order-sorted direct
                // children — the same set the old parentBlockId filter
                // produced, now through the Store API).
                val kids = try { store.childBlocks(b.id) } catch (e: Exception) {
                    allBlocks.filter { it.parentBlockId == b.id }
                }
                if (kids.isNotEmpty()) {
                    val kidBox = Ui.column(this)
                    container.addView(kidBox)
                    renderBlockGroup(kidBox, kids, allBlocks, page, depth + 1)
                }
            }
        }
    }

    private fun blockMenu(block: Block, page: Page, pageIndex: Int, depth: Int) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (block.type != "divider") {
            actions.add("Edit" to { showBlockEdit(page.id, block.id) })
        }
        // Round 4 (closes R3-N2): child blocks can now be authored —
        // a toggle's menu offers adding a child block, created via
        // Store.addBlock(parentBlockId = toggle.id).
        if (block.type == "toggle") {
            actions.add("＋ Add child block" to { addChildBlockFlow(page, block) })
        }
        // Round 4: Store.moveBlockUp/Down are sibling-scoped since
        // v0.4 (R3-N3), so Move is now offered for child blocks too —
        // a child steps among its siblings, a top-level block among
        // top-level blocks; neither can cross the other's group.
        actions.add("Move up" to {
            try { store.moveBlockUp(block.id) } catch (e: Exception) { /* ignore */ }
            persistStore()
            showPage(page.id)
        })
        actions.add("Move down" to {
            try { store.moveBlockDown(block.id) } catch (e: Exception) { /* ignore */ }
            persistStore()
            showPage(page.id)
        })
        actions.add("Turn into…" to {
            pickBlockType("Turn into") { type ->
                val ok = try { store.updateBlockType(block.id, type) } catch (e: Exception) { false }
                if (ok) persistStore() else toast("Could not change the block type")
                showPage(page.id)
            }
        })
        actions.add("Delete" to { deleteBlockWithUndo(block, pageIndex) })
        showItemMenu(blockTypeLabel(block.type) + " block", actions)
    }

    /** Type-appropriate rendering of one block. Tap the block to edit
     *  it; the ⋯ menu carries Edit / Move / Turn into / Delete. */
    private fun blockRowView(block: Block, page: Page, allBlocks: List<Block>, depth: Int, number: Int): View {
        val col = Ui.column(this)
        col.setPadding(Ui.dp(this, depth * 16f), Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
        val pageIndex = allBlocks.indexOfFirst { it.id == block.id }
        val menu: () -> Unit = { blockMenu(block, page, pageIndex, depth) }
        val edit: () -> Unit = { showBlockEdit(page.id, block.id) }
        val row = Ui.row(this)
        val content = Ui.column(this)
        when (block.type) {
            "heading" -> {
                val t = Ui.tv(this, block.text.ifBlank { "Heading" }, 20f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT, true)
                t.setOnClickListener { edit() }
                content.addView(t)
            }
            "bulleted_list" -> {
                val t = Ui.tv(this, "•  " + block.text.ifBlank { "List item" }, 15f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT)
                t.setOnClickListener { edit() }
                content.addView(t)
            }
            "numbered_list" -> {
                val t = Ui.tv(this, "$number.  " + block.text.ifBlank { "List item" }, 15f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT)
                t.setOnClickListener { edit() }
                content.addView(t)
            }
            "todo" -> {
                val todoRow = Ui.row(this)
                val box = Ui.tv(this, if (block.checked) "☑" else "☐", 19f,
                    if (block.checked) Ui.GREEN else Ui.ACCENT, true)
                box.setPadding(0, 0, Ui.dp(this, 8f), 0)
                box.setOnClickListener {
                    val ok = try { store.setBlockChecked(block.id, !block.checked) } catch (e: Exception) { false }
                    if (!ok) { toast("Could not update the to-do"); return@setOnClickListener }
                    persistStore()
                    showPage(page.id)
                }
                todoRow.addView(box, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val t = Ui.tv(this, block.text.ifBlank { "To-do" }, 15f,
                    if (block.checked) Ui.MUTED else Ui.TEXT)
                if (block.checked) t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                t.setOnClickListener { edit() }
                todoRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(todoRow)
            }
            "toggle" -> {
                val headRow = Ui.row(this)
                val arrow = Ui.tv(this, if (block.collapsed) "▸" else "▾", 15f, Ui.ACCENT, true)
                arrow.setPadding(0, 0, Ui.dp(this, 8f), 0)
                arrow.setOnClickListener {
                    val ok = try { store.setBlockCollapsed(block.id, !block.collapsed) } catch (e: Exception) { false }
                    if (!ok) { toast("Could not update the toggle"); return@setOnClickListener }
                    persistStore()
                    showPage(page.id)
                }
                headRow.addView(arrow, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val title = block.text.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Toggle"
                val t = Ui.tv(this, title, 15f, Ui.TEXT, true)
                t.setOnClickListener { edit() }
                headRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(headRow)
            }
            "quote" -> {
                val qRow = Ui.row(this)
                val bar = View(this)
                bar.setBackgroundColor(Ui.ACCENT)
                qRow.addView(bar, LinearLayout.LayoutParams(Ui.dp(this, 3f), ViewGroup.LayoutParams.MATCH_PARENT))
                val t = Ui.tv(this, block.text.ifBlank { "Quote" }, 15f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT)
                t.setTypeface(t.typeface, android.graphics.Typeface.ITALIC)
                t.setPadding(Ui.dp(this, 10f), Ui.dp(this, 2f), 0, Ui.dp(this, 2f))
                t.setOnClickListener { edit() }
                qRow.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                content.addView(qRow)
            }
            "code" -> {
                val t = Ui.tv(this, block.text.ifBlank { "code" }, 13.5f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT)
                t.typeface = android.graphics.Typeface.MONOSPACE
                t.background = Ui.panelBg(this, 8f)
                t.setPadding(Ui.dp(this, 10f), Ui.dp(this, 8f), Ui.dp(this, 10f), Ui.dp(this, 8f))
                t.setOnClickListener { edit() }
                content.addView(t)
            }
            "divider" -> {
                val line = View(this)
                line.setBackgroundColor(0xFF33333F.toInt())
                val lpLine = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1f))
                lpLine.setMargins(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
                content.addView(line, lpLine)
            }
            else -> { // "text" and any unknown stored type render as plain text
                val t = Ui.tv(this, block.text.ifBlank { "Empty text block" }, 15f,
                    if (block.text.isBlank()) Ui.MUTED else Ui.TEXT)
                t.setOnClickListener { edit() }
                content.addView(t)
            }
        }
        row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, "⋯") { menu() },
            Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, l = 8f))
        col.addView(row)
        // Toggle body text: the lines after the first, shown while expanded.
        if (block.type == "toggle" && !block.collapsed) {
            val bodyText = block.text.substringAfter("\n", "")
            if (bodyText.isNotBlank()) {
                val body = Ui.tv(this, bodyText, 14f, Ui.MUTED)
                body.setPadding(Ui.dp(this, 22f + depth * 16f), Ui.dp(this, 2f), 0, 0)
                col.addView(body)
            }
        }
        col.setOnLongClickListener { menu(); true }
        return col
    }

    /** Adds a child block under a toggle (Round 4, closes R3-N2):
     *  type picker → Store.addBlock(pageId, type, parentBlockId =
     *  toggle.id) → the toggle is expanded if collapsed (so the new
     *  child is visible under it) and the editor opens for the new
     *  block (a divider inserts immediately, like the top-level flow). */
    private fun addChildBlockFlow(page: Page, toggle: Block) {
        pickBlockType("Add child block") { type ->
            val created = try {
                store.addBlock(page.id, type, parentBlockId = toggle.id)
            } catch (e: Exception) { null }
            if (created == null) { toast("Could not add the child block"); return@pickBlockType }
            if (toggle.collapsed) {
                try { store.setBlockCollapsed(toggle.id, false) } catch (e: Exception) { /* best-effort expand */ }
            }
            persistStore()
            if (type == "divider") showPage(page.id) else showBlockEdit(page.id, created.id)
        }
    }

    private fun addBlockFlow(pageId: String) {
        pickBlockType("Add block") { type ->
            if (type == "divider") {
                try { store.addBlock(pageId, type) } catch (e: Exception) { toast("Could not add the block"); return@pickBlockType }
                persistStore()
                showPage(pageId)
            } else {
                val b = try { store.addBlock(pageId, type) } catch (e: Exception) { null }
                if (b == null) { toast("Could not add the block"); return@pickBlockType }
                persistStore()
                showBlockEdit(pageId, b.id)
            }
        }
    }

    // ---------- Screen 9: Block editor (draft-autosaved, Round 2 pattern) ----------

    private fun showBlockEdit(pageId: String, blockId: String) {
        val page = findPage(pageId) ?: run { showPages(); return }
        val block = findBlock(blockId) ?: run { showPage(pageId); return }
        screen = "blockEdit"
        currentPageId = pageId
        clearContent()
        header("Edit block", "${blockTypeLabel(block.type)} · on ${page.title}", backLabel = page.title) { showPage(pageId) }

        if (block.type == "toggle") {
            contentCol.addView(Ui.tv(this,
                "First line is the toggle title — the lines below it are the body, shown while the toggle is expanded. Child blocks can be added from the toggle's ⋯ menu on the page.",
                12f, Ui.MUTED), Ui.lp(this, b = 8f))
        }
        if (block.type == "todo") {
            contentCol.addView(Ui.button(this, if (block.checked) "☑ Checked — tap to uncheck" else "☐ Unchecked — tap to check") {
                val ok = try { store.setBlockChecked(block.id, !block.checked) } catch (e: Exception) { false }
                if (!ok) { toast("Could not update the to-do"); return@button }
                persistStore()
                showBlockEdit(pageId, blockId)
            }, Ui.lp(this, b = 8f))
        }

        // Draft autosave (same pattern as the script editors): every
        // change is written to SharedPreferences and restored on reopen
        // when it differs from the saved block text.
        val draftKey = "draft_block_$blockId"
        val draftText = loadDraftJson(draftKey)?.optString("text", "")
        val restored = !draftText.isNullOrEmpty() && draftText != block.text
        val input = Ui.input(this, "Block text…", value = if (restored) draftText!! else block.text, multi = true)
        input.minLines = 6
        contentCol.addView(input, Ui.lp(this, b = 6f))
        if (restored) {
            contentCol.addView(Ui.tv(this, "↩ Unsaved draft restored — tap Save to keep it", 12f, Ui.ACCENT), Ui.lp(this, b = 8f))
        }
        fun saveDraft() {
            val obj = JSONObject()
            obj.put("text", input.text.toString())
            saveDraftJson(draftKey, obj)
        }
        draftSaver = { saveDraft() }
        onTextChanged(input) { saveDraft() }

        contentCol.addView(Ui.button(this, "Save", primary = true) {
            val ok = try { store.updateBlockText(block.id, input.text.toString()) } catch (e: Exception) { false }
            if (!ok) { toast("Could not save the block"); return@button }
            clearDraft(draftKey)
            draftSaver = null
            persistStore()
            toast("Saved")
            showPage(pageId)
        }, Ui.lp(this, b = 8f))

        val row = Ui.row(this)
        row.addView(Ui.button(this, "Turn into…") {
            pickBlockType("Turn into") { type ->
                val ok = try { store.updateBlockType(block.id, type) } catch (e: Exception) { false }
                if (ok) persistStore() else toast("Could not change the block type")
                showPage(pageId)
            }
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, r = 8f))
        row.addView(Ui.button(this, "Delete block") {
            val pageIndex = try { store.blocksForPage(pageId).indexOfFirst { it.id == blockId } } catch (e: Exception) { 0 }
            deleteBlockWithUndo(block, pageIndex)
        }, Ui.lp(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentCol.addView(row)
    }

    // ---------- Screen 6: Search ----------

    private fun showSearch() {
        screen = "search"
        clearContent()
        header("Search", "Pages, scripts, ideas, tasks and channels — titles + body/notes/block text", backLabel = "Home") { showHome() }

        val input = Ui.input(this, "Type to search…")
        contentCol.addView(input, Ui.lp(this, b = 10f))
        val resultsCol = Ui.column(this)
        contentCol.addView(resultsCol)

        fun snippet(text: String): String {
            val flat = text.replace("\n", " ").trim()
            return if (flat.length > 90) flat.substring(0, 90) + "…" else flat
        }

        fun resultCard(type: String, title: String, sub: String, open: () -> Unit) {
            val card = Ui.card(this)
            val chips = Ui.row(this)
            chips.addView(Ui.chip(this, type, accent = true))
            card.addView(chips, Ui.lp(this, b = 6f))
            card.addView(Ui.tv(this, title, 15f, Ui.TEXT, true))
            if (sub.isNotBlank()) {
                card.addView(Ui.tv(this, sub, 12f, Ui.MUTED), Ui.lp(this, t = 4f))
            }
            card.setOnClickListener { open() }
            resultsCol.addView(card, Ui.lp(this, b = 8f))
        }

        fun render(query: String) {
            resultsCol.removeAllViews()
            val q = query.trim()
            if (q.isEmpty()) {
                resultsCol.addView(Ui.tv(this, "Start typing to search everything.", 14f, Ui.MUTED))
                return
            }
            val ql = q.lowercase()
            var hits = 0
            for (ch in store.channels) {
                if (ch.name.lowercase().contains(ql)) {
                    hits++
                    resultCard("CHANNEL", ch.name, "${scriptsOf(ch.id).size} scripts") { showChannel(ch.id) }
                }
            }
            for (p in store.pages) {
                // Pages match on title, and on the text of any of
                // their blocks (Round 3 — pages wired into Search).
                val pageBlocks = try { store.blocksForPage(p.id) } catch (e: Exception) { emptyList() }
                val titleHit = p.title.lowercase().contains(ql)
                val hitBlock = pageBlocks.firstOrNull { it.text.lowercase().contains(ql) }
                if (titleHit || hitBlock != null) {
                    hits++
                    val parentTitle = findPage(p.parentPageId)?.title
                    val sub = when {
                        !titleHit && hitBlock != null -> "Block: " + snippet(hitBlock.text)
                        parentTitle != null -> "Inside: $parentTitle"
                        else -> "Root page"
                    }
                    resultCard("PAGE", p.title, sub) { showPage(p.id) }
                }
            }
            for (s in store.scripts) {
                if (s.title.lowercase().contains(ql) || s.body.lowercase().contains(ql)) {
                    hits++
                    val chName = findChannel(s.channelId)?.name ?: ""
                    resultCard("SCRIPT", s.title, listOf(chName, snippet(s.body)).filter { it.isNotBlank() }.joinToString(" · ")) {
                        showScript(s.id)
                    }
                }
            }
            for (idea in ideasList()) {
                if (idea.title.lowercase().contains(ql) || idea.notes.lowercase().contains(ql)) {
                    hits++
                    resultCard("IDEA", idea.title, snippet(idea.notes)) { showIdeaDetail(idea.id) }
                }
            }
            for (t in tasksList()) {
                if (t.title.lowercase().contains(ql)) {
                    hits++
                    val chName = findChannel(t.channelId)?.name ?: "All tasks"
                    resultCard("TASK", t.title, chName + (if (t.done) " · done" else "")) { showTaskDetail(t.id) }
                }
            }
            if (hits == 0) {
                resultsCol.addView(Ui.tv(this, "No matches for \"$q\".", 14f, Ui.MUTED))
            }
        }

        onTextChanged(input) { render(input.text.toString()) }
        render("")
    }
}
