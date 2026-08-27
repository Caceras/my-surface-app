package com.caceras.surfacelab

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Not required by most of these surfaces -- it exists so the app appears in
 * the launcher, reports what it registered, and gives the brain somewhere to
 * report its own state.
 */
class MainActivity : Activity() {

    private lateinit var statusLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }

        fun heading(text: String, size: Float) = TextView(this).apply {
            this.text = text
            textSize = size
        }

        fun row(title: String, hint: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setPadding(0, 24, 0, 0)
            })
            root.addView(TextView(this).apply {
                text = hint
                textSize = 14f
                alpha = 0.7f
            })
        }

        root.addView(heading(getString(R.string.app_name), 26f))
        root.addView(TextView(this).apply {
            text = getString(R.string.brain_name) + " - " + getString(R.string.brain_blurb)
            textSize = 14f
            alpha = 0.7f
        })

        statusLine = TextView(this).apply {
            text = "Checking..."
            textSize = 15f
            setPadding(0, 24, 0, 0)
        }
        root.addView(statusLine)

        val brain = BrainProvider.get()
        brain.status(this) { statusLine.text = it.label }

        root.addView(Button(this).apply {
            text = "Check / prepare on-device model"
            setOnClickListener {
                statusLine.text = "Working..."
                brain.prepare(this@MainActivity) { statusLine.text = it.label }
            }
        })

        row("Text selection action",
            "Select text anywhere, then: " +
                brain.tasks.joinToString(", ") { it.alias })
        row("Quick Settings tile", "Shade, Edit tiles -- or the button below.")
        row("Home screen widget", "Long-press home, Widgets. Shows the last result.")
        row("Long-press app shortcuts", "Long-press the app icon in the launcher.")
        row("Share sheet target", "Share any text into this app.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            root.addView(Button(this).apply {
                text = "Add tile to Quick Settings"
                setOnClickListener {
                    getSystemService(StatusBarManager::class.java)
                        .requestAddTileService(
                            ComponentName(
                                this@MainActivity,
                                DemoTileService::class.java
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

        // Anything shared into the app can be run through the brain without
        // ever reaching the network.
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
            val output = TextView(this).apply {
                text = shared
                textSize = 14f
                setPadding(0, 32, 0, 0)
            }
            val task = brain.tasks.firstOrNull { it == Task.SUMMARIZE }
                ?: brain.tasks.first()
            root.addView(Button(this).apply {
                text = task.alias + " this, on device"
                setOnClickListener {
                    output.text = getString(R.string.working)
                    brain.run(this@MainActivity, task, shared) { result ->
                        output.text = if (result.ok) result.text
                            else result.note ?: "Failed."
                        if (result.ok) ResultStore.save(this@MainActivity, task, result.text)
                    }
                }
            })
            root.addView(output)
        }

        setContentView(ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }
}
