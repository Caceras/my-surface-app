package com.caceras.surfacelab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Ægentica hands, the opt-in accessibility service (Settings → Accessibility → Ægentica hands).
 * It does nothing on its own: it lends the phone agent the screen ([A11yScreen]) and draws the
 * rabbit ([RabbitOverlay]) while the agent works on something the owner asked. Touches by the
 * owner while it works pause it. Turning the service off in Android's settings removes it at once.
 */
class HandsService : AccessibilityService() {
    private var overlay: RabbitOverlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val drawn = RabbitOverlay(this)
        overlay = drawn
        Hands.attach(A11yScreen(this), drawn)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            // A click the owner made while the hands work means they took over; our own clicks
            // fall inside the hands' injecting window and are ignored there.
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                if (event.packageName?.toString() != packageName) Hands.userTouched()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (overlay == null) return
        overlay = null
        Hands.detach()
    }
}

/** An AccessibilityNodeInfo as the hands see it. */
class A11yNode(private val info: AccessibilityNodeInfo, override val parent: UiNode? = null) : UiNode {
    override val key: Any get() = info
    override val className get() = info.className?.toString().orEmpty()
    override val text get() = info.text?.toString()
    override val description get() = info.contentDescription?.toString()
    override val hint get() = info.hintText?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val bounds get() = Rect().also { info.getBoundsInScreen(it) }
    override val clickable get() = info.isClickable
    override val longClickable get() = info.isLongClickable
    override val editable get() = info.isEditable
    override val scrollable get() = info.isScrollable
    override val checkable get() = info.isCheckable
    override val checked get() = info.isChecked
    override val enabled get() = info.isEnabled
    override val selected get() = info.isSelected
    override val password get() = info.isPassword
    override val inputType get() = info.inputType
    override val visible get() = info.isVisibleToUser
    override val showingHint get() = info.isShowingHintText
    override val packageName get() = info.packageName?.toString().orEmpty()
    override val children: List<UiNode> by lazy { (0 until info.childCount).mapNotNull { i -> info.getChild(i)?.let { A11yNode(it, this) } } }
    override fun refresh() = info.refresh()
    override fun click() = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    override fun longClick() = info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    override fun focus() = info.performAction(AccessibilityNodeInfo.ACTION_FOCUS) || info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    override fun setText(text: String) = info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })

    override fun scroll(direction: String): Boolean {
        val listed = info.actionList.map { it.id }.toSet()
        val exact = when (direction) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        if (exact.id in listed && info.performAction(exact.id)) return true
        val fallback = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return fallback in listed && info.performAction(fallback)
    }

    override fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val action = AccessibilityAction.ACTION_IME_ENTER
        return info.actionList.any { it.id == action.id } && info.performAction(action.id)
    }
}

/** The screen through the accessibility service: windows, gestures, global actions and screenshots. */
class A11yScreen(private val service: AccessibilityService) : Screen {
    private val main = Handler(Looper.getMainLooper())

    /**
     * The window the owner is using. Ægentica's own sheet can sit on top when the agent starts, so
     * the app below it is read instead; the overlay's windows never count.
     */
    override fun root(): UiNode? {
        val active = service.rootInActiveWindow
        if (active != null && active.packageName?.toString() != service.packageName) return A11yNode(active)
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        return windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() != service.packageName } }
            ?.let { A11yNode(it) }
    }

    private val size: Rect get() = if (Build.VERSION.SDK_INT >= 30) service.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        else service.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
    override val width get() = size.width()
    override val height get() = size.height()

    private fun gesture(path: Path, durationMs: Long, done: (Boolean) -> Unit) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val sent = service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, main)
        if (!sent) done(false)
    }

    override fun tap(x: Int, y: Int, done: (Boolean) -> Unit) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60, done)
    override fun longPress(x: Int, y: Int, done: (Boolean) -> Unit) = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 700, done)
    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, done: (Boolean) -> Unit) =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs, done)

    override fun global(action: String) = service.performGlobalAction(when (action) {
        "back" -> AccessibilityService.GLOBAL_ACTION_BACK
        "home" -> AccessibilityService.GLOBAL_ACTION_HOME
        else -> AccessibilityService.GLOBAL_ACTION_RECENTS
    })

    override fun screenshot(done: (Bitmap?, String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null, "Screenshots need Android 11 or later."); return }
        take(done, retried = false)
    }

    @android.annotation.TargetApi(30)
    private fun take(done: (Bitmap?, String?) -> Unit, retried: Boolean) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                done(bitmap, if (bitmap == null) "Android did not give a screenshot." else null)
            }
            override fun onFailure(errorCode: Int) {
                when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                        if (!retried) main.postDelayed({ take(done, retried = true) }, 1100) else done(null, "Android allows one screenshot a second. Try again.")
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                        done(null, "This screen is protected (for example a banking app or a password screen), so Android gives no screenshot.")
                    else -> done(null, "Android did not give a screenshot.")
                }
            }
        })
    }

    override fun launch(intent: Intent) = runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    override fun apps(): List<Triple<String, String, Intent>> {
        val pm = service.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { r ->
            Triple(r.loadLabel(pm).toString(), r.activityInfo.packageName,
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(r.activityInfo.packageName, r.activityInfo.name))
        }.filter { it.second != service.packageName }
    }

    override fun appName(pkg: String): String = runCatching {
        service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) }
}
