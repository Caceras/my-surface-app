package com.caceras.surfacelab

import android.app.Activity
import android.content.Intent
import android.os.*
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.widget.*

class ReadingActivity:Activity() {
    private lateinit var status:TextView
    private lateinit var text:TextView
    private lateinit var play:TextView
    private lateinit var speed:TextView
    private val handler=Handler(Looper.getMainLooper())
    private var last:ReadingSnapshot?=null
    private val refresh=object:Runnable { override fun run() { update(); handler.postDelayed(this,300) } }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; padDp(20,12,20,12); setBackgroundColor(ink(R.color.chat_bg)) }
        root.addView(sheetHeader("Read aloud") { finish() })
        status=label("",13f,true).apply { accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE; padDp(0,12,0,12) }
        root.addView(status)
        text=label("",19f).apply { setTextIsSelectable(true); setLineSpacing(dp(7).toFloat(),1.1f); padDp(0,12,0,20) }
        root.addView(ScrollView(this).apply { addView(text) },LinearLayout.LayoutParams(-1,0,1f))
        root.addView(LinearLayout(this).apply {
            gravity=Gravity.CENTER
            addView(pill("Back") { ReadingService.move(-1) }.apply { contentDescription="Previous sentence" },LinearLayout.LayoutParams(0,-2,1f))
            play=pill("Play",true) { ReadingService.toggle(this@ReadingActivity) }; addView(play,LinearLayout.LayoutParams(0,-2,1f))
            addView(pill("Next") { ReadingService.move(1) }.apply { contentDescription="Next sentence" },LinearLayout.LayoutParams(0,-2,1f))
        })
        speed=pill("1×") {
            android.app.AlertDialog.Builder(this).setTitle("Reading speed").setItems(arrayOf("0.75×","1×","1.25×","1.5×","2×")) { _,n -> ReadingService.speed(listOf(.75f,1f,1.25f,1.5f,2f)[n]) }.showProtected(this)
        }
        root.addView(speed)
        root.addView(label("Playback can continue with your screen locked. Resume begins at the current sentence.",12f,true).apply { padDp(0,10,0,4) })
        setContentView(AdaptiveFrame(this,root).apply { padForSystemBars() }); readableSystemBars(); update()
    }
    private fun update() {
        var state=ReadingService.snapshot
        if(state.text.isEmpty()) WorkspaceStore(this).use { store ->
            val saved=store.value("reading-text")
            state=ReadingSnapshot(saved,store.value("reading-index","0").toIntOrNull() ?: 0,ReadingService.segments(saved).size,false,store.value("reading-speed","1").toFloatOrNull() ?: 1f)
        }
        if(state==last) return
        last=state
        status.text=state.error.ifBlank { if(state.count==0) "Choose Listen on a note or an AI answer." else "${if(state.playing) "Reading" else "Paused"} · ${state.index+1} of ${state.count}" }
        play.text=if(state.playing) "Pause" else "Play"; play.isEnabled=state.count>0; speed.text="${state.speed}×"
        val chunks=ReadingService.segments(state.text)
        val rendered=SpannableString(chunks.joinToString(""))
        if(state.index in chunks.indices) {
            val start=chunks.take(state.index).sumOf { it.length }; rendered.setSpan(BackgroundColorSpan(ink(R.color.presence_bg)),start,start+chunks[state.index].length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text=rendered
    }
    override fun onResume() { super.onResume(); NativePrivacy.apply(this,window); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
