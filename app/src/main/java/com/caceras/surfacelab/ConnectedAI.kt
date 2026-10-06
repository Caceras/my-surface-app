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
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Optional Chat Completions-compatible provider. No automatic provider choice or cloud fallback. */
object ConnectedAI {
    private const val KEY="aegentica-provider-key"
    private fun prefs(context:Context)=context.getSharedPreferences("connected-ai",0)
    fun enabled(context:Context)=prefs(context).getBoolean("enabled",false) && configured(context)
    fun configured(context:Context)=prefs(context).getString("endpoint","").orEmpty().isNotBlank() && prefs(context).getString("secret","").orEmpty().isNotBlank()
    fun host(context:Context)=runCatching { URL(prefs(context).getString("endpoint","")).host }.getOrDefault("Your provider")
    fun signature(context:Context)=listOf(prefs(context).getString("endpoint",""),prefs(context).getString("model","")).joinToString("|")
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
    private fun secret(context:Context):String {
        val parts=prefs(context).getString("secret","").orEmpty().split(":")
        require(parts.size==2) { "Configure your provider again." }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    fun validateEndpoint(raw:String):URL = URL(raw).also { require(it.protocol=="https" && it.host.isNotBlank() && it.userInfo==null && it.query==null && it.ref==null) { "Use a full HTTPS Chat Completions endpoint without credentials or query parameters." } }
    const val GEMINI_ENDPOINT="https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    /** A pinned stable model keeps polish and answers repeatable; aliases such as gemini-flash-latest can change underneath. */
    const val GEMINI_MODEL="gemini-3.8-flash"
    fun gemini(endpoint:String)=runCatching { URL(endpoint).host=="generativelanguage.googleapis.com" }.getOrDefault(false)
    /** Matching Library excerpts are sent to the provider only after this separate opt-in. */
    fun recallAllowed(context:Context)=prefs(context).getBoolean("recall",false)
    /** Gemini 3 models always reason, and reasoning tokens share the completion budget, so request low effort and leave room for the answer. */
    fun body(model:String,system:String,prompt:String,gemini:Boolean,maxTokens:Int=4096):JSONObject {
        val messages=JSONArray().put(JSONObject().put("role","system").put("content",system)).put(JSONObject().put("role","user").put("content",prompt))
        return JSONObject().put("model",model).put("messages",messages).put("max_completion_tokens",maxTokens).put("stream",false).apply { if(gemini) put("reasoning_effort","low") }
    }
    fun request(context:Context,prompt:String,task:Task=Task.ASK,onConnection:(HttpsURLConnection)->Unit={}):String {
        require(configured(context)) { "Set up connected AI first." }
        require(prompt.length<=16000) { "Shorten the question or remove some sources; connected requests are limited to 16,000 characters." }
        val settings=prefs(context)
        val endpoint=validateEndpoint(settings.getString("endpoint","").orEmpty())
        val conn=(endpoint.openConnection() as HttpsURLConnection)
        try {
            onConnection(conn)
            conn.instanceFollowRedirects=false; conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=90000; conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer ${secret(context)}"); conn.setRequestProperty("Content-Type","application/json")
            val body=body(settings.getString("model","").orEmpty(),Prompts.system(if(task==Task.UPPERCASE) Task.ASK else task),prompt,gemini(endpoint.toString()))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            require(conn.responseCode in 200..299) { "Provider returned HTTP ${conn.responseCode}. Check the model, key and provider account. No automatic retry was sent." }
            val json=JSONObject(conn.inputStream.use { it.readBounded(2_000_000) }.toString(Charsets.UTF_8))
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().also { require(it.isNotBlank()) { "The provider returned no text." } }
        } finally { conn.disconnect() }
    }
    val brain:SurfaceBrain=object:SurfaceBrain {
        private var generation=0
        @Volatile private var connection:HttpsURLConnection?=null
        override val tasks=listOf(Task.ASK,Task.POLISH)
        override fun status(context:Context,onStatus:(BrainStatus)->Unit) { onStatus(BrainStatus("Connected AI · ${host(context)}",configured(context))) }
        override fun prepare(context:Context,onStatus:(BrainStatus)->Unit)=status(context,onStatus)
        override fun cancel() { generation++; connection?.disconnect(); connection=null }
        override fun run(context:Context,task:Task,input:String,instruction:String,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) {
            cancel(); val token=generation; val app=context.applicationContext
            Thread {
                val result=runCatching { request(app,Prompts.user(task,input,instruction),task) { connection=it } }.fold({ BrainResult(it,true) },{ BrainResult.failure(it.message ?: "Connected AI could not respond.") })
                Handler(Looper.getMainLooper()).post { if(token==generation) onResult(result) }
            }.start()
        }
    }
    fun settings(activity:Activity,after:()->Unit={}) {
        val settings=prefs(activity)
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; padDp(20,8,20,8) }
        layout.addView(activity.label("Optional. Your questions, recent conversation and selected sources go to the provider you choose. Provider charges and privacy terms apply. No calendar or Beeper data is fetched automatically.",14f,true))
        fun field(hint:String,value:String)=EditText(activity).apply { styleField(); this.hint=hint; setSingleLine(); setText(value); layout.addView(this) }
        val endpoint=field("HTTPS Chat Completions endpoint",settings.getString("endpoint","").orEmpty())
        val model=field("Model ID",settings.getString("model","").orEmpty())
        layout.addView(activity.pill("Use Google Gemini") {
            endpoint.setText(GEMINI_ENDPOINT); model.setText(GEMINI_MODEL); endpoint.error=null
            Toast.makeText(activity,"Paste a Gemini API key from Google AI Studio (aistudio.google.com). Set a spending limit there.",Toast.LENGTH_LONG).show()
        }.apply { tag="connected-gemini" },LinearLayout.LayoutParams(-1,-2).apply { topMargin=activity.dp(4); bottomMargin=activity.dp(4) })
        val token=field(if(configured(activity)) "Key saved · leave blank to retain" else "API key","").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val enable=activity.preferenceSwitch("Use connected AI for typed chat",enabled(activity)) {}
        layout.addView(enable)
        val recall=activity.preferenceSwitch("Include matching Library excerpts",recallAllowed(activity)) {}
        layout.addView(recall)
        layout.addView(activity.label("Keys are encrypted with Android Keystore and excluded from exports. Nano remains available when this is off. Voice mode uses Nano.",12f,true))
        val dialog=AlertDialog.Builder(activity).setTitle("Connected AI").setView(ScrollView(activity).apply { addView(layout) }).setNegativeButton("Cancel",null).setNeutralButton("Disconnect") { _,_ -> settings.edit().clear().apply(); brain.cancel(); RoutineJobService.schedule(activity); after() }.setPositiveButton("Review",null).showProtected(activity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val url=validateEndpoint(endpoint.text.toString().trim()); require(model.text.isNotBlank()) { "Enter a model ID." }
                val encrypted=if(token.text.isNotBlank()) encrypt(token.text.toString().trim()) else settings.getString("secret","").orEmpty()
                require(encrypted.isNotBlank()) { "Enter your API key." }
                require(token.text.isNotBlank() || settings.getString("endpoint","")==url.toString()) { "Enter the key again when changing endpoint." }
                AlertDialog.Builder(activity).setTitle("Connect to ${url.host}?").setMessage("Endpoint: $url\nModel: ${model.text}\n\nWhen enabled, typed chat sends your question, recent conversation and selected source excerpts here${if(recall.isChecked) ", plus short excerpts of Library notes that match your question" else ""}. Routines require separate approval. Set spending limits with your provider.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Save connection") { _,_ ->
                        settings.edit().putString("endpoint",url.toString()).putString("model",model.text.toString().trim()).putString("secret",encrypted).putBoolean("enabled",enable.isChecked).putBoolean("recall",recall.isChecked).apply()
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
