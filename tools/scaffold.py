#!/usr/bin/env python3
"""Scaffold a minimal Android app that lights up one or more Pixel system surfaces.

Generates a complete, zero-dependency Gradle project plus a GitHub Actions
workflow that builds a debug APK and publishes it as a release asset, so the
APK can be downloaded and installed directly from a phone browser.

Usage:
    python scaffold.py --out ./my-app \
        --package com.example.surfaces \
        --app-name "Surface Lab" \
        --surface tile,widget

Surfaces: tile, widget, shortcuts, share, processtext (comma-separated, or "all")
"""

import argparse
import os
import re
import sys
import textwrap

SURFACES = ["tile", "widget", "shortcuts", "share", "processtext"]

# Toolchain versions. If a build fails with a plugin/compileSdk compatibility
# error, bump these together -- see references/versions.md.
GRADLE_VERSION = "8.9"
AGP_VERSION = "8.6.0"
KOTLIN_VERSION = "2.0.20"
JAVA_VERSION = "17"

# minSdk 29 is deliberate, not arbitrary: Tile.setSubtitle() is API 29 and
# Context.getMainExecutor() is API 28. Lowering it still compiles but crashes
# at runtime on older devices with NoSuchMethodError.
COMPILE_SDK = 35
TARGET_SDK = 35
MIN_SDK = 29

BRAND_ACCENT = "#2E7D9A"
BRAND_DARK = "#1F3B4D"

# Reserved words that cannot appear as a package segment, because segments
# become directory names and Kotlin identifiers.
RESERVED_SEGMENTS = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "true", "false", "null",
    "in", "is", "object", "fun", "val", "var", "when", "typealias",
}


def w(files, path, content):
    files[path] = textwrap.dedent(content).lstrip("\n")


def indent(text, spaces):
    """Re-indent a dedented block so assembled files stay readable."""
    body = textwrap.dedent(text).strip("\n")
    pad = " " * spaces
    return "\n".join(pad + line if line.strip() else line
                     for line in body.split("\n"))


def xml_text(value):
    """Escape for XML character data."""
    return (value.replace("&", "&amp;")
                 .replace("<", "&lt;")
                 .replace(">", "&gt;"))


def android_string(value):
    """Escape for an Android <string> value.

    Android's resource parser rejects unescaped apostrophes and quotes on top of
    normal XML escaping, so both layers are needed.
    """
    return (xml_text(value)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace('"', '\\"'))


def kotlin_string(value):
    """Escape for a Kotlin string literal, including template markers."""
    return (value.replace("\\", "\\\\")
                 .replace('"', '\\"')
                 .replace("$", "\\$"))


def slugify(value):
    """Filesystem-safe name for the built APK."""
    slug = re.sub(r"[^A-Za-z0-9]+", "-", value).strip("-").lower()
    return slug or "app"


# --------------------------------------------------------------------------
# Project skeleton (surface-independent)
# --------------------------------------------------------------------------

def skeleton(files, pkg, app_name):
    w(files, "settings.gradle.kts", f"""
        pluginManagement {{
            repositories {{
                google()
                mavenCentral()
                gradlePluginPortal()
            }}
        }}
        dependencyResolutionManagement {{
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {{
                google()
                mavenCentral()
            }}
        }}

        rootProject.name = "{kotlin_string(app_name)}"
        include(":app")
    """)

    w(files, "build.gradle.kts", f"""
        plugins {{
            id("com.android.application") version "{AGP_VERSION}" apply false
            id("org.jetbrains.kotlin.android") version "{KOTLIN_VERSION}" apply false
        }}
    """)

    w(files, "gradle.properties", """
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        org.gradle.parallel=true
        android.useAndroidX=true
        android.nonTransitiveRClass=true
        kotlin.code.style=official
    """)

    w(files, "app/build.gradle.kts", f"""
        plugins {{
            id("com.android.application")
            id("org.jetbrains.kotlin.android")
        }}

        android {{
            namespace = "{pkg}"
            compileSdk = {COMPILE_SDK}

            defaultConfig {{
                applicationId = "{pkg}"
                minSdk = {MIN_SDK}
                targetSdk = {TARGET_SDK}
                versionCode = 1
                versionName = "1.0"
            }}

            buildTypes {{
                release {{
                    isMinifyEnabled = false
                }}
            }}

            compileOptions {{
                sourceCompatibility = JavaVersion.VERSION_{JAVA_VERSION}
                targetCompatibility = JavaVersion.VERSION_{JAVA_VERSION}
            }}

            // Deprecated in Kotlin 2.0, removed in 2.2. If KOTLIN_VERSION is
            // raised to 2.2+, replace this with a top-level:
            //   kotlin {{ compilerOptions {{ jvmTarget.set(JvmTarget.JVM_{JAVA_VERSION}) }} }}
            kotlinOptions {{
                jvmTarget = "{JAVA_VERSION}"
            }}
        }}

        // Deliberately no dependencies: everything here uses framework APIs only,
        // which keeps the build fast and gives dependency resolution nothing to
        // break on.
        dependencies {{ }}
    """)

    w(files, ".gitignore", """
        .gradle/
        build/
        local.properties
        .idea/
        *.iml
        .DS_Store
    """)

    w(files, "app/src/main/res/values/colors.xml", f"""
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <color name="ic_launcher_background">{BRAND_DARK}</color>
        </resources>
    """)

    # Shared by the tile and the shortcuts. Quick Settings re-tints tile icons
    # by state, so the source colour only has to work in the launcher.
    w(files, "app/src/main/res/drawable/ic_surface.xml", f"""
        <?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="24dp"
            android:height="24dp"
            android:viewportWidth="24"
            android:viewportHeight="24">
            <path
                android:fillColor="{BRAND_ACCENT}"
                android:fillType="evenOdd"
                android:pathData="M12,2L22,12L12,22L2,12Z M12,7L7,12L12,17L17,12Z" />
        </vector>
    """)

    # Adaptive icon foreground: 108dp canvas with the artwork inside the centre
    # 72dp safe zone, so no launcher mask crops it.
    w(files, "app/src/main/res/drawable/ic_launcher_foreground.xml", """
        <?xml version="1.0" encoding="utf-8"?>
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
            <path
                android:fillColor="#FFFFFF"
                android:fillType="evenOdd"
                android:pathData="M54,28L80,54L54,80L28,54Z M54,41L41,54L54,67L67,54Z" />
        </vector>
    """)

    w(files, "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", """
        <?xml version="1.0" encoding="utf-8"?>
        <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            <background android:drawable="@color/ic_launcher_background" />
            <foreground android:drawable="@drawable/ic_launcher_foreground" />
        </adaptive-icon>
    """)


def strings(files, app_name, extra):
    body = f'    <string name="app_name">{android_string(app_name)}</string>'
    for name, value in extra:
        body += f'\n    <string name="{name}">{android_string(value)}</string>'

    files["app/src/main/res/values/strings.xml"] = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f"{body}\n"
        "</resources>\n"
    )


# --------------------------------------------------------------------------
# GitHub Actions workflow
# --------------------------------------------------------------------------

def workflow(files, app_name, slug):
    w(files, ".github/workflows/build.yml", f"""
        name: Build debug APK

        on:
          push:
            branches: ["**"]
          workflow_dispatch:

        permissions:
          contents: write

        concurrency:
          group: build-${{{{ github.ref }}}}
          cancel-in-progress: true

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4

              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: "{JAVA_VERSION}"

              - uses: android-actions/setup-android@v3

              - uses: gradle/actions/setup-gradle@v4
                with:
                  gradle-version: "{GRADLE_VERSION}"

              - name: Build
                run: gradle assembleDebug --no-daemon --stacktrace

              # Naming the APK after the project stops downloads from piling up
              # as "app-debug(1).apk" in the phone's Downloads folder.
              - name: Name the APK
                run: |
                  cp app/build/outputs/apk/debug/app-debug.apk \\
                     "{slug}-debug.apk"

              - name: Upload APK as workflow artifact
                uses: actions/upload-artifact@v4
                with:
                  name: {slug}-debug
                  path: {slug}-debug.apk

              # A release asset is a direct .apk link, so it installs straight
              # from the phone browser. Workflow artifacts are always served as
              # a .zip, which Android will not install.
              #
              # One rolling tag is reused so the phone can bookmark
              # /releases/latest and never need a new URL. Deleting first keeps
              # the step idempotent: re-running a workflow reuses the same run
              # number, which would otherwise collide with an existing tag.
              - name: Publish APK as the rolling debug release
                env:
                  GH_TOKEN: ${{{{ github.token }}}}
                run: |
                  gh release delete debug-latest --yes --cleanup-tag || true
                  gh release create debug-latest \\
                    "{slug}-debug.apk" \\
                    --title "{xml_text(app_name)} debug (build ${{{{ github.run_number }}}})" \\
                    --notes "Open this page on your phone and tap the .apk to install."
    """)


# --------------------------------------------------------------------------
# Surfaces
# --------------------------------------------------------------------------

def surface_tile(files, pkg, src, app_name):
    w(files, f"{src}/DemoTileService.kt", f"""
        package {pkg}

        import android.graphics.drawable.Icon
        import android.os.Build
        import android.service.quicksettings.Tile
        import android.service.quicksettings.TileService
        import android.widget.Toast

        /**
         * Quick Settings tile. The system binds this service only while the shade
         * is open, so keep the work here trivial.
         */
        class DemoTileService : TileService() {{

            private var active = false

            override fun onStartListening() {{
                super.onStartListening()
                render()
            }}

            override fun onClick() {{
                super.onClick()
                active = !active
                render()
                Toast.makeText(
                    this,
                    if (active) "Tile ON" else "Tile OFF",
                    Toast.LENGTH_SHORT
                ).show()
            }}

            private fun render() {{
                // qsTile is null outside the listening window -- touching it
                // unguarded is the most common way these services crash.
                val tile = qsTile ?: return
                tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                // Tile.setSubtitle landed in API 29; this app runs from 26, so
                // calling it unguarded throws NoSuchMethodError on older devices.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {{
                    tile.subtitle = if (active) "On" else "Off"
                }}
                tile.icon = Icon.createWithResource(this, R.drawable.ic_surface)
                tile.updateTile()
            }}
        }}
    """)

    return {
        "manifest": """
        <service
            android:name=".DemoTileService"
            android:exported="true"
            android:icon="@drawable/ic_surface"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>""",
        "status": "Quick Settings tile",
        "hint": "Shade, Edit tiles, drag it in -- or use the in-app button.",
    }


def surface_widget(files, pkg, src, app_name):
    w(files, f"{src}/DemoWidgetProvider.kt", f"""
        package {pkg}

        import android.app.PendingIntent
        import android.appwidget.AppWidgetManager
        import android.appwidget.AppWidgetProvider
        import android.content.ComponentName
        import android.content.Context
        import android.content.Intent
        import android.widget.RemoteViews
        import java.text.SimpleDateFormat
        import java.util.Date
        import java.util.Locale

        /**
         * Home screen widget. Tapping it fires a broadcast back to this provider,
         * which refreshes the timestamp -- enough to prove the round trip works.
         */
        class DemoWidgetProvider : AppWidgetProvider() {{

            override fun onUpdate(
                context: Context,
                appWidgetManager: AppWidgetManager,
                appWidgetIds: IntArray
            ) {{
                appWidgetIds.forEach {{ id -> push(context, appWidgetManager, id) }}
            }}

            override fun onReceive(context: Context, intent: Intent) {{
                super.onReceive(context, intent)
                if (intent.action == ACTION_REFRESH) {{
                    val manager = AppWidgetManager.getInstance(context)
                    val ids = manager.getAppWidgetIds(
                        ComponentName(context, DemoWidgetProvider::class.java)
                    )
                    onUpdate(context, manager, ids)
                }}
            }}

            private fun push(context: Context, manager: AppWidgetManager, id: Int) {{
                val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(Date())

                val refresh = Intent(context, DemoWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)

                // A mutability flag is mandatory on API 31+; omitting both
                // FLAG_IMMUTABLE and FLAG_MUTABLE throws here.
                val pending = PendingIntent.getBroadcast(
                    context,
                    0,
                    refresh,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val views = RemoteViews(context.packageName, R.layout.widget).apply {{
                    setTextViewText(
                        R.id.widget_title,
                        context.getString(R.string.app_name)
                    )
                    setTextViewText(R.id.widget_value, stamp)
                    setOnClickPendingIntent(R.id.widget_root, pending)
                }}

                manager.updateAppWidget(id, views)
            }}

            companion object {{
                const val ACTION_REFRESH = "{pkg}.WIDGET_REFRESH"
            }}
        }}
    """)

    # RemoteViews supports only a fixed set of views -- LinearLayout, TextView,
    # ImageView, Button and a few others. Anything else throws on inflation.
    w(files, "app/src/main/res/layout/widget.xml", """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:id="@+id/widget_root"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/widget_bg"
            android:orientation="vertical"
            android:padding="16dp">

            <TextView
                android:id="@+id/widget_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFF"
                android:textSize="14sp" />

            <TextView
                android:id="@+id/widget_value"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#FFFFFF"
                android:textSize="28sp"
                android:textStyle="bold" />

        </LinearLayout>
    """)

    w(files, "app/src/main/res/drawable/widget_bg.xml", f"""
        <?xml version="1.0" encoding="utf-8"?>
        <shape xmlns:android="http://schemas.android.com/apk/res/android"
            android:shape="rectangle">
            <solid android:color="{BRAND_DARK}" />
            <corners android:radius="24dp" />
        </shape>
    """)

    w(files, "app/src/main/res/xml/widget_info.xml", """
        <?xml version="1.0" encoding="utf-8"?>
        <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
            android:initialLayout="@layout/widget"
            android:minHeight="80dp"
            android:minWidth="180dp"
            android:previewLayout="@layout/widget"
            android:resizeMode="horizontal|vertical"
            android:targetCellHeight="1"
            android:targetCellWidth="3"
            android:updatePeriodMillis="1800000"
            android:widgetCategory="home_screen" />
    """)

    return {
        "manifest": f"""
        <receiver
            android:name=".DemoWidgetProvider"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
                <action android:name="{pkg}.WIDGET_REFRESH" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_info" />
        </receiver>""",
        "status": "Home screen widget",
        "hint": "Long-press the home screen, tap Widgets, find the app.",
    }


def surface_shortcuts(files, pkg, src, app_name):
    # An <intent> without an action is silently dropped by the launcher, which
    # looks identical to the shortcuts never having registered.
    w(files, "app/src/main/res/xml/shortcuts.xml", f"""
        <?xml version="1.0" encoding="utf-8"?>
        <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">

            <shortcut
                android:enabled="true"
                android:icon="@drawable/ic_surface"
                android:shortcutId="alpha"
                android:shortcutShortLabel="@string/shortcut_alpha">
                <intent
                    android:action="android.intent.action.VIEW"
                    android:targetClass="{pkg}.MainActivity"
                    android:targetPackage="{pkg}" />
            </shortcut>

            <shortcut
                android:enabled="true"
                android:icon="@drawable/ic_surface"
                android:shortcutId="beta"
                android:shortcutShortLabel="@string/shortcut_beta">
                <intent
                    android:action="android.intent.action.VIEW"
                    android:targetClass="{pkg}.MainActivity"
                    android:targetPackage="{pkg}" />
            </shortcut>

        </shortcuts>
    """)

    return {
        "manifest": None,
        "activity_meta": """
            <meta-data
                android:name="android.app.shortcuts"
                android:resource="@xml/shortcuts" />""",
        "strings": [
            ("shortcut_alpha", "First shortcut"),
            ("shortcut_beta", "Second shortcut"),
        ],
        "status": "Long-press app shortcuts",
        "hint": "Long-press the app icon in the launcher.",
    }


def surface_share(files, pkg, src, app_name):
    return {
        "manifest": None,
        "activity_intent_filter": """
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>""",
        "status": "Share sheet target",
        "hint": "Share any text from Chrome and pick the app.",
    }


def surface_processtext(files, pkg, src, app_name):
    w(files, f"{src}/ProcessTextActivity.kt", f"""
        package {pkg}

        import android.app.Activity
        import android.content.Intent
        import android.os.Bundle
        import android.widget.Toast

        /**
         * Appears in the text-selection popup menu anywhere in the system.
         * Returning EXTRA_PROCESS_TEXT replaces the selection, but only when the
         * source field is editable -- EXTRA_PROCESS_TEXT_READONLY says which
         * case this is.
         */
        class ProcessTextActivity : Activity() {{

            override fun onCreate(savedInstanceState: Bundle?) {{
                super.onCreate(savedInstanceState)

                val selected = intent
                    .getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                    ?.toString()
                    .orEmpty()

                val readOnly = intent
                    .getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)

                val transformed = selected.uppercase()

                if (readOnly) {{
                    Toast.makeText(this, transformed, Toast.LENGTH_LONG).show()
                }} else {{
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, transformed)
                    )
                }}

                // A translucent activity that never finishes leaves a dead
                // window on screen, so finish unconditionally.
                finish()
            }}
        }}
    """)

    return {
        "manifest": """
        <activity
            android:name=".ProcessTextActivity"
            android:exported="true"
            android:label="@string/app_name"
            android:theme="@android:style/Theme.Translucent.NoTitleBar">
            <intent-filter>
                <action android:name="android.intent.action.PROCESS_TEXT" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>""",
        "status": "Text selection action",
        "hint": "Select text anywhere, tap the overflow in the popup menu.",
    }


BUILDERS = {
    "tile": surface_tile,
    "widget": surface_widget,
    "shortcuts": surface_shortcuts,
    "share": surface_share,
    "processtext": surface_processtext,
}


# --------------------------------------------------------------------------
# MainActivity + manifest assembly
# --------------------------------------------------------------------------

def main_activity(files, pkg, src, app_name, chosen, parts):
    rows = "\n".join(
        '        row("{}", "{}")'.format(
            kotlin_string(parts[s]["status"]), kotlin_string(parts[s]["hint"])
        )
        for s in chosen
    )

    # requestAddTileService is inlined inside the SDK_INT check rather than
    # living in its own method: lint's version analysis does not follow guards
    # across method boundaries, so extracting it raises a false NewApi error.
    tile_block = ""
    if "tile" in chosen:
        tile_block = """
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
"""

    share_block = ""
    if "share" in chosen:
        share_block = """
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            root.addView(TextView(this).apply {
                text = "Shared in: " + shared
                setPadding(0, 32, 0, 0)
            })
        }
"""

    # No androidx here on purpose -- the project ships with zero dependencies,
    # so an androidx.annotation import would fail to resolve.
    imports = [
        "android.app.Activity",
        "android.os.Bundle",
        "android.view.ViewGroup",
        "android.widget.LinearLayout",
        "android.widget.ScrollView",
        "android.widget.TextView",
    ]
    if "share" in chosen:
        imports += ["android.content.Intent"]
    if "tile" in chosen:
        imports += [
            "android.app.StatusBarManager",
            "android.content.ComponentName",
            "android.graphics.drawable.Icon",
            "android.os.Build",
            "android.widget.Button",
        ]
    import_block = "\n".join(f"import {i}" for i in sorted(set(imports)))

    # Built without dedent: the interpolated blocks carry their own indentation,
    # which would otherwise collapse the common prefix to zero.
    files[f"{src}/MainActivity.kt"] = f"""package {pkg}

{import_block}

/**
 * Not required by most of these surfaces -- it exists so the app appears in the
 * launcher and can report what it registered.
 */
class MainActivity : Activity() {{

    override fun onCreate(savedInstanceState: Bundle?) {{
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {{
            orientation = LinearLayout.VERTICAL
            setPadding(56, 120, 56, 56)
        }}

        fun row(title: String, hint: String) {{
            root.addView(TextView(this).apply {{
                text = title
                textSize = 18f
                setPadding(0, 24, 0, 0)
            }})
            root.addView(TextView(this).apply {{
                text = hint
                textSize = 14f
                alpha = 0.7f
            }})
        }}

        root.addView(TextView(this).apply {{
            text = "{kotlin_string(app_name)}"
            textSize = 26f
        }})

{rows}
{tile_block}{share_block}
        setContentView(ScrollView(this).apply {{
            addView(
                root,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }})
    }}
}}
"""


def manifest(files, chosen, parts):
    blocks = "\n\n".join(
        indent(parts[s]["manifest"], 8)
        for s in chosen if parts[s].get("manifest")
    )
    activity_meta = "\n".join(
        indent(parts[s]["activity_meta"], 12)
        for s in chosen if parts[s].get("activity_meta")
    )
    activity_filters = "\n".join(
        indent(parts[s]["activity_intent_filter"], 12)
        for s in chosen if parts[s].get("activity_intent_filter")
    )

    inner = "".join(
        "\n" + block for block in (activity_filters, activity_meta) if block
    )
    outer = ("\n\n" + blocks) if blocks else ""

    files["app/src/main/AndroidManifest.xml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:theme="@android:style/Theme.DeviceDefault">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>{inner}
        </activity>{outer}

    </application>

</manifest>
"""


def readme(files, app_name, chosen, parts, slug):
    lines = "\n".join(f"- **{parts[s]['status']}** \u2014 {parts[s]['hint']}"
                      for s in chosen)

    # Written without textwrap.dedent on purpose. The interpolated block above
    # starts at column zero, which would make the common prefix zero and leave
    # every other line indented by eight spaces -- in Markdown that renders the
    # whole file as a code block.
    files["README.md"] = f"""# {app_name}

A throwaway Android app that registers real system surfaces on a Pixel.

{lines}

## Check it before pushing

```bash
python verify.py .
```

Catches dangling resource references, manifest classes with no source, and
malformed XML in a couple of seconds -- all of which otherwise cost a full CI
round trip to discover. (Run it from wherever the skill's `scripts/` live.)

## Getting it onto the phone

Create an empty repo and push:

```bash
git init -b main
git add .
git commit -m "Initial scaffold"
gh repo create <name> --private --source=. --push
```

(Or create the repo in the GitHub UI and `git remote add origin ...`.)

The **Build debug APK** workflow then runs on every push, roughly three
minutes cold. When it finishes:

1. On the phone, open the repo's **Releases** page. The `debug-latest`
   release is always the newest build, so the URL never changes.
2. Tap `{slug}-debug.apk`.
3. Allow installs from Chrome when prompted.

The APK is also uploaded as a workflow artifact, but artifacts download as a
`.zip`, which Android will not install. Use the release.

A private repo needs the phone's browser signed into GitHub for the download
to work.

## Building locally instead

Open the folder in Android Studio and press Run, or with Gradle
{GRADLE_VERSION} and JDK 17 installed: `gradle assembleDebug`.

A locally built APK is signed with a different debug key than the CI one, so
installing one over the other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
Uninstall first when switching.
"""


# --------------------------------------------------------------------------

def validate_package(pkg):
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", pkg):
        sys.exit(f"Invalid package name: {pkg!r}\n"
                 "Use lowercase letters, digits and underscores, with at least "
                 "two dot-separated segments (e.g. com.example.app).")
    bad = [seg for seg in pkg.split(".") if seg in RESERVED_SEGMENTS]
    if bad:
        sys.exit(f"Package segment(s) {', '.join(bad)} are reserved words and "
                 "cannot be used in a package name.")


def main():
    p = argparse.ArgumentParser(
        description="Scaffold an Android app that registers Pixel system surfaces."
    )
    p.add_argument("--out", required=True, help="output directory")
    p.add_argument("--package", required=True, help="e.g. com.example.surfaces")
    p.add_argument("--app-name", required=True)
    p.add_argument("--surface", default="tile",
                   help=f"comma-separated: {', '.join(SURFACES)} — or 'all'")
    p.add_argument("--force", action="store_true",
                   help="write into a non-empty directory, overwriting files")
    args = p.parse_args()

    pkg = args.package.strip()
    validate_package(pkg)

    app_name = args.app_name.strip()
    if not app_name:
        sys.exit("--app-name cannot be empty.")

    chosen = SURFACES if args.surface.strip() == "all" else [
        s.strip() for s in args.surface.split(",") if s.strip()
    ]
    seen = set()
    chosen = [s for s in chosen if not (s in seen or seen.add(s))]
    unknown = [s for s in chosen if s not in SURFACES]
    if unknown:
        sys.exit(f"Unknown surface(s): {', '.join(unknown)}.\n"
                 f"Available: {', '.join(SURFACES)}, or 'all'.")
    if not chosen:
        sys.exit("No surfaces selected.")

    if os.path.isdir(args.out) and os.listdir(args.out) and not args.force:
        sys.exit(f"{args.out} is not empty. Re-run with --force to overwrite.")

    slug = slugify(app_name)
    src = "app/src/main/java/" + pkg.replace(".", "/")
    files = {}

    skeleton(files, pkg, app_name)
    workflow(files, app_name, slug)

    parts = {s: BUILDERS[s](files, pkg, src, app_name) for s in chosen}

    extra_strings = []
    for s in chosen:
        extra_strings.extend(parts[s].get("strings", []))
    strings(files, app_name, extra_strings)

    main_activity(files, pkg, src, app_name, chosen, parts)
    manifest(files, chosen, parts)
    readme(files, app_name, chosen, parts, slug)

    for rel, content in sorted(files.items()):
        dest = os.path.join(args.out, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as fh:
            fh.write(content)

    print(f"Wrote {len(files)} files to {args.out}")
    print(f"Surfaces: {', '.join(chosen)}")
    print(f"APK will be published as: {slug}-debug.apk")
    print("Next: push to a GitHub repo; the workflow publishes it as a release.")


if __name__ == "__main__":
    main()
