package com.caceras.surfacelab

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.text.*
import android.view.*
import android.view.GestureDetector
import android.widget.*
import java.text.DateFormat
import java.util.*

/** Today and Library are views of the same records. AI stays in the shared conversation. */
class WorkspaceActivity : Activity() {
    private lateinit var store: WorkspaceStore
    private lateinit var body: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var pageHost: FrameLayout
    private var pageShell: AdaptiveFrame? = null
    private var destination="today"
    private var filter=""
    private var query=""
    private var editor: Dialog?=null
    private var flush: (() -> Unit)?=null
    private var ears:Ears?=null
    private var dictate: (() -> Unit)?=null
    private var stopDictation: (() -> Unit)?=null
    private val handler=Handler(Looper.getMainLooper())
    private var pendingSearch:Runnable?=null
    private var loaded=false
    private var importRaw:String?=null
    private lateinit var pageGestures: GestureDetector

    override fun onCreate(state:Bundle?) {
        super.onCreate(state); store=WorkspaceStore(this)
        destination=state?.getString("destination") ?: intent.getStringExtra("destination") ?: "today"
        filter=state?.getString("filter").orEmpty(); query=state?.getString("query").orEmpty()
        pageGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(first: MotionEvent?, last: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if(first==null || kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY) * 1.25f || kotlin.math.abs(last.x-first.x) < dp(72)) return false
                val order=listOf("today","calendar","ai","tasks","library")
                val current=order.indexOf(destination).coerceAtLeast(0)
                val next=(current + if(last.x < first.x) 1 else -1).coerceIn(0,order.lastIndex)
                if(next==current) return false
                val target=order[next]
                val direction=if(last.x < first.x) 1 else -1
                if(target=="ai") {
                    startActivity(Intent(this@WorkspaceActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    smoothPageTransition(direction)
                } else switchDestination(target,direction)
                return true
            }
        })
        render(); loaded=true
        val record=state?.getString("editing") ?: intent.getStringExtra("record")
        if(record!=null) store.get(record)?.let { edit(it) }
        else if(state==null && intent.getBooleanExtra("capture",false)) capture(intent.getStringExtra("text").orEmpty())
    }
    private var editingId:String?=null
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun section(title:String) { rows.addView(label(title,17f).apply { medium(); isAccessibilityHeading=true; padDp(0,18,0,8) }) }
    private fun note(text:String) { rows.addView(label(text,14f,true).apply { padDp(0,4,0,12) }) }
    private fun tools(vararg actions:Pair<String,()->Unit>)=HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled=false
        addView(LinearLayout(this@WorkspaceActivity).apply { actions.forEach { (title,action) ->
            val selected=title.startsWith("• ")
            addView(pill(title.removePrefix("• "),selected) { stopDictation?.invoke(); action() },LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(8) })
        } })
    }
    internal fun switchDestination(target:String, direction:Int=0) {
        if(target==destination) return
        destination=target
        filter=""
        query=""
        intent.putExtra("destination",target)
        render(direction)
    }

    private fun render(direction:Int=0) {
        val previous = if (::body.isInitialized) body else null
        body=column().apply { setBackgroundColor(android.graphics.Color.TRANSPARENT); padDp(18,12,18,0) }
        body.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(column().apply {
                addView(label("Ægentica AI",12f,true).apply { medium(); letterSpacing=.03f })
                addView(label(when(destination) { "calendar" -> "Calendar"; "tasks" -> "Tasks"; "library" -> "Notes"; else -> "Today" },27f).apply { medium(); isAccessibilityHeading=true })
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(pill("More") { menu() }.apply { contentDescription="Workspace options" })
        })
        if(destination=="library") {
            val search=EditText(this).apply { styleField(); hint="Search your knowledge"; setSingleLine(); setText(query); tag="workspace-search" }
            body.addView(search,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(14); bottomMargin=dp(10) })
            search.afterChange {
                query=it; pendingSearch?.let(handler::removeCallbacks)
                pendingSearch=Runnable { populate() }.also { task -> handler.postDelayed(task,160) }
            }
            body.addView(tools(*listOf("" to "All","note" to "Notes","project" to "Projects","person" to "People","collection" to "Collections","task" to "Tasks","routine" to "Routines","trash" to "Trash").map { (value,title) ->
                (if(filter==value) "• $title" else title) to { filter=value; render() }
            }.toTypedArray()))
        }
        rows=column().apply { padDp(0,8,0,20) }
        body.addView(ScrollView(this).apply {
            isFillViewport=true; addView(rows)
            setOnTouchListener { _, event -> pageGestures.onTouchEvent(event); false }
        },LinearLayout.LayoutParams(-1,0,1f))
        if(destination=="today" || destination=="library") body.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(pill("Capture",true) { capture() }.apply { contentDescription="Capture a thought" },LinearLayout.LayoutParams(0,-2,1f))
            addView(pill("New") { newRecord() }.apply { contentDescription="Create task, project, person, collection or routine" },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(8) })
        },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        body.addView(workspaceNavigation(destination))
        val next = body
        if (pageShell == null) {
            pageHost = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                setBackgroundColor(ink(R.color.chat_bg))
                addView(next, FrameLayout.LayoutParams(-1,-1))
            }
            pageShell = AdaptiveFrame(this,pageHost).apply { padForSystemBars() }
            setContentView(pageShell)
            readableSystemBars()
        } else if (previous != null && previous !== next) {
            pageHost.addView(next, FrameLayout.LayoutParams(-1,-1))
            val width = pageHost.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            if (direction == 0 || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
                pageHost.removeView(previous)
            } else {
                val distance = width.toFloat()
                next.translationX = direction * distance
                next.alpha = 0.96f
                next.animate().translationX(0f).alpha(1f).setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                previous.animate().translationX(-direction * distance).alpha(0.96f).setDuration(240)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .withEndAction {
                        pageHost.removeView(previous)
                        previous.translationX = 0f
                        previous.alpha = 1f
                    }.start()
            }
        }
        populate()
    }
    private fun populate() {
        rows.removeAllViews()
        when(destination) {
            "library" -> {
                val all=store.list(query,if(filter=="trash") "" else filter,filter=="trash")
                if(all.isEmpty()) {
                    section(if(query.isNotBlank()) "Nothing found" else if(filter=="trash") "Trash is empty" else "No items")
                    note(when {
                        query.isNotBlank() -> "Try another word or change the filter."
                        filter=="trash" -> "Deleted items appear here."
                        else -> "Capture or create something to get started."
                    })
                }
                all.forEach(::card)
                if(all.size==200) note("Showing the latest 200 items. Search to find older records.")
            }
            "calendar" -> {
                rows.addView(pill("Choose calendars") { CalendarAccess.choose(this) { populate() } })
                val agenda=column(); rows.addView(agenda)
                CalendarAccess.showAgenda(this,agenda)
            }
            "tasks" -> {
                val tasks=store.list(kind="task").sortedWith(compareBy<Record> { it.done }.thenBy { if(it.due==0L) Long.MAX_VALUE else it.due }.thenByDescending { it.updated })
                if(tasks.isEmpty()) note("No tasks yet.")
                tasks.forEach(::card)
                rows.addView(pill("Add task",true) { edit(Record(kind="task")) })
            }
            else -> {
                rows.addView(label(java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")),14f,true).apply { padDp(0,18,0,4) })
                val draft=store.value("capture")
                if(draft.isNotBlank()) rows.addView(pill("Continue note") { capture() })
                val tasks=store.list(kind="task").filter { !it.done }.sortedWith(compareBy<Record> { if(it.due==0L) Long.MAX_VALUE else it.due }.thenBy { it.created })
                section("Next")
                if(tasks.isEmpty()) note("Nothing waiting.")
                tasks.take(3).forEach(::card)
                val pinned=store.list().filter { it.pinned }.take(4)
                if(pinned.isNotEmpty()) { section("Pinned"); pinned.forEach(::card) }
                val routines=store.list(kind="routine").filter { it.enabled }.take(3)
                if(routines.isNotEmpty()) { section("Routines"); routines.forEach(::card) }
            }
        }
    }
    private fun card(record:Record) {
        val card=column().apply {
            tag="record-${record.id}"; background=glassSurface(20); elevation=dp(2).toFloat(); padDp(16,15,16,14)
            addView(label(record.kind.replaceFirstChar { it.uppercase() } + if(record.pinned) " · Pinned" else "",11f,true).apply { letterSpacing=.03f })
            addView(label(record.title.ifBlank { record.body.lineSequence().firstOrNull().orEmpty().take(80).ifBlank { "Untitled ${record.kind}" } },17f).apply { medium(); maxLines=2; ellipsize=TextUtils.TruncateAt.END; padDp(0,5,0,3) })
            if(record.body.isNotBlank()) addView(label(record.body,13f,true).apply { maxLines=2; ellipsize=TextUtils.TruncateAt.END })
            if(record.due>0) addView(label((if(record.done) "Completed · " else if(!record.enabled) "Paused · " else "")+date(record.due),12f,true).apply { padDp(0,8,0,0) })
            isFocusable=true; buttonSemantics(); setOnClickListener { edit(record) }
        }
        rows.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }
    private fun newRecord() {
        val kinds=WorkspaceStore.KINDS
        AlertDialog.Builder(this).setTitle("Create something")
            .setItems(kinds.map { it.replaceFirstChar(Char::uppercase) }.toTypedArray()) { _,which -> if(kinds[which]=="note") capture() else edit(Record(kind=kinds[which])) }.showProtected(this)
    }
    private fun capture(shared:String="") {
        val existing=store.value("capture")
        val text=listOf(existing,shared).filter(String::isNotBlank).joinToString("\n\n")
        edit(Record(body=text,original=""),true)
    }
    private fun edit(initial:Record, capture:Boolean=false) {
        editor?.dismiss()
        var record=store.get(initial.id) ?: initial
        var saving=false
        var textFromVoice=false
        var recognizing=false
        var closed=false
        var savedTask:Runnable?=null
        val dialog=Dialog(this); editor=dialog; editingId=record.id
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val layout=column().apply { setBackgroundColor(ink(R.color.chat_bg)); padDp(20,12,20,8) }
        val status=label(if(capture) "Draft saved on this phone" else "Saved on this phone",12f,true).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE; tag="note-save-state" }
        val title=EditText(this).apply { styleField(); hint="Title"; setSingleLine(); filters=arrayOf(InputFilter.LengthFilter(200)); setText(record.title); tag="note-title" }
        val input=EditText(this).apply {
            styleField(); hint=if(record.kind=="routine") "What should AI help you with?" else "Write something…"
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines=6; gravity=Gravity.TOP; filters=arrayOf(InputFilter.LengthFilter(200000)); setText(record.body); tag="note-body"
        }
        fun save(final:Boolean=false):Boolean {
            if(saving) return true
            savedTask?.let(handler::removeCallbacks); savedTask=null
            if(title.text.isBlank() && input.text.isBlank() && store.get(record.id)==null) return true
            return runCatching {
                saving=true
                val next=record.copy(title=title.text.toString(),body=input.text.toString(),original=if(final && record.original.isBlank()) input.text.toString() else record.original)
                if(store.get(record.id)==null || next!=record) record=store.save(next,store.get(record.id)?.let { record.revision })
                if(capture) store.put("capture","")
                status.text="Saved on this phone"; SurfaceWidgetProvider.refresh(this)
            }.onFailure { status.text=it.message ?: "Could not save. Keep this screen open and copy your text." }.also { saving=false }.isSuccess
        }
        fun close() { if(save(true)) dialog.dismiss() }
        flush={ save() }
        layout.addView(sheetHeader(record.kind.replaceFirstChar(Char::uppercase)) { close() })
        layout.addView(status,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val content=column()
        content.addView(title,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        content.addView(input)
        val mic=pill("Dictate") {}
        val speech=Ears(this); ears=speech
        stopDictation={ recognizing=false; speech.cancel(); mic.text="Dictate"; mic.speechLevel(0f) }
        val startDictation:()->Unit = {
            ReadingService.pauseForCapture()
            if(!speech.available()) {
                status.text="On-device dictation is unavailable. Keep typing or open voice setup."
                AlertDialog.Builder(this).setTitle("Voice setup").setMessage("Your note is safe. Set up on-device speech to dictate on this phone.")
                    .setNegativeButton("Keep typing",null).setPositiveButton("Voice setup") { _,_ -> startActivity(Intent(this,VoiceActivity::class.java).putExtra("setup",true)) }.showProtected(this)
            } else if(recognizing) speech.stop() else {
                recognizing=true; mic.text="Finish dictation"
                val prefix=input.text.toString().trimEnd()
                speech.listen(onLevel={mic.speechLevel(it)},onPartial={ partial ->
                    if(recognizing && !closed) { textFromVoice=true; input.setText(listOf(prefix,partial).filter(String::isNotBlank).joinToString(" ")); input.setSelection(input.length()); textFromVoice=false }
                },onFinal={ final ->
                    if(recognizing && !closed) { textFromVoice=true; input.setText(listOf(prefix,final).filter(String::isNotBlank).joinToString(" ")); input.setSelection(input.length()); textFromVoice=false; status.text="Review your words" }
                },onStop={ problem -> recognizing=false; mic.text="Dictate"; mic.speechLevel(0f); if(problem!=null) status.text=problem.message })
            }
        }
        dictate=startDictation
        mic.setOnClickListener {
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),31)
            else startDictation()
        }
        input.afterChange {
            if(recognizing && !textFromVoice) { recognizing=false; speech.cancel(); mic.text="Dictate" }
            status.text="Saving…"; savedTask?.let(handler::removeCallbacks)
            savedTask=Runnable { save() }.also { handler.postDelayed(it,350) }
        }
        title.afterChange { savedTask?.let(handler::removeCallbacks); savedTask=Runnable { save() }.also { handler.postDelayed(it,350) } }
        val actions=LinearLayout(this).apply {
            addView(mic,LinearLayout.LayoutParams(0,-2,1f))
            addView(pill("Listen") { if(input.text.isNotBlank()) { stopDictation?.invoke(); ReadingService.start(this@WorkspaceActivity,input.text.toString()) } },LinearLayout.LayoutParams(0,-2,1f))
            addView(pill("AI") { if(save(true)) { dialog.dismiss(); askWith(record) } },LinearLayout.LayoutParams(0,-2,1f))
        }
        content.addView(actions,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12); bottomMargin=dp(12) })
        if(record.deleted) {
            content.addView(pill("Restore from Trash",true) { record=store.save(record.copy(deleted=false)); dialog.dismiss(); Reminders.schedule(this,record) })
            content.addView(pill("Delete permanently") {
                AlertDialog.Builder(this).setTitle("Delete permanently?").setMessage("This removes the item, its links and custom values. It cannot be undone.")
                    .setNegativeButton("Keep",null).setPositiveButton("Delete") { _,_ -> flush=null; savedTask?.let(handler::removeCallbacks); store.purge(record.id); closed=true; dialog.dismiss() }.showProtected(this)
            })
            title.isEnabled=false; input.isEnabled=false; actions.visibility=View.GONE
        } else {
            content.addView(tools((if(record.pinned) "Unpin" else "Pin") to { if(save()) { record=store.save(record.copy(pinned=!record.pinned)); close() } },
                "Link" to { if(save()) chooseLink(record) { dialog.dismiss(); edit(store.get(record.id)!!) } },
                "Share" to { if(save()) startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,listOf(record.title,record.body).filter(String::isNotBlank).joinToString("\n\n")),"Share note")) },
                "Original" to { AlertDialog.Builder(this).setTitle("Original capture").setMessage(record.original.ifBlank { "The first finished version is kept when you tap Done." }).setPositiveButton("Done",null).showProtected(this) },
                "Trash" to { if(save()) {
                    record=store.save(record.copy(deleted=true)); Reminders.cancel(this,record.id); dialog.dismiss()
                    AlertDialog.Builder(this).setMessage("Moved to Trash").setNegativeButton("Done",null).setPositiveButton("Undo") { _,_ -> val restored=store.save(record.copy(deleted=false)); Reminders.schedule(this,restored); populate() }.showProtected(this)
                } }))
            if(record.kind in listOf("task","routine")) {
                content.addView(pill(if(record.due>0) "Reminder · ${date(record.due)}" else "Choose reminder time") {
                    if(save()) chooseTime(record) { next -> record=store.save(next); Reminders.schedule(this,record); dialog.dismiss(); edit(record); askNotificationPermission() }
                })
                if(record.kind=="task") content.addView(pill(if(record.done) "Mark incomplete" else "Mark complete") { if(save()) { record=store.save(record.copy(done=!record.done)); Reminders.schedule(this,record); close() } })
                else {
                    content.addView(pill("Repeat · ${record.cadence}") { AlertDialog.Builder(this).setItems(arrayOf("Once","Daily","Weekly")) { _,n -> if(save()) { record=store.save(record.copy(cadence=listOf("once","daily","weekly")[n])); Reminders.schedule(this,record); dialog.dismiss(); edit(record) } }.showProtected(this) })
                    content.addView(pill(if(record.enabled) "Pause routine" else "Enable routine") { if(save()) { record=store.save(record.copy(enabled=!record.enabled)); Reminders.schedule(this,record); dialog.dismiss(); edit(record) } })
                    content.addView(pill(if(ConnectedAI.routineApproval(store,this,record)!=null) "Connected routine · configured" else "Set up connected routine") { if(save()) ConnectedAI.approveRoutine(this,record) { dialog.dismiss(); edit(record) } })
                    content.addView(pill("Run with AI",true) { if(save(true)) { dialog.dismiss(); askWith(record,record.body) } })
                    content.addView(label("Runs on this phone when you open AI. Reminders may be delayed by Android.",13f,true).apply { padDp(0,10,0,10) })
                    store.runs(record.id).forEach { content.addView(label(it,12f,true).apply { padDp(0,6,0,6) }) }
                }
                if(record.due>0) content.addView(pill("Remove reminder") { if(save()) { record=store.save(record.copy(due=0)); Reminders.cancel(this,record.id); dialog.dismiss(); edit(record) } })
            } else if(record.kind=="note") content.addView(pill("Create linked task") {
                if(save(true)) { val task=store.save(Record(kind="task",title=record.title.ifBlank { record.body.take(80) })); store.link(task.id,record.id,"source"); dialog.dismiss(); edit(task) }
            })
        }
        val links=store.linked(record.id)
        if(links.isNotEmpty()) {
            content.addView(label("Linked items",18f).apply { medium(); padDp(0,20,0,8) })
            links.forEach { link -> content.addView(pill("${link.kind} · ${link.title.ifBlank { link.body.take(50) }}") { close(); edit(link) }.apply { setOnLongClickListener { AlertDialog.Builder(this@WorkspaceActivity).setMessage("Remove this link?").setNegativeButton("Keep",null).setPositiveButton("Unlink") { _,_ -> store.unlink(record.id,link.id); dialog.dismiss(); edit(store.get(record.id)!!) }.showProtected(this@WorkspaceActivity); true } }) }
        }
        if(record.kind=="collection" && !record.deleted) {
            content.addView(pill("Table view",true) { if(save()) showTable(record) })
            content.addView(pill("Add a field") { if(save()) propertyForm(record) { dialog.dismiss(); edit(store.get(record.id)!!) } })
        }
        if(record.source.isNotBlank()) content.addView(label("Source\n${record.source}",12f,true).apply { setTextIsSelectable(true); padDp(0,20,0,8) })
        layout.addView(ScrollView(this).apply { addView(content) },LinearLayout.LayoutParams(-1,0,1f))
        dialog.setContentView(AdaptiveFrame(this,layout).apply { padForSystemBars() })
        dialog.window?.setBackgroundDrawableResource(R.color.chat_bg)
        dialog.setOnDismissListener {
            if(!closed) save(true)
            closed=true; savedTask?.let(handler::removeCallbacks); speech.cancel(); ears=null; dictate=null; stopDictation=null; flush=null; editingId=null
            if(editor===dialog) editor=null
            populate()
        }
        dialog.show(); dialog.window?.setLayout(-1,-1); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); dialog.window?.let { readableSystemBars(it) }
    }
    private fun chooseLink(record:Record, after:()->Unit) {
        val candidates=store.list().filter { it.id!=record.id }
        if(candidates.isEmpty()) { toast("Create another item in Library, then link it here."); return }
        val search=EditText(this).apply { styleField(); hint="Find an item" }
        val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val view=column().apply { padDp(20,8,20,16); addView(search); addView(ScrollView(this@WorkspaceActivity).apply { addView(list) },LinearLayout.LayoutParams(-1,dp(300))) }
        val dialog=AlertDialog.Builder(this).setTitle("Link an item").setView(view).setNegativeButton("Cancel",null).create()
        fun update(q:String) { list.removeAllViews(); candidates.filter { q.isBlank() || (it.title+it.body).contains(q,true) }.take(30).forEach { item -> list.addView(pill("${item.kind} · ${item.title.ifBlank { item.body.take(40) }}") { store.link(record.id,item.id,if(record.kind=="collection") "member" else "related"); dialog.dismiss(); after() }) } }
        search.afterChange(::update); update(""); dialog.showProtected(this)
    }
    private fun showTable(collection:Record) {
        val members=store.linked(collection.id,"member"); val props=store.properties(collection.id)
        val tableColumn=column()
        val filter=EditText(this).apply { styleField(); hint="Filter rows and values"; setSingleLine(); setText(store.value("table-filter:${collection.id}")) }
        val sort=Spinner(this).apply { adapter=ArrayAdapter(this@WorkspaceActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Sort by title")+props.map { "Sort by ${it.name}" }); setSelection(store.value("table-sort:${collection.id}","0").toIntOrNull()?.coerceIn(0,props.size) ?: 0) }
        fun row(cells:List<String>,action:(()->Unit)?=null) = LinearLayout(this).apply {
            cells.forEachIndexed { i,text -> addView(label(text.ifBlank { "—" },if(action==null) 13f else 15f).apply { padDp(14,14,14,14); if(action==null) medium(); maxLines=3; ellipsize=TextUtils.TruncateAt.END; if(action!=null) { buttonSemantics(); isFocusable=true; setOnClickListener { action() } } },LinearLayout.LayoutParams(dp(if(i==0) 180 else 140),-2)) }
            background=surface(if(action==null) R.color.chip_bg else R.color.bubble_ai,8,true)
        }
        fun draw() {
            val values=members.associate { r -> r.id to props.map { store.propertyValue(r.id,it.id) } }
            val q=filter.text.toString()
            val sortIndex=sort.selectedItemPosition-1
            var visible=members.filter { r -> q.isBlank() || (listOf(r.title,r.body)+values.getValue(r.id)).any { it.contains(q,true) } }
            visible=if(sortIndex in props.indices && props[sortIndex].type=="number") visible.sortedBy { values.getValue(it.id)[sortIndex].toDoubleOrNull() ?: Double.POSITIVE_INFINITY }
                else visible.sortedBy { if(sortIndex in props.indices) values.getValue(it.id)[sortIndex].lowercase() else it.title.lowercase() }
            tableColumn.removeAllViews(); tableColumn.addView(row(listOf("Item","Type")+props.map { it.name }))
            visible.forEach { r -> tableColumn.addView(row(listOf(r.title.ifBlank { r.body.take(60) },r.kind)+values.getValue(r.id)) { if(props.isEmpty()) edit(r) else editProperties(r,props) { draw() } }) }
            if(visible.isEmpty()) tableColumn.addView(label(if(members.isEmpty()) "Use Link to add items to this collection." else "No matching rows.",14f,true).apply { padDp(14,20,14,20) })
        }
        val dialog=Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=column().apply {
            setBackgroundColor(ink(R.color.chat_bg)); padDp(16,12,16,12)
            addView(sheetHeader(collection.title.ifBlank { "Collection" }) { dialog.dismiss() })
            addView(filter); addView(sort)
            addView(label("Swipe across for fields. Tap a row to edit. This view is remembered.",13f,true).apply { padDp(0,8,0,10) })
            addView(ScrollView(this@WorkspaceActivity).apply { addView(HorizontalScrollView(this@WorkspaceActivity).apply { addView(tableColumn) }) },LinearLayout.LayoutParams(-1,0,1f))
        }
        filter.afterChange { store.put("table-filter:${collection.id}",it); draw() }
        sort.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) { store.put("table-sort:${collection.id}",position.toString()); draw() }
        }
        draw()
        dialog.setContentView(AdaptiveFrame(this,root).apply { padForSystemBars() }); dialog.window?.setBackgroundDrawableResource(R.color.chat_bg); dialog.show(); dialog.window?.setLayout(-1,-1); dialog.window?.let { readableSystemBars(it) }
    }
    private fun propertyForm(record:Record,after:()->Unit) {
        val name=EditText(this).apply { styleField(); hint="Field name"; setSingleLine() }
        val type=Spinner(this).apply { adapter=ArrayAdapter(this@WorkspaceActivity,android.R.layout.simple_spinner_dropdown_item,WorkspaceStore.PROPERTY_TYPES) }
        val layout=column().apply { padDp(20,8,20,8); addView(name); addView(type) }
        val dialog=AlertDialog.Builder(this).setTitle("Add a field").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Add",null).showProtected(this)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { runCatching { store.addProperty(record.id,name.text.toString(),type.selectedItem.toString()) }.onSuccess { dialog.dismiss(); after() }.onFailure { name.error=it.message ?: "Use a unique field name." } }
    }
    private fun editProperties(record:Record,props:List<Property>,after:()->Unit={}) {
        val layout=column().apply { padDp(20,8,20,8) }
        val fields=props.map { p ->
            layout.addView(label("${p.name} · ${p.type}${if(p.type=="date") " (YYYY-MM-DD)" else ""}",13f,true))
            EditText(this).apply { styleField(); setText(store.propertyValue(record.id,p.id)); if(p.type=="number") inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED; layout.addView(this) }
        }
        val dialog=AlertDialog.Builder(this).setTitle(record.title.ifBlank { "Edit fields" }).setView(ScrollView(this).apply { addView(layout) }).setNegativeButton("Cancel",null).setPositiveButton("Save",null).showProtected(this)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val db=store.writableDatabase; db.beginTransaction()
            try { props.forEachIndexed { i,p -> store.setProperty(record.id,p,fields[i].text.toString()) }; db.setTransactionSuccessful(); dialog.dismiss(); after() }
            catch(e:Exception) { toast(e.message ?: "Check the field values.") } finally { db.endTransaction() }
        }
    }
    private fun chooseTime(record:Record, result:(Record)->Unit) {
        stopDictation?.invoke()
        val calendar=Calendar.getInstance().apply { timeInMillis=if(record.due>System.currentTimeMillis()) record.due else System.currentTimeMillis()+3600000 }
        DatePickerDialog(this,{ _,y,m,d ->
            calendar.set(y,m,d)
            TimePickerDialog(this,{ _,hour,minute ->
                calendar.set(Calendar.HOUR_OF_DAY,hour); calendar.set(Calendar.MINUTE,minute); calendar.set(Calendar.SECOND,0); calendar.set(Calendar.MILLISECOND,0)
                if(calendar.timeInMillis<=System.currentTimeMillis()) { toast("Choose a future time."); return@TimePickerDialog }
                AlertDialog.Builder(this).setTitle("Set reminder?").setMessage("${date(calendar.timeInMillis)}\n${calendar.timeZone.id}\n\nAndroid may delay delivery to save battery.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Set reminder") { _,_ -> result(record.copy(due=calendar.timeInMillis,zone=calendar.timeZone.id)) }.showProtected(this)
            },calendar.get(Calendar.HOUR_OF_DAY),calendar.get(Calendar.MINUTE),android.text.format.DateFormat.is24HourFormat(this)).showProtected(this)
        },calendar.get(Calendar.YEAR),calendar.get(Calendar.MONTH),calendar.get(Calendar.DAY_OF_MONTH)).showProtected(this)
    }
    private fun askWith(record:Record,prompt:String="") {
        store.put("ai-context", (if(record.kind=="routine") store.linked(record.id).take(5).map { it.id } else listOf(record.id)).joinToString(","))
        if(prompt.isNotBlank()) {
            val current=Chat.draft(this)
            Chat.saveDraft(this,listOf(current,prompt).filter(String::isNotBlank).distinct().joinToString("\n\n"))
            if(record.kind=="routine") store.put("ai-routine",record.id)
        }
        startActivity(Intent(this,MainActivity::class.java).putExtra("workspaceDraft",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    private fun menu() {
        AlertDialog.Builder(this).setTitle("Your workspace").setItems(arrayOf("Beeper conversations","Export everything","Restore workspace","Open AI settings","Read aloud player")) { _,n -> when(n) {
            0 -> BeeperAccess.open(this)
            1 -> startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"aegentica-workspace.json"),61)
            2 -> startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json"),62)
            3 -> startActivity(Intent(this,MainActivity::class.java).putExtra("settings",true))
            4 -> startActivity(Intent(this,ReadingActivity::class.java))
        } }.showProtected(this)
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || requestCode !in listOf(61,62)) return
        val uri=data?.data ?: return
        Thread {
            try {
                if(requestCode==61) {
                    val snapshot=WorkspaceStore(this).use { it.backup() }
                    contentResolver.openOutputStream(uri,"wt")?.use { it.write(snapshot.toByteArray()) } ?: error("Cannot open that file.")
                    runOnUiThread { if(!isDestroyed) toast("Workspace exported. Keep this file private.") }
                } else {
                    val bytes=contentResolver.openInputStream(uri)?.use { it.readBounded(WorkspaceStore.MAX_BACKUP) } ?: error("Cannot open that file.")
                    require(bytes.size<=WorkspaceStore.MAX_BACKUP) { "Backup exceeds 32 MB." }
                    val raw=bytes.toString(Charsets.UTF_8)
                    runOnUiThread { if(!isDestroyed) AlertDialog.Builder(this).setTitle("Restore workspace?").setMessage("Merge saved items and links. Existing edits are preserved as separate copies when needed. Imported routines stay paused.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Restore") { _,_ ->
                            Thread { runCatching { WorkspaceStore(this).use { it.restore(raw) } }.onSuccess { count -> Reminders.reschedule(this); runOnUiThread { if(!isDestroyed) { populate(); toast("Restored $count items") } } }.onFailure { e -> runOnUiThread { if(!isDestroyed) toast(e.message ?: "Could not restore.") } } }.start()
                        }.showProtected(this) }
                }
            } catch(e:Exception) { runOnUiThread { if(!isDestroyed) toast(e.message ?: "Could not access this backup.") } }
        }.start()
    }
    private fun askNotificationPermission() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),32)
        else if(!Reminders.allowed(this)) toast("Notifications are disabled. Enable them in Android settings for reminders.")
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if(code==31 && results.firstOrNull()==PackageManager.PERMISSION_GRANTED && editor?.isShowing==true) dictate?.invoke()
        if(code==32) { if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) Reminders.reschedule(this) else toast("Reminder saved. Notifications need permission to alert you.") }
        if(code==CalendarAccess.REQUEST) { if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) CalendarAccess.choose(this) { populate() } else toast("Calendar stays disconnected.") }
        if(code==BeeperAccess.READ_REQUEST) { if(results.firstOrNull()==PackageManager.PERMISSION_GRANTED) BeeperAccess.open(this) else toast("Beeper stays disconnected.") }
        if(code==BeeperAccess.SEND_REQUEST) toast("Return to the draft and review Send again.")
    }
    override fun onResume() { super.onResume(); NativePrivacy.apply(this,window); if(loaded && editor==null) populate() }
    override fun onPause() { flush?.invoke(); stopDictation?.invoke(); super.onPause() }
    override fun onSaveInstanceState(state:Bundle) { flush?.invoke(); state.putString("destination",destination); state.putString("filter",filter); state.putString("query",query); state.putString("editing",editingId); super.onSaveInstanceState(state) }
    override fun onDestroy() { flush?.invoke(); editor?.dismiss(); pendingSearch?.let(handler::removeCallbacks); ears?.cancel(); store.close(); super.onDestroy() }
    private fun toast(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    private fun date(value:Long)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(value))
    companion object {
        val PAGES=listOf("today","calendar","tasks","library")
        fun intent(context:Context,destination:String="today",id:String?=null)=Intent(context,WorkspaceActivity::class.java).putExtra("destination",destination.takeIf { it in PAGES } ?: "today").apply { if(id!=null) putExtra("record",id) }
    }
}

internal fun EditText.afterChange(block:(String)->Unit) { addTextChangedListener(object:TextWatcher {
    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { block(s?.toString().orEmpty()) }
    override fun afterTextChanged(s:Editable?) {}
}) }
fun Activity.workspaceNavigation(selected:String):View = LinearLayout(this).apply {
    gravity=Gravity.CENTER_VERTICAL; padDp(0,8,0,4)
    val order=listOf("today","calendar","ai","tasks","library")
    for((key,title) in listOf("today" to "Today","calendar" to "Calendar","ai" to "AI","tasks" to "Tasks","library" to "Notes")) {
        addView(pill(title,key==selected) {
            if(key!=selected) {
                val direction=if(order.indexOf(key) > order.indexOf(selected)) 1 else -1
                if(key=="ai") {
                    startActivity(Intent(this@workspaceNavigation,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    smoothPageTransition(direction)
                } else if(this@workspaceNavigation is WorkspaceActivity) {
                    this@workspaceNavigation.switchDestination(key,direction)
                } else {
                    startActivity(WorkspaceActivity.intent(this@workspaceNavigation,key))
                    smoothPageTransition(direction)
                }
            }
        }.apply { isSelected=key==selected; tag="nav-$key"; textSize=12f; padDp(3,10,3,10) },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(1); marginEnd=dp(1) })
    }
}
