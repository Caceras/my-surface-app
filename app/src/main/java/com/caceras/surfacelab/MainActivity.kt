package com.caceras.surfacelab

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The launcher screen. It reports what the app registered, exposes the brain's
 * own state, and -- when the brain can take a free-form prompt -- is a plain
 * prompt box against a model running on this phone.
 *
 * Every dimension goes through dp(): setPadding takes pixels, and using raw
 * numbers made the whole screen shrink as density rose.
 */
class MainActivity : Activity() {

    /**
     * The version this APK was built from. Shown on screen because the whole
     * loop -- change it, push it, install it -- is worth nothing if you
     * cannot tell which build you are looking at.
     */
    private fun buildLabel(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val brain = BrainProvider.get()
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
        } else ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 16, 20, 32)
        }

        fun label(text: String, size: Float, dim: Boolean = false) =
            TextView(this).apply {
                this.text = text
                textSize = size
                if (dim) alpha = 0.7f
            }

        fun section(title: String) =
            label(title, 18f).apply { padDp(0, 24, 0, 4) }

        fun row(title: String, hint: String) {
            root.addView(label(title, 16f).apply { padDp(0, 12, 0, 0) })
            root.addView(label(hint, 14f, dim = true))
        }

        root.addView(label(getString(R.string.app_name), 26f))
        root.addView(label(
            getString(R.string.brain_name) + " - " + getString(R.string.brain_blurb),
            14f, dim = true
        ))
        root.addView(label("Build " + buildLabel(), 13f, dim = true))

        val statusLine = label("Checking...", 15f).apply { padDp(0, 16, 0, 8) }
        root.addView(statusLine)
        brain.status(this) { statusLine.text = it.label }

        root.addView(Button(this).apply {
            text = "Check / prepare on-device model"
            setOnClickListener {
                statusLine.text = "Working..."
                brain.prepare(this@MainActivity) { statusLine.text = it.label }
            }
        })

        // ---- the actual point: type anything, get an answer, offline -------
        if (brain.tasks.contains(Task.ASK)) {
            root.addView(section("Ask"))
            root.addView(label(
                "Runs on this phone. Works in aeroplane mode.", 14f, dim = true
            ))

            val prompt = EditText(this).apply {
                hint = if (shared.isEmpty()) "Ask anything"
                       else getString(R.string.ask_hint)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                maxLines = 5
            }
            root.addView(prompt)

            // A blank box is a worse prompt than a bad suggestion.
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                padDp(0, 4, 0, 4)
            }
            Prompts.SUGGESTIONS.forEach { suggestion ->
                chips.addView(suggestionButton(this, suggestion) {
                    prompt.setText(suggestion)
                    prompt.setSelection(suggestion.length)
                })
            }
            root.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })

            val answer = label("", 15f).apply { padDp(0, 16, 0, 0) }
            val send = Button(this).apply { text = "Send" }

            send.setOnClickListener {
                val question = prompt.text.toString().trim()
                if (question.isEmpty() && shared.isEmpty()) return@setOnClickListener
                send.isEnabled = false
                answer.text = getString(R.string.working)
                brain.run(
                    context = this@MainActivity,
                    task = Task.ASK,
                    input = shared,
                    instruction = question,
                    onPartial = { partial -> answer.text = partial }
                ) { result ->
                    send.isEnabled = true
                    answer.text = if (result.ok) result.text
                                  else result.note ?: "Failed."
                    if (result.ok) {
                        ResultStore.save(this@MainActivity, Task.ASK, result.text)
                    }
                }
            }
            root.addView(send)

            if (shared.isNotEmpty()) {
                root.addView(label("Shared in:", 14f, dim = true)
                    .apply { padDp(0, 16, 0, 0) })
                root.addView(label(
                    if (shared.length > 400) shared.take(397) + "..." else shared,
                    13f, dim = true
                ))
            }

            root.addView(answer)
        } else if (shared.isNotEmpty()) {
            root.addView(label("Shared in: " + shared, 14f, dim = true)
                .apply { padDp(0, 16, 0, 0) })
        }

        // ---- where everything else lives ----------------------------------
        root.addView(section("Surfaces"))
        row("Text selection", "Select text anywhere: " +
            brain.tasks.joinToString(", ") { it.alias })
        row("Quick Settings tile", "Shade, Edit tiles -- or the button below.")
        row("Home screen widget", "Long-press home, Widgets. Shows the last answer.")
        row("App shortcuts", "Long-press the app icon in the launcher.")
        row("Share sheet", "Share any text into this app.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            root.addView(Button(this).apply {
                text = "Add tile to Quick Settings"
                setOnClickListener {
                    getSystemService(StatusBarManager::class.java)
                        .requestAddTileService(
                            ComponentName(
                                this@MainActivity,
                                SurfaceTileService::class.java
                            ),
                            getString(R.string.app_name),
                            Icon.createWithResource(
                                this@MainActivity,
                                R.drawable.ic_surface
                            ),
                            mainExecutor
                        ) { /* result code -- ignored for this demo */ }
                }
            })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setContentView(scroll)
        scroll.padForSystemBars()
    }
}
