package app.vaultchat.ui.notes

/** One line of a checklist note: its text and whether it is ticked. */
data class CheckItem(val text: String, val done: Boolean)

/**
 * Checklist notes (owner 2026-11-25: "☐/☑ wali list, jaise saaman ki list"). A checklist is stored in the note's
 * ordinary text, one item per line starting with "☐ " (open) or "☑ " (ticked), so the server needs nothing new and
 * the note still reads as a list anywhere it is shown as plain text.
 */
object Checklist {
    const val OPEN = "☐ "
    const val DONE = "☑ "

    /** A note is a checklist when it has at least one line and every non-empty line is an item. */
    fun isChecklist(body: String): Boolean {
        val lines = body.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.all { it.startsWith(OPEN.trim()) || it.startsWith(DONE.trim()) }
    }

    fun parse(body: String): List<CheckItem> = body.lines().filter { it.isNotBlank() }.map { line ->
        val done = line.startsWith(DONE.trim())
        CheckItem(line.drop(1).removePrefix(" "), done)
    }

    fun format(items: List<CheckItem>): String = items.joinToString("\n") { (if (it.done) DONE else OPEN) + it.text.replace('\n', ' ') }

    /** Turns ordinary text into a checklist, one item per non-empty line; an empty note gets one empty item. */
    fun fromText(body: String): List<CheckItem> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { CheckItem(it, false) }.ifEmpty { listOf(CheckItem("", false)) }

    /** Turns a checklist back into ordinary text, one line per item. */
    fun toText(items: List<CheckItem>): String = items.joinToString("\n") { it.text }.trim()

    /** True when the checklist has no text at all (only empty items), so there is nothing to save yet. */
    fun isEmpty(body: String): Boolean = isChecklist(body) && parse(body).all { it.text.isBlank() }
}
