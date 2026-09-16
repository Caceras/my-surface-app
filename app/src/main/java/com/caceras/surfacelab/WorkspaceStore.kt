package com.caceras.surfacelab

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Canonical, local content. Settings and provider credentials never enter this database/export. */
data class Record(
    val id: String = UUID.randomUUID().toString(), val kind: String = "note",
    val title: String = "", val body: String = "", val original: String = body,
    val created: Long = System.currentTimeMillis(), val updated: Long = created,
    val pinned: Boolean = false, val deleted: Boolean = false, val done: Boolean = false,
    val due: Long = 0, val zone: String = java.time.ZoneId.systemDefault().id,
    val cadence: String = "once", val enabled: Boolean = true, val source: String = "",
    val revision: Int = 1
)
data class Property(val id: String, val collection: String, val name: String, val type: String)

class WorkspaceStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "aegentica.db", null, 1) {
    private val app = context.applicationContext
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,original TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,pinned INTEGER NOT NULL,deleted INTEGER NOT NULL,done INTEGER NOT NULL,due INTEGER NOT NULL,zone TEXT NOT NULL,cadence TEXT NOT NULL,enabled INTEGER NOT NULL,source TEXT NOT NULL,revision INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX records_list ON records(deleted,kind,pinned,updated)")
        db.execSQL("CREATE INDEX records_due ON records(deleted,done,enabled,due)")
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(id UNINDEXED,title,body,tokenize=unicode61)")
        db.execSQL("CREATE TABLE links(a TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,b TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,label TEXT NOT NULL,PRIMARY KEY(a,b,label),CHECK(a<>b))")
        db.execSQL("CREATE TABLE properties(id TEXT PRIMARY KEY,collection TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,name TEXT NOT NULL,type TEXT NOT NULL,UNIQUE(collection,name))")
        db.execSQL("CREATE TABLE property_values(record TEXT NOT NULL REFERENCES records(id) ON DELETE CASCADE,property TEXT NOT NULL REFERENCES properties(id) ON DELETE CASCADE,value TEXT NOT NULL,PRIMARY KEY(record,property))")
        db.execSQL("CREATE INDEX property_lookup ON property_values(property,value)")
        db.execSQL("CREATE TABLE content(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE executions(id TEXT PRIMARY KEY,record TEXT NOT NULL,at INTEGER NOT NULL,state TEXT NOT NULL,detail TEXT NOT NULL)")
        // SQLiteOpenHelper runs creation in a transaction. The old preferences remain a recovery copy.
        val legacy = app.getSharedPreferences("surfacelab", 0)
        for (key in listOf("chat_turns", "draft", "conversations")) {
            legacy.getString(key, null)?.let { db.insertOrThrow("content", null, values("key" to key, "value" to it)) }
        }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported database upgrade") }

    fun value(key: String, fallback: String = ""): String {
        // CursorWindow is bounded independently of SQLite TEXT size. Read large legacy drafts in code-point chunks.
        val chunk=200000
        val first=readableDatabase.rawQuery("SELECT substr(value,1,?),length(value) FROM content WHERE key=?",arrayOf(chunk.toString(),key)).use {
            if(it.moveToFirst()) it.getString(0) to it.getInt(1) else return fallback
        }
        if(first.second<=chunk) return first.first
        val out=StringBuilder(first.first)
        var offset=chunk+1
        while(offset<=first.second) {
            readableDatabase.rawQuery("SELECT substr(value,?,?) FROM content WHERE key=?",arrayOf(offset.toString(),chunk.toString(),key)).use { if(it.moveToFirst()) out.append(it.getString(0)) }
            offset+=chunk
        }
        return out.toString()
    }
    fun put(key: String, value: String) { writableDatabase.insertWithOnConflict("content", null, values("key" to key, "value" to value), SQLiteDatabase.CONFLICT_REPLACE) }
    fun get(id: String): Record? = readableDatabase.rawQuery("SELECT * FROM records WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) row(it) else null }
    fun list(query: String = "", kind: String = "", trash: Boolean = false, limit: Int = 200): List<Record> {
        val args = mutableListOf(if (trash) "1" else "0")
        val clauses = mutableListOf("deleted=?")
        if (kind.isNotBlank()) { clauses += "kind=?"; args += kind }
        val tokens = Regex("[\\p{L}\\p{N}]+").findAll(query).take(12).map { "\"${it.value}\"*" }.toList()
        if (tokens.isNotEmpty()) { clauses += "id IN (SELECT id FROM search WHERE search MATCH ?)"; args += tokens.joinToString(" AND ") }
        return readableDatabase.rawQuery("SELECT * FROM records WHERE ${clauses.joinToString(" AND ")} ORDER BY pinned DESC,updated DESC LIMIT ${limit.coerceIn(1, 10000)}", args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(row(c)) } }
    }
    fun scheduled(): List<Record> = readableDatabase.rawQuery("SELECT * FROM records WHERE deleted=0 AND done=0 AND enabled=1 AND due>0 AND kind IN ('task','routine') ORDER BY due", null).use { c -> buildList { while(c.moveToNext()) add(row(c)) } }

    fun save(record: Record, expectedRevision: Int? = null): Record {
        validate(record)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = get(record.id)
            require(expectedRevision == null || previous?.revision == expectedRevision) { "This item changed elsewhere. Reopen it before editing." }
            val next = record.copy(original = previous?.original?.takeIf { it.isNotBlank() } ?: record.original, created = previous?.created ?: record.created,
                updated = System.currentTimeMillis(), revision = (previous?.revision ?: 0) + 1)
            if (previous == null) db.insertOrThrow("records", null, recordValues(next))
            else db.update("records", recordValues(next), "id=?", arrayOf(next.id))
            index(db, next)
            db.setTransactionSuccessful()
            return next
        } finally { db.endTransaction() }
    }
    fun link(a: String, b: String, label: String = "related") {
        require(a != b && label in listOf("related", "member", "source"))
        require(get(a)?.deleted == false && get(b)?.deleted == false) { "Choose existing items." }
        writableDatabase.insertWithOnConflict("links", null, values("a" to a, "b" to b, "label" to label), SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun unlink(a: String, b: String) { writableDatabase.delete("links", "(a=? AND b=?) OR (a=? AND b=?)", arrayOf(a,b,b,a)) }
    fun linked(id: String, label: String? = null): List<Record> {
        val args = mutableListOf(id,id)
        val filter = if (label == null) "" else " AND label=?".also { args += label }
        return readableDatabase.rawQuery("SELECT DISTINCT r.* FROM records r JOIN links l ON ((l.a=? AND l.b=r.id) OR (l.b=? AND l.a=r.id)) WHERE r.deleted=0$filter ORDER BY r.updated DESC", args.toTypedArray()).use { c -> buildList { while(c.moveToNext()) add(row(c)) } }
    }
    fun purge(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(get(id)?.deleted == true) { "Move an item to Trash first." }
            db.delete("records", "id=?", arrayOf(id)); db.delete("search", "id=?", arrayOf(id))
            db.delete("executions", "record=?", arrayOf(id)); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun properties(collection: String): List<Property> = readableDatabase.rawQuery("SELECT * FROM properties WHERE collection=? ORDER BY name", arrayOf(collection)).use { c -> buildList { while(c.moveToNext()) add(Property(c.getString(0),c.getString(1),c.getString(2),c.getString(3))) } }
    fun addProperty(collection: String, name: String, type: String) {
        require(get(collection)?.kind == "collection" && name.isNotBlank() && name.length <= 80 && type in PROPERTY_TYPES)
        writableDatabase.insertOrThrow("properties", null, values("id" to UUID.randomUUID().toString(),"collection" to collection,"name" to name.trim(),"type" to type))
    }
    fun propertyValue(record: String, property: String): String = readableDatabase.rawQuery("SELECT value FROM property_values WHERE record=? AND property=?", arrayOf(record,property)).use { if(it.moveToFirst()) it.getString(0) else "" }
    fun setProperty(record: String, property: Property, value: String) {
        require(value.length <= 2000)
        if (value.isNotBlank()) when(property.type) {
            "number" -> require(value.toDoubleOrNull()?.isFinite() == true) { "Enter a number." }
            "date" -> java.time.LocalDate.parse(value)
            "checkbox" -> require(value in listOf("true","false")) { "Use true or false." }
        }
        writableDatabase.insertWithOnConflict("property_values", null, values("record" to record,"property" to property.id,"value" to value),SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun execution(id: String, record: String, state: String, detail: String = ""): Boolean = writableDatabase.insertWithOnConflict("executions", null,
        values("id" to id,"record" to record,"at" to System.currentTimeMillis(),"state" to state,"detail" to detail),SQLiteDatabase.CONFLICT_IGNORE) != -1L
    fun executionState(id: String, state: String, detail: String = "") { writableDatabase.update("executions", values("state" to state,"detail" to detail),"id=?", arrayOf(id)) }
    fun runs(record: String): List<String> = readableDatabase.rawQuery("SELECT at,state,detail FROM executions WHERE record=? ORDER BY at DESC LIMIT 20",arrayOf(record)).use { c -> buildList { while(c.moveToNext()) add("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(c.getLong(0)))} · ${c.getString(1)}\n${c.getString(2)}") } }

    /** Full workspace snapshot, including legacy chat and user drafts; never credentials or calendar selections. */
    fun backup(): String {
        val db = readableDatabase
        db.beginTransaction()
        try {
            val result = JSONObject().put("format", "aegentica-workspace-v1")
            for (table in TABLES) {
                val rows = JSONArray()
                db.rawQuery(if(table=="content") "SELECT key FROM content" else "SELECT * FROM $table", null).use { c -> while(c.moveToNext()) {
                    val item = JSONObject()
                    c.columnNames.forEachIndexed { i, name -> item.put(name, if(c.getType(i) == Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else c.getString(i)) }
                    if(table=="content") item.put("value",value(item.getString("key")))
                    rows.put(item)
                } }
                result.put(table, rows)
            }
            val raw = result.toString()
            require(raw.toByteArray().size <= MAX_BACKUP) { "Backup exceeds 32 MB. Export individual notes before removing large items." }
            db.setTransactionSuccessful()
            return raw
        } finally { db.endTransaction() }
    }
    /** Validation and merging share one transaction: any invalid row rolls back the entire import. */
    fun restore(raw: String): Int {
        require(raw.toByteArray().size <= MAX_BACKUP) { "Backup exceeds 32 MB." }
        val json = JSONObject(raw)
        require(json.getString("format") == "aegentica-workspace-v1") { "Choose a workspace backup. Older chat backups can be restored in AI Settings." }
        val rows = json.getJSONArray("records")
        require(rows.length() <= 10000) { "Too many records in one import." }
        val records = (0 until rows.length()).map { fromJson(rows.getJSONObject(it)).also(::validate) }
        require(records.map { it.id }.distinct().size == records.size) { "Duplicate record IDs." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val mapping = mutableMapOf<String,String>()
            val inserted = mutableSetOf<String>()
            for (r in records) {
                val old = get(r.id)
                val id = if (old == null || old.copy(enabled=r.enabled) == r) r.id else UUID.nameUUIDFromBytes((r.id + rows.getJSONObject(records.indexOf(r)).toString()).toByteArray()).toString()
                mapping[r.id] = id
                if (get(id) == null) { val imported = r.copy(id=id); db.insertOrThrow("records",null,recordValues(imported)); index(db, imported); inserted += id }
            }
            val props = mutableMapOf<String,String>()
            val definitions = json.getJSONArray("properties")
            for (i in 0 until definitions.length()) {
                val p = definitions.getJSONObject(i)
                val collection = mapping[p.getString("collection")] ?: error("Missing collection")
                require(get(collection)?.kind == "collection" && p.getString("type") in PROPERTY_TYPES)
                require(p.getString("name").isNotBlank() && p.getString("name").length <= 80)
                val existing = properties(collection).firstOrNull { it.name == p.getString("name") && it.type == p.getString("type") }
                val id = existing?.id ?: UUID.nameUUIDFromBytes((collection+p.getString("id")).toByteArray()).toString()
                props[p.getString("id")] = id
                db.insertWithOnConflict("properties",null,values("id" to id,"collection" to collection,"name" to p.getString("name"),"type" to p.getString("type")),SQLiteDatabase.CONFLICT_IGNORE)
            }
            val links = json.getJSONArray("links")
            for (i in 0 until links.length()) {
                val l = links.getJSONObject(i); val a = mapping[l.getString("a")] ?: error("Missing link"); val b = mapping[l.getString("b")] ?: error("Missing link")
                require(a != b && l.getString("label") in listOf("related","member","source"))
                db.insertWithOnConflict("links",null,values("a" to a,"b" to b,"label" to l.getString("label")),SQLiteDatabase.CONFLICT_IGNORE)
            }
            val fields = json.getJSONArray("property_values")
            for (i in 0 until fields.length()) {
                val f=fields.getJSONObject(i); val id=props[f.getString("property")] ?: error("Missing property")
                val p=readableDatabase.rawQuery("SELECT * FROM properties WHERE id=?",arrayOf(id)).use { require(it.moveToFirst()); Property(it.getString(0),it.getString(1),it.getString(2),it.getString(3)) }
                setProperty(mapping[f.getString("record")] ?: error("Missing item"),p,f.getString("value"))
            }
            // Retain colliding imports as visible notes; never bury a conflicting draft in an inaccessible key.
            val content = json.getJSONArray("content")
            for(i in 0 until content.length()) {
                val item=content.getJSONObject(i); val key=item.getString("key"); val value=item.getString("value")
                val safe = key in listOf("chat_turns","draft","conversations","capture","reading-text","reading-index","reading-speed") || key.startsWith("beeper-draft:")
                if(!safe) continue
                when(key) {
                    "chat_turns" -> Chat.readBackup(JSONObject().put("format","surface-chat-v1").put("turns",JSONArray(value.ifBlank { "[]" })).toString())
                    "conversations" -> Chat.readBackup(JSONObject().put("format","surface-chat-v1").put("turns",JSONArray()).put("conversations",JSONArray(value.ifBlank { "[]" })).toString())
                }
                if(value(key).isBlank() || value(key)=="[]") put(key,value)
                else if(value(key)!=value && value.isNotBlank() && key !in listOf("reading-index","reading-speed")) {
                    val readable=when(key) {
                        "chat_turns" -> JSONArray(value).let { a -> (0 until a.length()).joinToString("\n\n") { n -> a.getJSONObject(n).let { it.getString("q")+"\n"+it.getString("a") } } }
                        "conversations" -> JSONArray(value).let { a -> (0 until a.length()).joinToString("\n\n") { n -> val c=a.getJSONObject(n); c.getString("title")+"\n"+c.optString("draft")+"\n"+c.getJSONArray("turns").let { t -> (0 until t.length()).joinToString("\n\n") { x -> t.getJSONObject(x).let { it.getString("q")+"\n"+it.getString("a") } } } } }
                        else -> value
                    }
                    readable.chunked(100000).forEachIndexed { part, text ->
                        val id=UUID.nameUUIDFromBytes((key+part+value).toByteArray()).toString()
                        if(get(id)==null) save(Record(id=id,title="Imported ${if(key.contains("draft")) "draft" else "conversation"}${if(part>0) " · ${part+1}" else ""}",body=text,source="Workspace backup · $key"))
                    }
                }
            }
            val runs=json.optJSONArray("executions") ?: JSONArray()
            require(runs.length()<=100000) { "Too much execution history." }
            for(i in 0 until runs.length()) {
                val run=runs.getJSONObject(i); val record=mapping[run.getString("record")] ?: continue
                val id="import:"+UUID.nameUUIDFromBytes((run.getString("id")+record).toByteArray())
                db.insertWithOnConflict("executions",null,values("id" to id,"record" to record,"at" to run.getLong("at"),"state" to ("Imported · "+run.getString("state").take(80)),"detail" to run.getString("detail").take(1000)),SQLiteDatabase.CONFLICT_IGNORE)
            }
            // Routines imported from another installation are paused; no unexpected notifications or execution.
            for(r in records) if(r.kind=="routine" && mapping[r.id] in inserted) {
                db.update("records",values("enabled" to false),"id=?",arrayOf(mapping.getValue(r.id)))
            }
            db.setTransactionSuccessful()
            return records.size
        } finally { db.endTransaction() }
    }
    private fun index(db: SQLiteDatabase, r: Record) { db.delete("search","id=?",arrayOf(r.id)); if(!r.deleted) db.insertOrThrow("search",null,values("id" to r.id,"title" to r.title,"body" to r.body)) }
    private fun row(c: Cursor): Record = Record(c.str("id"),c.str("kind"),c.str("title"),c.str("body"),c.str("original"),c.long("created"),c.long("updated"),c.long("pinned")==1L,c.long("deleted")==1L,c.long("done")==1L,c.long("due"),c.str("zone"),c.str("cadence"),c.long("enabled")==1L,c.str("source"),c.long("revision").toInt())
    private fun fromJson(j: JSONObject) = Record(j.getString("id"),j.getString("kind"),j.getString("title"),j.getString("body"),j.getString("original"),j.getLong("created"),j.getLong("updated"),j.getInt("pinned")==1,j.getInt("deleted")==1,j.getInt("done")==1,j.getLong("due"),j.getString("zone"),j.getString("cadence"),j.getInt("enabled")==1,j.getString("source"),j.getInt("revision"))
    private fun recordValues(r: Record) = values("id" to r.id,"kind" to r.kind,"title" to r.title,"body" to r.body,"original" to r.original,"created" to r.created,"updated" to r.updated,"pinned" to r.pinned,"deleted" to r.deleted,"done" to r.done,"due" to r.due,"zone" to r.zone,"cadence" to r.cadence,"enabled" to r.enabled,"source" to r.source,"revision" to r.revision)
    companion object {
        const val MAX_BACKUP = 32_000_000
        val KINDS = listOf("note","task","person","project","collection","routine")
        val PROPERTY_TYPES = listOf("text","number","date","checkbox")
        private val TABLES = listOf("records","links","properties","property_values","content","executions")
        fun validate(r: Record) {
            require(r.id.isNotBlank() && r.id.length <= 200 && r.kind in KINDS)
            require(r.title.length <= 200 && r.body.length <= 200000 && r.original.length <= 200000 && r.source.length <= 10000) { "This item is too large." }
            require(r.due >= 0 && r.revision >= 1 && r.cadence in listOf("once","daily","weekly"))
            java.time.ZoneId.of(r.zone)
        }
        private fun values(vararg pairs: Pair<String, Any>) = ContentValues().apply { pairs.forEach { (k,v) -> when(v) { is String -> put(k,v); is Long -> put(k,v); is Int -> put(k,v); is Boolean -> put(k,if(v) 1 else 0); else -> error("Unsupported value") } } }
        private fun Cursor.str(name:String) = getString(getColumnIndexOrThrow(name))
        private fun Cursor.long(name:String) = getLong(getColumnIndexOrThrow(name))
    }
}
