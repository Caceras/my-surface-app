package com.caceras.surfacelab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How an agent step is gated, from Glimt's model (apps/chatbar/src/lib/tools.ts):
 * - [READ] runs on its own: it changes nothing.
 * - [ACT] waits for the person's Allow, which they may give always for the app in front.
 * - [ALWAYS] asks every time and is never remembered: sending, calling, paying, buying,
 *   deleting and changing accounts.
 */
enum class Risk { READ, ACT, ALWAYS }

/** One tool as the playbook describes it: what the model sees, and how a call is gated. */
class ToolSpec(
    val name: String,
    val description: String,
    val risk: Risk,
    /** "phone" for the hands; "backend" for Ægentica's server tools. */
    val target: String,
    val label: String,
    val parameters: JSONObject,
) {
    val required: List<String> = parameters.optJSONArray("required").strings()
    val properties: Set<String> = parameters.optJSONObject("properties")?.keys()?.asSequence()?.toSet().orEmpty()
    /** Acting tools carry a one-line reason the person sees before they allow it. */
    val needsWhy get() = "why" in required

    /** The Chat Completions function definition. */
    fun definition(): JSONObject = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", description).put("parameters", JSONObject(parameters.toString())))

    /**
     * How the step reads to the person: the model's `why` when it gave one, else the label with
     * the call's own fields filled in ("Finding “Wi-Fi”"). Never markup, never longer than a line.
     */
    fun label(args: JSONObject): String {
        args.optString("why").oneLine().takeIf { it.isNotEmpty() }?.let { return it.take(160) }
        return Regex("\\{(\\w+)}").replace(label) { m -> args.opt(m.groupValues[1])?.toString()?.oneLine()?.take(40).orEmpty() }
            .replace(Regex("\\s+"), " ").replace("“”", "").trim().ifEmpty { name.replace('_', ' ') }
    }
}

/**
 * The one shared playbook (assets/playbook.json): tool names, risk tiers, the `why` rule and the
 * instruction wording, mirrored from Glimt and the live browser so the agents behave the same on
 * every platform. docs/playbook.md explains it. Model text never changes what is in here.
 */
class Playbook(json: JSONObject) {
    val version: Int = json.getInt("version")
    val tools: List<ToolSpec> = json.getJSONArray("tools").objects().map { t ->
        ToolSpec(
            name = t.getString("name"),
            description = t.getString("description"),
            risk = if (t.optBoolean("askEveryTime")) Risk.ALWAYS else when (t.getString("risk")) {
                "read" -> Risk.READ
                "act" -> Risk.ACT
                "always" -> Risk.ALWAYS
                else -> error("Unknown risk ${t.getString("risk")} for ${t.getString("name")}")
            },
            target = t.getString("target"),
            label = t.optString("label").ifBlank { t.getString("name") },
            parameters = t.getJSONObject("parameters"),
        )
    }
    private val byName = tools.associateBy { it.name }
    fun tool(name: String): ToolSpec? = byName[name]

    private val instructions = json.getJSONObject("instructions")
    val shared: List<String> = instructions.optJSONArray("shared").strings()
    val hands: List<String> = instructions.optJSONArray("hands").strings()
    val noHands: List<String> = instructions.optJSONArray("noHands").strings()
    /** When the phone is connected to Ægentica's back office (the aegentica_* tools). */
    val backend: List<String> = instructions.optJSONArray("backend").strings()

    private val sensitive = json.getJSONObject("sensitive")
    /** Words on a button or field that make a step always ask, by kind (send, call, pay, delete, account). */
    val sensitiveWords: Map<String, List<String>> = listOf("send", "call", "pay", "delete", "account")
        .associateWith { kind -> sensitive.optJSONArray(kind).strings().map { it.lowercase() } }
    val sensitiveSchemes: Set<String> = sensitive.optJSONArray("schemes").strings().map { it.lowercase() }.toSet()
    /** Banking, payment and store apps: every acting step in them always asks. */
    val sensitiveApps: Set<String> = sensitive.optJSONArray("apps").strings().toSet()
    /** Words in a field's label, hint or id that mark it as a password, card or code field. */
    val secretFields: List<String> = json.optJSONArray("secretFields").strings().map { it.lowercase() }

    private val why = json.getJSONObject("why")
    val whyMin = why.optInt("min", 3)
    val whyMax = why.optInt("max", 160)

    init {
        require(byName.size == tools.size) { "Tool names must be unique." }
        tools.forEach { t ->
            t.required.forEach { require(it in t.properties) { "${t.name} requires undeclared $it" } }
            require(t.risk == Risk.READ || t.needsWhy) { "${t.name} acts, so it must require why" }
            require(t.target == "phone" || t.target == "backend") { "${t.name} has an unknown target ${t.target}" }
        }
    }

    companion object {
        const val ASSET = "playbook.json"
        @Volatile private var loaded: Playbook? = null

        fun get(context: Context): Playbook = loaded ?: synchronized(this) {
            loaded ?: parse(context.assets.open(ASSET).use { it.readBytes().toString(Charsets.UTF_8) }).also { loaded = it }
        }

        fun parse(text: String) = Playbook(JSONObject(text))
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun String.oneLine(): String = replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
