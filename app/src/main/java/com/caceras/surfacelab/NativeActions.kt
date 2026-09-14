package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.text.InputType
import android.view.Gravity
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Calendar

/** Permission-light, explicit handoffs. Generated model text is never an executable command. */
object NativeActions {
    fun timer(minutes: Int): Intent {
        require(minutes in 1..1440) { "Choose 1 to 1,440 minutes." }
        return Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Ægentica AI").putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    fun alarm(hour: Int, minute: Int): Intent {
        require(hour in 0..23 && minute in 0..59)
        return Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute).putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }
    fun calendar(title: String) = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title.trim().take(200))
    fun maps(query: String): Intent {
        require(query.isNotBlank()) { "Enter a place to find." }
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query.trim().take(500))))
    }
    fun dial(number: String): Intent {
        val value = number.trim()
        require(value.matches(Regex("[+0-9 ()-]{1,40}")) && value.any { it.isDigit() }) { "Enter a phone number." }
        return Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
    }
    private fun launch(activity: Activity, intent: Intent, dismiss: () -> Unit) {
        try { activity.startActivity(intent); dismiss() }
        catch (_: ActivityNotFoundException) { Toast.makeText(activity, "No compatible app is installed for this action.", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(activity, "Android could not open this action. Check the destination app.", Toast.LENGTH_LONG).show() }
    }
    fun show(activity: Activity, draft: String = ""): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun form(title: String, hint: String, type: Int, initial: String = "", confirm: String = "Open", make: (String) -> Intent) {
            val field = EditText(activity).apply {
                this.hint = hint; inputType = type; setText(initial); maxLines = 3
                styleField()
                filters = arrayOf(android.text.InputFilter.LengthFilter(if (type == InputType.TYPE_CLASS_PHONE) 40 else 500))
            }
            val form = AlertDialog.Builder(activity).setTitle(title).setView(field, activity.dp(24), activity.dp(12), activity.dp(24), activity.dp(8))
                .setNegativeButton("Cancel", null).setPositiveButton(confirm, null).create()
            form.show()
            NativePrivacy.apply(activity, form.window)
            form.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { launch(activity, make(field.text.toString())) { form.dismiss(); dialog.dismiss() } }
                catch (e: IllegalArgumentException) { field.error = e.message }
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; padDp(24, 16, 24, 24)
            addView(activity.sheetHeader("On your phone") { dialog.dismiss() })
            addView(activity.label("A little help with the everyday.", 16f, true).apply { padDp(0, 16, 0, 24) })
            fun action(title: String, hint: String, click: () -> Unit) {
                addView(activity.pill(title) { click() }, LinearLayout.LayoutParams(-1, -2))
                addView(activity.label(hint, 13f, true).apply { padDp(12, 8, 12, 20) })
            }
            action("Set a timer", "Choose minutes, then open your Clock app.") {
                form("Set a timer", "Minutes", InputType.TYPE_CLASS_NUMBER, confirm = "Set timer") { timer(it.toIntOrNull() ?: 0) }
            }
            action("Set an alarm", "Choose a time in your phone’s local time zone.") {
                val now = Calendar.getInstance()
                val picker = TimePickerDialog(activity, { _, hour, minute ->
                    launch(activity, alarm(hour, minute)) { dialog.dismiss() }
                }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(activity))
                picker.show(); NativePrivacy.apply(activity, picker.window)
            }
            action("Draft a calendar event", "Review the date and details in Calendar before saving.") {
                form("Calendar event", "Event title", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, draft.take(200), confirm = "Review in Calendar") { calendar(it) }
            }
            action("Find a place", "Search in Maps. Your search is shared with that app.") {
                form("Find a place", "Place or address", InputType.TYPE_CLASS_TEXT, confirm = "Search") { maps(it) }
            }
            action("Open the dialer", "Review a number before choosing to call.") {
                form("Open the dialer", "Phone number", InputType.TYPE_CLASS_PHONE, confirm = "Open dialer") { dial(it) }
            }
        }
        dialog.setContentView(AdaptiveFrame(activity, ScrollView(activity).apply { addView(content) }).apply { padForSystemBars() })
        dialog.window?.setBackgroundDrawableResource(R.color.chat_bg)
        dialog.show(); dialog.window?.setLayout(-1, -1); dialog.window?.let { activity.readableSystemBars(it) }
        return dialog
    }
}
