package com.ghostblade.notion

import java.time.LocalDate

/** Sort keys for [Logic.sortScripts] (Round 4 — table/board views). */
enum class ScriptSort { TITLE, LANGUAGE, STATUS, DATE, UPDATED }

/** Sort keys for [Logic.sortTasks] (Round 4). */
enum class TaskSort { TITLE, DONE, DATE }

/** Pure-Kotlin logic for GhostBlade Notion: dates, script/idea link
 *  rules and block ordering. NO Android imports — runs on a plain JVM. */
object Logic {

    /* ---------- ids ---------- */

    private var uidCounter: Long = 0

    /** Timestamp + monotonic counter id, unique within a run and
     *  practically unique across runs. */
    @Synchronized
    fun uid(): String {
        uidCounter++
        return "n" + System.currentTimeMillis().toString(36) + uidCounter.toString(36)
    }

    /* ---------- dates ("yyyy-MM-dd"; lexicographic = date order) ---------- */

    /** null/blank is valid (the date is optional). Otherwise the string
     *  must be strict yyyy-MM-dd AND a real calendar date — month 1-12,
     *  day valid for that month, leap years honoured (2024-02-29 valid,
     *  2026-02-29 not). */
    fun isValidDate(s: String?): Boolean {
        if (s.isNullOrBlank()) return true
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)) return false
        return try {
            val p = s.split("-")
            // LocalDate.of throws on month 13, Feb 30, non-leap Feb 29…
            LocalDate.of(p[0].toInt(), p[1].toInt(), p[2].toInt())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Add n days (n may be negative) to a valid yyyy-MM-dd date.
     *  Correct across month, year and leap-year edges. */
    fun addDays(date: String, n: Int): String =
        LocalDate.parse(date).plusDays(n.toLong()).toString()

    /* ---------- script / idea rules ---------- */

    /** Only a DRAFT can be marked FINAL. */
    fun canMarkFinal(s: Script): Boolean = s.status == ScriptStatus.DRAFT

    /** True only when the idea and the script point at each other:
     *  idea.linkedScriptId == script.id AND script.linkedIdeaId == idea.id. */
    fun ideaLinkConsistent(idea: Idea, script: Script): Boolean =
        idea.linkedScriptId == script.id && script.linkedIdeaId == idea.id

    /** True only when the task and the script point at each other:
     *  task.linkedScriptIds contains script.id AND
     *  script.linkedTaskIds contains task.id. */
    fun taskLinkConsistent(task: GTask, script: Script): Boolean =
        task.linkedScriptIds.contains(script.id) && script.linkedTaskIds.contains(task.id)

    /** True only when the page and the script point at each other:
     *  page.linkedScriptIds contains script.id AND
     *  script.linkedPageIds contains page.id. */
    fun pageLinkConsistent(page: Page, script: Script): Boolean =
        page.linkedScriptIds.contains(script.id) && script.linkedPageIds.contains(page.id)

    /** True only when the idea and the task point at each other:
     *  idea.linkedTaskIds contains task.id AND
     *  task.linkedIdeaIds contains idea.id. */
    fun ideaTaskLinkConsistent(idea: Idea, task: GTask): Boolean =
        idea.linkedTaskIds.contains(task.id) && task.linkedIdeaIds.contains(idea.id)

    /* ---------- blocks ---------- */

    /** The block types the editor supports (Round 3). Store.addBlock
     *  throws on anything else; Store.updateBlockType returns false.
     *  ("page" is deliberately NOT a content-block type here: pages are
     *  first-class Page records, FINDINGS §7.1.) */
    val BLOCK_TYPES: Set<String> = setOf(
        "text", "heading", "bulleted_list", "numbered_list",
        "todo", "toggle", "quote", "code", "divider"
    )

    fun isValidBlockType(type: String): Boolean = BLOCK_TYPES.contains(type)

    /** Blocks sorted by their order field. The sort is stable: blocks
     *  with equal order keep their input relative order. */
    fun sortBlocks(blocks: List<Block>): List<Block> = blocks.sortedBy { it.order }

    /* ---------- list helpers (used by Store and handy for UI) ---------- */

    fun scriptsForChannel(scripts: List<Script>, channelId: String): List<Script> =
        scripts.filter { it.channelId == channelId }

    fun countScriptsForChannel(scripts: List<Script>, channelId: String): Int =
        scripts.count { it.channelId == channelId }

    fun countDrafts(scripts: List<Script>): Int =
        scripts.count { it.status == ScriptStatus.DRAFT }

    fun countFinals(scripts: List<Script>): Int =
        scripts.count { it.status == ScriptStatus.FINAL }

    fun countOpenTasks(tasks: List<GTask>): Int = tasks.count { !it.done }

    /* ---------- sorting + board grouping (Round 4) ----------
     * Pure, stable sorts for the table/board views. "Stable" means
     * elements that compare equal keep their input relative order
     * (Kotlin's sortedWith is a stable sort). The one direction-proof
     * rule: for DATE, scripts/tasks with a null or blank date sort
     * LAST in BOTH directions — an undated item never floats to the
     * top just because the user flipped the sort. */

    /** Date comparison honouring the nulls-last-both-ways rule:
     *  both blank -> equal; exactly one blank -> the blank one is
     *  "greater" (sorts last) regardless of [ascending]; otherwise
     *  plain lexicographic order (yyyy-MM-dd sorts chronologically),
     *  reversed when descending. */
    private fun compareDatesNullsLast(a: String?, b: String?, ascending: Boolean): Int {
        val aBlank = a.isNullOrBlank()
        val bBlank = b.isNullOrBlank()
        if (aBlank && bBlank) return 0
        if (aBlank) return 1
        if (bBlank) return -1
        val cmp = a!!.compareTo(b!!)
        return if (ascending) cmp else -cmp
    }

    /** Sorts scripts by [by]. Rules per key:
     *  TITLE — case-insensitive title order;
     *  LANGUAGE — by enum name (BANGLA before ENGLISH ascending);
     *  STATUS — DRAFT before FINAL ascending (enum ordinal);
     *  DATE — lexicographic, null/blank dates ALWAYS last;
     *  UPDATED — numeric updatedAt, ties keep input order.
     *  [ascending] = false reverses the key order (except the
     *  nulls-last date rule, which holds in both directions). */
    fun sortScripts(scripts: List<Script>, by: ScriptSort, ascending: Boolean = true): List<Script> {
        val cmp: Comparator<Script> = when (by) {
            ScriptSort.TITLE -> Comparator { a, b ->
                val c = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
                if (ascending) c else -c
            }
            ScriptSort.LANGUAGE -> Comparator { a, b ->
                val c = a.language.name.compareTo(b.language.name)
                if (ascending) c else -c
            }
            ScriptSort.STATUS -> Comparator { a, b ->
                val c = a.status.compareTo(b.status)
                if (ascending) c else -c
            }
            ScriptSort.DATE -> Comparator { a, b -> compareDatesNullsLast(a.date, b.date, ascending) }
            ScriptSort.UPDATED -> Comparator { a, b ->
                val c = a.updatedAt.compareTo(b.updatedAt)
                if (ascending) c else -c
            }
        }
        return scripts.sortedWith(cmp)
    }

    /** Sorts tasks by [by]. Rules per key:
     *  TITLE — case-insensitive title order;
     *  DONE — ascending puts open tasks (done = false) first;
     *  DATE — lexicographic dueDate, null/blank dates ALWAYS last
     *  (both directions, same rule as scripts). */
    fun sortTasks(tasks: List<GTask>, by: TaskSort, ascending: Boolean = true): List<GTask> {
        val cmp: Comparator<GTask> = when (by) {
            TaskSort.TITLE -> Comparator { a, b ->
                val c = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
                if (ascending) c else -c
            }
            TaskSort.DONE -> Comparator { a, b ->
                val c = a.done.compareTo(b.done)
                if (ascending) c else -c
            }
            TaskSort.DATE -> Comparator { a, b -> compareDatesNullsLast(a.dueDate, b.dueDate, ascending) }
        }
        return tasks.sortedWith(cmp)
    }

    /** Groups scripts by status for the board view (DRAFT / FINAL
     *  columns). BOTH keys are always present (an empty column is an
     *  empty list, never a missing key), and each group preserves
     *  the input order. Iteration order is DRAFT, then FINAL. */
    fun groupScriptsByStatus(scripts: List<Script>): Map<ScriptStatus, List<Script>> =
        linkedMapOf(
            ScriptStatus.DRAFT to scripts.filter { it.status == ScriptStatus.DRAFT },
            ScriptStatus.FINAL to scripts.filter { it.status == ScriptStatus.FINAL }
        )
}
