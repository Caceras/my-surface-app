package com.caceras.surfacelab

import android.app.*
import android.content.*
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/**
 * Optional Chat Completions-compatible provider: Google Gemini, OpenRouter (one
 * key, hundreds of models) or a local server such as Ollama or LM Studio on
 * the owner's Mac behind HTTPS. One provider answers at a time; each provider
 * keeps its own Keystore-encrypted key, so switching chat to OpenRouter never
 * removes the Gemini key that Live voice and Gemini Transcribe use. No
 * automatic provider choice, cloud fallback or retry.
 */
object ConnectedAI {
    private const val KEY="aegentica-provider-key"
    private fun prefs(context:Context)=context.getSharedPreferences("connected-ai",0)
    private fun endpoint(context:Context)=prefs(context).getString("endpoint","").orEmpty()
    fun enabled(context:Context)=prefs(context).getBoolean("enabled",false) && configured(context)
    fun configured(context:Context)=endpoint(context).isNotBlank() && (prefs(context).getString("secret","").orEmpty().isNotBlank() || prefs(context).getBoolean("keyless",false))
    fun host(context:Context)=runCatching { URL(endpoint(context)).host }.getOrDefault("Your provider")
    fun model(context:Context)=prefs(context).getString("model","").orEmpty()
    /** Short label for status lines: "OpenRouter · google/gemini-3.8-flash". */
    fun label(context:Context)=when { openRouter(endpoint(context)) -> "OpenRouter"; gemini(endpoint(context)) -> "Gemini"; prefs(context).getBoolean("keyless",false) -> "Local server"; else -> host(context) }+" · "+model(context)
    fun signature(context:Context)=listOf(endpoint(context),model(context)).joinToString("|")
    /** The chosen model accepts images (set from OpenRouter's model list, the Gemini preset or the owner's own switch). */
    fun vision(context:Context)=configured(context) && prefs(context).getBoolean("vision",gemini(endpoint(context)))
    /** Voice and the screen assistant use the provider only after this separate opt-in; otherwise they stay on Nano. */
    fun forVoice(context:Context)=enabled(context) && prefs(context).getBoolean("voice",false)
    /** The brain that voice and the assistant use: the connected provider after its own opt-in, otherwise Nano on the phone. */
    fun assistantBrain(context:Context):SurfaceBrain=if(forVoice(context)) brain else Brains.get()

    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(text:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(text.toByteArray()),Base64.NO_WRAP)
    }
    private fun decrypt(raw:String):String {
        val parts=raw.split(":")
        require(parts.size==2) { "Configure your provider again." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    /** Keystore encryption for other private settings (the LiveKit agent's access code). */
    internal fun seal(text:String)=encrypt(text)
    internal fun unseal(raw:String)=decrypt(raw)
    /** Encrypted key saved for a provider host. Older installs kept one key for the active endpoint only. */
    private fun savedKey(context:Context,host:String):String? {
        val settings=prefs(context)
        settings.getString("key:$host",null)?.takeIf { it.isNotBlank() }?.let { return it }
        val legacy=settings.getString("secret","").orEmpty()
        return legacy.takeIf { it.isNotBlank() && runCatching { URL(endpoint(context)).host }.getOrNull()==host }
    }
    private fun secret(context:Context):String = if(prefs(context).getBoolean("keyless",false)) "" else decrypt(prefs(context).getString("secret","").orEmpty())

    /** HTTPS, or plain HTTP inside the owner's Tailscale network (*.ts.net), which Tailscale already encrypts. */
    fun validateEndpoint(raw:String):URL = URL(raw).also { require((it.protocol=="https" || (it.protocol=="http" && tailnet(it.host))) && it.host.isNotBlank() && it.userInfo==null && it.query==null && it.ref==null) { "Use a full HTTPS Chat Completions endpoint without credentials or query parameters (plain HTTP only for a *.ts.net Tailscale address)." } }
    fun tailnet(host:String)=host.lowercase().endsWith(".ts.net")
    const val GEMINI_ENDPOINT="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    /** A pinned stable model keeps polish and answers repeatable; aliases such as gemini-flash-latest can change underneath. */
    const val GEMINI_MODEL="gemini-3.8-flash"
    private const val GEMINI_HOST="generativelanguage.googleapis.com"
    fun gemini(endpoint:String)=runCatching { URL(endpoint).host==GEMINI_HOST }.getOrDefault(false)
    const val OPENROUTER_ENDPOINT="https://openrouter.ai/api/v1/chat/completions"
    const val OPENROUTER_MODELS="https://openrouter.ai/api/v1/models"
    /** Sees screenshots, hears audio, calls tools and writes good Swedish (checked against OpenRouter's model list, October 2026). */
    const val OPENROUTER_MODEL="google/gemini-3.8-flash"
    fun openRouter(endpoint:String)=runCatching { URL(endpoint).host=="openrouter.ai" }.getOrDefault(false)
    /** Ollama and LM Studio both serve this path; the owner exposes it over HTTPS, for example with Tailscale Serve. */
    fun localEndpoint(base:String)=base.trim().trimEnd('/').let { if(it.endsWith("/chat/completions")) it else if(it.endsWith("/v1")) "$it/chat/completions" else "$it/v1/chat/completions" }

    /** Test-only stand-in for the Keystore-held key. */
    @Volatile var geminiKeyForTest:String?=null
    /** The saved Gemini key, whichever provider currently answers chat (Live voice and Transcribe use it). */
    fun geminiKey(context:Context):String? = geminiKeyForTest ?: savedKey(context,GEMINI_HOST)?.let { runCatching { decrypt(it) }.getOrNull() }
    fun geminiConfigured(context:Context)=savedKey(context,GEMINI_HOST)!=null
    /** Matching Library excerpts are sent to the provider only after this separate opt-in. */
    fun recallAllowed(context:Context)=prefs(context).getBoolean("recall",false)

    /** One chat turn; [image] is a JPEG the model is asked to look at (only sent to models that accept images). */
    class Message(val role:String,val text:String,val image:ByteArray?=null)

    /** Gemini 3 models always reason, and reasoning tokens share the completion budget, so request low effort and leave room for the answer. */
    fun body(model:String,system:String,prompt:String,gemini:Boolean,maxTokens:Int=4096):JSONObject =
        body(model,listOf(Message("system",system),Message("user",prompt)),if(gemini) GEMINI_ENDPOINT else "",maxTokens,stream=false)

    fun body(model:String,messages:List<Message>,endpoint:String,maxTokens:Int=4096,stream:Boolean=true,local:Boolean=false):JSONObject {
        val array=JSONArray()
        messages.forEach { m ->
            val content:Any=if(m.image==null) m.text else JSONArray().put(JSONObject().put("type","text").put("text",m.text))
                .put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:image/jpeg;base64,"+Base64.encodeToString(m.image,Base64.NO_WRAP))))
            array.put(JSONObject().put("role",m.role).put("content",content))
        }
        val json=JSONObject().put("model",model).put("messages",array).put("stream",stream)
        when {
            openRouter(endpoint) -> json.put("max_tokens",maxTokens)
                // Low reasoning keeps voice quick; the reasoning text itself is not needed. Providers that store or train on prompts are excluded.
                .put("reasoning",JSONObject().put("effort","low").put("exclude",true))
                .put("provider",JSONObject().put("data_collection","deny"))
            gemini(endpoint) -> json.put("max_completion_tokens",maxTokens).put("reasoning_effort","low")
            local -> json.put("max_tokens",maxTokens)
            else -> json.put("max_completion_tokens",maxTokens)
        }
        return json
    }

    /** A request in flight; cancel() aborts the connection. */
    fun interface Call { fun cancel() }
    class Response(val code:Int,val contentType:String,val body:InputStream,val close:()->Unit)
    fun interface Http { fun post(url:String,headers:Map<String,String>,body:ByteArray,onCall:(Call)->Unit):Response }
    /** Test-only network seam. */
    @Volatile var httpForTest:Http?=null
    private val http:Http get()=httpForTest ?: UrlHttp

    private object UrlHttp:Http {
        override fun post(url:String,headers:Map<String,String>,body:ByteArray,onCall:(Call)->Unit):Response {
            val conn=URL(url).openConnection() as java.net.HttpURLConnection
            onCall(Call { conn.disconnect() })
            conn.instanceFollowRedirects=false; conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=90000; conn.doOutput=true
            headers.forEach { (k,v) -> conn.setRequestProperty(k,v) }
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code=conn.responseCode
            val stream=(if(code in 200..299) conn.inputStream else conn.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
            return Response(code,conn.contentType.orEmpty(),stream) { runCatching { stream.close() }; conn.disconnect() }
        }
    }

    /** Plain-language reason for a failed request, from the HTTP status and the provider's own error message. */
    fun problem(code:Int,body:String,provider:String):String {
        val error=runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val detail=error?.optString("message").orEmpty().take(200)
        val source=error?.optJSONObject("metadata")?.optString("limit_source").orEmpty()
        return when(code) {
            401 -> "$provider rejected the key. Check it in Connected AI."
            402 -> "Your $provider account or key is out of credits. Add credits or raise the key's limit."
            403 -> if(source.contains("limit")||source.contains("budget")) "This key reached its spending limit at $provider." else "$provider blocked this request${if(detail.isNotBlank()) ": $detail" else "."}"
            404 -> "$provider does not know this model. Choose another model in Connected AI."
            408,504 -> "$provider timed out. Try again or choose a faster model."
            413 -> "The request is too large for $provider. Shorten it or leave out the screenshot."
            429 -> "$provider is rate limiting this key. Wait a moment and try again."
            502,503 -> "The model is unavailable at $provider right now. Try again or choose another model."
            else -> "$provider returned HTTP $code${if(detail.isNotBlank()) ": $detail" else ""}."
        }+" No automatic retry was sent."
    }

    /**
     * Sends [messages] and streams the answer: [onText] receives the whole
     * text so far after each fragment. Works with SSE streams (OpenRouter,
     * Gemini, Ollama, LM Studio) and with servers that ignore `stream` and
     * answer with one JSON object. Errors inside a 200 stream are failures.
     */
    fun complete(context:Context,messages:List<Message>,maxTokens:Int=4096,onCall:(Call)->Unit={},onText:(String)->Unit={}):String {
        require(configured(context)) { "Set up connected AI first." }
        val settings=prefs(context)
        val url=validateEndpoint(endpoint(context)).toString()
        val provider=label(context).substringBefore(" · ")
        val sent=if(vision(context)) messages else messages.map { Message(it.role,it.text) }
        val payload=body(model(context),sent,url,maxTokens,stream=true,local=settings.getBoolean("keyless",false)).toString().toByteArray()
        require(payload.size<=8_000_000) { "The request is too large to send." }
        val headers=buildMap {
            put("Content-Type","application/json"); put("Accept","text/event-stream, application/json")
            secret(context).takeIf { it.isNotBlank() }?.let { put("Authorization","Bearer $it") }
        }
        val response=http.post(url,headers,payload,onCall)
        try {
            if(response.code !in 200..299) error(problem(response.code,response.body.readBounded(200_000).toString(Charsets.UTF_8),provider))
            val text=if(response.contentType.contains("event-stream")) readStream(response.body,provider,onText)
                else readJson(response.body.readBounded(4_000_000).toString(Charsets.UTF_8),provider).also(onText)
            require(text.isNotBlank()) { "$provider returned no text. Try again or choose another model." }
            return text.trim()
        } finally { response.close() }
    }

    private fun failure(json:JSONObject,provider:String):String? = json.optJSONObject("error")?.let { e ->
        problem(e.optInt("code",500),JSONObject().put("error",e).toString(),provider)
    }

    internal fun readJson(raw:String,provider:String):String {
        val json=JSONObject(raw)
        failure(json,provider)?.let { error(it) }
        val choice=json.getJSONArray("choices").getJSONObject(0)
        return choice.getJSONObject("message").optString("content").takeUnless { it=="null" }.orEmpty()
    }

    internal fun readStream(input:InputStream,provider:String,onText:(String)->Unit):String {
        val out=StringBuilder()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for(raw in lines) {
                val line=raw.trim()
                if(line.isEmpty() || line.startsWith(":") || !line.startsWith("data:")) continue
                val data=line.removePrefix("data:").trim()
                if(data=="[DONE]") break
                val json=runCatching { JSONObject(data) }.getOrNull() ?: continue
                failure(json,provider)?.let { error(it) }
                val delta=json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val piece=if(delta.isNull("content")) "" else delta.optString("content")
                if(piece.isNotEmpty()) { out.append(piece); require(out.length<=400_000) { "The answer is too long." }; onText(out.toString()) }
            }
        }
        return out.toString()
    }

    fun request(context:Context,prompt:String,task:Task=Task.ASK,onCall:(Call)->Unit={}):String {
        require(prompt.length<=16000) { "Shorten the question or remove some sources; connected requests are limited to 16,000 characters." }
        return complete(context,listOf(Message("system",Prompts.system(if(task==Task.UPPERCASE) Task.ASK else task)),Message("user",prompt)),onCall=onCall)
    }

    /** Streams on a worker thread; [onPartial] and [onResult] arrive on the main thread and are dropped after cancel(). */
    class Run(private val context:Context,private val messages:List<Message>) {
        @Volatile private var call:Call?=null
        @Volatile private var cancelled=false
        fun start(onPartial:(String)->Unit,onResult:(BrainResult)->Unit) {
            val main=Handler(Looper.getMainLooper()); val app=context.applicationContext
            Thread {
                val result=runCatching { complete(app,messages,onCall={ call=it }) { text -> if(!cancelled) main.post { if(!cancelled) onPartial(text) } } }
                    .fold({ BrainResult(it,true) },{ BrainResult.failure(it.message ?: "Connected AI could not respond.") })
                main.post { if(!cancelled) onResult(result) }
            }.start()
        }
        fun cancel() { cancelled=true; call?.cancel() }
    }

    val brain:SurfaceBrain=object:SurfaceBrain {
        private var current:Run?=null
        override val tasks=listOf(Task.ASK,Task.POLISH)
        override fun status(context:Context,onStatus:(BrainStatus)->Unit) { onStatus(BrainStatus("Connected AI · ${host(context)}",configured(context))) }
        override fun prepare(context:Context,onStatus:(BrainStatus)->Unit)=status(context,onStatus)
        override fun cancel() { current?.cancel(); current=null }
        override fun run(context:Context,task:Task,input:String,instruction:String,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) =
            start(context,task,Prompts.user(task,input,instruction),null,onPartial,onResult)
        override fun seesImages(context:Context)=vision(context)
        override fun look(context:Context,task:Task,input:String,instruction:String,image:android.graphics.Bitmap,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) =
            start(context,task,Prompts.user(task,input,instruction),ScreenContexts.jpeg(image),onPartial,onResult)
        private fun start(context:Context,task:Task,prompt:String,image:ByteArray?,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) {
            cancel()
            if(prompt.length>16000) { onResult(BrainResult.failure("Shorten the question or remove some sources; connected requests are limited to 16,000 characters.")); return }
            val run=Run(context,listOf(Message("system",Prompts.system(if(task==Task.UPPERCASE) Task.ASK else task)),Message("user",prompt,image)))
            current=run
            run.start(onPartial) { result -> if(current===run) current=null; onResult(result) }
        }
    }

    data class ModelInfo(val id:String,val name:String,val inputs:List<String>,val promptPerMillion:Double,val completionPerMillion:Double,val tools:Boolean) {
        val vision get()="image" in inputs
        val audio get()="audio" in inputs
        val summary get()=listOfNotNull("sees images".takeIf { vision },"hears audio".takeIf { audio },"tools".takeIf { tools },
            if(promptPerMillion==0.0 && completionPerMillion==0.0) "free" else String.format(java.util.Locale.ROOT,"$%.2f in / $%.2f out per M tokens",promptPerMillion,completionPerMillion)).joinToString(" · ")
    }
    /** Shown first when present in OpenRouter's list: strong Swedish, screenshots, low cost (October 2026). */
    val RECOMMENDED=listOf(OPENROUTER_MODEL,"anthropic/claude-haiku-5.5","openai/gpt-6-luna","google/gemini-3.1-flash-lite","deepseek/deepseek-v4.1-flash")

    /** Text-answering models from OpenRouter's public list, recommended first, then by name. */
    fun parseModels(raw:String):List<ModelInfo> {
        val data=JSONObject(raw).getJSONArray("data")
        val models=(0 until data.length()).mapNotNull { i ->
            val m=data.optJSONObject(i) ?: return@mapNotNull null
            val arch=m.optJSONObject("architecture")
            fun list(key:String)=arch?.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            if("text" !in list("output_modalities")) return@mapNotNull null
            val pricing=m.optJSONObject("pricing")
            fun price(key:String)=(pricing?.optString(key)?.toDoubleOrNull() ?: 0.0)*1_000_000
            val params=m.optJSONArray("supported_parameters")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            ModelInfo(m.optString("id"),m.optString("name").ifBlank { m.optString("id") },list("input_modalities"),price("prompt"),price("completion"),"tools" in params)
        }.filter { it.id.isNotBlank() }
        return models.sortedWith(compareBy({ RECOMMENDED.indexOf(it.id).let { r -> if(r<0) Int.MAX_VALUE else r } },{ it.name.lowercase() }))
    }

    /** OpenRouter's model list is public; no key or personal data is sent. */
    fun fetchModels():List<ModelInfo> {
        val conn=URL(OPENROUTER_MODELS).openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects=false; conn.connectTimeout=15000; conn.readTimeout=30000
            require(conn.responseCode==200) { "OpenRouter's model list is unavailable (HTTP ${conn.responseCode})." }
            return parseModels(conn.inputStream.use { it.readBounded(12_000_000) }.toString(Charsets.UTF_8))
        } finally { conn.disconnect() }
    }

    /** Searchable OpenRouter model list; [chosen] receives the model the owner taps. */
    fun chooseModel(activity:Activity,models:List<ModelInfo>,chosen:(ModelInfo)->Unit) {
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; padDp(20,8,20,8) }
        val search=EditText(activity).apply { styleField(); hint="Search ${models.size} models"; setSingleLine(); tag="model-search" }
        layout.addView(search)
        val rows=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        layout.addView(rows)
        lateinit var dialog:AlertDialog
        fun render(query:String) {
            rows.removeAllViews()
            val terms=query.lowercase().split(' ').filter(String::isNotBlank)
            models.filter { m -> terms.all { t -> m.id.lowercase().contains(t) || m.name.lowercase().contains(t) } }.take(60).forEach { m ->
                rows.addView(LinearLayout(activity).apply {
                    orientation=LinearLayout.VERTICAL; padDp(0,10,0,10); tag="model-${m.id}"; isClickable=true; isFocusable=true
                    contentDescription=m.name+", "+m.summary
                    addView(activity.label(m.name+if(m.id in RECOMMENDED) " · recommended" else "",15f))
                    addView(activity.label(m.id+"\n"+m.summary,12f,true))
                    setOnClickListener { chosen(m); dialog.dismiss() }
                })
            }
        }
        search.afterChange { render(search.text.toString()) }
        render("")
        dialog=AlertDialog.Builder(activity).setTitle("Choose a model").setView(ScrollView(activity).apply { addView(layout) }).setNegativeButton("Cancel",null).showProtected(activity)
    }

    fun settings(activity:Activity,after:()->Unit={}) {
        val settings=prefs(activity)
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; padDp(20,8,20,8) }
        layout.addView(activity.label("Optional. Your questions, recent conversation and selected sources go to the provider you choose. Provider charges and privacy terms apply. No calendar or Beeper data is fetched automatically.",14f,true))
        fun field(hint:String,value:String)=EditText(activity).apply { styleField(); this.hint=hint; setSingleLine(); setText(value); layout.addView(this) }
        val presets=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
        layout.addView(presets,LinearLayout.LayoutParams(-1,-2).apply { topMargin=activity.dp(4); bottomMargin=activity.dp(4) })
        val endpoint=field("HTTPS Chat Completions endpoint",endpoint(activity))
        val model=field("Model ID",model(activity))
        val token=field("","").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        fun hostOf(raw:String)=runCatching { URL(raw.trim()).host }.getOrDefault("")
        fun refreshKeyHint() { val h=hostOf(endpoint.text.toString()); token.hint=if(h.isNotBlank() && savedKey(activity,h)!=null) "Key saved for $h · leave blank to keep" else "API key" }
        refreshKeyHint()
        var models:List<ModelInfo> = emptyList()
        val vision=activity.preferenceSwitch("Model can see images (screenshots)",settings.getBoolean("vision",gemini(endpoint(activity)))) {}.apply { tag="connected-vision" }
        val keyless=activity.preferenceSwitch("No key needed (my own local server)",settings.getBoolean("keyless",false)) {}.apply { tag="connected-keyless" }
        val choose=activity.pill("Choose OpenRouter model") {
            fun open(list:List<ModelInfo>) = chooseModel(activity,list) { m -> model.setText(m.id); vision.isChecked=m.vision }
            if(models.isNotEmpty()) { open(models); return@pill }
            Toast.makeText(activity,"Loading OpenRouter's model list…",Toast.LENGTH_SHORT).show()
            Thread { val result=runCatching { fetchModels() }; activity.runOnUiThread { if(activity.isFinishing) return@runOnUiThread
                result.onSuccess { models=it; open(it) }.onFailure { Toast.makeText(activity,it.message ?: "Could not load the model list.",Toast.LENGTH_LONG).show() } } }.start()
        }.apply { tag="connected-choose-model" }
        layout.addView(choose,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=activity.dp(4) })
        fun showChoose() { choose.visibility=if(openRouter(endpoint.text.toString())) android.view.View.VISIBLE else android.view.View.GONE }
        showChoose(); endpoint.afterChange { refreshKeyHint(); showChoose() }
        fun preset(title:String,tagName:String,action:()->Unit)=presets.addView(activity.pill(title) { action(); endpoint.error=null; refreshKeyHint(); showChoose() }.apply { tag=tagName },LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=activity.dp(4) })
        preset("Gemini","connected-gemini") {
            endpoint.setText(GEMINI_ENDPOINT); model.setText(GEMINI_MODEL); vision.isChecked=true; keyless.isChecked=false
            Toast.makeText(activity,"Paste a Gemini API key from Google AI Studio (aistudio.google.com). Set a spending limit there.",Toast.LENGTH_LONG).show()
        }
        preset("OpenRouter","connected-openrouter") {
            endpoint.setText(OPENROUTER_ENDPOINT); model.setText(OPENROUTER_MODEL); vision.isChecked=true; keyless.isChecked=false
            Toast.makeText(activity,"Create a key at openrouter.ai/settings/keys and give it a credit limit. Tap Choose OpenRouter model to switch models.",Toast.LENGTH_LONG).show()
        }
        preset("Local","connected-local") {
            if(!openRouter(endpoint.text.toString()) && !gemini(endpoint.text.toString()) && endpoint.text.isNotBlank()) endpoint.setText(localEndpoint(endpoint.text.toString()))
            else endpoint.setText("http://")
            keyless.isChecked=true; vision.isChecked=false
            Toast.makeText(activity,"Enter your Mac's Tailscale address, for example http://your-mac.your-tailnet.ts.net:11434 for Ollama or :1234 for LM Studio, and a model such as gemma4:12b.",Toast.LENGTH_LONG).show()
        }
        layout.addView(keyless); layout.addView(vision)
        val enable=activity.preferenceSwitch("Use connected AI for typed chat",enabled(activity)) {}
        layout.addView(enable)
        val voice=activity.preferenceSwitch("Also use it for voice and the screen assistant",settings.getBoolean("voice",false)) {}.apply { tag="connected-voice" }
        layout.addView(voice)
        val recall=activity.preferenceSwitch("Include matching Library excerpts",recallAllowed(activity)) {}
        layout.addView(recall)
        layout.addView(activity.label("Each provider's key is encrypted with Android Keystore and excluded from exports; Live voice and Transcribe keep using a saved Gemini key. OpenRouter requests skip providers that store or train on prompts. Nano remains available when this is off.",12f,true))
        val dialog=AlertDialog.Builder(activity).setTitle("Connected AI").setView(ScrollView(activity).apply { addView(layout) }).setNegativeButton("Cancel",null).setNeutralButton("Disconnect all") { _,_ -> settings.edit().clear().apply(); brain.cancel(); RoutineJobService.schedule(activity); after() }.setPositiveButton("Review",null).showProtected(activity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val raw=endpoint.text.toString().trim()
                val url=validateEndpoint(if(keyless.isChecked && !raw.contains("/chat/completions")) localEndpoint(raw) else raw); require(model.text.isNotBlank()) { "Enter a model ID." }
                val existing=savedKey(activity,url.host)
                val encrypted=when { token.text.isNotBlank() -> encrypt(token.text.toString().trim()); keyless.isChecked -> ""; else -> existing.orEmpty() }
                require(encrypted.isNotBlank() || keyless.isChecked) { "Enter the API key for ${url.host}." }
                AlertDialog.Builder(activity).setTitle("Connect to ${url.host}?").setMessage("Endpoint: $url\nModel: ${model.text}\n\nWhen enabled, typed chat sends your question, recent conversation and selected source excerpts here${if(recall.isChecked) ", plus short excerpts of Library notes that match your question" else ""}.${if(voice.isChecked) " Voice questions and, when you invoke the assistant, the text and screenshot of your screen are sent too." else ""} Routines require separate approval. Set spending limits with your provider.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Save connection") { _,_ ->
                        settings.edit().putString("endpoint",url.toString()).putString("model",model.text.toString().trim()).putString("secret",encrypted)
                            .apply { if(encrypted.isNotBlank()) putString("key:${url.host}",encrypted) }
                            .putBoolean("keyless",keyless.isChecked).putBoolean("vision",vision.isChecked).putBoolean("voice",voice.isChecked)
                            .putBoolean("enabled",enable.isChecked).putBoolean("recall",recall.isChecked).apply()
                        RoutineJobService.schedule(activity); dialog.dismiss(); after()
                        Toast.makeText(activity,"Connection saved. Send a short question to test it.",Toast.LENGTH_LONG).show()
                    }.showProtected(activity)
            }.onFailure { endpoint.error=it.message ?: "Check the connection details." }
        }
    }
    fun approveRoutine(activity:Activity,record:Record,after:()->Unit) {
        if(!configured(activity)) { settings(activity); return }
        val store=WorkspaceStore(activity)
        val linked=store.linked(record.id).take(5)
        AlertDialog.Builder(activity).setTitle("Run this routine with connected AI?")
            .setMessage("Provider: ${host(activity)}\nPrompt: ${record.body}\nSources: ${linked.joinToString { it.title.ifBlank { it.kind } }.ifBlank { "None" }}\n\nAt each scheduled time, this prompt and these source excerpts may leave your phone. One request per occurrence, with no automatic retry. Android can delay execution. Results are saved in Library; no messages or calendar changes are executed.")
            .setNegativeButton("Cancel",null).setNeutralButton("Use local reminder") { _,_ -> store.put("remote-routine:${record.id}",""); store.close(); Reminders.schedule(activity,record); after() }
            .setPositiveButton("Enable connected routine") { _,_ -> store.put("remote-routine:${record.id}",JSONObject().put("provider",signature(activity)).put("prompt",record.body).put("sources",linked.map { it.id }.joinToString(",")).toString()); store.close(); Reminders.schedule(activity,record); after() }
            .setOnCancelListener { store.close() }.showProtected(activity)
    }
    fun routineApproval(store:WorkspaceStore,context:Context,record:Record):JSONObject? = runCatching {
        JSONObject(store.value("remote-routine:${record.id}")).takeIf { configured(context) && it.getString("provider")==signature(context) && it.getString("prompt")==record.body }
    }.getOrNull()
}
