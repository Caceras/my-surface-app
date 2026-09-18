package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date

/** A bounded local history, with the same typography and spacing as chat. */
class ConversationSheet(private val activity: Activity, private val open: (String) -> Unit) : Dialog(activity) {
    private lateinit var rows: LinearLayout
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.ink(R.color.chat_bg))
            padDp(24, 16, 24, 12)
            isFocusableInTouchMode = true
            addView(context.sheetHeader("History") { dismiss() }, LinearLayout.LayoutParams(-1, -2))
            addView(context.label("Pick up where you left off.", 15f, true).apply { padDp(0, 10, 0, 20) })
            search = EditText(context).apply {
                tag = "conversation-search"
                hint = "Search saved conversations"
                setSingleLine(true)
                styleField()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (::rows.isInitialized) refresh() }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            addView(search, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(16) })
            rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(context).apply {
                tag = "saved-conversations"
                addView(rows)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(context.label("Saved on this phone · Up to 12 recent conversations", 12f, true).apply { padDp(0, 12, 0, 6) })
            addView(context.pill("Clear saved conversations") {
                if (Chat.archives(context).isNotEmpty()) AlertDialog.Builder(context)
                    .setTitle("Clear saved conversations?")
                    .setMessage("Your current chat stays open. Saved conversations will be removed from this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear saved") { _, _ -> Chat.clearArchives(context); refresh() }.showProtected(context)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(AdaptiveFrame(activity, body).apply { padForSystemBars() })
        window?.setBackgroundDrawableResource(R.color.chat_bg)
        window?.let { activity.readableSystemBars(it) }
        body.requestFocus()
        refresh()
    }

    override fun onStart() { super.onStart(); window?.setLayout(-1, -1) }

    private fun refresh() {
        rows.removeAllViews()
        val all = Chat.archives(context)
        val query = search.text.toString().trim()
        val matching = all.filter { saved ->
            query.isBlank() || saved.title.contains(query, true) || saved.draft.contains(query, true) ||
                saved.turns.any { it.you.contains(query, true) || it.reply.contains(query, true) }
        }
        if (matching.isEmpty()) rows.addView(context.label(
            if (all.isEmpty()) "A fresh start.\n\nTap New in chat to save a conversation here."
            else "No matching conversations.\n\nTry a different word.", 17f, true).apply { padDp(4, 24, 4, 24) })
        matching.forEach { saved ->
            rows.addView(LinearLayout(context).apply {
                tag = "saved-${saved.id}"
                orientation = LinearLayout.VERTICAL
                background = context.surface(R.color.composer_bg, 22, true)
                padDp(18, 16, 18, 8)
                addView(context.label(saved.title, 18f).apply { medium(); maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(saved.savedAt))
                addView(context.label("$date · ${saved.turns.size} ${if (saved.turns.size == 1) "exchange" else "exchanges"}", 12f, true).apply { padDp(0, 6, 0, 10) })
                val preview = saved.draft.ifBlank { saved.turns.lastOrNull()?.reply.orEmpty() }
                addView(context.label(Markdown.strip(preview), 15f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    padDp(0, 12, 0, 4)
                    addView(context.pill("Resume", true) { dismiss(); open(saved.id) }.apply {
                        contentDescription = "Resume ${saved.title}"
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = context.dp(12) })
                    addView(context.pill("Delete") {
                        AlertDialog.Builder(context).setTitle("Delete conversation?")
                            .setMessage(saved.title).setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ -> Chat.deleteArchive(context, saved.id); refresh() }.showProtected(context)
                    }.apply { contentDescription = "Delete ${saved.title}" })
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(12) })
        }
    }
}
