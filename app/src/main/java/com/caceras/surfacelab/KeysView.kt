package com.caceras.surfacelab

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * The keys, drawn in one view so a tap costs one invalidate and no layout. Touch handling covers
 * what typing needs: a key fires when the finger lifts (a second finger landing fires the first at
 * once, so fast typing never drops letters), a held letter offers its alternates, a held backspace
 * repeats, a finger sliding on the space bar moves the cursor, and holding space reports
 * [Listener.spaceHeld] for voice. Key previews and alternates are drawn by [previews], which spans
 * the bar above so the top row's preview has room.
 */
@SuppressLint("ViewConstructor")
class KeysView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun key(key: Key)
        fun text(text: String)
        fun cursor(steps: Int)
        /** Space held still: true when the listener started something (voice), so the lift types nothing. */
        fun spaceHeld(): Boolean = false
        fun spaceReleased() {}
        /** A long press on a control key (space shows the keyboard picker). */
        fun longPress(key: Key): Boolean = false
    }

    enum class Shift { OFF, ONCE, LOCKED }

    var layout: KeyLayout? = null
        set(value) { val rows = field?.rows?.size; field = value; place(); if (value?.rows?.size != rows) requestLayout(); invalidate() }
    var shift = Shift.OFF
        set(value) { if (field != value) { field = value; invalidate() } }
    var enter = Keys.Enter.NEWLINE
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Plain keys only: the Æ key is drawn faded. */
    var genieOff = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** Shown dim on the space bar: the language. */
    var spaceLabel = ""
        set(value) { field = value; invalidate() }
    var previews: KeysOverlay? = null

    private val density = resources.displayMetrics.density
    private val rowHeight = (54 * density)
    private val gap = 5 * density
    private val radius = 7 * density
    private val slop = 10 * density
    private val main = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icons = HashMap<Int, Drawable>()
    private val colors = HashMap<Int, Int>()
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Colours are read once; a theme change recreates the keyboard's views. */
    private fun color(id: Int) = colors.getOrPut(id) { context.getColor(id) }

    /** One finger on the keys. */
    private class Finger(val id: Int, var key: Key?, val downX: Float, var lastX: Float) {
        var sliding = false
        var held = false
        var alternates: List<String> = emptyList()
        var choice = 0
        var voice = false
    }
    private val fingers = HashMap<Int, Finger>()
    private val pressed = HashSet<Key>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = layout?.rows?.size ?: 4
        val height = (rows * rowHeight + gap).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    /** Lays out every key: each row spans the width in proportion to its keys' widths. */
    private fun place() {
        val layout = layout ?: return
        val w = width.toFloat() - paddingLeft - paddingRight
        if (w <= 0) return
        layout.rows.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            var x = paddingLeft.toFloat()
            val top = paddingTop + r * rowHeight + gap
            row.forEach { key ->
                val kw = w * key.width / units
                key.rect.set(x + gap / 2, top, x + kw - gap / 2, top + rowHeight - gap)
                x += kw
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        labels.color = color(R.color.keys_ink)
        small.color = color(R.color.keys_ink_dim)
        for (key in layout.keys) {
            if (key.character && key.text.isEmpty()) continue // a half-key spacer
            fill.color = color(when {
                key.code == Keys.ENTER -> if (key in pressed) R.color.keys_action_pressed else R.color.keys_action
                key in pressed -> R.color.keys_pressed
                key.code == Keys.GENIE -> R.color.keys_key
                key.modifier -> R.color.keys_modifier
                else -> R.color.keys_key
            })
            canvas.drawRoundRect(key.rect, radius, radius, fill)
            drawFace(canvas, key)
        }
    }

    private fun drawFace(canvas: Canvas, key: Key) {
        val r = key.rect
        val icon = when (key.code) {
            Keys.SHIFT -> when (shift) { Shift.OFF -> R.drawable.ic_key_shift; Shift.ONCE -> R.drawable.ic_key_shift_on; Shift.LOCKED -> R.drawable.ic_key_caps }
            Keys.BACKSPACE -> R.drawable.ic_key_backspace
            Keys.ENTER -> when (enter) {
                Keys.Enter.SEARCH -> R.drawable.ic_key_search
                Keys.Enter.SEND -> R.drawable.ic_arrow_up
                Keys.Enter.GO, Keys.Enter.NEXT -> R.drawable.ic_key_next
                Keys.Enter.DONE -> R.drawable.ic_key_done
                Keys.Enter.PREVIOUS -> R.drawable.ic_key_previous
                Keys.Enter.NEWLINE -> R.drawable.ic_key_enter
            }
            else -> 0
        }
        if (icon != 0) {
            val d = icons.getOrPut(icon) { context.getDrawable(icon)!!.mutate() }
            d.setTint(color(if (key.code == Keys.ENTER) R.color.keys_on_action else R.color.keys_ink))
            val size = (22 * density).toInt()
            val cx = r.centerX().toInt(); val cy = r.centerY().toInt()
            d.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
            d.draw(canvas)
            return
        }
        when (key.code) {
            Keys.SPACE -> if (spaceLabel.isNotEmpty()) { small.textSize = 13 * density * fontScale(); canvas.drawText(spaceLabel, r.centerX(), r.centerY() + small.textSize / 3, small) }
            Keys.GENIE -> {
                labels.textSize = 21 * density * fontScale(); labels.typeface = medium
                if (genieOff) labels.color = color(R.color.keys_ink_dim)
                canvas.drawText("Æ", r.centerX(), r.centerY() + labels.textSize / 3, labels)
                labels.typeface = regular; labels.color = color(R.color.keys_ink)
            }
            else -> {
                val text = if (key.character && shift != Shift.OFF) key.upper else key.label
                labels.textSize = (if (text.length > 1 && !isEmoji(text)) 15f else 23f) * density * fontScale()
                canvas.drawText(text, r.centerX(), r.centerY() + labels.textSize / 3, labels)
                key.hint?.let { small.textSize = 10 * density * fontScale(); canvas.drawText(it, r.right - 8 * density, r.top + 13 * density, small) }
            }
        }
    }

    private fun fontScale() = resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private fun isEmoji(text: String) = text.codePointAt(0) > 0x2000

    // ------------------------------------------------------------------ touch

    fun keyAt(x: Float, y: Float): Key? {
        val layout = layout ?: return null
        val rows = layout.rows
        val r = ((y - paddingTop - gap / 2) / rowHeight).toInt().coerceIn(0, rows.size - 1)
        // In a row, the nearest key by x: the gaps between keys belong to a key too.
        return rows[r].filter { !(it.character && it.text.isEmpty()) }.minByOrNull { k -> if (x < k.rect.left) k.rect.left - x else if (x > k.rect.right) x - k.rect.right else 0f }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landing types the first finger's letter now: fast typing rolls.
                fingers.values.filter { it.key?.character == true && !it.held }.toList().forEach { lift(it, fire = true) }
                val i = event.actionIndex
                val key = keyAt(event.getX(i), event.getY(i)) ?: return true
                val finger = Finger(event.getPointerId(i), key, event.getX(i), event.getX(i))
                fingers[finger.id] = finger
                press(finger, key)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val finger = fingers[event.getPointerId(i)] ?: continue
                move(finger, event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val finger = fingers[event.getPointerId(event.actionIndex)] ?: return true
                lift(finger, fire = true)
            }
            MotionEvent.ACTION_CANCEL -> fingers.values.toList().forEach { lift(it, fire = false) }
        }
        return true
    }

    private fun press(finger: Finger, key: Key) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        pressed += key
        if (key.character) previews?.preview(this, key, if (shift != Shift.OFF) key.upper else key.label)
        // Each runnable posts itself, tagged with the finger, so lifting the finger cancels it.
        if (key.repeats) { listener.key(key); repeater(finger) } else holder(finger)
        invalidate()
    }

    private fun move(finger: Finger, x: Float, y: Float) {
        val key = finger.key ?: return
        when {
            finger.alternates.isNotEmpty() -> {
                val choice = previews?.choose(x + left) ?: 0
                if (choice != finger.choice) { finger.choice = choice; previews?.select(choice) }
            }
            finger.voice -> Unit
            key.code == Keys.SPACE && (finger.sliding || abs(x - finger.downX) > slop) -> {
                if (!finger.sliding) { finger.sliding = true; main.removeCallbacksAndMessages(finger); finger.lastX = x }
                val steps = ((x - finger.lastX) / (12 * density)).toInt()
                if (steps != 0) { finger.lastX += steps * 12 * density; listener.cursor(steps); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            }
            key.character && !finger.held -> {
                val now = keyAt(x, y)
                if (now != null && now !== key && now.character) {
                    pressed -= key; pressed += now; finger.key = now
                    main.removeCallbacksAndMessages(finger)
                    holder(finger)
                    previews?.preview(this, now, if (shift != Shift.OFF) now.upper else now.label)
                    invalidate()
                }
            }
        }
    }

    private fun lift(finger: Finger, fire: Boolean) {
        fingers.remove(finger.id)
        main.removeCallbacksAndMessages(finger)
        val key = finger.key
        if (key != null) pressed -= key
        previews?.clear()
        invalidate()
        if (!fire || key == null) return
        when {
            finger.voice -> listener.spaceReleased()
            finger.alternates.isNotEmpty() -> finger.alternates.getOrNull(finger.choice)?.let { listener.text(it) }
            finger.sliding -> Unit
            key.repeats -> Unit
            finger.held && key.code != Keys.SPACE -> Unit
            else -> listener.key(key)
        }
    }

    private fun repeater(finger: Finger): Runnable = object : Runnable {
        override fun run() {
            if (fingers[finger.id] !== finger) return
            finger.key?.let { listener.key(it); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
            main.postAtTime(this, finger, SystemClock.uptimeMillis() + REPEAT_EVERY)
        }
    }.also { r -> main.removeCallbacksAndMessages(finger); main.postAtTime(r, finger, SystemClock.uptimeMillis() + REPEAT_START) }

    private fun holder(finger: Finger): Runnable = Runnable {
        if (fingers[finger.id] !== finger || finger.sliding) return@Runnable
        val key = finger.key ?: return@Runnable
        finger.held = true
        when {
            key.code == Keys.SPACE -> {
                if (listener.spaceHeld()) { finger.voice = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                else if (listener.longPress(key)) { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                else finger.held = false
            }
            key.alternates.isNotEmpty() -> {
                val shown = if (shift != Shift.OFF && key.character) key.alternates.map { it.uppercase() } else key.alternates
                finger.alternates = shown; finger.choice = 0
                previews?.alternates(this, key, shown)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            listener.longPress(key) -> performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            else -> finger.held = false
        }
    }.also { r -> main.postAtTime(r, finger, SystemClock.uptimeMillis() + (if (finger.key?.code == Keys.SPACE) SPACE_HOLD else LONG_PRESS)) }

    /** TalkBack: explore by touch announces keys and types the one under the finger when it lifts. */
    private var explored: Key? = null
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val key = keyAt(event.x, event.y)
                if (key != null && key !== explored) { explored = key; announceForAccessibility(describe(key)) }
            }
            MotionEvent.ACTION_HOVER_EXIT -> { explored?.let { listener.key(it) }; explored = null }
        }
        return true
    }

    override fun onInitializeAccessibilityEvent(event: AccessibilityEvent) {
        super.onInitializeAccessibilityEvent(event)
        event.className = "android.inputmethodservice.Keyboard"
    }

    fun describe(key: Key): String = when (key.code) {
        Keys.SHIFT -> if (shift == Shift.LOCKED) "Caps lock" else "Shift"
        Keys.BACKSPACE -> "Delete"
        Keys.ENTER -> enter.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (enter == Keys.Enter.NEWLINE) "New line" else it }
        Keys.SPACE -> "Space"
        Keys.GENIE -> "Ægentica"
        Keys.SYMBOLS -> "Numbers and symbols"
        Keys.LETTERS -> "Letters"
        Keys.MORE -> "More symbols"
        Keys.EMOJI -> "Emoji"
        else -> if (shift != Shift.OFF) key.upper else key.label
    }

    /** Forget fingers and timers (the keyboard was hidden mid-press). */
    fun reset() {
        fingers.clear(); pressed.clear(); main.removeCallbacksAndMessages(null); previews?.clear(); invalidate()
    }

    companion object {
        const val LONG_PRESS = 300L
        const val SPACE_HOLD = 450L
        const val REPEAT_START = 400L
        const val REPEAT_EVERY = 50L
    }
}

/**
 * Draws over the bar and the keys: the preview of the key under the finger and the row of
 * alternates a long press offers.
 */
class KeysOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bubble = RectF()
    private var label: String? = null
    private var options: List<String> = emptyList()
    private var chosen = 0
    private val cell = 44 * density

    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Never sets the keyboard's height: it takes the panel's size once that is known. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (exact) MeasureSpec.getSize(heightMeasureSpec) else 0)
    }

    private fun place(keys: View, key: Key, w: Float) {
        val dx = keys.left.toFloat(); val dy = keys.top.toFloat()
        val h = key.rect.height() * 1.15f
        val cx = (key.rect.centerX() + dx).coerceIn(w / 2, width - w / 2)
        bubble.set(cx - w / 2, key.rect.top + dy - h - 4 * density, cx + w / 2, key.rect.top + dy - 4 * density)
        if (bubble.top < 0) bubble.offset(0f, -bubble.top)
    }

    fun preview(keys: View, key: Key, shown: String) {
        options = emptyList()
        label = shown
        place(keys, key, maxOf(key.rect.width() * 1.2f, 44 * density))
        invalidate()
    }

    fun alternates(keys: View, key: Key, shown: List<String>) {
        label = null
        options = shown; chosen = 0
        place(keys, key, cell * shown.size)
        invalidate()
    }

    /** Which alternate is under the finger at [x] (in this view's coordinates). */
    fun choose(x: Float): Int = if (options.isEmpty()) 0 else ((x - bubble.left) / cell).toInt().coerceIn(0, options.size - 1)

    fun select(index: Int) { chosen = index; invalidate() }

    fun clear() { if (label != null || options.isNotEmpty()) { label = null; options = emptyList(); invalidate() } }

    override fun onDraw(canvas: Canvas) {
        if (label == null && options.isEmpty()) return
        val r = 10 * density
        fill.color = context.getColor(R.color.keys_preview)
        edge.color = context.getColor(R.color.keys_edge)
        canvas.drawRoundRect(bubble, r, r, fill)
        canvas.drawRoundRect(bubble, r, r, edge)
        text.color = context.getColor(R.color.keys_ink)
        label?.let {
            text.textSize = 30 * density
            canvas.drawText(it, bubble.centerX(), bubble.centerY() + text.textSize / 3, text)
            return
        }
        text.textSize = 22 * density
        options.forEachIndexed { i, option ->
            val left = bubble.left + i * cell
            if (i == chosen) {
                fill.color = context.getColor(R.color.keys_action)
                canvas.drawRoundRect(left + 3 * density, bubble.top + 3 * density, left + cell - 3 * density, bubble.bottom - 3 * density, r - 3 * density, r - 3 * density, fill)
                text.color = context.getColor(R.color.keys_on_action)
            } else text.color = context.getColor(R.color.keys_ink)
            canvas.drawText(option, left + cell / 2, bubble.centerY() + text.textSize / 3, text)
        }
    }
}
