package com.ghostblade.notion

/* GhostBlade Notion — data model.
 *
 * Pure Kotlin: NO Android imports, NO org.json, so this file (with
 * Logic.kt) compiles and runs on a plain JVM with only kotlin-stdlib.
 *
 * Record mirrors Notion's record model (FINDINGS §7.1): everything is a
 * record in a named table; a page is a block of type "page"; block
 * content ordering lives in Block.order.
 *
 * Round 3 (v0.3) additions:
 * - Page: a first-class page entity (FINDINGS §7.1: a page IS a record
 *   of type "page"). Pages nest via parentPageId and own Blocks.
 * - Block gains checked (to-do) and collapsed (toggle) flags.
 * - The last two link pairings are completed, both sides maintained by
 *   Store exactly like the v0.2 task<->script pattern:
 *   page<->script (Script.linkedPageIds + Page.linkedScriptIds) and
 *   idea<->task (Idea.linkedTaskIds + GTask.linkedIdeaIds).
 * - ChannelCascadeSnapshot / PageCascadeSnapshot: everything a cascade
 *   delete removed (plus pre-delete copies of surviving entities whose
 *   links were cleaned), so a full restore is possible.
 *
 * Round 4 (v0.4) additions:
 * - TemplateBlock / PageTemplate: a named snapshot of a page's blocks
 *   (Store.savePageAsTemplate / createPageFromTemplate). Templates
 *   are plain Store data persisted in the authoritative JSON; they are
 *   NOT Db records (the mirror covers only the six entity tables).
 * - TrashEntry: a persisted trash/bin record (Store's ToTrash APIs).
 *   payloadJson fully encodes what restore needs (entity + index for
 *   singles, a full cascade snapshot for channel/page deletes).
 */

enum class ScriptStatus { DRAFT, FINAL }

enum class Language { BANGLA, ENGLISH }

/** Notion-style record. tableName is one of:
 *  space, block, collection, collection_view, channel, script, idea, task.
 *  Pages are emitted with tableName "block" and "type":"page" inside
 *  dataJson — exactly FINDINGS §7.1 (a page is a block of type page). */
data class Record(
    var id: String,
    var tableName: String,
    var parentId: String? = null,
    var createdAt: Long = 0,
    var updatedAt: Long = 0,
    var alive: Boolean = true,
    var dataJson: String = "{}"
)

/** One block of page content. Blocks belong to a page via pageId
 *  (a Page's id, or any page-like id). Valid types are listed in
 *  Logic.BLOCK_TYPES: text, heading, bulleted_list, numbered_list,
 *  todo, toggle, quote, code, divider.
 *  checked is the to-do checkbox state (type "todo");
 *  collapsed is the toggle state (type "toggle"). Both default false
 *  and are ignored for other types. */
data class Block(
    var id: String,
    var pageId: String,
    var type: String = "text",
    var text: String = "",
    var order: Int = 0,
    var parentBlockId: String? = null,
    var checked: Boolean = false,
    var collapsed: Boolean = false
)

/** A Notion-style page (Round 3). A page is a record of type "page"
 *  (FINDINGS §7.1): it has a title, nests inside another page via
 *  parentPageId (null = root page), and its content is the Blocks
 *  whose pageId == this page's id, ordered by Block.order.
 *  linkedScriptIds is the page side of the page<->script link
 *  (Script.linkedPageIds is the other side). */
data class Page(
    var id: String,
    var title: String,
    var parentPageId: String? = null,
    var linkedScriptIds: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0,
    var updatedAt: Long = 0
)

/** A YouTube channel (Sir's content channels get a home tab each). */
data class Channel(
    var id: String,
    var name: String,
    var createdAt: Long = 0
)

/** A video script. Drafts become final scripts via markScriptFinal.
 *  linkedTaskIds is the script side of the task<->script link
 *  (Store.linkTaskToScript / unlinkTaskFromScript keep both sides in
 *  sync: GTask.linkedScriptIds is the other side).
 *  linkedPageIds is the script side of the page<->script link
 *  (Page.linkedScriptIds is the other side). */
data class Script(
    var id: String,
    var channelId: String,
    var title: String,
    var body: String = "",
    var language: Language = Language.BANGLA,
    var status: ScriptStatus = ScriptStatus.DRAFT,
    var date: String? = null,
    var linkedIdeaId: String? = null,
    var linkedTaskIds: MutableList<String> = mutableListOf(),
    var linkedPageIds: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0,
    var updatedAt: Long = 0
)

/** A captured video idea. Starting to write from an idea creates a
 *  linked Script (both link fields are set, both ways).
 *  linkedTaskIds is the idea side of the idea<->task link
 *  (GTask.linkedIdeaIds is the other side). */
data class Idea(
    var id: String,
    var title: String,
    var notes: String = "",
    var channelId: String? = null,
    var linkedScriptId: String? = null,
    var linkedTaskIds: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0
)

/** A to-do (named GTask so it can never clash with GhostBlade Task's
 *  TaskItem or any future Task type).
 *  linkedScriptIds is the task side of the task<->script link
 *  (Script.linkedTaskIds is the other side).
 *  linkedIdeaIds is the task side of the idea<->task link
 *  (Idea.linkedTaskIds is the other side). */
data class GTask(
    var id: String,
    var channelId: String? = null,
    var title: String,
    var dueDate: String? = null,
    var done: Boolean = false,
    var linkedScriptIds: MutableList<String> = mutableListOf(),
    var linkedIdeaIds: MutableList<String> = mutableListOf(),
    var createdAt: Long = 0
)

/** Everything Store.removeChannelCascade removed, captured BEFORE any
 *  mutation (deep copies), so Store.restoreChannelCascade can put it
 *  all back: the channel, its scripts/tasks/ideas at their original
 *  list indices, and — via the survivor copies — the link fields of
 *  surviving entities that pointed at the removed ones.
 *  Pairs are (original index in the Store list, entity copy). */
data class ChannelCascadeSnapshot(
    val channel: Channel,
    val channelIndex: Int,
    val scripts: List<Pair<Int, Script>>,
    val tasks: List<Pair<Int, GTask>>,
    val ideas: List<Pair<Int, Idea>>,
    val survivorScripts: List<Script>,
    val survivorTasks: List<GTask>,
    val survivorIdeas: List<Idea>,
    val survivorPages: List<Page>
)

/** Everything Store.removePageCascade removed: the page, all its
 *  descendant pages, and every block belonging to any of them, at
 *  their original list indices, plus pre-delete copies of scripts
 *  whose linkedPageIds pointed at a removed page. */
data class PageCascadeSnapshot(
    val pages: List<Pair<Int, Page>>,
    val blocks: List<Pair<Int, Block>>,
    val survivorScripts: List<Script>
)

/** One block inside a [PageTemplate] (Round 4). Mirrors Block's
 *  content fields, except the parent link is positional:
 *  [parentIndex] is the index — within the template's own blocks
 *  list — of this block's parent block, or null for a top-level
 *  block. Positional (not id-based) so a template stays valid when
 *  it is instantiated with fresh block ids. */
data class TemplateBlock(
    var type: String,
    var text: String,
    var checked: Boolean = false,
    var collapsed: Boolean = false,
    var parentIndex: Int? = null
)

/** A named page template (Round 4): a snapshot of one page's blocks
 *  in page order. Store.createPageFromTemplate re-creates the blocks
 *  on a brand-new page, mapping parentIndex -> the new block ids.
 *  Persisted in the authoritative JSON under "templates"; NOT a Db
 *  record (see file header). */
data class PageTemplate(
    var id: String,
    var name: String,
    var blocks: List<TemplateBlock> = emptyList(),
    var createdAt: Long = 0
)

/** One entry in the persisted trash/bin (Round 4). [kind] is one of
 *  "channel", "page", "script", "idea", "task", "block".
 *  [label] is the human-readable name shown in a trash UI (channel
 *  name / page title / script title / idea title / task title /
 *  block text, falling back to the block type when its text is
 *  blank). [payloadJson] fully encodes what
 *  Store.restoreFromTrash needs: for singles an object
 *  {"index":Int,"entity":{...}}; for channel/page a serialised
 *  cascade snapshot. Trash entries are plain Store data persisted
 *  under "trash"; they are NOT Db records. */
data class TrashEntry(
    var id: String,
    var kind: String,
    var label: String,
    var deletedAt: Long = 0,
    var payloadJson: String = "{}"
)
