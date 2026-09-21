package com.caceras.surfacelab

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HomeActivity : Activity() {
    private val pageKeys = listOf("today","calendar","ai","tasks","notes")
    private val pageTitles = listOf("Today","Calendar","AI","Tasks","Notes")
    private lateinit var store: WorkspaceStore
    private lateinit var pager: WorkspacePager
    private lateinit var assistantInput: EditText
    private lateinit var assistantSend: ImageButton
    private lateinit var answerCard: LinearLayout
    private lateinit var answerText: TextView
    private lateinit var nav: LinearLayout
    private val pageBodies = mutableMapOf<String, LinearLayout>()
    private var currentPage = 0
    private var busy = false
    private var requestId = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = WorkspaceStore(this)
        currentPage = state?.getInt("page") ?: pageKeys.indexOf(intent.getStringExtra("destination")).takeIf { it >= 0 } ?: 0
        renderShell()
        refreshAll()
        pager.setPage(currentPage, false)
        readableSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (::pager.isInitialized) refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing) {
            Brains.get().cancel()
            ConnectedAI.brain.cancel()
        }
        store.close()
        super.onDestroy()
    }

    private fun renderShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(ink(R.color.chat_bg)) }

        pager = WorkspacePager(this).apply {
            tag = "workspace-pager"
            onPageChanged = { index ->
                currentPage = index
                updateNav()
                updateAssistantHint()
            }
        }
        pageKeys.forEach { key -> pager.addPage(page(key)) }
        root.addView(pager, FrameLayout.LayoutParams(-1,-1).apply { bottomMargin = dp(132) })

        val floating = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(14,0,14,10)
            addView(answerPanel(), LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            addView(assistantBar(), LinearLayout.LayoutParams(-1,-2))
            addView(navigation(), LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        root.addView(floating, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))

        setContentView(AdaptiveFrame(this,root).apply { padForSystemBars() })
    }

    private fun page(key:String): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(16),dp(20),dp(190))
        }
        pageBodies[key] = content
        return ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(Color.TRANSPARENT)
            addView(content, ViewGroup.LayoutParams(-1,-2))
        }
    }

    private fun refreshAll() {
        pageKeys.forEach(::refreshPage)
        updateNav()
        updateAssistantHint()
    }

    private fun refreshPage(key:String) {
        val body = pageBodies[key] ?: return
        body.removeAllViews()
        body.addView(header(key))
        when(key) {
            "today" -> fillToday(body)
            "calendar" -> fillCalendar(body)
            "ai" -> fillAi(body)
            "tasks" -> fillTasks(body)
            "notes" -> fillNotes(body)
        }
    }

    private fun header(key:String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Ægentica AI",12f,true).apply { medium(); letterSpacing=.03f })
            addView(label(pageTitles[pageKeys.indexOf(key)],28f).apply { medium(); isAccessibilityHeading=true })
        },LinearLayout.LayoutParams(0,-2,1f))
        addView(label("•••",20f,true).apply {
            gravity=Gravity.CENTER; minWidth=dp(48); minHeight=dp(48); buttonSemantics(); isFocusable=true
            setOnClickListener { startActivity(Intent(this@HomeActivity,MainActivity::class.java).putExtra("settings",true)) }
        })
    }

    private fun section(parent:LinearLayout,title:String) {
        parent.addView(label(title,16f).apply { medium(); padDp(0,22,0,10) })
    }

    private fun muted(parent:LinearLayout,text:String) {
        parent.addView(label(text,14f,true).apply { padDp(0,4,0,10) })
    }

    private fun fillToday(parent:LinearLayout) {
        parent.addView(label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),13f,true).apply { padDp(0,16,0,4) })
        val tasks=store.list(kind="task").filter { !it.done }.sortedWith(compareBy<Record>{ if(it.due==0L) Long.MAX_VALUE else it.due }.thenByDescending { it.updated })
        section(parent,"Next")
        if(tasks.isEmpty()) muted(parent,"Nothing waiting.")
        tasks.take(4).forEach { parent.addView(recordCard(it)) }
        val pinned=store.list().filter { it.pinned }.take(4)
        if(pinned.isNotEmpty()) {
            section(parent,"Pinned")
            pinned.forEach { parent.addView(recordCard(it)) }
        }
    }

    private fun fillCalendar(parent:LinearLayout) {
        parent.addView(actionRow("Choose calendars") { CalendarAccess.choose(this) { refreshPage("calendar") } },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(10) })
        val agenda=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        parent.addView(agenda)
        CalendarAccess.showAgenda(this,agenda)
    }

    private fun fillAi(parent:LinearLayout) {
        val turns=Chat.load(this)
        if(turns.isEmpty()) {
            muted(parent,"Your conversation lives here. The same AI bar stays available on every page.")
            return
        }
        turns.takeLast(8).forEach { turn ->
            parent.addView(messageCard(turn.you,true))
            parent.addView(messageCard(turn.reply,false))
        }
    }

    private fun fillTasks(parent:LinearLayout) {
        parent.addView(actionRow("Add task") {
            startActivity(WorkspaceActivity.intent(this,"tasks").putExtra("newTask",true))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(12) })
        val tasks=store.list(kind="task").sortedWith(compareBy<Record>{it.done}.thenBy { if(it.due==0L) Long.MAX_VALUE else it.due }.thenByDescending { it.updated })
        if(tasks.isEmpty()) muted(parent,"No tasks yet.")
        tasks.forEach { parent.addView(recordCard(it)) }
    }

    private fun fillNotes(parent:LinearLayout) {
        parent.addView(actionRow("New note") {
            startActivity(WorkspaceActivity.intent(this,"library").putExtra("capture",true))
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(16); bottomMargin=dp(12) })
        val records=store.list().filter { it.kind!="task" && it.kind!="routine" }.take(60)
        if(records.isEmpty()) muted(parent,"No notes yet.")
        records.forEach { parent.addView(recordCard(it)) }
    }

    private fun recordCard(record:Record):View = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        background=glassSurface(20)
        padDp(16,14,16,14)
        addView(label(record.kind.replaceFirstChar(Char::uppercase)+(if(record.pinned) " · Pinned" else ""),11f,true))
        addView(label(record.title.ifBlank { record.body.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "Untitled" } },17f).apply {
            medium(); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END; padDp(0,5,0,3)
        })
        if(record.body.isNotBlank()) addView(label(record.body,13f,true).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
        isFocusable=true; buttonSemantics()
        setOnClickListener {
            startActivity(WorkspaceActivity.intent(this@HomeActivity,if(record.kind=="task") "tasks" else "library",record.id))
        }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) }
    }

    private fun messageCard(text:String,user:Boolean)=TextView(this).apply {
        this.text=if(user) text else Markdown.render(text,dp(18))
        textSize=if(user) 15f else 16f
        setTextColor(ink(R.color.text_primary))
        background=if(user) surface(R.color.bubble_you,20) else glassSurface(20)
        padDp(15,12,15,12)
        layoutParams=LinearLayout.LayoutParams(if(user) -2 else -1,-2).apply {
            gravity=if(user) Gravity.END else Gravity.START
            topMargin=dp(8)
        }
    }

    private fun actionRow(text:String,onClick:()->Unit)=TextView(this).apply {
        this.text=text; textSize=14f; medium(); gravity=Gravity.CENTER
        setTextColor(ink(R.color.text_primary))
        minHeight=dp(48); background=glassSurface(18)
        buttonSemantics(); isFocusable=true
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); onClick() }
    }

    private fun answerPanel():View {
        answerText=label("",15f).apply { setTextIsSelectable(true) }
        val actions=LinearLayout(this).apply {
            gravity=Gravity.END
            addView(miniAction("Save note") {
                val text=answerText.text.toString().trim()
                if(text.isNotBlank()) { store.save(Record(title=text.lineSequence().first().take(80),body=text)); refreshPage("notes"); toast("Saved to Notes") }
            })
            addView(miniAction("Add task") {
                val text=Markdown.strip(answerText.text.toString()).lineSequence().firstOrNull().orEmpty().take(200)
                if(text.isNotBlank()) { store.save(Record(kind="task",title=text,body=text)); refreshPage("tasks"); refreshPage("today"); toast("Task added") }
            })
            addView(miniAction("Close") { answerCard.visibility=View.GONE })
        }
        answerCard=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=glassSurface(22,true)
            padDp(16,14,12,10)
            visibility=View.GONE
            addView(answerText)
            addView(actions,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        return answerCard
    }

    private fun miniAction(text:String,onClick:()->Unit)=TextView(this).apply {
        this.text=text; textSize=12f; medium(); gravity=Gravity.CENTER
        minHeight=dp(40); minWidth=dp(48); padDp(10,6,10,6)
        setTextColor(ink(R.color.accent_text)); buttonSemantics(); isFocusable=true
        setOnClickListener { onClick() }
    }

    private fun assistantBar():View {
        assistantInput=EditText(this).apply {
            background=null
            textSize=16f
            setTextColor(ink(R.color.text_primary))
            setHintTextColor(ink(R.color.text_dim))
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines=4
            imeOptions=EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _,action,_ -> if(action==EditorInfo.IME_ACTION_SEND){ submitAssistant(); true } else false }
            padDp(6,8,6,8)
        }
        assistantSend=ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background=getDrawable(R.drawable.send_bg)
            minimumWidth=dp(48); minimumHeight=dp(48)
            contentDescription="Send"
            setPadding(dp(10),dp(10),dp(10),dp(10))
            setOnClickListener { submitAssistant() }
        }
        return LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            background=glassSurface(28,true)
            padDp(10,6,8,6)
            addView(TextView(this@HomeActivity).apply {
                text="Æ"; textSize=20f; medium(); gravity=Gravity.CENTER
                setTextColor(ink(R.color.accent_text)); minWidth=dp(44); minHeight=dp(48)
                buttonSemantics(); isFocusable=true
                setOnClickListener { pager.setPage(2,true) }
            })
            addView(assistantInput,LinearLayout.LayoutParams(0,-2,1f))
            addView(assistantSend,LinearLayout.LayoutParams(dp(48),dp(48)))
        }
    }

    private fun navigation():View {
        nav=LinearLayout(this).apply {
            gravity=Gravity.CENTER
            background=glassSurface(22)
            padDp(5,4,5,4)
        }
        pageTitles.forEachIndexed { index,title ->
            nav.addView(TextView(this).apply {
                text=title; textSize=11f; gravity=Gravity.CENTER; minHeight=dp(42)
                buttonSemantics(); isFocusable=true
                setOnClickListener { pager.setPage(index,true) }
            },LinearLayout.LayoutParams(0,dp(42),1f))
        }
        updateNav()
        return nav
    }

    private fun updateNav() {
        if(!::nav.isInitialized) return
        for(i in 0 until nav.childCount) {
            val item=nav.getChildAt(i) as TextView
            val active=i==currentPage
            item.setTextColor(ink(if(active) R.color.on_accent else R.color.text_dim))
            item.background=if(active) surface(R.color.accent,16) else null
            item.typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateAssistantHint() {
        if(!::assistantInput.isInitialized) return
        assistantInput.hint=when(pageKeys[currentPage]) {
            "tasks" -> "Ask or add a task…"
            "notes" -> "Ask about your notes…"
            "calendar" -> "Ask about your schedule…"
            "ai" -> "Continue the conversation…"
            else -> "Ask Ægentica…"
        }
    }

    private fun currentContext():String = when(pageKeys[currentPage]) {
        "tasks" -> store.list(kind="task").take(12).joinToString("\n") { "- "+it.title.ifBlank { it.body.take(80) }+(if(it.done) " [done]" else "") }
        "notes" -> store.list().filter { it.kind!="task" && it.kind!="routine" }.take(10).joinToString("\n") { "- "+it.title.ifBlank { it.body.take(80) } }
        "calendar" -> "The user is viewing Calendar. Calendar events are private Android data and only selected or saved events are included in AI context."
        "ai" -> Chat.load(this).takeLast(6).joinToString("\n") { "User: "+it.you+"\nAI: "+it.reply.take(400) }
        else -> {
            val tasks=store.list(kind="task").filterNot { it.done }.take(6)
            val pinned=store.list().filter { it.pinned }.take(5)
            buildString {
                if(tasks.isNotEmpty()) append("Open tasks:\n"+tasks.joinToString("\n"){"- "+it.title.ifBlank { it.body.take(80) }})
                if(pinned.isNotEmpty()) append("\nPinned:\n"+pinned.joinToString("\n"){"- "+it.title.ifBlank { it.body.take(80) }})
            }
        }
    }

    private fun submitAssistant() {
        val question=assistantInput.text.toString().trim()
        if(question.isBlank() || busy) return
        handleDirectWrite(question)?.let { result ->
            assistantInput.setText("")
            showAnswer(result)
            return
        }
        busy=true
        val token=++requestId
        assistantSend.alpha=.45f; assistantSend.isEnabled=false
        answerCard.visibility=View.VISIBLE
        answerText.text="Thinking…"
        val history=Chat.load(this)
        val contextual="Current screen: "+pageTitles[currentPage]+".\n"+currentContext()+"\n\nUser request:\n"+question
        val instruction=KnowledgeContext.prompt(this,Prompts.conversation(history,contextual))
        val brain=if(ConnectedAI.enabled(this)) ConnectedAI.brain else Brains.get()
        brain.run(this,Task.ASK,input="",instruction=instruction,onPartial={ partial ->
            if(token!=requestId) return@run
            val reply=Prompts.reply(partial)
            if(!Prompts.isEcho(reply,Task.ASK)) answerText.text=Markdown.render(reply,dp(18),true)
        }) { result ->
            if(token!=requestId) return@run
            busy=false; assistantSend.alpha=1f; assistantSend.isEnabled=true
            val said=Prompts.reply(result.text)
            if(result.ok && !Prompts.isEcho(said,Task.ASK)) {
                answerText.text=Markdown.render(said,dp(18))
                Chat.append(this,Turn(question,said))
                assistantInput.setText("")
                refreshPage("ai")
            } else {
                answerText.text=result.note ?: "Could not answer that."
            }
        }
    }

    private fun handleDirectWrite(question:String):String? {
        Regex("^(?:add|create|make)\\s+(?:a\\s+)?task\\s*[:\\-]?\\s*(.+)$",RegexOption.IGNORE_CASE).matchEntire(question)?.let {
            val text=it.groupValues[1].trim().take(500)
            val record=store.save(Record(kind="task",title=text.take(200),body=text))
            refreshPage("tasks"); refreshPage("today")
            return "Added task: "+record.title
        }
        Regex("^(?:save|create|make)\\s+(?:a\\s+)?note\\s*[:\\-]?\\s*(.+)$",RegexOption.IGNORE_CASE).matchEntire(question)?.let {
            val text=it.groupValues[1].trim().take(10000)
            val record=store.save(Record(title=text.lineSequence().first().take(200),body=text))
            refreshPage("notes")
            return "Saved note: "+record.title
        }
        Regex("^mark\\s+(?:task\\s+)?(.+?)\\s+done$",RegexOption.IGNORE_CASE).matchEntire(question)?.let {
            val needle=it.groupValues[1].trim()
            val match=store.list(kind="task").firstOrNull { task -> task.title.contains(needle,true) || task.body.contains(needle,true) }
                ?: return "I couldn't find a matching task."
            store.save(match.copy(done=true),match.revision)
            refreshPage("tasks"); refreshPage("today")
            return "Completed: "+match.title.ifBlank { needle }
        }
        return null
    }

    private fun showAnswer(text:String) {
        answerCard.visibility=View.VISIBLE
        answerText.text=text
    }

    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_SHORT).show()
}
