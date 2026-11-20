package com.ghostblade.notion

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** GhostBlade Notion on-device persistence layer (SQLite).
 *
 *  Mirrors Notion's local record store (FINDINGS §7.1): one `records`
 *  table holds every record type; the concrete payload lives in
 *  data_json. Fully offline — no network anything.
 *
 *  ROLE IN v0.2 (honest wording): Db is a WRITE-THROUGH MIRROR, not
 *  the authoritative store and not the read path. The authoritative
 *  copy is Store's JSON string (kept by the UI in
 *  `ghostnotion-state.json`). [init] does three real things:
 *  1. opens the database, which forces Android to actually CREATE
 *     `ghostnotion.db` and its tables (SQLiteOpenHelper is lazy —
 *     in v0.1 the file was never created because nothing opened it);
 *  2. installs Db.upsertRecord as Store's record sink, so every
 *     Store mutation from then on is written through to `records`;
 *  3. backfills the mirror with every record of every live Store
 *     (Store.syncRegisteredStoresToSink), covering whatever was
 *     seeded/loaded before init ran.
 *  Same object + private Helper pattern as GhostBlade Task's Db.kt.
 */
object Db {
    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "ghostnotion.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE records(" +
                    "id TEXT PRIMARY KEY, " +
                    "table_name TEXT, " +
                    "parent_id TEXT, " +
                    "created_at INTEGER, " +
                    "updated_at INTEGER, " +
                    "alive INTEGER, " +
                    "data_json TEXT)"
            )
            db.execSQL("CREATE INDEX idx_records_table ON records(table_name)")
            db.execSQL("CREATE INDEX idx_records_parent ON records(parent_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    private lateinit var helper: Helper
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun init(ctx: Context) {
        helper = Helper(ctx.applicationContext)
        // Force the lazy SQLiteOpenHelper to create ghostnotion.db and
        // run onCreate NOW (merely constructing the Helper, as v0.1
        // did, creates nothing on disk).
        val opened = db
        check(opened.isOpen) { "ghostnotion.db failed to open" }
        // Write-through mirror: every future Store mutation lands in
        // the records table as well as in the authoritative JSON file.
        Store.installRecordSink { r -> upsertRecord(r) }
        // Backfill records that already exist (seeded / restored from
        // JSON before this init call ran).
        Store.syncRegisteredStoresToSink()
    }

    /* ---------- writes ---------- */

    /** Insert or replace a record (keyed on Record.id). */
    fun upsertRecord(r: Record) {
        val cv = ContentValues()
        cv.put("id", r.id)
        cv.put("table_name", r.tableName)
        if (r.parentId == null) cv.putNull("parent_id") else cv.put("parent_id", r.parentId)
        cv.put("created_at", r.createdAt)
        cv.put("updated_at", r.updatedAt)
        cv.put("alive", if (r.alive) 1 else 0)
        cv.put("data_json", r.dataJson)
        db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Soft delete: the row stays, alive flips to 0 (Notion's trash
     *  model). Returns true if a live row was deleted. */
    fun softDelete(id: String): Boolean {
        val cv = ContentValues()
        cv.put("alive", 0)
        cv.put("updated_at", System.currentTimeMillis())
        return db.update("records", cv, "id=? AND alive=1", arrayOf(id)) > 0
    }

    /* ---------- reads ---------- */

    fun recordById(id: String): Record? {
        val c = db.query("records", null, "id=?", arrayOf(id), null, null, null)
        val r = if (c.moveToFirst()) fromCursor(c) else null
        c.close()
        return r
    }

    /** All records of one table (e.g. "block", "script"), oldest first.
     *  includeDead=false (default) hides soft-deleted rows. */
    fun recordsByTable(tableName: String, includeDead: Boolean = false): List<Record> {
        val selection = if (includeDead) "table_name=?" else "table_name=? AND alive=1"
        val c = db.query("records", null, selection, arrayOf(tableName), null, null, "created_at ASC, id ASC")
        val out = mutableListOf<Record>()
        while (c.moveToNext()) out.add(fromCursor(c))
        c.close()
        return out
    }

    /** Live children of a parent record, oldest first. */
    fun recordsByParent(parentId: String): List<Record> {
        val c = db.query("records", null, "parent_id=? AND alive=1", arrayOf(parentId), null, null, "created_at ASC, id ASC")
        val out = mutableListOf<Record>()
        while (c.moveToNext()) out.add(fromCursor(c))
        c.close()
        return out
    }

    private fun fromCursor(c: Cursor): Record {
        fun col(name: String) = c.getColumnIndexOrThrow(name)
        return Record(
            id = c.getString(col("id")),
            tableName = c.getString(col("table_name")),
            parentId = if (c.isNull(col("parent_id"))) null else c.getString(col("parent_id")),
            createdAt = c.getLong(col("created_at")),
            updatedAt = c.getLong(col("updated_at")),
            alive = c.getInt(col("alive")) != 0,
            dataJson = c.getString(col("data_json")) ?: "{}"
        )
    }
}
