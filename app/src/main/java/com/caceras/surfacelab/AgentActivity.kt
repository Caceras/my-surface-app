package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Real-time voice with the owner's own LiveKit agent (OpenRouter models,
 * phone-side tools, optional screen sharing). The conversation lives in
 * [AgentService] so it can continue, visibly, while the owner shows the agent
 * another app; this screen shows the transcript and the agent's proposals.
 */
class AgentActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var text: TextView
    private lateinit var scroller: ReadingScrollView
    private lateinit var proposals: LinearLayout
    private lateinit var primary: TextView
    private lateinit var share: TextView
    private lateinit var hint: TextView
    private var unwatch: (() -> Unit)? = null

    private fun prefs() = getSharedPreferences("surfacelab", 0)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(20, 12, 20, 12); setBackgroundColor(ink(R.color.chat_bg)) }
        root.addView(sheetHeader("Agent voice") { finish() })
        status = label("", 14f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "agent-status"; padDp(0, 10, 0, 8) }
        root.addView(status)
        text = label("", 18f).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1.1f); padDp(0, 8, 0, 16); tag = "agent-text" }
        scroller = ReadingScrollView(this).apply { addView(text) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        proposals = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "agent-proposals" }
        root.addView(proposals)
        hint = label("", 12f, true).apply { padDp(0, 4, 0, 8); tag = "agent-hint" }
        root.addView(hint)
        primary = pill("Start talking", true) { primaryAction() }.apply { tag = "agent-primary" }
        share = pill("Share screen") { toggleShare() }.apply { tag = "agent-share" }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(share, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(flatLink("Agent server settings") { settings() }.apply { tag = "agent-settings" })
        setContentView(AdaptiveFrame(this, root).apply { padForSystemBars() }); readableSystemBars()
        if (state == null && intent.getBooleanExtra(START, false) && AgentService.conversation?.ended != false) primaryAction()
    }

    private fun flatLink(title: String, action: () -> Unit) = label(title, 14f).apply {
        medium(); gravity = Gravity.CENTER; minHeight = dp(48); setTextColor(ink(R.color.accent_text)); isFocusable = true; buttonSemantics()
        setOnClickListener { action() }
    }

    private fun live() = AgentService.conversation?.takeIf { !it.ended }

    private fun render() {
        val c = AgentService.conversation
        val active = c != null && !c.ended
        status.text = when {
            !AgentRooms.available() -> "Agent voice needs the Nano build of Ægentica."
            !AgentConfig.configured(this) -> "Connect your agent first: tap Agent server settings."
            c == null -> "Talk with your own LiveKit agent. It answers with the OpenRouter model you chose on the server, can search your notes and suggest actions you confirm here."
            c.ended -> listOf(c.message, if (c.saved != null) "Saved to Notes." else "").filter(String::isNotBlank).joinToString(" ")
            c.state == AgentConversation.State.CONNECTING -> c.message.ifBlank { "Connecting…" }
            c.state == AgentConversation.State.THINKING -> "Thinking…"
            c.state == AgentConversation.State.SPEAKING -> "Speaking · talk to interrupt"
            else -> "Listening · speak naturally" + if (c.sharing) " · sharing your screen" else ""
        }
        val out = SpannableStringBuilder()
        c?.lines?.forEach { line ->
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length; out.append(if (line.you) "You" else "Ægentica")
            out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(ink(if (line.you) R.color.text_dim else R.color.accent_text)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n").append(line.text)
        }
        text.text = out; scroller.contentChanged()
        proposals.removeAllViews()
        c?.proposals?.toList()?.forEach { p ->
            proposals.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; padDp(16, 12, 12, 8); background = glassSurface(20, true); tag = "agent-proposal"
                addView(label(p.label, 15f).apply { medium() })
                addView(label("Suggested by your agent. Nothing happens until you tap.", 12f, true))
                addView(LinearLayout(this@AgentActivity).apply {
                    gravity = Gravity.END
                    addView(pill("Dismiss") { c.dismiss(p) }.apply { tag = "agent-dismiss" }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                    addView(pill("Do it", true) { c.accept(p)?.let { open(it) } ?: toast("Saved to Notes.") }.apply { tag = "agent-accept" })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        primary.text = when { active -> "End & save"; c?.saved != null -> "Open saved note"; else -> "Start talking" }
        share.visibility = if (active && c!!.state != AgentConversation.State.CONNECTING) View.VISIBLE else View.GONE
        share.text = if (c?.sharing == true) "Stop sharing" else "Share screen"
        hint.text = if (active) "Keeps going while you open other apps, so your agent can see a screen you share. End here or from the notification." else ""
        hint.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun open(intent: Intent) { try { startActivity(intent) } catch (_: Exception) { toast("No app on this phone can do that.") } }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun primaryAction() {
        val c = AgentService.conversation
        when {
            c != null && !c.ended -> AgentService.stop(this)
            c?.saved != null -> { startActivity(WorkspaceActivity.intent(this, "library", c.saved!!.id)); AgentService.clear(); finish() }
            else -> begin()
        }
    }

    private fun begin() {
        if (!AgentRooms.available()) { render(); return }
        if (!AgentConfig.configured(this)) { settings(); return }
        if (!prefs().getBoolean("agent_consent", false)) {
            AlertDialog.Builder(this).setTitle("Talk with your agent?")
                .setMessage("While the conversation runs, your microphone audio goes to your LiveKit server and agent at ${AgentConfig.host(this)}, and from there to the speech and AI providers configured on it (OpenRouter by default). When the agent searches your notes, matching titles and excerpts are sent. A screen you choose to share is sent as video until you stop sharing. Provider and LiveKit charges apply. The conversation is saved as a note on this phone when it ends.")
                .setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ -> prefs().edit().putBoolean("agent_consent", true).apply(); begin() }.showProtected(this)
            return
        }
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !prefs().getBoolean("agent_notice_asked", false)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) {
            if (Manifest.permission.POST_NOTIFICATIONS in missing) prefs().edit().putBoolean("agent_notice_asked", true).apply()
            requestPermissions(missing.toTypedArray(), REQUEST); return
        }
        AgentService.clear()
        AgentService.start(this)
    }

    /** Android asks for consent each time; the projection stops when the phone locks or the owner taps the status chip. */
    private fun toggleShare() {
        val c = live() ?: return
        if (c.sharing) { c.shareScreen(null, null); return }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), SCREEN)
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN) return
        if (resultCode != RESULT_OK || data == null) { toast("Screen sharing was not started."); return }
        live()?.shareScreen(data, AgentService.notification(this, live(), sharing = true))
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else status.text = "Microphone access is off. Enable it in Android settings to talk with your agent."
    }

    private fun settings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(20, 8, 20, 8) }
        layout.addView(label("Your agent runs on your own server or LiveKit Cloud and holds the LiveKit secret; this phone only asks it for a short-lived room ticket. Setup: docs/agent.md in the app's repository.", 14f, true))
        val endpoint = EditText(this).apply { styleField(); hint = "Token address, e.g. https://agent.example.se/token"; setSingleLine(); setText(AgentConfig.endpoint(this@AgentActivity)); tag = "agent-endpoint" }
        val code = EditText(this).apply {
            styleField(); hint = if (AgentConfig.configured(this@AgentActivity)) "Access code saved · leave blank to keep" else "Access code"; setSingleLine(); tag = "agent-code"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(endpoint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        layout.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val dialog = AlertDialog.Builder(this).setTitle("Agent server").setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton("Cancel", null).setNeutralButton("Forget") { _, _ -> AgentConfig.forget(this); render() }.setPositiveButton("Save", null).showProtected(this)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { AgentConfig.save(this, endpoint.text.toString(), code.text.toString()) }
                .onSuccess { dialog.dismiss(); render(); toast("Saved. Tap Start talking to test it.") }
                .onFailure { endpoint.error = it.message ?: "Check the address." }
        }
    }

    override fun onResume() { super.onResume(); NativePrivacy.apply(this, window); unwatch = AgentService.watch { runOnUiThread { render() } }; render() }
    override fun onPause() { unwatch?.invoke(); unwatch = null; super.onPause() }

    companion object {
        const val START = "start"
        private const val REQUEST = 47
        private const val SCREEN = 48
        fun intent(context: Context, start: Boolean = false) = Intent(context, AgentActivity::class.java).putExtra(START, start)
    }
}
