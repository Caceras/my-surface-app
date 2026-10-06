package com.caceras.surfacelab

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * One-way export of the workspace into a user-chosen folder as plain Markdown:
 * YAML properties, [[wiki links]] for relations and the verbatim original in
 * its own section. Obsidian, Syncthing, Git or a Drive sync client can take it
 * from there. The app only rewrites or removes files it wrote itself, and the
 * SQLite database remains the source of truth.
 */
object MarkdownVault {
    const val FOLDER = "Aegentica"
    private const val MIME = "application/octet-stream"
    private fun prefs(context: Context) = context.getSharedPreferences("vault", 0)
    fun folder(context: Context): Uri? = prefs(context).getString("tree", null)?.let(Uri::parse)
    fun lastSync(context: Context) = prefs(context).getLong("synced", 0)
    fun setFolder(context: Context, tree: Uri) { prefs(context).edit().putString("tree", tree.toString()).remove("files").putLong("synced", 0).apply() }
    fun forget(context: Context) { prefs(context).edit().clear().apply() }

    fun fileName(record: Record): String {
        val title = record.title.ifBlank { record.body.lineSequence().firstOrNull { it.isNotBlank() }?.let(Transcripts::words).orEmpty() }.ifBlank { "Untitled ${record.kind}" }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80).trimEnd('.', ' ')
        return "${safe.ifBlank { record.kind }} (${record.id.take(8)}).md"
    }

    private fun iso(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
    private fun quote(value: String) = JSONObject.quote(value)
    private fun fence(text: String): String {
        var marks = "```"
        while (text.contains(marks)) marks += "`"
        return "${marks}text\n$text\n$marks"
    }

    /** Markdown for one record. [links] are (linked record, relation label); [fields] are (name, value). */
    fun render(record: Record, links: List<Pair<Record, String>>, fields: List<Pair<String, String>> = emptyList()): String = buildString {
        append("---\n")
        append("id: ").append(quote(record.id)).append('\n')
        append("kind: ").append(if (Transcripts.isTranscript(record)) "transcript" else record.kind).append('\n')
        append("created: ").append(iso(record.created)).append('\n')
        append("updated: ").append(iso(record.updated)).append('\n')
        if (record.source.isNotBlank()) append("source: ").append(quote(record.source)).append('\n')
        if (record.pinned) append("pinned: true\n")
        if (record.kind == "task") append("done: ").append(record.done).append('\n')
        if (record.due > 0) append("due: ").append(iso(record.due)).append('\n')
        fields.filter { it.second.isNotBlank() }.forEach { (name, value) -> append(quote(name)).append(": ").append(quote(value)).append('\n') }
        if (links.isNotEmpty()) {
            append("links:\n")
            links.forEach { (other, _) -> append("  - ").append(quote("[[" + fileName(other).removeSuffix(".md") + "]]")).append('\n') }
        }
        append("app: aegentica-workspace-v1\n---\n\n")
        append("# ").append(record.title.ifBlank { fileName(record).removeSuffix(".md").substringBeforeLast(" (") }).append("\n\n")
        val text = if (Transcripts.isTranscript(record) && record.body == record.original) Transcripts.words(record.body) else record.body
        if (text.isNotBlank()) append(text.trim()).append("\n\n")
        if (links.isNotEmpty()) {
            append("## Links\n\n")
            links.forEach { (other, label) -> append("- [[").append(fileName(other).removeSuffix(".md")).append("]] · ").append(label).append('\n') }
            append('\n')
        }
        if (record.original.isNotBlank() && record.original != record.body || Transcripts.isTranscript(record)) {
            append("## Verbatim original\n\n").append(fence(record.original)).append('\n')
        }
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    @Volatile private var syncing = false
    /** Background refresh when leaving the app; unchanged files are skipped, so sync tools see only real edits. */
    fun syncSoon(context: Context) {
        val app = context.applicationContext
        if (folder(app) == null || syncing || System.currentTimeMillis() - lastSync(app) < 120_000) return
        Thread { runCatching { sync(app) } }.start()
    }

    /** Writes changed files and removes ones this app wrote for records that are gone. Returns files written. */
    @Synchronized
    fun sync(context: Context): Int = try { syncing = true; write(context) } finally { syncing = false }

    private fun write(context: Context): Int {
        val tree = folder(context) ?: error("Choose a folder first.")
        val resolver = context.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val dir = child(context, tree, root, FOLDER, DocumentsContract.Document.MIME_TYPE_DIR)
            ?: DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, FOLDER) ?: error("Cannot create a folder there.")
        val previous = JSONObject(prefs(context).getString("files", "{}") ?: "{}")
        val next = JSONObject()
        val existing = children(context, tree, dir)
        var written = 0
        WorkspaceStore(context).use { store ->
            val records = store.list(limit = 10000).filter { it.kind != "routine" }
            for (record in records) {
                val links = store.linked(record.id).map { other ->
                    other to store.readableDatabase.rawQuery("SELECT label FROM links WHERE (a=? AND b=?) OR (a=? AND b=?) LIMIT 1", arrayOf(record.id, other.id, other.id, record.id)).use { if (it.moveToFirst()) it.getString(0) else "related" }
                }
                val fields = store.readableDatabase.rawQuery("SELECT p.name,v.value FROM property_values v JOIN properties p ON p.id=v.property WHERE v.record=? ORDER BY p.name", arrayOf(record.id)).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
                val text = render(record, links, fields)
                val name = fileName(record)
                val digest = hash(text)
                val before = previous.optJSONObject(record.id)
                next.put(record.id, JSONObject().put("name", name).put("hash", digest))
                if (before?.optString("name") == name && before.optString("hash") == digest && existing.containsKey(name)) continue
                before?.optString("name")?.takeIf { it != name }?.let { old -> existing[old]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } } }
                val target = existing[name] ?: DocumentsContract.createDocument(resolver, dir, MIME, name) ?: error("Cannot write $name.")
                resolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) } ?: error("Cannot write $name.")
                written++
            }
        }
        // Remove only files this app wrote for records that are now deleted or in Trash.
        for (id in previous.keys()) if (!next.has(id)) previous.optJSONObject(id)?.optString("name")?.let { old -> existing[old]?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } } }
        prefs(context).edit().putString("files", next.toString()).putLong("synced", System.currentTimeMillis()).apply()
        return written
    }

    private fun child(context: Context, tree: Uri, parent: Uri, name: String, mime: String): Uri? =
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name && c.getString(2) == mime) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            null
        }

    private fun children(context: Context, tree: Uri, parent: Uri): Map<String, Uri> =
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) }
        } ?: emptyMap()
}
