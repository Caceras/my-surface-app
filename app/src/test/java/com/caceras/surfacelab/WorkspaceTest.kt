package com.caceras.surfacelab

import android.content.Intent
import android.os.Looper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
class WorkspaceTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun `migration is transactional and does not resurrect cleared drafts`() {
        context.getSharedPreferences("surfacelab",0).edit().putString("draft","Before migration").putString("chat_turns","[{\"q\":\"Hello\",\"a\":\"Hej\"}]").commit()
        assertEquals("Before migration",Chat.draft(context))
        assertEquals(Turn("Hello","Hej"),Chat.load(context).single())
        Chat.saveDraft(context,"")
        assertEquals("",Chat.draft(context))
        assertNull(context.getSharedPreferences("surfacelab",0).getString("draft",null))
    }
    @Test fun `original text survives edits and stale editor cannot overwrite`() {
        WorkspaceStore(context).use { store ->
            val note=store.save(Record(body="The original thought"))
            val edited=store.save(note.copy(body="A better version",original="overwrite attempt"),note.revision)
            assertEquals("The original thought",edited.original)
            assertThrows(IllegalArgumentException::class.java) { store.save(note.copy(body="stale"),note.revision) }
            assertEquals("A better version",store.get(note.id)!!.body)
        }
    }
    @Test fun `search supports Swedish and removes trashed content until restored`() {
        WorkspaceStore(context).use { store ->
            val note=store.save(Record(title="Möte",body="Återkoppling från kunden"))
            assertEquals(note.id,store.list("återkoppling").single().id)
            store.save(note.copy(deleted=true)); assertTrue(store.list("återkoppling").isEmpty())
            val restored=store.save(store.get(note.id)!!.copy(deleted=false))
            assertEquals(restored.id,store.list("kunden").single().id)
            assertTrue(store.list("\" OR **").isEmpty())
        }
    }
    @Test fun `links and typed fields round trip without duplicate imports`() {
        val backup=WorkspaceStore(context).use { store ->
            val group=store.save(Record(kind="collection",title="Projects"))
            val item=store.save(Record(kind="project",title="Ægentica",body="Build thoughtful things"))
            store.link(group.id,item.id,"member"); store.addProperty(group.id,"Priority","number")
            store.setProperty(item.id,store.properties(group.id).single(),"2")
            store.backup()
        }
        context.deleteDatabase("aegentica.db")
        WorkspaceStore(context).use { store ->
            store.restore(backup); store.restore(backup)
            assertEquals(2,store.list().size)
            val group=store.list(kind="collection").single(); val item=store.linked(group.id,"member").single()
            assertEquals("2",store.propertyValue(item.id,store.properties(group.id).single().id))
            assertThrows(IllegalArgumentException::class.java) { store.setProperty(item.id,store.properties(group.id).single(),"many") }
        }
    }
    @Test fun `failed import rolls back records and fields`() {
        WorkspaceStore(context).use { store ->
            store.save(Record(title="Keep me",body="Safe"))
            val raw=JSONObject(store.backup()); raw.getJSONArray("links").put(JSONObject().put("a","missing").put("b","absent").put("label","related"))
            assertThrows(Exception::class.java) { store.restore(raw.toString()) }
            assertEquals("Safe",store.list().single().body)
        }
    }
    @Test fun `edited record conflicts import as stable separate copies`() {
        WorkspaceStore(context).use { store ->
            val note=store.save(Record(title="Plan",body="Version one")); val backup=store.backup()
            store.save(note.copy(body="Version two")); store.restore(backup); store.restore(backup)
            assertEquals(setOf("Version one","Version two"),store.list().map { it.body }.toSet())
            assertEquals(2,store.list().size)
        }
    }
    @Test fun `permanent deletion cascades relations and search while preserving linked records`() {
        WorkspaceStore(context).use { store ->
            val a=store.save(Record(body="First")); val b=store.save(Record(body="Second")); store.link(a.id,b.id)
            store.save(a.copy(deleted=true)); store.purge(a.id)
            assertNull(store.get(a.id)); assertEquals(b.id,store.list().single().id); assertTrue(store.linked(b.id).isEmpty()); assertTrue(store.list("First").isEmpty())
        }
    }
    @Test fun `recurring local time survives daylight saving and skips missed days`() {
        val zone=java.time.ZoneId.of("Europe/Stockholm")
        val first=java.time.ZonedDateTime.of(2026,3,28,9,0,0,0,zone)
        val record=Record(kind="routine",due=first.toInstant().toEpochMilli(),zone=zone.id,cadence="daily")
        val next=java.time.Instant.ofEpochMilli(Reminders.nextDue(record,first.toInstant().toEpochMilli()+1000)).atZone(zone)
        assertEquals(9,next.hour); assertEquals(23L,java.time.Duration.between(first,next).toHours())
        val late=first.plusDays(5).plusHours(1)
        assertTrue(Reminders.nextDue(record,late.toInstant().toEpochMilli())>late.toInstant().toEpochMilli())
    }
    @Test fun `claims prevent double sends and routine imports stay paused`() {
        WorkspaceStore(context).use { store ->
            val r=store.save(Record(kind="routine",title="Morning",body="Summarize my plans",due=1000))
            assertTrue(store.execution("occurrence",r.id,"started")); assertFalse(store.execution("occurrence",r.id,"started"))
            val backup=store.backup(); store.save(r.copy(deleted=true)); store.purge(r.id)
            store.restore(backup)
            assertFalse(store.get(r.id)!!.enabled)
        }
    }
    @Test fun `only selected live records enter AI context`() {
        WorkspaceStore(context).use { store ->
            val chosen=store.save(Record(title="Chosen",body="Known source")); store.save(Record(body="Do not include"))
            store.put("ai-context",chosen.id)
            val prompt=KnowledgeContext.prompt(context,"What did I write?")
            assertTrue(prompt.contains("Known source")); assertFalse(prompt.contains("Do not include")); assertTrue(prompt.contains("[1]"))
            store.save(chosen.copy(deleted=true)); assertEquals("Question",KnowledgeContext.prompt(context,"Question"))
        }
    }
    @Test fun `Beeper query parameters cannot change the chosen recipient`() {
        val uri=BeeperAccess.uri("messages","roomId" to "room&text=injected","text" to "Hello & goodbye")
        assertEquals("room&text=injected",uri.getQueryParameter("roomId")); assertEquals("Hello & goodbye",uri.getQueryParameter("text"))
        assertThrows(IllegalArgumentException::class.java) { ConnectedAI.validateEndpoint("http://example.com/chat") }
        assertThrows(IllegalArgumentException::class.java) { ConnectedAI.validateEndpoint("https://user:secret@example.com/chat") }
    }
    @Test fun `sentence chunks remain within engine limit and preserve surrogate pairs`() {
        val text="A long thought " + "😀".repeat(2200) + ". Another thought."
        val chunks=ReadingService.segments(text)
        assertEquals(text,chunks.joinToString("")); assertTrue(chunks.all { it.length<=2000 && !it.last().isHighSurrogate() })
    }
    @Test fun `note autosave survives activity recreation without starting dictation`() {
        val controller=Robolectric.buildActivity(WorkspaceActivity::class.java,WorkspaceActivity.intent(context,"library").putExtra("capture",true)).setup()
        val editor=ShadowDialog.getLatestDialog()
        editor.window!!.decorView.findViewWithTag<EditText>("note-body").setText("Keep this thought")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        controller.recreate()
        val restored=ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag<EditText>("note-body")
        assertEquals("Keep this thought",restored.text.toString())
        WorkspaceStore(context).use { assertEquals("Keep this thought",it.list().single().body) }
        controller.pause().stop().destroy()
    }
    @Test fun `pausing reader before engine initialization never resumes by itself`() {
        val controller=Robolectric.buildService(ReadingService::class.java).create()
        val service=controller.get()
        service.onStartCommand(Intent(context,ReadingService::class.java).putExtra("text","First sentence. Second sentence."),0,1)
        ReadingService.pauseForCapture()
        val engine=org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
        org.robolectric.shadows.ShadowTextToSpeech.addVoice(android.speech.tts.Voice("local",java.util.Locale.getDefault(),300,300,false,emptySet()))
        shadowOf(engine).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReadingService.snapshot.playing)
        assertTrue(shadowOf(engine).spokenTextList.isEmpty())
        assertEquals("First sentence. Second sentence.",WorkspaceStore(context).use { it.value("reading-text") })
        controller.destroy()
    }

    @Test fun `conflicting imported conversation remains accessible without replacing a live draft`() {
        Chat.saveDraft(context,"Imported thought")
        val backup=WorkspaceStore(context).use { it.backup() }
        Chat.saveDraft(context,"Current thought")
        WorkspaceStore(context).use { store ->
            store.restore(backup); store.restore(backup)
            assertEquals("Current thought",Chat.draft(context))
            assertEquals("Imported thought",store.list().single().body)
        }
    }
    @Test fun `interrupted connected occurrence is not replayed and does not strand a recurring routine`() {
        WorkspaceStore(context).use { store ->
            val now=java.time.Instant.parse("2026-09-16T12:00:00Z").toEpochMilli()
            val daily=store.save(Record(kind="routine",body="Plan today",due=now-60000,cadence="daily",zone="UTC"))
            val once=store.save(Record(kind="routine",body="One summary",due=now-60000,cadence="once",zone="UTC"))
            for(record in listOf(daily,once)) assertTrue(store.execution("remote:${record.id}:${record.due}",record.id,"started"))
            RoutineJobService.recoverInterrupted(store,now)
            assertTrue(store.get(daily.id)!!.due>now); assertTrue(store.get(daily.id)!!.enabled)
            assertFalse(store.get(once.id)!!.enabled)
            assertTrue(store.runs(daily.id).single().contains("interrupted"))
            assertFalse(store.execution("remote:${daily.id}:${daily.due}",daily.id,"started"))
            val next=store.get(daily.id)!!.due
            RoutineJobService.recoverInterrupted(store,now)
            assertEquals(next,store.get(daily.id)!!.due)
        }
    }

}
