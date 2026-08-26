package com.example.surfacelab

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
 * Not required by most of these surfaces -- it exists so the app appears in the
 * launcher and can report what it registered.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
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

        root.addView(TextView(this).apply {
            text = "Pixel Surface Lab"
            textSize = 26f
        })

        row("Quick Settings tile", "Shade, Edit tiles, drag it in -- or use the in-app button.")
        row("Home screen widget", "Long-press the home screen, tap Widgets, find the app.")
        row("Long-press app shortcuts", "Long-press the app icon in the launcher.")
        row("Share sheet target", "Share any text from Chrome and pick the app.")
        row("Text selection action", "Select text anywhere, tap the overflow in the popup menu.")

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

        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            root.addView(TextView(this).apply {
                text = "Shared in: " + shared
                setPadding(0, 32, 0, 0)
            })
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
