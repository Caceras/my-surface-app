package com.caceras.surfacelab

import android.graphics.RectF
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * One key: what it shows, what it types, its width in key units, and what a long press offers.
 * [code] is a character key (`TEXT`) or one of the control codes in [Keys].
 */
class Key(
    val label: String,
    val code: Int = Keys.TEXT,
    val text: String = label,
    val width: Float = 1f,
    val alternates: List<String> = emptyList(),
    /** Small hint drawn in the corner: the first alternate, usually a digit. */
    val hint: String? = null,
) {
    /** Where the key sits, set when the keyboard is laid out. */
    val rect = RectF()
    val upper: String = label.uppercase()
    val repeats get() = code == Keys.BACKSPACE
    val character get() = code == Keys.TEXT
    /** Drawn on the darker modifier fill. */
    val modifier get() = code != Keys.TEXT && code != Keys.SPACE && code != Keys.GENIE
}

/** A page of keys: rows top to bottom; each row fills the width in proportion to its keys' widths. */
class KeyLayout(val name: String, val rows: List<List<Key>>) {
    val keys get() = rows.flatten()
}

/** Field facts that change the keyboard: which page, which enter action, what the genie may do. */
data class FieldMode(
    /** Number pad instead of letters. */
    val numeric: Boolean,
    val email: Boolean,
    val url: Boolean,
    /** Multi-line text: Enter is a new line unless the field asks for an action. */
    val multiLine: Boolean,
    /** Sentence-start capitals. */
    val autoCaps: Boolean,
)

object Keys {
    const val TEXT = 0
    const val SHIFT = -1
    const val BACKSPACE = -2
    const val ENTER = -3
    const val SPACE = -4
    const val SYMBOLS = -5
    const val LETTERS = -6
    const val MORE = -7
    const val GENIE = -8
    const val EMOJI = -9

    /** What Enter does in a field. */
    enum class Enter { NEWLINE, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

    /**
     * Enter follows the field: an action the field asks for (Search, Send, Go, Next, Done,
     * Previous), else a new line. IME_FLAG_NO_ENTER_ACTION, as in a chat box that wants new lines,
     * always means a new line.
     */
    fun enter(info: EditorInfo?): Enter {
        val options = info?.imeOptions ?: return Enter.NEWLINE
        if (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return Enter.NEWLINE
        return when (options and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> Enter.GO
            EditorInfo.IME_ACTION_SEARCH -> Enter.SEARCH
            EditorInfo.IME_ACTION_SEND -> Enter.SEND
            EditorInfo.IME_ACTION_NEXT -> Enter.NEXT
            EditorInfo.IME_ACTION_DONE -> Enter.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> Enter.PREVIOUS
            else -> Enter.NEWLINE
        }
    }

    fun action(enter: Enter): Int = when (enter) {
        Enter.GO -> EditorInfo.IME_ACTION_GO
        Enter.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        Enter.SEND -> EditorInfo.IME_ACTION_SEND
        Enter.NEXT -> EditorInfo.IME_ACTION_NEXT
        Enter.DONE -> EditorInfo.IME_ACTION_DONE
        Enter.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
        Enter.NEWLINE -> EditorInfo.IME_ACTION_NONE
    }

    fun mode(info: EditorInfo?): FieldMode {
        val type = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val text = cls == InputType.TYPE_CLASS_TEXT
        val email = text && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        val url = text && variation == InputType.TYPE_TEXT_VARIATION_URI
        return FieldMode(
            numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME,
            email = email,
            url = url,
            multiLine = text && type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
            autoCaps = text && !email && !url && type and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0,
        )
    }

    /** Long-press alternates: the top row's digits first, then accents, as phone keyboards do. */
    val ALTERNATES: Map<String, List<String>> = mapOf(
        "q" to listOf("1"), "w" to listOf("2"), "e" to listOf("3", "é", "è", "ê", "ë", "€"), "r" to listOf("4"), "t" to listOf("5"),
        "y" to listOf("6", "ý", "ÿ"), "u" to listOf("7", "ü", "ú", "ù", "û"), "i" to listOf("8", "í", "ì", "î", "ï"),
        "o" to listOf("9", "ó", "ò", "ô", "õ", "ø", "œ"), "p" to listOf("0"),
        "a" to listOf("à", "á", "â", "ã", "æ", "å", "ä"), "s" to listOf("ß", "ś", "š", "$"), "d" to listOf("ð"), "l" to listOf("ł"),
        "ö" to listOf("ø", "ő", "ó", "ò", "ô"), "ä" to listOf("æ", "à", "á", "â"), "å" to listOf("à", "á", "â"),
        "z" to listOf("ž", "ź", "ż"), "c" to listOf("ç", "ć", "č"), "n" to listOf("ñ", "ń"),
        "." to listOf(",", "?", "!", ":", ";", "…", "'", "\"", "-"), "," to listOf("😊", ";", ":", "!", "?"),
    )

    private fun letter(c: String) = Key(c, alternates = ALTERNATES[c].orEmpty(), hint = ALTERNATES[c]?.firstOrNull()?.takeIf { it.length == 1 && it[0].isDigit() })
    private fun row(chars: String) = chars.map { letter(it.toString()) }

    private fun bottom(mode: FieldMode, page: Int): List<Key> {
        val left = if (page == 0) Key("?123", Keys.SYMBOLS, width = 1.5f) else Key("ABC", Keys.LETTERS, width = 1.5f)
        val second = when {
            mode.email -> Key("@", alternates = listOf(","))
            mode.url -> Key("/", alternates = listOf(",", "-", "_", ":"))
            page != 0 -> Key("😊", Keys.EMOJI)
            else -> Key(",", alternates = ALTERNATES[","].orEmpty())
        }
        val dot = if (mode.email || mode.url) Key(".", alternates = listOf(".se", ".com", ".nu", ".org", "-", "_")) else Key(".", alternates = ALTERNATES["."].orEmpty())
        return listOf(left, second, Key("Æ", Keys.GENIE), Key("", Keys.SPACE, text = " ", width = 4.5f), dot, Key("", Keys.ENTER, width = 2f))
    }

    /** Swedish QWERTY with å, ä and ö (the Svenska subtype). */
    fun swedish(mode: FieldMode) = KeyLayout("sv", listOf(
        row("qwertyuiopå"),
        row("asdfghjklöä"),
        listOf(Key("", SHIFT, width = 2f)) + row("zxcvbnm") + Key("", BACKSPACE, width = 2f),
        bottom(mode, 0),
    ))

    /** US English QWERTY (the English subtype): ten keys on top, a half-key inset on the home row. */
    fun english(mode: FieldMode) = KeyLayout("en", listOf(
        row("qwertyuiop"),
        listOf(Key("", Keys.TEXT, text = "", width = 0.5f)) + row("asdfghjkl") + Key("", Keys.TEXT, text = "", width = 0.5f),
        listOf(Key("", SHIFT, width = 1.5f)) + row("zxcvbnm") + Key("", BACKSPACE, width = 1.5f),
        bottom(mode, 0),
    ))

    fun symbols(mode: FieldMode) = KeyLayout("symbols", listOf(
        "1234567890".map { Key(it.toString()) },
        listOf("@", "#", "kr", "_", "&", "-", "+", "(", ")", "/").map { Key(it, alternates = if (it == "kr") listOf("€", "$", "£", "¥") else emptyList()) },
        listOf(Key("=\\<", MORE, width = 1.5f)) + listOf("*", "\"", "'", ":", ";", "!", "?").map { Key(it) } + Key("", BACKSPACE, width = 1.5f),
        bottom(mode, 1),
    ))

    fun more(mode: FieldMode) = KeyLayout("more", listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆").map { Key(it) },
        listOf("€", "£", "$", "¥", "^", "°", "=", "{", "}", "\\").map { Key(it) },
        listOf(Key("?123", SYMBOLS, width = 1.5f)) + listOf("%", "©", "®", "™", "✓", "[", "]").map { Key(it) } + Key("", BACKSPACE, width = 1.5f),
        bottom(mode, 2),
    ))

    /** Phone, number and date fields: a number pad. The genie is not offered there. */
    fun numbers(info: EditorInfo?): KeyLayout {
        val phone = (info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_PHONE
        return KeyLayout("numbers", listOf(
            listOf(Key("1"), Key("2"), Key("3"), Key(if (phone) "+" else "-", alternates = listOf("-", "+", "(", ")"))),
            listOf(Key("4"), Key("5"), Key("6"), Key(if (phone) "*" else ",", alternates = listOf(",", "*", "#", "/"))),
            listOf(Key("7"), Key("8"), Key("9"), Key("", BACKSPACE)),
            listOf(Key("ABC", LETTERS), Key(if (phone) "#" else ".", alternates = listOf(".", ",", "#")), Key("0"), Key("", SPACE, text = " "), Key("", ENTER)),
        ))
    }

    /** A first page of everyday emoji. */
    val EMOJI_SET = listOf(
        "😂", "😊", "😍", "🥲", "😅", "🙂", "😉", "😘", "🤔", "😭",
        "😮", "😎", "🥳", "🙏", "👍", "👏", "❤️", "🔥", "✨", "🎉",
        "✅", "💯", "👋", "🐇", "☕", "😴", "😬", "🤗", "👀", "💪")

    fun emoji(mode: FieldMode) = KeyLayout("emoji", EMOJI_SET.chunked(10).map { line -> line.map { Key(it) } } + listOf(bottom(mode, 3)))
}
