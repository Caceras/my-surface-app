package com.caceras.surfacelab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.widget.*

/** Narrow, explicitly selected reads; provider content never becomes executable instructions. */
object CalendarAccess {
    const val REQUEST=41
    private fun prefs(context:Context)=context.getSharedPreferences("connections",0)
    fun choose(activity:Activity,after:()->Unit) {
        if(activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(activity).setTitle("Show your agenda?").setMessage("Read the calendars you choose. Event creation still opens your calendar app for review.")
                .setNegativeButton("Not now",null).setPositiveButton("Choose calendars") { _,_ -> activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR),REQUEST) }.showProtected(activity)
            return
        }
        Thread {
            runCatching {
                activity.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf("_id","calendar_displayName","account_name"),null,null,"calendar_displayName ASC")?.use { c -> buildList { while(c.moveToNext()) add(c.getString(0) to "${c.getString(1)} · ${c.getString(2)}") } } ?: emptyList()
            }.onSuccess { calendars -> activity.runOnUiThread {
                if(activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                val chosen=prefs(activity).getStringSet("calendars",emptySet()).orEmpty().toMutableSet()
                AlertDialog.Builder(activity).setTitle("Calendars in Today")
                    .setMultiChoiceItems(calendars.map { it.second }.toTypedArray(),calendars.map { it.first in chosen }.toBooleanArray()) { _,n,on -> if(on) chosen+=calendars[n].first else chosen-=calendars[n].first }
                    .setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ -> prefs(activity).edit().putStringSet("calendars",chosen).apply(); after() }.showProtected(activity)
            } }.onFailure { e -> activity.runOnUiThread { if(!activity.isDestroyed) Toast.makeText(activity,"Calendar unavailable: ${e.message}",Toast.LENGTH_LONG).show() } }
        }.start()
    }
    fun showAgenda(activity:Activity,container:LinearLayout) {
        val ids=prefs(activity).getStringSet("calendars",emptySet()).orEmpty().filter { it.toLongOrNull()!=null }
        if(activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)!=PackageManager.PERMISSION_GRANTED || ids.isEmpty()) {
            container.addView(activity.label("Choose calendars to see the next seven days here.",13f,true).apply { padDp(0,8,0,8) }); return
        }
        Thread {
            val result=runCatching {
                val start=System.currentTimeMillis()
                val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it,start); ContentUris.appendId(it,start+7*86400000L) }.build()
                activity.contentResolver.query(uri,arrayOf("event_id","title","begin","end","allDay","calendar_id"),"calendar_id IN (${ids.joinToString(",") { "?" }})",ids.toTypedArray(),"begin ASC")?.use { c -> buildList { while(c.moveToNext() && size<20) add(AgendaEvent(c.getLong(0),c.getString(1).orEmpty(),c.getLong(2),c.getLong(3),c.getInt(4)==1)) } } ?: emptyList()
            }
            activity.runOnUiThread {
                if(activity.isDestroyed || !container.isAttachedToWindow) return@runOnUiThread
                container.removeAllViews()
                result.onSuccess { events ->
                    if(events.isEmpty()) container.addView(activity.label("No upcoming events in your selected calendars.",13f,true))
                    events.forEach { event ->
                        val whenText=if(event.allDay) java.time.Instant.ofEpochMilli(event.start).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()+" · All day" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(event.start))
                        container.addView(activity.pill("$whenText\n${event.title}") {
                            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW,ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,event.id)).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,event.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME,event.end)) }
                                .onFailure { Toast.makeText(activity,"No calendar app could open this event.",Toast.LENGTH_LONG).show() }
                        }.apply { gravity=android.view.Gravity.START })
                    }
                }.onFailure { container.addView(activity.label("Calendar access changed. Choose calendars again.",13f,true)) }
            }
        }.start()
    }
    private data class AgendaEvent(val id:Long,val title:String,val start:Long,val end:Long,val allDay:Boolean)
}

object BeeperAccess {
    const val READ_REQUEST=43
    const val SEND_REQUEST=44
    const val READ="com.beeper.android.permission.READ_PERMISSION"
    const val SEND="com.beeper.android.permission.SEND_PERMISSION"
    data class ChatRef(val id:String,val title:String,val protocol:String,val preview:String)
    data class MessageRef(val id:String,val room:String,val sender:String,val text:String,val time:Long)
    fun uri(path:String,vararg parameters:Pair<String,String>):Uri=Uri.Builder().scheme("content").authority("com.beeper.api").appendPath(path).apply { parameters.forEach { appendQueryParameter(it.first,it.second) } }.build()
    fun open(activity:Activity) {
        if(activity.packageManager.resolveContentProvider("com.beeper.api",0)==null) {
            AlertDialog.Builder(activity).setTitle("Beeper is not available").setMessage("Install or update Beeper on this phone. Its experimental Android integration must expose the content provider. Notes and AI work independently.").setPositiveButton("Done",null).showProtected(activity); return
        }
        if(activity.checkSelfPermission(READ)!=PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(activity).setTitle("Connect Beeper?").setMessage("Browse recent chats, then choose one to read. Nothing is imported or sent automatically. Beeper’s Android interface is experimental.")
                .setNegativeButton("Not now",null).setPositiveButton("Allow reading") { _,_ -> activity.requestPermissions(arrayOf(READ),READ_REQUEST) }.showProtected(activity); return
        }
        background<List<ChatRef>>(activity,{ activity.contentResolver.query(uri("chats","limit" to "50"),null,null,null,null)?.use { c -> buildList { while(c.moveToNext()) add(ChatRef(c.text("roomId"),c.text("title"),c.text("protocol"),c.text("messagePreview"))) } } ?: error("Beeper returned no provider response.") }) { chats ->
            if(chats.isEmpty()) { message(activity,"No recent chats found."); return@background }
            AlertDialog.Builder(activity).setTitle("Beeper · Choose a conversation").setItems(chats.map { "${it.title} · ${it.protocol}" }.toTypedArray()) { _,n -> conversation(activity,chats[n]) }.setNegativeButton("Close",null).showProtected(activity)
        }
    }
    private fun conversation(activity:Activity,chat:ChatRef) {
        background<List<MessageRef>>(activity,{
            activity.contentResolver.query(uri("messages","roomIds" to chat.id,"limit" to "40"),null,null,null,null)?.use { c -> buildList { while(c.moveToNext()) { if(c.text("isDeleted")=="1" || c.text("roomId")!=chat.id) continue; val text=c.text("text_content"); if(text.isNotBlank()) add(MessageRef(c.text("originalId"),c.text("roomId"),c.text("displayName"),text,c.text("timestamp").toLongOrNull() ?: 0)) } }.sortedBy { it.time } } ?: error("Messages unavailable.")
        }) { messages ->
            val text=messages.joinToString("\n\n") { "${it.sender}: ${it.text}" }.take(30000)
            AlertDialog.Builder(activity).setTitle(chat.title).setMessage(text.ifBlank { "No text messages available." })
                .setNegativeButton("Close",null).setNeutralButton("Draft reply") { _,_ -> draft(activity,chat,"") }
                .setPositiveButton("Use with AI") { _,_ ->
                    AlertDialog.Builder(activity).setTitle("Save selected context?").setMessage("Save this displayed conversation excerpt in your private Library and attach it to AI. It will remain until you delete it.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Save & open AI") { _,_ ->
                            WorkspaceStore(activity).use { store ->
                                val saved=store.save(Record(title="Beeper · ${chat.title}",body=text,source="Beeper ${chat.protocol}\nRoom: ${chat.id}\nMessages: ${messages.joinToString(",") { it.id }.take(8000)}"))
                                store.put("ai-context",saved.id)
                            }
                            val current=Chat.draft(activity)
                            Chat.saveDraft(activity,listOf(current,"Summarize this conversation and suggest an editable reply. Cite the saved source.").filter(String::isNotBlank).joinToString("\n\n"))
                            activity.startActivity(Intent(activity,MainActivity::class.java).putExtra("workspaceDraft",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        }.showProtected(activity)
                }.showProtected(activity)
        }
    }
    private fun draft(activity:Activity,chat:ChatRef,initial:String) {
        val key="beeper-draft:${chat.id}"
        val edit=EditText(activity).apply { styleField(); hint="Write or paste a reply"; minLines=4; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; filters=arrayOf(android.text.InputFilter.LengthFilter(10000)); setText(WorkspaceStore(activity).use { it.value(key,initial) }) }
        edit.afterChange { value -> WorkspaceStore(activity).use { it.put(key,value) } }
        val dialog=AlertDialog.Builder(activity).setTitle("Reply to ${chat.title}").setView(edit).setNegativeButton("Keep draft",null).setPositiveButton("Review",null).showProtected(activity)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text=edit.text.toString().trim()
            if(text.isBlank()) { edit.error="Write a reply first."; return@setOnClickListener }
            if(activity.checkSelfPermission(SEND)!=PackageManager.PERMISSION_GRANTED) { activity.requestPermissions(arrayOf(SEND),SEND_REQUEST); return@setOnClickListener }
            val action=java.util.UUID.randomUUID().toString()
            val review=AlertDialog.Builder(activity).setTitle("Send this message?").setMessage("To: ${chat.title} · ${chat.protocol}\nRoom: ${chat.id}\n\n$text")
                .setNegativeButton("Edit",null).setPositiveButton("Send",null).showProtected(activity)
            review.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
                button.isEnabled=false
                background(activity,{
                    WorkspaceStore(activity).use { store ->
                        require(store.execution(action,chat.id,"sending","Reviewed Beeper send")) { "This send was already submitted." }
                        try {
                            val response=activity.contentResolver.insert(uri("messages","roomId" to chat.id,"text" to text),null)
                            require(!response?.getQueryParameter("messageId").isNullOrBlank()) { "Beeper did not confirm delivery. Check the conversation before retrying." }
                            store.executionState(action,"sent",response!!.getQueryParameter("messageId").orEmpty()); store.put(key,"")
                        } catch(e:Exception) { store.executionState(action,"unknown","Check Beeper before retrying; this send will not retry automatically."); throw e }
                    }
                }) { review.dismiss(); dialog.dismiss(); message(activity,"Beeper accepted the message.") }
            }
        }
    }
    private fun Cursor.text(column:String):String { val index=getColumnIndex(column); return if(index<0 || isNull(index)) "" else getString(index) }
    private fun message(activity:Activity,text:String) { if(!activity.isDestroyed) Toast.makeText(activity,text,Toast.LENGTH_LONG).show() }
    private fun <T> background(activity:Activity,work:()->T,result:(T)->Unit) {
        Thread { runCatching(work).onSuccess { value -> activity.runOnUiThread { if(!activity.isDestroyed && !activity.isFinishing) result(value) } }
            .onFailure { error -> activity.runOnUiThread { message(activity,error.message ?: "Beeper is unavailable. Update it or check permissions.") } } }.start()
    }
}
