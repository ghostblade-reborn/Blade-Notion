import com.ghostblade.notion.Block
import com.ghostblade.notion.GTask
import com.ghostblade.notion.Idea
import com.ghostblade.notion.Language
import com.ghostblade.notion.Logic
import com.ghostblade.notion.Page
import com.ghostblade.notion.Record
import com.ghostblade.notion.Script
import com.ghostblade.notion.ScriptSort
import com.ghostblade.notion.ScriptStatus
import com.ghostblade.notion.Store
import com.ghostblade.notion.TaskSort

var failures = 0
var total = 0
fun check(name: String, actual: Any?, expected: Any?) {
    total++
    if (actual == expected) println("PASS  $name")
    else { failures++; println("FAIL  $name -> expected [$expected] but got [$actual]") }
}

fun main() {
    /* ---------- Logic: date validation ---------- */
    check("valid date", Logic.isValidDate("2026-11-20"), true)
    check("valid leap day 2024", Logic.isValidDate("2024-02-29"), true)
    check("invalid non-leap feb29 2026", Logic.isValidDate("2026-02-29"), false)
    check("invalid month 13", Logic.isValidDate("2026-13-01"), false)
    check("invalid month 00", Logic.isValidDate("2026-00-10"), false)
    check("invalid apr 31", Logic.isValidDate("2026-04-31"), false)
    check("invalid feb 30", Logic.isValidDate("2024-02-30"), false)
    check("malformed text date", Logic.isValidDate("tomorrow"), false)
    check("malformed unpadded date", Logic.isValidDate("2026-2-9"), false)
    check("malformed slashes", Logic.isValidDate("2026/11/20"), false)
    check("null date ok (optional)", Logic.isValidDate(null), true)
    check("blank date ok (optional)", Logic.isValidDate(""), true)

    /* ---------- Logic: addDays ---------- */
    check("addDays month edge", Logic.addDays("2026-01-31", 1), "2026-02-01")
    check("addDays year edge", Logic.addDays("2026-12-31", 1), "2027-01-01")
    check("addDays leap edge", Logic.addDays("2024-02-28", 1), "2024-02-29")
    check("addDays non-leap feb", Logic.addDays("2026-02-28", 1), "2026-03-01")
    check("addDays negative", Logic.addDays("2026-03-01", -1), "2026-02-28")
    check("addDays negative year edge", Logic.addDays("2027-01-01", -1), "2026-12-31")
    check("addDays zero", Logic.addDays("2026-11-20", 0), "2026-11-20")
    check("addDays +30 over month", Logic.addDays("2026-11-20", 30), "2026-12-20")

    /* ---------- Logic: ids / block sort ---------- */
    check("uid unique", Logic.uid() != Logic.uid(), true)
    val unsorted = listOf(
        Block(id = "b1", pageId = "p", text = "third", order = 2),
        Block(id = "b2", pageId = "p", text = "first", order = 0),
        Block(id = "b3", pageId = "p", text = "second", order = 1)
    )
    check("sortBlocks by order", Logic.sortBlocks(unsorted).map { it.text }, listOf("first", "second", "third"))
    val ties = listOf(
        Block(id = "t1", pageId = "p", text = "a", order = 1),
        Block(id = "t2", pageId = "p", text = "b", order = 1),
        Block(id = "t3", pageId = "p", text = "c", order = 0)
    )
    check("sortBlocks stable on ties", Logic.sortBlocks(ties).map { it.text }, listOf("c", "a", "b"))

    /* ---------- Store: seeding ---------- */
    val store = Store()
    store.seedIfEmpty()
    check("seed creates 1 template channel", store.channels.size, 1)
    check("seed channel name is the template", store.channels.map { it.name }, listOf("Write your channel name"))
    store.seedIfEmpty()
    check("seed twice does not duplicate", store.channels.size, 1)
    val riseId = store.channels[0].id
    val rebornId = store.addChannel("Second Channel").id

    /* ---------- Store: script flow (draft -> final) ---------- */
    val script = store.addScript(riseId, "Test Script", "body text", Language.BANGLA, "2026-11-21")
    check("addScript returns script", script != null, true)
    check("new script is DRAFT", script?.status, ScriptStatus.DRAFT)
    check("new script fields", script?.title to script?.channelId, "Test Script" to riseId)
    check("canMarkFinal on draft", Logic.canMarkFinal(script!!), true)
    check("markScriptFinal first time", store.markScriptFinal(script.id), true)
    check("script now FINAL", script.status, ScriptStatus.FINAL)
    check("canMarkFinal on final", Logic.canMarkFinal(script), false)
    check("markScriptFinal second time false", store.markScriptFinal(script.id), false)
    check("markScriptFinal missing id false", store.markScriptFinal("no-such-id"), false)
    check("addScript missing channel null", store.addScript("no-such-channel", "X", "", Language.BANGLA, null), null)
    check("addScript invalid date null", store.addScript(riseId, "X", "", Language.ENGLISH, "2026-02-29"), null)
    check("addScript null date ok", store.addScript(rebornId, "No Date Script") != null, true)
    check("scriptsForChannel rise count", store.scriptsForChannel(riseId).size, 1)
    check("scriptsForChannel reborn count", store.scriptsForChannel(rebornId).size, 1)
    check("Logic count helper", Logic.countScriptsForChannel(store.scripts, riseId), 1)

    /* ---------- Store: idea -> script linking ---------- */
    val idea = store.addIdea("Idea One", "these are the notes", riseId)
    check("idea starts unlinked", idea.linkedScriptId, null)
    val fromIdea = store.startScriptFromIdea(idea.id)
    check("startScriptFromIdea returns script", fromIdea != null, true)
    check("idea script is DRAFT", fromIdea?.status, ScriptStatus.DRAFT)
    check("idea script title = idea title", fromIdea?.title, "Idea One")
    check("idea script body = idea notes", fromIdea?.body, "these are the notes")
    check("idea script in idea channel", fromIdea?.channelId, riseId)
    check("script.linkedIdeaId set", fromIdea?.linkedIdeaId, idea.id)
    check("idea.linkedScriptId set", idea.linkedScriptId, fromIdea?.id)
    check("ideaLinkConsistent true", Logic.ideaLinkConsistent(idea, fromIdea!!), true)
    check("startScriptFromIdea twice null", store.startScriptFromIdea(idea.id), null)
    check("startScriptFromIdea missing idea null", store.startScriptFromIdea("no-such-id"), null)
    check("idea script in scriptsForChannel", store.scriptsForChannel(riseId).map { it.id }.contains(fromIdea.id), true)
    val broken = Script(id = "sx", channelId = riseId, title = "t", linkedIdeaId = "someone-else")
    check("ideaLinkConsistent false when one-way", Logic.ideaLinkConsistent(idea, broken), false)

    // Idea with no channel falls back to the first channel.
    val orphanIdea = store.addIdea("Orphan Idea", "n", null)
    val orphanScript = store.startScriptFromIdea(orphanIdea.id)
    check("orphan idea uses first channel", orphanScript?.channelId, riseId)
    check("orphan idea link consistent", orphanScript != null && Logic.ideaLinkConsistent(orphanIdea, orphanScript), true)

    // No channels at all -> null.
    val emptyStore = Store()
    val lonely = emptyStore.addIdea("Lonely", "", null)
    check("startScriptFromIdea no channels null", emptyStore.startScriptFromIdea(lonely.id), null)

    /* ---------- Store: tasks ---------- */
    val task = store.addTask(riseId, "Record voiceover", "2026-11-22")
    check("addTask returns task", task != null, true)
    check("task starts not done", task?.done, false)
    check("toggleTask on returns true", store.toggleTask(task!!.id), true)
    check("task done after toggle on", task.done, true)
    check("toggleTask off returns true", store.toggleTask(task.id), true)
    check("task not done after toggle off", task.done, false)
    check("toggleTask missing id false", store.toggleTask("no-such-id"), false)
    check("addTask invalid date null", store.addTask(null, "Bad", "2026-13-40"), null)
    check("addTask no channel/date ok", store.addTask(null, "Someday") != null, true)
    val gt: GTask? = store.tasks.firstOrNull()
    check("tasks list holds added tasks", gt != null && store.tasks.size >= 2, true)

    /* ---------- Store: blocks ---------- */
    val b1 = store.addBlock("page-1", "text", "alpha")
    val b2 = store.addBlock("page-1", "heading", "beta")
    val b3 = store.addBlock("page-1", "text", "gamma")
    check("block orders assigned sequentially", listOf(b1.order, b2.order, b3.order), listOf(0, 1, 2))
    check("blocksForPage in order", store.blocksForPage("page-1").map { it.text }, listOf("alpha", "beta", "gamma"))
    // Simulate a re-order (e.g. after a drag / import): scramble orders.
    b1.order = 20; b2.order = 5; b3.order = 10
    check("blocksForPage sorted after reorder", store.blocksForPage("page-1").map { it.text }, listOf("beta", "gamma", "alpha"))
    val other = store.addBlock("page-2", "text", "elsewhere")
    check("blocksForPage filters by page", store.blocksForPage("page-2").map { it.id }, listOf(other.id))
    check("next block on page-1 continues past max", store.addBlock("page-1", "text", "delta").order, 21)
    val directIdea = Idea(id = "i9", title = "x")
    check("idea defaults", directIdea.notes to directIdea.linkedScriptId, "" to null)

    /* ---------- Store: updateScript (v0.2) ---------- */
    val us = Store()
    us.seedIfEmpty()
    val uChan = us.channels[0].id
    val uScript = us.addScript(uChan, "Old Title", "old body", Language.BANGLA, "2026-11-20")!!
    uScript.updatedAt = 1000L
    check("updateScript returns true", us.updateScript(uScript.id, title = "New Title", body = "new body", language = Language.ENGLISH, date = "2026-12-01"), true)
    check("updateScript title applied", uScript.title, "New Title")
    check("updateScript body applied", uScript.body, "new body")
    check("updateScript language applied", uScript.language, Language.ENGLISH)
    check("updateScript date applied", uScript.date, "2026-12-01")
    check("updateScript bumps updatedAt", uScript.updatedAt > 1000L, true)
    check("updateScript null params return true", us.updateScript(uScript.id), true)
    check("updateScript null params leave fields", uScript.title to uScript.body, "New Title" to "new body")
    check("updateScript invalid date false", us.updateScript(uScript.id, title = "Should Not Apply", date = "2026-02-29"), false)
    check("updateScript invalid date changes nothing", uScript.title to uScript.date, "New Title" to "2026-12-01")
    check("updateScript missing id false", us.updateScript("no-such-id", body = "x"), false)
    check("updateScript blank date clears returns true", us.updateScript(uScript.id, date = ""), true)
    check("updateScript date cleared to null", uScript.date, null)

    /* ---------- Store: task <-> script linking (v0.2) ---------- */
    val ls = Store()
    ls.seedIfEmpty()
    val lChan = ls.channels[0].id
    val lScript = ls.addScript(lChan, "Linked Script")!!
    val lTask = ls.addTask(lChan, "Linked Task")!!
    val lTask2 = ls.addTask(lChan, "Second Task")!!
    check("taskLinkConsistent false before link", Logic.taskLinkConsistent(lTask, lScript), false)
    check("linkTaskToScript returns true", ls.linkTaskToScript(lTask.id, lScript.id), true)
    check("task side holds script id", lTask.linkedScriptIds, listOf(lScript.id))
    check("script side holds task id", lScript.linkedTaskIds, listOf(lTask.id))
    check("taskLinkConsistent true after link", Logic.taskLinkConsistent(lTask, lScript), true)
    check("duplicate link returns false", ls.linkTaskToScript(lTask.id, lScript.id), false)
    check("duplicate link adds no entries", lTask.linkedScriptIds.size to lScript.linkedTaskIds.size, 1 to 1)
    check("link missing task false", ls.linkTaskToScript("no-such-task", lScript.id), false)
    check("link missing script false", ls.linkTaskToScript(lTask.id, "no-such-script"), false)
    check("tasksForScript finds task", ls.tasksForScript(lScript.id).map { it.id }, listOf(lTask.id))
    check("scriptsForTask finds script", ls.scriptsForTask(lTask.id).map { it.id }, listOf(lScript.id))
    check("link second task to same script true", ls.linkTaskToScript(lTask2.id, lScript.id), true)
    check("script now links 2 tasks", lScript.linkedTaskIds.size, 2)
    check("unlink returns true", ls.unlinkTaskFromScript(lTask.id, lScript.id), true)
    check("unlink clears task side", lTask.linkedScriptIds, emptyList<String>())
    check("unlink clears script side for that task", lScript.linkedTaskIds, listOf(lTask2.id))
    check("unlink non-linked returns false", ls.unlinkTaskFromScript(lTask.id, lScript.id), false)
    check("unlink missing id false", ls.unlinkTaskFromScript("no-such-task", lScript.id), false)
    check("relink after unlink true", ls.linkTaskToScript(lTask.id, lScript.id), true)

    /* ---------- JSON round-trip preserves links (v0.2 pure-Kotlin codec) ---------- */
    val lIdea = ls.addIdea("Round Trip Idea", "notes body", lChan)
    val lIdeaScript = ls.startScriptFromIdea(lIdea.id)!!
    val weird = ls.addScript(lChan, "Quote \"and\" newline\nBengali \u09ac\u09be\u0982\u09b2\u09be", "body \\ slash")!!
    val restored = Store()
    restored.loadFromJsonString(ls.toJsonString())
    check("round-trip counts match", listOf(restored.channels.size, restored.scripts.size, restored.ideas.size, restored.tasks.size),
        listOf(ls.channels.size, ls.scripts.size, ls.ideas.size, ls.tasks.size))
    val rScript = restored.scriptById(lScript.id)!!
    check("round-trip script linkedTaskIds preserved", rScript.linkedTaskIds.toSet(), setOf(lTask.id, lTask2.id))
    check("round-trip task linkedScriptIds preserved", restored.taskById(lTask.id)?.linkedScriptIds, listOf(lScript.id))
    check("round-trip idea->script link preserved", restored.ideaById(lIdea.id)?.linkedScriptId, lIdeaScript.id)
    check("round-trip script->idea link preserved", restored.scriptById(lIdeaScript.id)?.linkedIdeaId, lIdea.id)
    check("round-trip special-char title preserved", restored.scriptById(weird.id)?.title, weird.title)
    check("round-trip special-char body preserved", restored.scriptById(weird.id)?.body, weird.body)
    val sizeBeforeBadLoad = restored.scripts.size
    restored.loadFromJsonString("{not json")
    check("malformed JSON leaves store untouched", restored.scripts.size, sizeBeforeBadLoad)
    // A v0.1-format file (org.json output, no linkedTaskIds/linkedScriptIds keys) still loads.
    val legacy = Store()
    legacy.loadFromJsonString(
        "{\"channels\":[{\"id\":\"c1\",\"name\":\"Legacy\",\"createdAt\":5}]," +
            "\"scripts\":[{\"id\":\"s1\",\"channelId\":\"c1\",\"title\":\"Old\",\"body\":\"\",\"language\":\"BANGLA\",\"status\":\"DRAFT\",\"date\":null,\"linkedIdeaId\":null,\"createdAt\":5,\"updatedAt\":6}]," +
            "\"ideas\":[],\"tasks\":[],\"blocks\":[]}"
    )
    check("legacy v0.1 JSON loads channel", legacy.channels.map { it.name }, listOf("Legacy"))
    check("legacy v0.1 script has empty task links", legacy.scriptById("s1")?.linkedTaskIds, emptyList<String>())

    /* ---------- Db write-through: Store-side contract (JVM) ----------
     * Db itself needs Android SQLite and cannot run on a plain JVM, so
     * what is tested here is the exact contract Db.init relies on:
     * install a sink -> every mutation emits a Record; install a sink
     * after data exists -> syncRegisteredStoresToSink backfills it;
     * uninstall -> emissions stop. On Android the sink is
     * Db.upsertRecord (see Db.init). */
    val collected = mutableListOf<Record>()
    Store.installRecordSink { r -> collected.add(r) }
    val ws = Store()
    val wChan = ws.addChannel("WT Channel")
    check("sink receives channel record on addChannel", collected.size, 1)
    check("channel record table+id", collected[0].tableName to collected[0].id, "channel" to wChan.id)
    val wScript = ws.addScript(wChan.id, "WT Script", "v1")!!
    check("sink receives script record", collected.any { it.tableName == "script" && it.id == wScript.id }, true)
    check("script record dataJson holds title", collected.last { it.id == wScript.id }.dataJson.contains("WT Script"), true)
    ws.updateScript(wScript.id, body = "v2")
    check("updateScript emits again", collected.count { it.id == wScript.id }, 2)
    check("update record dataJson holds new body", collected.last { it.id == wScript.id }.dataJson.contains("v2"), true)
    val wTask = ws.addTask(wChan.id, "WT Task")!!
    ws.linkTaskToScript(wTask.id, wScript.id)
    check("link emits task record carrying the link", collected.any { it.tableName == "task" && it.id == wTask.id && it.dataJson.contains(wScript.id) }, true)
    // Backfill: populate a store with NO sink, then install + sync (what Db.init does).
    Store.installRecordSink(null)
    val bs = Store()
    val bChan = bs.addChannel("Backfill Channel")
    val bScript = bs.addScript(bChan.id, "Backfill Script")!!
    val backfill = mutableListOf<Record>()
    Store.installRecordSink { r -> backfill.add(r) }
    Store.syncRegisteredStoresToSink()
    check("backfill emits pre-existing channel", backfill.any { it.tableName == "channel" && it.id == bChan.id }, true)
    check("backfill emits pre-existing script", backfill.any { it.tableName == "script" && it.id == bScript.id }, true)
    Store.installRecordSink(null)
    val backfillSize = backfill.size
    bs.addChannel("After Uninstall")
    check("no emissions after sink uninstalled", backfill.size, backfillSize)

    /* ================= ROUND 3 (v0.3) ================= */

    /* ---------- R3: updateScript channelId ---------- */
    val aRecs = mutableListOf<Record>()
    Store.installRecordSink { r -> aRecs.add(r) }
    val cs = Store()
    cs.seedIfEmpty()
    val cA = cs.channels[0]
    val cB = cs.addChannel("Channel B")
    val cScript = cs.addScript(cA.id, "Movable")!!
    check("updateScript channelId moves channel", cs.updateScript(cScript.id, channelId = cB.id), true)
    check("script now in channel B", cScript.channelId, cB.id)
    check("channel move emitted with new parent", aRecs.any { it.id == cScript.id && it.parentId == cB.id }, true)
    check("scriptsForChannel A empty after move", cs.scriptsForChannel(cA.id).size, 0)
    check("scriptsForChannel B has moved script", cs.scriptsForChannel(cB.id).map { it.id }, listOf(cScript.id))
    check("updateScript missing channel false", cs.updateScript(cScript.id, title = "Nope", channelId = "no-such-channel"), false)
    check("updateScript missing channel changes nothing", cScript.title to cScript.channelId, "Movable" to cB.id)
    check("updateScript null channelId leaves channel", cs.updateScript(cScript.id, title = "Renamed") && cScript.channelId == cB.id, true)
    check("updateScript empty channelId leaves channel", cs.updateScript(cScript.id, channelId = "") && cScript.channelId == cB.id, true)
    Store.installRecordSink(null)

    /* ---------- R3: rename/remove APIs + tombstones ---------- */
    val sinkRecs = mutableListOf<Record>()
    Store.installRecordSink { r -> sinkRecs.add(r) }
    val rs = Store()
    val sChan = rs.addChannel("Sink Channel")
    val sScr = rs.addScript(sChan.id, "Sink Script")!!
    val sIdea = rs.addIdea("Sink Idea", "n", sChan.id)
    val sTask = rs.addTask(sChan.id, "Sink Task")!!
    val sPage = rs.addPage("Sink Page")!!
    rs.linkTaskToScript(sTask.id, sScr.id)
    rs.linkIdeaToTask(sIdea.id, sTask.id)
    rs.linkPageToScript(sPage.id, sScr.id)
    sinkRecs.clear()
    check("renameChannel true", rs.renameChannel(sChan.id, "Renamed Channel"), true)
    check("renameChannel applied", sChan.name, "Renamed Channel")
    check("renameChannel emitted alive record", sinkRecs.any { it.id == sChan.id && it.alive && it.dataJson.contains("Renamed Channel") }, true)
    check("renameChannel missing false", rs.renameChannel("no-such", "X"), false)
    check("renameScript true", rs.renameScript(sScr.id, "Renamed Script"), true)
    check("renameScript emitted", sinkRecs.any { it.id == sScr.id && it.alive && it.dataJson.contains("Renamed Script") }, true)
    check("renameIdea true", rs.renameIdea(sIdea.id, "Renamed Idea"), true)
    check("updateIdea notes applied", rs.updateIdea(sIdea.id, notes = "new notes") && sIdea.notes == "new notes", true)
    check("updateIdea missing channel false", rs.updateIdea(sIdea.id, channelId = "no-such"), false)
    check("updateIdea empty channelId clears", rs.updateIdea(sIdea.id, channelId = "") && sIdea.channelId == null, true)
    check("renameTask true", rs.renameTask(sTask.id, "Renamed Task"), true)
    check("renamePage true", rs.renamePage(sPage.id, "Renamed Page"), true)
    check("renamePage missing false", rs.renamePage("no-such", "X"), false)
    sinkRecs.clear()
    check("removeTask true", rs.removeTask(sTask.id), true)
    check("removeTask gone", rs.taskById(sTask.id), null)
    check("removeTask tombstone emitted", sinkRecs.any { it.id == sTask.id && !it.alive && it.tableName == "task" }, true)
    check("removeTask cleaned script side", sScr.linkedTaskIds.contains(sTask.id), false)
    check("removeTask cleaned idea side", sIdea.linkedTaskIds.contains(sTask.id), false)
    check("removeTask again false", rs.removeTask(sTask.id), false)
    sinkRecs.clear()
    check("removeScript true", rs.removeScript(sScr.id), true)
    check("removeScript tombstone emitted", sinkRecs.any { it.id == sScr.id && !it.alive && it.tableName == "script" }, true)
    check("removeScript cleaned page side", sPage.linkedScriptIds.contains(sScr.id), false)
    check("removeIdea true", rs.removeIdea(sIdea.id), true)
    check("removeIdea tombstone emitted", sinkRecs.any { it.id == sIdea.id && !it.alive && it.tableName == "idea" }, true)
    check("removePage true", rs.removePage(sPage.id), true)
    check("removePage tombstone is block/page record", sinkRecs.any { it.id == sPage.id && !it.alive && it.tableName == "block" && it.dataJson.contains("\"type\":\"page\"") }, true)
    Store.installRecordSink(null)
    // updateTask validation conventions
    val us2 = Store()
    us2.seedIfEmpty()
    val u2Task = us2.addTask(us2.channels[0].id, "T")!!
    check("updateTask invalid date false", us2.updateTask(u2Task.id, dueDate = "2026-02-29"), false)
    check("updateTask valid date + clear channel", us2.updateTask(u2Task.id, dueDate = "2026-12-01", channelId = ""), true)
    check("updateTask channel cleared to global", u2Task.channelId, null)
    check("updateTask missing id false", us2.updateTask("no-such", title = "x"), false)

    /* ---------- R3: import per-key type validation (R2-N4) + ghost rows (R2-N1) ---------- */
    val iv = Store()
    iv.seedIfEmpty()
    val ivScript = iv.addScript(iv.channels[0].id, "Keep Me")!!
    check("loadFromJsonString valid returns true", iv.loadFromJsonString(iv.toJsonString()), true)
    check("import {channels:5} rejected", iv.loadFromJsonString("{\"channels\": 5}"), false)
    check("store untouched after bad-type import (channels)", iv.channels.size, 1)
    check("store untouched after bad-type import (script kept)", iv.scriptById(ivScript.id)?.title, "Keep Me")
    check("import {scripts:string} rejected", iv.loadFromJsonString("{\"scripts\": \"x\"}"), false)
    check("import {pages:object} rejected", iv.loadFromJsonString("{\"pages\": {}}"), false)
    check("import {blocks:null} rejected", iv.loadFromJsonString("{\"blocks\": null}"), false)
    check("import {tasks:bool} rejected", iv.loadFromJsonString("{\"tasks\": true}"), false)
    check("store still intact after all bad imports", iv.scripts.size, 1)
    check("malformed JSON returns false", iv.loadFromJsonString("{not json"), false)
    check("valid single-key import returns true", iv.loadFromJsonString("{\"channels\":[{\"id\":\"only\",\"name\":\"Only\",\"createdAt\":1}]}"), true)
    check("single-key import replaced channels", iv.channels.map { it.id }, listOf("only"))
    check("single-key import cleared scripts (replace-all by design)", iv.scripts.size, 0)
    // Ghost rows: loading dataset B over dataset A tombstones A's ids.
    val ghostRecs = mutableListOf<Record>()
    Store.installRecordSink { r -> ghostRecs.add(r) }
    val gA = Store()
    val gAChan = gA.addChannel("Ghost A")
    val gB = Store()
    val gBChan = gB.addChannel("Keeper B")
    ghostRecs.clear()
    check("load for ghost test returns true", gA.loadFromJsonString(gB.toJsonString()), true)
    check("ghost channel tombstoned on load", ghostRecs.any { it.id == gAChan.id && !it.alive && it.tableName == "channel" }, true)
    check("loaded channel emitted alive on load", ghostRecs.any { it.id == gBChan.id && it.alive }, true)
    Store.installRecordSink(null)

    /* ---------- R3: cascade channel delete + full restore ---------- */
    val ccRecs = mutableListOf<Record>()
    Store.installRecordSink { r -> ccRecs.add(r) }
    val cc = Store()
    val ccA = cc.addChannel("Chan A")
    val ccB = cc.addChannel("Chan B")
    val ccS1 = cc.addScript(ccA.id, "A Script")!!
    val ccS2 = cc.addScript(ccB.id, "B Script")!!
    val ccT1 = cc.addTask(ccA.id, "A Task")!!
    val ccT2 = cc.addTask(ccB.id, "B Task")!!
    val ccI1 = cc.addIdea("A Idea", "notes", ccA.id)
    val ccI2 = cc.addIdea("B Idea", "notes", ccB.id)
    val ccP = cc.addPage("Survivor Page")!!
    cc.linkTaskToScript(ccT1.id, ccS1.id)
    cc.linkTaskToScript(ccT2.id, ccS1.id)
    cc.linkTaskToScript(ccT1.id, ccS2.id)
    cc.linkIdeaToTask(ccI1.id, ccT2.id)
    cc.linkPageToScript(ccP.id, ccS1.id)
    val ccSI = cc.startScriptFromIdea(ccI1.id)!!
    ccRecs.clear()
    val snap = cc.removeChannelCascade(ccA.id)
    check("cascade returns snapshot", snap != null, true)
    check("cascade tombstones for every removed entity",
        listOf(ccA.id, ccS1.id, ccT1.id, ccI1.id, ccSI.id).all { id -> ccRecs.any { it.id == id && !it.alive } }, true)
    check("cascade removed channel", cc.channelById(ccA.id), null)
    check("cascade removed A script", cc.scriptById(ccS1.id), null)
    check("cascade removed A task", cc.taskById(ccT1.id), null)
    check("cascade removed A idea", cc.ideaById(ccI1.id), null)
    check("cascade removed idea-born script", cc.scriptById(ccSI.id), null)
    check("cascade leaves only B script", cc.scripts.map { it.id }, listOf(ccS2.id))
    check("cascade kept B channel", cc.channelById(ccB.id)?.name, "Chan B")
    check("cascade kept B task/idea", listOf(cc.taskById(ccT2.id) != null, cc.ideaById(ccI2.id) != null), listOf(true, true))
    check("survivor task link to dead script cleaned", ccT2.linkedScriptIds.contains(ccS1.id), false)
    check("survivor script link to dead task cleaned", ccS2.linkedTaskIds.contains(ccT1.id), false)
    check("survivor task link to dead idea cleaned", ccT2.linkedIdeaIds.contains(ccI1.id), false)
    check("survivor page link to dead script cleaned", ccP.linkedScriptIds.contains(ccS1.id), false)
    check("cascade missing channel null", cc.removeChannelCascade("no-such"), null)
    ccRecs.clear()
    check("restore cascade true", cc.restoreChannelCascade(snap!!), true)
    check("restore emitted alive records", listOf(ccA.id, ccS1.id, ccT1.id).all { id -> ccRecs.any { it.id == id && it.alive } }, true)
    check("restored channel at original index", cc.channels.map { it.id }, listOf(ccA.id, ccB.id))
    check("restored scripts at original indices", cc.scripts.map { it.id }, listOf(ccS1.id, ccS2.id, ccSI.id))
    check("restored tasks at original indices", cc.tasks.map { it.id }, listOf(ccT1.id, ccT2.id))
    check("restored ideas at original indices", cc.ideas.map { it.id }, listOf(ccI1.id, ccI2.id))
    check("restored internal task-script link consistent", Logic.taskLinkConsistent(cc.taskById(ccT1.id)!!, cc.scriptById(ccS1.id)!!), true)
    check("restored idea-script link consistent", Logic.ideaLinkConsistent(cc.ideaById(ccI1.id)!!, cc.scriptById(ccSI.id)!!), true)
    check("restored survivor task->script link", cc.taskById(ccT2.id)!!.linkedScriptIds.contains(ccS1.id), true)
    check("restored survivor script->task link", cc.scriptById(ccS2.id)!!.linkedTaskIds.contains(ccT1.id), true)
    check("restored survivor task->idea link", cc.taskById(ccT2.id)!!.linkedIdeaIds.contains(ccI1.id), true)
    check("restored survivor page->script link", cc.pageById(ccP.id)!!.linkedScriptIds.contains(ccS1.id), true)
    check("restore cascade twice false", cc.restoreChannelCascade(snap), false)
    check("removeChannel plain also cascades (policy)", cc.removeChannel(ccB.id) && cc.channelById(ccB.id) == null && cc.scriptById(ccS2.id) == null, true)
    Store.installRecordSink(null)

    /* ---------- R3: page tree + page cascade/restore ---------- */
    val pg = Store()
    pg.seedIfEmpty()
    val pgChan = pg.channels[0].id
    val root1 = pg.addPage("Root One")!!
    val root2 = pg.addPage("Root Two")!!
    val child = pg.addPage("Child", root1.id)!!
    val grand = pg.addPage("Grandchild", child.id)!!
    check("addPage missing parent null", pg.addPage("Nope", "no-such-parent"), null)
    check("rootPages", pg.rootPages().map { it.id }, listOf(root1.id, root2.id))
    check("childPages", pg.childPages(root1.id).map { it.id }, listOf(child.id))
    check("pageAncestors grandchild root-first", pg.pageAncestors(grand.id).map { it.title }, listOf("Root One", "Child"))
    check("descendantPageIds includes subtree", pg.descendantPageIds(root1.id), setOf(root1.id, child.id, grand.id))
    check("movePage to other root true", pg.movePage(child.id, root2.id), true)
    check("child now under root2", pg.childPages(root2.id).map { it.id }, listOf(child.id))
    check("grandchild followed its parent", pg.pageAncestors(grand.id).map { it.title }, listOf("Root Two", "Child"))
    check("movePage cycle rejected", pg.movePage(root2.id, grand.id), false)
    check("movePage self rejected", pg.movePage(root2.id, root2.id), false)
    check("movePage missing parent rejected", pg.movePage(child.id, "no-such"), false)
    check("movePage to root true", pg.movePage(child.id, null), true)
    check("child is root now", pg.rootPages().map { it.id }.contains(child.id), true)
    check("movePage no-op false", pg.movePage(child.id, null), false)
    pg.movePage(child.id, root1.id)
    val pgScript = pg.addScript(pgChan, "Page Linked Script")!!
    pg.linkPageToScript(child.id, pgScript.id)
    pg.linkPageToScript(root2.id, pgScript.id)
    pg.addBlock(root1.id, "text", "root block")
    val cb2 = pg.addBlock(child.id, "text", "child block")
    pg.addBlock(grand.id, "todo", "grand todo")
    val cbKeep = pg.addBlock(root2.id, "text", "keep me")
    val pSnap = pg.removePageCascade(child.id)
    check("page cascade returns snapshot", pSnap != null, true)
    check("page cascade removed child+grandchild", listOf(pg.pageById(child.id), pg.pageById(grand.id)), listOf(null, null))
    check("page cascade kept roots", listOf(pg.pageById(root1.id) != null, pg.pageById(root2.id) != null), listOf(true, true))
    check("page cascade removed child blocks", pg.blocksForPage(child.id).size + pg.blocksForPage(grand.id).size, 0)
    check("page cascade kept other page blocks", pg.blocksForPage(root2.id).map { it.id }, listOf(cbKeep.id))
    check("page cascade cleaned script page links", pgScript.linkedPageIds, listOf(root2.id))
    check("page cascade missing null", pg.removePageCascade("no-such"), null)
    check("restore page cascade true", pg.restorePageCascade(pSnap!!), true)
    check("restored pages present", listOf(pg.pageById(child.id) != null, pg.pageById(grand.id) != null), listOf(true, true))
    check("restored child blocks back", pg.blocksForPage(child.id).map { it.id }, listOf(cb2.id))
    check("restored grandchild block back with type", pg.blocksForPage(grand.id).map { it.type }, listOf("todo"))
    check("restored script page links both", pgScript.linkedPageIds.toSet(), setOf(child.id, root2.id))
    check("restored page-script link consistent", Logic.pageLinkConsistent(pg.pageById(child.id)!!, pgScript), true)
    check("restore page cascade twice false", pg.restorePageCascade(pSnap), false)

    /* ---------- R3: blocks — types, edit, reorder, todo/toggle ---------- */
    val bl = Store()
    val bp = bl.addPage("Block Page")!!
    val typePage = bl.addPage("Types")!!
    val allTypes = listOf("text", "heading", "bulleted_list", "numbered_list", "todo", "toggle", "quote", "code", "divider")
    check("all 9 block types accepted", allTypes.map { ty -> bl.addBlock(typePage.id, ty, "x").type == ty }.all { it }, true)
    check("isValidBlockType rejects unknown", Logic.isValidBlockType("video"), false)
    val t1 = bl.addBlock(bp.id, "text", "one")
    val t2 = bl.addBlock(bp.id, "heading", "two")
    val t3 = bl.addBlock(bp.id, "todo", "three")
    val t4 = bl.addBlock(bp.id, "toggle", "four")
    val threw = try { bl.addBlock(bp.id, "video", "x"); false } catch (e: IllegalArgumentException) { true }
    check("addBlock unknown type throws", threw, true)
    check("failed addBlock added nothing", bl.blocksForPage(bp.id).size, 4)
    check("updateBlockText true", bl.updateBlockText(t1.id, "one edited"), true)
    check("updateBlockText applied", t1.text, "one edited")
    check("updateBlockType valid true", bl.updateBlockType(t1.id, "quote"), true)
    check("updateBlockType invalid false", bl.updateBlockType(t1.id, "video"), false)
    check("updateBlockType invalid leaves type", t1.type, "quote")
    check("updateBlock missing false", bl.updateBlock("no-such", text = "x"), false)
    check("setBlockChecked true", bl.setBlockChecked(t3.id, true), true)
    check("todo checked applied", t3.checked, true)
    check("setBlockCollapsed true", bl.setBlockCollapsed(t4.id, true), true)
    check("toggle collapsed applied", t4.collapsed, true)
    check("setBlockChecked missing false", bl.setBlockChecked("no-such", true), false)
    check("moveBlock to index 2 true", bl.moveBlock(t1.id, 2), true)
    check("sequence after move", bl.blocksForPage(bp.id).map { it.id }, listOf(t2.id, t3.id, t1.id, t4.id))
    check("orders normalised after move", bl.blocksForPage(bp.id).map { it.order }, listOf(0, 1, 2, 3))
    check("moveBlockUp true", bl.moveBlockUp(t1.id), true)
    check("sequence after move up", bl.blocksForPage(bp.id).map { it.id }, listOf(t2.id, t1.id, t3.id, t4.id))
    check("moveBlockDown at last false", bl.moveBlockDown(t4.id), false)
    check("moveBlockUp at first false", bl.moveBlockUp(t2.id), false)
    check("moveBlock missing false", bl.moveBlock("no-such", 0), false)
    check("removeBlock true", bl.removeBlock(t3.id), true)
    check("orders normalised after remove", bl.blocksForPage(bp.id).map { it.order }, listOf(0, 1, 2))
    check("removeBlock missing false", bl.removeBlock("no-such"), false)
    t2.order = 50; t4.order = 99
    check("moveBlock no-op still normalises orders", bl.moveBlock(t4.id, 2) && bl.blocksForPage(bp.id).map { it.order } == listOf(0, 1, 2), true)
    check("sequence after scramble + normalise", bl.blocksForPage(bp.id).map { it.id }, listOf(t1.id, t2.id, t4.id))

    /* ---------- R3: page<->script and idea<->task linking ---------- */
    val lk = Store()
    lk.seedIfEmpty()
    val lkChan = lk.channels[0].id
    val lkScript = lk.addScript(lkChan, "Link Script")!!
    val lkPage = lk.addPage("Link Page")!!
    val lkIdea = lk.addIdea("Link Idea", "", lkChan)
    val lkTask = lk.addTask(lkChan, "Link Task")!!
    check("pageLinkConsistent false before", Logic.pageLinkConsistent(lkPage, lkScript), false)
    check("linkPageToScript true", lk.linkPageToScript(lkPage.id, lkScript.id), true)
    check("page side holds script", lkPage.linkedScriptIds, listOf(lkScript.id))
    check("script side holds page", lkScript.linkedPageIds, listOf(lkPage.id))
    check("pageLinkConsistent true", Logic.pageLinkConsistent(lkPage, lkScript), true)
    check("duplicate page link false", lk.linkPageToScript(lkPage.id, lkScript.id), false)
    check("link page missing false", lk.linkPageToScript("no-such", lkScript.id), false)
    check("scriptsForPage", lk.scriptsForPage(lkPage.id).map { it.id }, listOf(lkScript.id))
    check("pagesForScript", lk.pagesForScript(lkScript.id).map { it.id }, listOf(lkPage.id))
    check("unlinkPageFromScript true", lk.unlinkPageFromScript(lkPage.id, lkScript.id), true)
    check("page link cleared both sides", lkPage.linkedScriptIds.isEmpty() && lkScript.linkedPageIds.isEmpty(), true)
    check("unlink non-linked page false", lk.unlinkPageFromScript(lkPage.id, lkScript.id), false)
    check("relink page true", lk.linkPageToScript(lkPage.id, lkScript.id), true)
    check("ideaTaskLinkConsistent false before", Logic.ideaTaskLinkConsistent(lkIdea, lkTask), false)
    check("linkIdeaToTask true", lk.linkIdeaToTask(lkIdea.id, lkTask.id), true)
    check("idea side holds task", lkIdea.linkedTaskIds, listOf(lkTask.id))
    check("task side holds idea", lkTask.linkedIdeaIds, listOf(lkIdea.id))
    check("ideaTaskLinkConsistent true", Logic.ideaTaskLinkConsistent(lkIdea, lkTask), true)
    check("duplicate idea-task link false", lk.linkIdeaToTask(lkIdea.id, lkTask.id), false)
    check("link idea-task missing false", lk.linkIdeaToTask("no-such", lkTask.id), false)
    check("tasksForIdea", lk.tasksForIdea(lkIdea.id).map { it.id }, listOf(lkTask.id))
    check("ideasForTask", lk.ideasForTask(lkTask.id).map { it.id }, listOf(lkIdea.id))
    check("unlinkIdeaFromTask true", lk.unlinkIdeaFromTask(lkIdea.id, lkTask.id), true)
    check("idea-task cleared both sides", lkIdea.linkedTaskIds.isEmpty() && lkTask.linkedIdeaIds.isEmpty(), true)
    check("relink idea-task true", lk.linkIdeaToTask(lkIdea.id, lkTask.id), true)

    /* ---------- R3: JSON round-trip + v0.2 backward compatibility ---------- */
    val rtTodo = lk.addBlock(lkPage.id, "todo", "rt todo")
    lk.setBlockChecked(rtTodo.id, true)
    val rtTog = lk.addBlock(lkPage.id, "toggle", "rt toggle")
    lk.setBlockCollapsed(rtTog.id, true)
    val lkChild = lk.addPage("Child Page", lkPage.id)!!
    val rt = Store()
    check("R3 round-trip load returns true", rt.loadFromJsonString(lk.toJsonString()), true)
    check("round-trip pages preserved", rt.pages.size, lk.pages.size)
    check("round-trip child parent preserved", rt.pageById(lkChild.id)?.parentPageId, lkPage.id)
    check("round-trip page-script link preserved", rt.pageById(lkPage.id)?.linkedScriptIds, listOf(lkScript.id))
    check("round-trip script-page link preserved", rt.scriptById(lkScript.id)?.linkedPageIds, listOf(lkPage.id))
    check("round-trip idea-task link preserved", rt.ideaById(lkIdea.id)?.linkedTaskIds, listOf(lkTask.id))
    check("round-trip task-idea link preserved", rt.taskById(lkTask.id)?.linkedIdeaIds, listOf(lkIdea.id))
    check("round-trip todo checked preserved", rt.blocksForPage(lkPage.id).first { it.id == rtTodo.id }.checked, true)
    check("round-trip toggle collapsed preserved", rt.blocksForPage(lkPage.id).first { it.id == rtTog.id }.collapsed, true)
    // A v0.2-format file: no "pages" key, no linkedPageIds / linkedTaskIds /
    // linkedIdeaIds keys, blocks without checked/collapsed — must load with defaults.
    val v02 = Store()
    check("v0.2-format JSON loads true", v02.loadFromJsonString(
        "{\"channels\":[{\"id\":\"c1\",\"name\":\"Legacy\",\"createdAt\":5}]," +
            "\"scripts\":[{\"id\":\"s1\",\"channelId\":\"c1\",\"title\":\"Old\",\"body\":\"\",\"language\":\"BANGLA\",\"status\":\"DRAFT\",\"date\":null,\"linkedIdeaId\":null,\"linkedTaskIds\":[],\"createdAt\":5,\"updatedAt\":6}]," +
            "\"ideas\":[{\"id\":\"i1\",\"title\":\"Old Idea\",\"notes\":\"\",\"channelId\":\"c1\",\"linkedScriptId\":null,\"createdAt\":5}]," +
            "\"tasks\":[{\"id\":\"t1\",\"channelId\":\"c1\",\"title\":\"Old Task\",\"dueDate\":null,\"done\":false,\"linkedScriptIds\":[],\"createdAt\":5}]," +
            "\"blocks\":[{\"id\":\"b1\",\"pageId\":\"p1\",\"type\":\"todo\",\"text\":\"old todo\",\"order\":0,\"parentBlockId\":null}]}"
    ), true)
    check("v0.2 load has no pages", v02.pages.size, 0)
    check("v0.2 script linkedPageIds defaults empty", v02.scriptById("s1")?.linkedPageIds, emptyList<String>())
    check("v0.2 idea linkedTaskIds defaults empty", v02.ideaById("i1")?.linkedTaskIds, emptyList<String>())
    check("v0.2 task linkedIdeaIds defaults empty", v02.taskById("t1")?.linkedIdeaIds, emptyList<String>())
    check("v0.2 block checked/collapsed default false", v02.blocks.first().checked to v02.blocks.first().collapsed, false to false)

    /* ================= ROUND 4 (v0.4) ================= */

    /* ---------- R4: sortScripts — every key, asc/desc, nulls-last, stability ---------- */
    val srt = listOf(
        Script(id = "s1", channelId = "c", title = "banana", language = Language.ENGLISH, status = ScriptStatus.FINAL, date = "2026-03-01", updatedAt = 300),
        Script(id = "s2", channelId = "c", title = "Apple", language = Language.BANGLA, status = ScriptStatus.DRAFT, date = null, updatedAt = 100),
        Script(id = "s3", channelId = "c", title = "cherry", language = Language.BANGLA, status = ScriptStatus.DRAFT, date = "2026-01-15", updatedAt = 200),
        Script(id = "s4", channelId = "c", title = "apple pie", language = Language.ENGLISH, status = ScriptStatus.FINAL, date = "", updatedAt = 200),
        Script(id = "s5", channelId = "c", title = "Banana Split", language = Language.ENGLISH, status = ScriptStatus.DRAFT, date = "2026-02-01", updatedAt = 50)
    )
    check("sortScripts TITLE asc case-insensitive", Logic.sortScripts(srt, ScriptSort.TITLE).map { it.id }, listOf("s2", "s4", "s1", "s5", "s3"))
    check("sortScripts TITLE desc", Logic.sortScripts(srt, ScriptSort.TITLE, ascending = false).map { it.id }, listOf("s3", "s5", "s1", "s4", "s2"))
    check("sortScripts TITLE default is ascending", Logic.sortScripts(srt, ScriptSort.TITLE).map { it.id }, Logic.sortScripts(srt, ScriptSort.TITLE, true).map { it.id })
    check("sortScripts LANGUAGE asc (BANGLA first)", Logic.sortScripts(srt, ScriptSort.LANGUAGE).map { it.id }, listOf("s2", "s3", "s1", "s4", "s5"))
    check("sortScripts LANGUAGE desc", Logic.sortScripts(srt, ScriptSort.LANGUAGE, false).map { it.id }, listOf("s1", "s4", "s5", "s2", "s3"))
    check("sortScripts STATUS asc (DRAFT first)", Logic.sortScripts(srt, ScriptSort.STATUS).map { it.id }, listOf("s2", "s3", "s5", "s1", "s4"))
    check("sortScripts STATUS desc (FINAL first)", Logic.sortScripts(srt, ScriptSort.STATUS, false).map { it.id }, listOf("s1", "s4", "s2", "s3", "s5"))
    check("sortScripts DATE asc nulls last", Logic.sortScripts(srt, ScriptSort.DATE).map { it.id }, listOf("s3", "s5", "s1", "s2", "s4"))
    check("sortScripts DATE desc nulls STILL last", Logic.sortScripts(srt, ScriptSort.DATE, false).map { it.id }, listOf("s1", "s5", "s3", "s2", "s4"))
    check("sortScripts UPDATED asc ties stable", Logic.sortScripts(srt, ScriptSort.UPDATED).map { it.id }, listOf("s5", "s2", "s3", "s4", "s1"))
    check("sortScripts UPDATED desc ties stable", Logic.sortScripts(srt, ScriptSort.UPDATED, false).map { it.id }, listOf("s1", "s3", "s4", "s2", "s5"))
    check("sortScripts does not mutate input", srt.map { it.id }, listOf("s1", "s2", "s3", "s4", "s5"))

    /* ---------- R4: sortTasks — every key ---------- */
    val tsk = listOf(
        GTask(id = "t1", title = "write", done = true, dueDate = "2026-05-01"),
        GTask(id = "t2", title = "Edit", done = false, dueDate = null),
        GTask(id = "t3", title = "film", done = false, dueDate = "2026-04-01"),
        GTask(id = "t4", title = "upload", done = true, dueDate = "")
    )
    check("sortTasks TITLE asc case-insensitive", Logic.sortTasks(tsk, TaskSort.TITLE).map { it.id }, listOf("t2", "t3", "t4", "t1"))
    check("sortTasks TITLE desc", Logic.sortTasks(tsk, TaskSort.TITLE, false).map { it.id }, listOf("t1", "t4", "t3", "t2"))
    check("sortTasks DONE asc open first", Logic.sortTasks(tsk, TaskSort.DONE).map { it.id }, listOf("t2", "t3", "t1", "t4"))
    check("sortTasks DONE desc done first", Logic.sortTasks(tsk, TaskSort.DONE, false).map { it.id }, listOf("t1", "t4", "t2", "t3"))
    check("sortTasks DATE asc nulls last", Logic.sortTasks(tsk, TaskSort.DATE).map { it.id }, listOf("t3", "t1", "t2", "t4"))
    check("sortTasks DATE desc nulls STILL last", Logic.sortTasks(tsk, TaskSort.DATE, false).map { it.id }, listOf("t1", "t3", "t2", "t4"))

    /* ---------- R4: groupScriptsByStatus ---------- */
    val grouped = Logic.groupScriptsByStatus(srt)
    check("group keys both present in order", grouped.keys.toList(), listOf(ScriptStatus.DRAFT, ScriptStatus.FINAL))
    check("group DRAFT preserves input order", grouped[ScriptStatus.DRAFT]?.map { it.id }, listOf("s2", "s3", "s5"))
    check("group FINAL preserves input order", grouped[ScriptStatus.FINAL]?.map { it.id }, listOf("s1", "s4"))
    val groupedEmpty = Logic.groupScriptsByStatus(emptyList())
    check("group empty input still has both keys", groupedEmpty.keys.toList(), listOf(ScriptStatus.DRAFT, ScriptStatus.FINAL))
    check("group empty input groups empty", groupedEmpty.values.all { it.isEmpty() }, true)
    val groupedDraftsOnly = Logic.groupScriptsByStatus(listOf(srt[1], srt[2]))
    check("group drafts-only FINAL key present empty", groupedDraftsOnly[ScriptStatus.FINAL], emptyList<Script>())

    /* ---------- R4: addBlock parent validation + childBlocks ---------- */
    val cb = Store()
    val cbPage = cb.addPage("Toggle Page")!!
    val otherPage = cb.addPage("Other Page")!!
    val top1 = cb.addBlock(cbPage.id, "text", "top one")
    val tog = cb.addBlock(cbPage.id, "toggle", "Toggle head")
    val top2 = cb.addBlock(cbPage.id, "text", "top two")
    val kid1 = cb.addBlock(cbPage.id, "text", "kid one", parentBlockId = tog.id)
    val kid2 = cb.addBlock(cbPage.id, "todo", "kid two", parentBlockId = tog.id)
    check("child block parentBlockId set", kid1.parentBlockId, tog.id)
    check("child block order appends at page max+1", listOf(kid1.order, kid2.order), listOf(3, 4))
    check("top-level block parent still null", top1.parentBlockId, null)
    check("childBlocks returns direct children in order", cb.childBlocks(tog.id).map { it.id }, listOf(kid1.id, kid2.id))
    check("childBlocks leaf empty", cb.childBlocks(top1.id), emptyList<Block>())
    check("childBlocks missing block empty", cb.childBlocks("no-such"), emptyList<Block>())
    val sizeBeforeBadParent = cb.blocks.size
    val threwMissingParent = try { cb.addBlock(cbPage.id, "text", "x", parentBlockId = "no-such-parent"); false } catch (e: IllegalArgumentException) { true }
    check("addBlock missing parent throws", threwMissingParent, true)
    val foreignBlock = cb.addBlock(otherPage.id, "text", "foreign")
    val threwForeignParent = try { cb.addBlock(cbPage.id, "text", "x", parentBlockId = foreignBlock.id); false } catch (e: IllegalArgumentException) { true }
    check("addBlock parent on other page throws", threwForeignParent, true)
    check("failed child adds added nothing on page", cb.blocksForPage(cbPage.id).size, 5)
    check("blocks size only grew by foreign block", cb.blocks.size, sizeBeforeBadParent + 1)

    /* ---------- R4: sibling-scoped move ----------
     * Page order now: [top1, tog, top2, kid1, kid2]; sibling groups:
     * top-level [top1, tog, top2], toggle children [kid1, kid2]. */
    check("sibling move: top2 up swaps with toggle, not into kids", cb.moveBlockUp(top2.id), true)
    check("full order after top2 up", cb.blocksForPage(cbPage.id).map { it.id }, listOf(top1.id, top2.id, tog.id, kid1.id, kid2.id))
    check("orders normalised after sibling move", cb.blocksForPage(cbPage.id).map { it.order }, listOf(0, 1, 2, 3, 4))
    check("kids untouched by top-level move", cb.childBlocks(tog.id).map { it.id }, listOf(kid1.id, kid2.id))
    check("first child cannot move up (sibling scope)", cb.moveBlockUp(kid1.id), false)
    check("kid2 up swaps within children only", cb.moveBlockUp(kid2.id), true)
    check("full order after kid2 up", cb.blocksForPage(cbPage.id).map { it.id }, listOf(top1.id, top2.id, tog.id, kid2.id, kid1.id))
    check("childBlocks reflects kid swap", cb.childBlocks(tog.id).map { it.id }, listOf(kid2.id, kid1.id))
    check("top-level relative order unchanged by kid move", cb.blocksForPage(cbPage.id).filter { it.parentBlockId == null }.map { it.id }, listOf(top1.id, top2.id, tog.id))
    check("last child cannot move down (sibling scope)", cb.moveBlockDown(kid1.id), false)
    check("moveBlock child to sibling index 0", cb.moveBlock(kid1.id, 0), true)
    check("children back in original order", cb.childBlocks(tog.id).map { it.id }, listOf(kid1.id, kid2.id))
    check("moveBlock top-level toggle to sibling index 0", cb.moveBlock(tog.id, 0), true)
    check("full order after toggle to front", cb.blocksForPage(cbPage.id).map { it.id }, listOf(tog.id, top1.id, top2.id, kid1.id, kid2.id))
    check("kids still grouped after toggle move", cb.childBlocks(tog.id).map { it.id }, listOf(kid1.id, kid2.id))
    check("moveBlockDown last top-level sibling false", cb.moveBlockDown(top2.id), false)
    check("moveBlock missing still false (R4)", cb.moveBlock("no-such", 0), false)

    /* ---------- R4: linkIdeaToScript / unlinkIdeaFromScript ---------- */
    val liRecs = mutableListOf<Record>()
    Store.installRecordSink { r -> liRecs.add(r) }
    val li = Store()
    li.seedIfEmpty()
    val liChan = li.channels[0].id
    val liIdeaA = li.addIdea("Idea A", "", liChan)
    val liIdeaB = li.addIdea("Idea B", "", liChan)
    val liScrA = li.addScript(liChan, "Script A")!!
    val liScrB = li.addScript(liChan, "Script B")!!
    liRecs.clear()
    check("linkIdeaToScript true", li.linkIdeaToScript(liIdeaA.id, liScrA.id), true)
    check("link sets idea side", liIdeaA.linkedScriptId, liScrA.id)
    check("link sets script side", liScrA.linkedIdeaId, liIdeaA.id)
    check("link consistent", Logic.ideaLinkConsistent(liIdeaA, liScrA), true)
    check("link emits idea record", liRecs.any { it.id == liIdeaA.id && it.alive && it.tableName == "idea" }, true)
    check("link emits script record", liRecs.any { it.id == liScrA.id && it.alive && it.tableName == "script" }, true)
    check("link already-linked pair idempotent TRUE (documented choice)", li.linkIdeaToScript(liIdeaA.id, liScrA.id), true)
    check("idempotent link changes nothing", liIdeaA.linkedScriptId to liScrA.linkedIdeaId, liScrA.id to liIdeaA.id)
    check("link conflict: idea already taken false", li.linkIdeaToScript(liIdeaA.id, liScrB.id), false)
    check("conflict left script B unlinked", liScrB.linkedIdeaId, null)
    check("link conflict: script already taken false", li.linkIdeaToScript(liIdeaB.id, liScrA.id), false)
    check("conflict left idea B unlinked", liIdeaB.linkedScriptId, null)
    check("link missing idea false", li.linkIdeaToScript("no-such", liScrB.id), false)
    check("link missing script false", li.linkIdeaToScript(liIdeaB.id, "no-such"), false)
    liRecs.clear()
    check("unlinkIdeaFromScript true", li.unlinkIdeaFromScript(liIdeaA.id, liScrA.id), true)
    check("unlink clears both sides", liIdeaA.linkedScriptId to liScrA.linkedIdeaId, null to null)
    check("unlink emits both entities", liRecs.any { it.id == liIdeaA.id } && liRecs.any { it.id == liScrA.id }, true)
    check("unlink non-linked false", li.unlinkIdeaFromScript(liIdeaA.id, liScrA.id), false)
    check("unlink missing id false", li.unlinkIdeaFromScript("no-such", liScrA.id), false)
    // One-way repair: idea points at script, script side empty.
    liIdeaB.linkedScriptId = liScrB.id
    check("one-way link repaired by linkIdeaToScript", li.linkIdeaToScript(liIdeaB.id, liScrB.id), true)
    check("repair set script side", liScrB.linkedIdeaId, liIdeaB.id)
    check("repaired link consistent", Logic.ideaLinkConsistent(liIdeaB, liScrB), true)
    // One-way unlink: only the script side names the pair.
    val liIdeaC = li.addIdea("Idea C", "", liChan)
    val liScrC = li.addScript(liChan, "Script C")!!
    liScrC.linkedIdeaId = liIdeaC.id
    check("one-way unlink clears naming side", li.unlinkIdeaFromScript(liIdeaC.id, liScrC.id), true)
    check("one-way unlink cleared script side", liScrC.linkedIdeaId, null)
    // startScriptFromIdea link conflicts with a manual link attempt.
    val liIdeaD = li.addIdea("Idea D", "", liChan)
    val liScrD = li.startScriptFromIdea(liIdeaD.id)!!
    check("idea taken by startScriptFromIdea blocks manual link", li.linkIdeaToScript(liIdeaD.id, liScrA.id), false)
    check("startScript link intact after conflict", Logic.ideaLinkConsistent(liIdeaD, liScrD), true)
    Store.installRecordSink(null)

    /* ---------- R4: templates ---------- */
    val tm = Store()
    val tpPage = tm.addPage("Video Page")!!
    val tbHead = tm.addBlock(tpPage.id, "heading", "Hook")
    val tbTog = tm.addBlock(tpPage.id, "toggle", "Checklist")
    tm.setBlockCollapsed(tbTog.id, true)
    val tbKid = tm.addBlock(tpPage.id, "todo", "Shoot B-roll", checked = true, parentBlockId = tbTog.id)
    tm.addBlock(tpPage.id, "text", "Outro notes")
    val tmpl = tm.savePageAsTemplate(tpPage.id, "Video Template")
    check("savePageAsTemplate returns template", tmpl != null, true)
    check("template snapshot block count", tmpl!!.blocks.size, 4)
    check("template snapshot texts in page order", tmpl.blocks.map { it.text }, listOf("Hook", "Checklist", "Shoot B-roll", "Outro notes"))
    check("template snapshot types", tmpl.blocks.map { it.type }, listOf("heading", "toggle", "todo", "text"))
    check("template kid parentIndex points at toggle index", tmpl.blocks[2].parentIndex, 1)
    check("template top-level parentIndex null", tmpl.blocks[0].parentIndex to tmpl.blocks[3].parentIndex, null to null)
    check("template kid checked snapshotted", tmpl.blocks[2].checked, true)
    check("template toggle collapsed snapshotted", tmpl.blocks[1].collapsed, true)
    check("save template missing page null", tm.savePageAsTemplate("no-such", "X"), null)
    check("save template blank name null", tm.savePageAsTemplate(tpPage.id, "  "), null)
    check("failed saves added no templates", tm.templates.size, 1)
    check("templateById finds template", tm.templateById(tmpl.id)?.name, "Video Template")
    check("templateById missing/null", tm.templateById("no-such") to tm.templateById(null), null to null)
    tm.updateBlockText(tbHead.id, "Hook CHANGED")
    check("template snapshot independent of later page edits", tmpl.blocks[0].text, "Hook")
    val fromT = tm.createPageFromTemplate(tmpl.id)
    check("createPageFromTemplate returns page", fromT != null, true)
    check("created page title defaults to template name", fromT!!.title, "Video Template")
    check("created page is a new page", fromT.id != tpPage.id, true)
    val newBlocks = tm.blocksForPage(fromT.id)
    check("created page block texts reproduced", newBlocks.map { it.text }, listOf("Hook", "Checklist", "Shoot B-roll", "Outro notes"))
    check("created page block types reproduced", newBlocks.map { it.type }, listOf("heading", "toggle", "todo", "text"))
    check("created blocks have fresh ids", newBlocks.none { nb -> listOf(tbHead.id, tbTog.id, tbKid.id).contains(nb.id) }, true)
    check("created kid checked preserved", newBlocks[2].checked, true)
    check("created toggle collapsed preserved", newBlocks[1].collapsed, true)
    check("created kid parent is the NEW toggle block", newBlocks[2].parentBlockId, newBlocks[1].id)
    check("created structure via childBlocks", tm.childBlocks(newBlocks[1].id).map { it.id }, listOf(newBlocks[2].id))
    val fromT2 = tm.createPageFromTemplate(tmpl.id, "Custom Title")
    check("create with explicit title", fromT2?.title, "Custom Title")
    val fromT3 = tm.createPageFromTemplate(tmpl.id, "   ")
    check("create with blank title falls back to template name", fromT3?.title, "Video Template")
    check("create from missing template null", tm.createPageFromTemplate("no-such"), null)
    val tmpl2 = tm.savePageAsTemplate(tpPage.id, "Keep Template")!!
    check("renameTemplate true", tm.renameTemplate(tmpl.id, "Renamed Template"), true)
    check("renameTemplate applied", tmpl.name, "Renamed Template")
    check("renameTemplate blank false", tm.renameTemplate(tmpl.id, " "), false)
    check("renameTemplate missing false", tm.renameTemplate("no-such", "X"), false)
    check("removeTemplate true", tm.removeTemplate(tmpl.id), true)
    check("removed template gone", tm.templateById(tmpl.id), null)
    check("removeTemplate again false", tm.removeTemplate(tmpl.id), false)
    // JSON round-trip preserves templates (incl. parent mapping).
    val tmRt = Store()
    check("template round-trip load true", tmRt.loadFromJsonString(tm.toJsonString()), true)
    check("round-trip template count", tmRt.templates.size, tm.templates.size)
    val rtTmpl = tmRt.templateById(tmpl2.id)!!
    check("round-trip template blocks preserved", rtTmpl.blocks.map { it.text }, tmpl2.blocks.map { it.text })
    check("round-trip template parentIndex preserved", rtTmpl.blocks.map { it.parentIndex }, tmpl2.blocks.map { it.parentIndex })
    val rtPage = tmRt.createPageFromTemplate(rtTmpl.id)
    check("create from round-tripped template works", rtPage != null && tmRt.blocksForPage(rtPage.id).size == 4, true)
    val rtKid = tmRt.blocksForPage(rtPage!!.id)[2]
    check("round-tripped template parent structure reproduced", rtKid.parentBlockId, tmRt.blocksForPage(rtPage.id)[1].id)
    // v0.3-format backward compat: no templates/trash keys at all.
    val v03 = Store()
    check("v0.3-format JSON (no templates/trash) loads true", v03.loadFromJsonString(
        "{\"channels\":[{\"id\":\"c1\",\"name\":\"Legacy\",\"createdAt\":5}],\"scripts\":[],\"ideas\":[],\"tasks\":[]," +
            "\"pages\":[{\"id\":\"p1\",\"title\":\"Old Page\",\"parentPageId\":null,\"linkedScriptIds\":[],\"createdAt\":5,\"updatedAt\":6}]," +
            "\"blocks\":[{\"id\":\"b1\",\"pageId\":\"p1\",\"type\":\"text\",\"text\":\"old\",\"order\":0,\"parentBlockId\":null,\"checked\":false,\"collapsed\":false}]}"
    ), true)
    check("v0.3 load templates empty", v03.templates.size, 0)
    check("v0.3 load trash empty", v03.trash.size, 0)
    // Wrong-typed templates/trash keys reject the load untouched.
    val tv = Store()
    tv.seedIfEmpty()
    val tvPage = tv.addPage("Guard Page")!!
    tv.savePageAsTemplate(tvPage.id, "Guard Template")
    check("import {templates:5} rejected", tv.loadFromJsonString("{\"templates\": 5}"), false)
    check("templates untouched after bad-type import", tv.templates.size, 1)
    check("channels untouched after bad-type import", tv.channels.size, 1)
    check("import {trash:string} rejected", tv.loadFromJsonString("{\"trash\": \"x\"}"), false)
    check("trash untouched after bad-type import", tv.trash.size, 0)

    /* ---------- R4: trash — singles ---------- */
    val tr = Store()
    tr.seedIfEmpty()
    val trChan = tr.channels[0].id
    val ts1 = tr.addScript(trChan, "Trash Script One")!!
    val ts2 = tr.addScript(trChan, "Trash Script Two")!!
    val ts3 = tr.addScript(trChan, "Trash Script Three")!!
    check("plain removeScript creates NO trash entry", tr.removeScript(ts3.id) && tr.trash.isEmpty(), true)
    val se = tr.deleteScriptToTrash(ts2.id)
    check("deleteScriptToTrash returns entry", se != null, true)
    check("script trash kind", se!!.kind, "script")
    check("script trash label is title", se.label, "Trash Script Two")
    check("script actually removed by ToTrash", tr.scriptById(ts2.id), null)
    check("trash holds the entry", tr.trashById(se.id)?.kind, "script")
    check("deleteScriptToTrash missing null", tr.deleteScriptToTrash("no-such"), null)
    check("restoreFromTrash script true", tr.restoreFromTrash(se.id), true)
    check("restored script back at original index", tr.scripts.map { it.id }, listOf(ts1.id, ts2.id))
    check("restored script title intact", tr.scriptById(ts2.id)?.title, "Trash Script Two")
    check("entry removed after successful restore", tr.trashById(se.id), null)
    check("restoreFromTrash missing entry false", tr.restoreFromTrash("no-such-entry"), false)
    // Collision: a same-id entity now exists -> restore fails, entry kept.
    val se2 = tr.deleteScriptToTrash(ts1.id)!!
    check("collision setup: impostor restored", tr.restoreScript(Script(id = ts1.id, channelId = trChan, title = "Impostor"), 0), true)
    check("restoreFromTrash collision false", tr.restoreFromTrash(se2.id), false)
    check("collision keeps the trash entry", tr.trashById(se2.id) != null, true)
    check("impostor removed for cleanup (no trash entry)", tr.removeScript(ts1.id) && tr.trash.size == 1, true)
    check("restore succeeds once collision cleared", tr.restoreFromTrash(se2.id), true)
    check("restored original (not impostor) title", tr.scriptById(ts1.id)?.title, "Trash Script One")
    // Idea + task singles.
    val ti1 = tr.addIdea("Trash Idea", "n", trChan)
    val ti2 = tr.addIdea("Other Idea", "n", trChan)
    val ie = tr.deleteIdeaToTrash(ti1.id)!!
    check("idea trash kind/label", ie.kind to ie.label, "idea" to "Trash Idea")
    check("restore idea back at index", tr.restoreFromTrash(ie.id) && tr.ideas.map { it.id } == listOf(ti1.id, ti2.id), true)
    val tt1 = tr.addTask(trChan, "Trash Task", "2026-12-05")!!
    val tt2 = tr.addTask(trChan, "Other Task")!!
    val te = tr.deleteTaskToTrash(tt1.id)!!
    check("task trash kind/label", te.kind to te.label, "task" to "Trash Task")
    check("restore task at index with fields", tr.restoreFromTrash(te.id) && tr.tasks.map { it.id } == listOf(tt1.id, tt2.id) && tr.taskById(tt1.id)?.dueDate == "2026-12-05", true)
    // Block single (+ divider label fallback).
    val tbPage = tr.addPage("Trash Block Page")!!
    val bb1 = tr.addBlock(tbPage.id, "text", "keep block")
    val bb2 = tr.addBlock(tbPage.id, "todo", "trash block")
    val be = tr.deleteBlockToTrash(bb2.id)!!
    check("block trash kind/label", be.kind to be.label, "block" to "trash block")
    check("block removed by ToTrash", tr.blockById(bb2.id), null)
    check("restore block true", tr.restoreFromTrash(be.id), true)
    check("restored block fields intact", tr.blockById(bb2.id)?.text to tr.blockById(bb2.id)?.type, "trash block" to "todo")
    check("kept block untouched by block trash cycle", tr.blockById(bb1.id)?.text, "keep block")
    val bd = tr.addBlock(tbPage.id, "divider", "")
    val de = tr.deleteBlockToTrash(bd.id)!!
    check("divider trash label falls back to type", de.label, "divider")
    check("divider entry cleaned via permanent delete", tr.permanentlyDeleteTrash(de.id), true)

    /* ---------- R4: trash — channel cascade ---------- */
    val tc = Store()
    val tcA = tc.addChannel("Trash Chan A")
    val tcB = tc.addChannel("Trash Chan B")
    val tcSA = tc.addScript(tcA.id, "A Script")!!
    val tcTA = tc.addTask(tcA.id, "A Task")!!
    val tcIA = tc.addIdea("A Idea", "n", tcA.id)
    val tcSB = tc.addScript(tcB.id, "B Script")!!
    val tcTB = tc.addTask(tcB.id, "B Task")!!
    val tcPage = tc.addPage("Survivor Page")!!
    tc.linkTaskToScript(tcTB.id, tcSA.id)
    tc.linkPageToScript(tcPage.id, tcSA.id)
    val ce = tc.deleteChannelToTrash(tcA.id)
    check("deleteChannelToTrash returns entry", ce != null, true)
    check("channel trash kind/label", ce!!.kind to ce.label, "channel" to "Trash Chan A")
    check("channel cascade removed contents", listOf(tc.channelById(tcA.id), tc.scriptById(tcSA.id), tc.taskById(tcTA.id), tc.ideaById(tcIA.id)), listOf(null, null, null, null))
    check("channel trash delete cleaned survivor task link", tcTB.linkedScriptIds.contains(tcSA.id), false)
    check("channel trash delete cleaned survivor page link", tcPage.linkedScriptIds.contains(tcSA.id), false)
    check("deleteChannelToTrash missing null", tc.deleteChannelToTrash("no-such"), null)
    check("restore channel cascade from trash true", tc.restoreFromTrash(ce.id), true)
    check("restored channel at original index", tc.channels.map { it.id }, listOf(tcA.id, tcB.id))
    check("restored cascade contents back", listOf(tc.scriptById(tcSA.id) != null, tc.taskById(tcTA.id) != null, tc.ideaById(tcIA.id) != null), listOf(true, true, true))
    check("restored survivor task link from trash", tc.taskById(tcTB.id)!!.linkedScriptIds.contains(tcSA.id), true)
    check("restored survivor page link from trash", tc.pageById(tcPage.id)!!.linkedScriptIds.contains(tcSA.id), true)
    check("channel entry removed after restore", tc.trashById(ce.id), null)

    /* ---------- R4: trash — page cascade ---------- */
    val tp = Store()
    tp.seedIfEmpty()
    val tpChan = tp.channels[0].id
    val rp = tp.addPage("Trash Root")!!
    val cp = tp.addPage("Trash Child", rp.id)!!
    val rb = tp.addBlock(rp.id, "text", "root block")
    val cbk = tp.addBlock(cp.id, "text", "child block")
    val ps = tp.addScript(tpChan, "Page Script")!!
    tp.linkPageToScript(cp.id, ps.id)
    val pe = tp.deletePageToTrash(rp.id)
    check("deletePageToTrash returns entry", pe != null, true)
    check("page trash kind/label (root page)", pe!!.kind to pe.label, "page" to "Trash Root")
    check("page cascade removed pages+blocks", listOf(tp.pageById(rp.id), tp.pageById(cp.id), tp.blockById(rb.id), tp.blockById(cbk.id)), listOf(null, null, null, null))
    check("page trash delete cleaned script link", ps.linkedPageIds.contains(cp.id), false)
    check("restore page cascade from trash true", tp.restoreFromTrash(pe.id), true)
    check("restored pages back", listOf(tp.pageById(rp.id) != null, tp.pageById(cp.id) != null), listOf(true, true))
    check("restored blocks back on their pages", tp.blocksForPage(rp.id).map { it.id } to tp.blocksForPage(cp.id).map { it.id }, listOf(rb.id) to listOf(cbk.id))
    check("restored script page link from trash", tp.scriptById(ps.id)!!.linkedPageIds.contains(cp.id), true)
    check("page entry removed after restore", tp.trashById(pe.id), null)

    /* ---------- R4: trash — permanent delete / empty / JSON round-trip ---------- */
    val pd = Store()
    pd.seedIfEmpty()
    val pdChan = pd.channels[0].id
    val pdTask = pd.addTask(pdChan, "Doomed Task")!!
    val pdEntry = pd.deleteTaskToTrash(pdTask.id)!!
    check("permanentlyDeleteTrash true", pd.permanentlyDeleteTrash(pdEntry.id), true)
    check("permanent delete does NOT restore entity", pd.taskById(pdTask.id), null)
    check("permanentlyDeleteTrash again false", pd.permanentlyDeleteTrash(pdEntry.id), false)
    check("restore of permanently deleted entry false", pd.restoreFromTrash(pdEntry.id), false)
    val pe1 = pd.deleteIdeaToTrash(pd.addIdea("Bin One").id)!!
    val pe2 = pd.deleteIdeaToTrash(pd.addIdea("Bin Two").id)!!
    check("trash accumulates entries (newest last)", pd.trash.map { it.id }, listOf(pe1.id, pe2.id))
    pd.emptyTrash()
    check("emptyTrash clears all", pd.trash.size, 0)
    // Trash survives a JSON round-trip, payload included.
    val tj = Store()
    tj.seedIfEmpty()
    val tjScript = tj.addScript(tj.channels[0].id, "JSON Trash Script")!!
    val tjEntry = tj.deleteScriptToTrash(tjScript.id)!!
    val tj2 = Store()
    check("trash round-trip load true", tj2.loadFromJsonString(tj.toJsonString()), true)
    check("round-trip trash size", tj2.trash.size, 1)
    val rtEntry = tj2.trash[0]
    check("round-trip trash kind/label preserved", rtEntry.kind to rtEntry.label, "script" to "JSON Trash Script")
    check("round-trip trash deletedAt preserved", rtEntry.deletedAt, tjEntry.deletedAt)
    check("restore from round-tripped trash true", tj2.restoreFromTrash(rtEntry.id), true)
    check("round-tripped restore brings script back", tj2.scriptById(tjScript.id)?.title, "JSON Trash Script")

    println("----")
    println("$total checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
