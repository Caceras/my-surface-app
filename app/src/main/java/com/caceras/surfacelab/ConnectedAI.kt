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
    fun request(context:Context,prompt:String,onConnection:(HttpsURLConnection)->Unit={}):String {
        require(configured(context)) { "Set up connected AI first." }
        val settings=prefs(context)
        val endpoint=validateEndpoint(settings.getString("endpoint","").orEmpty())
        val conn=(endpoint.openConnection() as HttpsURLConnection)
        try {
            onConnection(conn)
            conn.instanceFollowRedirects=false; conn.requestMethod="POST"; conn.connectTimeout=15000; conn.readTimeout=45000; conn.doOutput=true
            conn.setRequestProperty("Authorization","Bearer ${secret(context)}"); conn.setRequestProperty("Content-Type","application/json")
            val messages=JSONArray().put(JSONObject().put("role","system").put("content",Prompts.system(Task.ASK))).put(JSONObject().put("role","user").put("content",prompt.take(16000)))
            val body=JSONObject().put("model",settings.getString("model","")).put("messages",messages).put("max_completion_tokens",1024).put("stream",false)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            require(conn.responseCode in 200..299) { "Provider returned HTTP ${conn.responseCode}. Check the model, key and provider account. No automatic retry was sent." }
            val json=JSONObject(conn.inputStream.use { it.readBounded(2_000_000) }.toString(Charsets.UTF_8))
            return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().also { require(it.isNotBlank()) { "The provider returned no text." } }
        } finally { conn.disconnect() }
    }
    val brain:SurfaceBrain=object:SurfaceBrain {
        private var generation=0
        @Volatile private var connection:HttpsURLConnection?=null
        override val tasks=listOf(Task.ASK)
        override fun status(context:Context,onStatus:(BrainStatus)->Unit) { onStatus(BrainStatus("Connected AI · ${host(context)}",configured(context))) }
        override fun prepare(context:Context,onStatus:(BrainStatus)->Unit)=status(context,onStatus)
        override fun cancel() { generation++; connection?.disconnect(); connection=null }
        override fun run(context:Context,task:Task,input:String,instruction:String,onPartial:(String)->Unit,onResult:(BrainResult)->Unit) {
            cancel(); val token=generation; val app=context.applicationContext
            Thread {
                val result=runCatching { request(app,Prompts.user(task,input,instruction)) { connection=it } }.fold({ BrainResult(it,true) },{ BrainResult.failure(it.message ?: "Connected AI could not respond.") })
                Handler(Looper.getMainLooper()).post { if(token==generation) onResult(result) }
            }.start()
        }
    }
    fun settings(activity:Activity) {
        val settings=prefs(activity)
        val layout=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; padDp(20,8,20,8) }
        layout.addView(activity.label("Optional. Your questions, recent conversation and selected sources go to the provider you choose. Provider charges and privacy terms apply. No calendar or Beeper data is fetched automatically.",14f,true))
        fun field(hint:String,value:String)=EditText(activity).apply { styleField(); this.hint=hint; setSingleLine(); setText(value); layout.addView(this) }
        val endpoint=field("HTTPS Chat Completions endpoint",settings.getString("endpoint","").orEmpty())
        val model=field("Model ID",settings.getString("model","").orEmpty())
        val token=field(if(configured(activity)) "Key saved · leave blank to retain" else "API key","").apply { inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val enable=activity.preferenceSwitch("Use connected AI for typed chat",enabled(activity)) {}
        layout.addView(enable)
        layout.addView(activity.label("Keys are encrypted with Android Keystore and excluded from exports. Nano remains available when this is off. Voice mode uses Nano.",12f,true))
        val dialog=AlertDialog.Builder(activity).setTitle("Connected AI").setView(ScrollView(activity).apply { addView(layout) }).setNegativeButton("Cancel",null).setNeutralButton("Disconnect") { _,_ -> settings.edit().clear().apply(); brain.cancel(); RoutineJobService.schedule(activity) }.setPositiveButton("Review",null).showProtected(activity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {
                val url=validateEndpoint(endpoint.text.toString().trim()); require(model.text.isNotBlank()) { "Enter a model ID." }
                val encrypted=if(token.text.isNotBlank()) encrypt(token.text.toString().trim()) else settings.getString("secret","").orEmpty()
                require(encrypted.isNotBlank()) { "Enter your API key." }
                require(token.text.isNotBlank() || settings.getString("endpoint","")==url.toString()) { "Enter the key again when changing endpoint." }
                AlertDialog.Builder(activity).setTitle("Connect to ${url.host}?").setMessage("Endpoint: $url\nModel: ${model.text}\n\nWhen enabled, typed chat sends your question, recent conversation and selected source excerpts here. Routines require separate approval. Set spending limits with your provider.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Save connection") { _,_ ->
                        settings.edit().putString("endpoint",url.toString()).putString("model",model.text.toString().trim()).putString("secret",encrypted).putBoolean("enabled",enable.isChecked).apply()
                        RoutineJobService.schedule(activity); dialog.dismiss()
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
