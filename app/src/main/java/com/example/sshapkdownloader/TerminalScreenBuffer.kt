package com.example.sshapkdownloader

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

class TerminalScreenBuffer(
    private val maxRows: Int = 2_000
) {
    private val lines = mutableListOf<MutableList<Cell>>(mutableListOf())
    private val pendingEscape = StringBuilder()
    private var cursorRow = 0
    private var cursorColumn = 0
    private var savedCursorRow = 0
    private var savedCursorColumn = 0
    private var currentStyle = CellStyle()

    fun append(text: String): String {
        var index = 0

        if (pendingEscape.isNotEmpty()) {
            val pendingLength = pendingEscape.length
            pendingEscape.append(text)
            val consumed = consumeEscape(pendingEscape.toString(), 0)
            if (consumed == -1) {
                return render()
            }
            applyEscape(pendingEscape.toString(), 0, consumed)
            pendingEscape.clear()
            index = consumed - pendingLength
            if (index < 0) {
                index = 0
            }
        }

        while (index < text.length) {
            when (val char = text[index]) {
                ESCAPE -> {
                    val consumed = consumeEscape(text, index)
                    if (consumed == -1) {
                        pendingEscape.append(text.substring(index))
                        break
                    }
                    applyEscape(text, index, consumed)
                    index = consumed
                }
                '\r' -> {
                    cursorColumn = 0
                    index++
                }
                '\n' -> {
                    newLine()
                    index++
                }
                '\b' -> {
                    cursorColumn = (cursorColumn - 1).coerceAtLeast(0)
                    index++
                }
                '\t' -> {
                    repeat(TAB_WIDTH - cursorColumn % TAB_WIDTH) {
                        writeChar(' ')
                    }
                    index++
                }
                else -> {
                    if (!Character.isISOControl(char)) {
                        writeChar(char)
                    }
                    index++
                }
            }
        }

        trimRows()
        return render()
    }

    fun clear(): String {
        lines.clear()
        lines.add(mutableListOf())
        cursorRow = 0
        cursorColumn = 0
        savedCursorRow = 0
        savedCursorColumn = 0
        currentStyle = CellStyle()
        pendingEscape.clear()
        return render()
    }

    private fun writeChar(char: Char) {
        ensureCursorLine()
        val line = lines[cursorRow]
        while (line.size < cursorColumn) {
            line.add(Cell(' ', currentStyle))
        }
        if (cursorColumn == line.size) {
            line.add(Cell(char, currentStyle))
        } else {
            line[cursorColumn] = Cell(char, currentStyle)
        }
        cursorColumn++
    }

    private fun newLine() {
        cursorRow++
        cursorColumn = 0
        ensureCursorLine()
    }

    private fun ensureCursorLine() {
        while (lines.size <= cursorRow) {
            lines.add(mutableListOf())
        }
    }

    private fun trimRows() {
        if (lines.size <= maxRows) {
            return
        }

        val removeCount = lines.size - maxRows
        repeat(removeCount) {
            lines.removeAt(0)
        }
        cursorRow = (cursorRow - removeCount).coerceAtLeast(0)
    }

    fun renderStyled(): CharSequence {
        var lastVisibleLine = lines.indexOfLast { line -> line.any { it.char != ' ' } }
        if (lastVisibleLine < cursorRow) {
            lastVisibleLine = cursorRow
        }
        if (lastVisibleLine < 0) {
            return ""
        }

        val builder = SpannableStringBuilder()
        lines.take(lastVisibleLine + 1).forEachIndexed { rowIndex, line ->
            val trimmedLine = line.dropLastWhile { it.char == ' ' }
            trimmedLine.forEach { cell ->
                val start = builder.length
                builder.append(cell.char)
                builder.setSpan(
                    ForegroundColorSpan(cell.style.foregroundColor),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (cell.style.textStyle != NORMAL_TEXT_STYLE) {
                    builder.setSpan(
                        StyleSpan(cell.style.textStyle),
                        start,
                        builder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            if (rowIndex < lastVisibleLine) {
                builder.append('\n')
            }
        }
        return builder
    }

    private fun render(): String {
        var lastVisibleLine = lines.indexOfLast { line -> line.any { it.char != ' ' } }
        if (lastVisibleLine < cursorRow) {
            lastVisibleLine = cursorRow
        }
        if (lastVisibleLine < 0) {
            return ""
        }
        return lines.take(lastVisibleLine + 1).joinToString("\n") { line ->
            line.dropLastWhile { it.char == ' ' }.joinToString("") { it.char.toString() }
        }
    }

    private fun consumeEscape(text: String, start: Int): Int {
        if (start + 1 >= text.length) {
            return -1
        }

        return when (text[start + 1]) {
            '[' -> consumeCsi(text, start + 2)
            ']' -> consumeOsc(text, start + 2)
            '(', ')' -> if (start + 2 < text.length) start + 3 else -1
            '7', '8', 'c' -> start + 2
            else -> start + 2
        }
    }

    private fun consumeCsi(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] in '@'..'~') {
                return index + 1
            }
            index++
        }
        return -1
    }

    private fun consumeOsc(text: String, start: Int): Int {
        var index = start
        while (index < text.length) {
            if (text[index] == '\u0007') {
                return index + 1
            }
            if (text[index] == ESCAPE && index + 1 < text.length && text[index + 1] == '\\') {
                return index + 2
            }
            index++
        }
        return -1
    }

    private fun applyEscape(text: String, start: Int, end: Int) {
        if (end == start + 2) {
            when (text[start + 1]) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'c' -> clear()
            }
            return
        }

        if (end <= start + 2 || text[start + 1] != '[') {
            return
        }

        val finalChar = text[end - 1]
        val body = text.substring(start + 2, end - 1)
        if (body.startsWith("?") && (finalChar == 'h' || finalChar == 'l')) {
            applyPrivateMode(body)
            return
        }

        val params = parseCsiParameters(body)
        when (finalChar) {
            'm' -> applySgrParameters(params)
            'A' -> cursorRow = (cursorRow - param(params, 0, 1)).coerceAtLeast(0)
            'B' -> {
                cursorRow += param(params, 0, 1)
                ensureCursorLine()
            }
            'C' -> cursorColumn += param(params, 0, 1)
            'D' -> cursorColumn = (cursorColumn - param(params, 0, 1)).coerceAtLeast(0)
            'G' -> cursorColumn = (param(params, 0, 1) - 1).coerceAtLeast(0)
            'H', 'f' -> moveCursor(params)
            'J' -> eraseDisplay(param(params, 0, 0))
            'K' -> eraseLine(param(params, 0, 0))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'd' -> {
                cursorRow = (param(params, 0, 1) - 1).coerceAtLeast(0)
                ensureCursorLine()
            }
        }
    }

    private fun applyPrivateMode(body: String) {
        val modes = body.drop(1)
            .split(';')
            .mapNotNull { it.toIntOrNull() }
        if (1049 in modes || 47 in modes || 1047 in modes) {
            clear()
        }
    }

    private fun parseCsiParameters(body: String): List<Int?> {
        if (body.isBlank()) {
            return emptyList()
        }
        return body.split(';').map { it.toIntOrNull() }
    }

    private fun param(params: List<Int?>, index: Int, defaultValue: Int): Int {
        return params.getOrNull(index)?.takeIf { it > 0 } ?: defaultValue
    }

    private fun moveCursor(params: List<Int?>) {
        cursorRow = (param(params, 0, 1) - 1).coerceAtLeast(0)
        cursorColumn = (param(params, 1, 1) - 1).coerceAtLeast(0)
        ensureCursorLine()
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorColumn = cursorColumn
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorColumn = savedCursorColumn
        ensureCursorLine()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (row in cursorRow + 1 until lines.size) {
                    lines[row].clear()
                }
            }
            1 -> {
                for (row in 0 until cursorRow) {
                    lines[row].clear()
                }
                eraseLine(1)
            }
            2, 3 -> clear()
        }
    }

    private fun eraseLine(mode: Int) {
        ensureCursorLine()
        val line = lines[cursorRow]
        when (mode) {
            0 -> {
                if (cursorColumn < line.size) {
                    line.subList(cursorColumn, line.size).clear()
                }
            }
            1 -> {
                val end = (cursorColumn + 1).coerceAtMost(line.size)
                for (index in 0 until end) {
                    line[index] = Cell(' ', currentStyle)
                }
            }
            2 -> line.clear()
        }
    }

    private fun applySgrParameters(parameters: List<Int?>) {
        val normalized = if (parameters.isEmpty()) listOf(0) else parameters.map { it ?: 0 }
        var index = 0
        while (index < normalized.size) {
            when (val parameter = normalized[index]) {
                0 -> currentStyle = CellStyle()
                1 -> currentStyle = currentStyle.copy(textStyle = BOLD_TEXT_STYLE)
                22 -> currentStyle = currentStyle.copy(textStyle = NORMAL_TEXT_STYLE)
                30, 31, 32, 33, 34, 35, 36, 37 -> {
                    currentStyle = currentStyle.copy(foregroundColor = ANSI_COLORS[parameter - 30])
                }
                39 -> currentStyle = currentStyle.copy(foregroundColor = DEFAULT_TEXT_COLOR)
                90, 91, 92, 93, 94, 95, 96, 97 -> {
                    currentStyle = currentStyle.copy(foregroundColor = BRIGHT_ANSI_COLORS[parameter - 90])
                }
                38 -> {
                    val consumed = applyExtendedForegroundColor(normalized, index)
                    if (consumed > index) {
                        index = consumed
                    }
                }
            }
            index++
        }
    }

    private fun applyExtendedForegroundColor(parameters: List<Int>, start: Int): Int {
        if (start + 2 >= parameters.size) {
            return start
        }

        return when (parameters[start + 1]) {
            5 -> {
                currentStyle = currentStyle.copy(foregroundColor = colorFrom256Palette(parameters[start + 2]))
                start + 2
            }
            2 -> {
                if (start + 4 >= parameters.size) {
                    start
                } else {
                    currentStyle = currentStyle.copy(
                        foregroundColor = rgb(
                            parameters[start + 2],
                            parameters[start + 3],
                            parameters[start + 4]
                        )
                    )
                    start + 4
                }
            }
            else -> start
        }
    }

    private fun colorFrom256Palette(color: Int): Int {
        val normalized = color.coerceIn(0, 255)
        if (normalized < 8) {
            return ANSI_COLORS[normalized]
        }
        if (normalized < 16) {
            return BRIGHT_ANSI_COLORS[normalized - 8]
        }
        if (normalized >= 232) {
            val level = 8 + (normalized - 232) * 10
            return rgb(level, level, level)
        }

        val colorIndex = normalized - 16
        val red = COLOR_CUBE_LEVELS[colorIndex / 36]
        val green = COLOR_CUBE_LEVELS[(colorIndex / 6) % 6]
        val blue = COLOR_CUBE_LEVELS[colorIndex % 6]
        return rgb(red, green, blue)
    }

    private data class Cell(
        val char: Char,
        val style: CellStyle
    )

    private data class CellStyle(
        val foregroundColor: Int = DEFAULT_TEXT_COLOR,
        val textStyle: Int = NORMAL_TEXT_STYLE
    )

    companion object {
        private const val ESCAPE = '\u001B'
        private const val TAB_WIDTH = 4
        private const val NORMAL_TEXT_STYLE = 0
        private const val BOLD_TEXT_STYLE = 1
        private val DEFAULT_TEXT_COLOR = rgb(183, 247, 200)
        private val ANSI_COLORS = intArrayOf(
            rgb(75, 85, 99),
            rgb(248, 113, 113),
            rgb(74, 222, 128),
            rgb(250, 204, 21),
            rgb(96, 165, 250),
            rgb(232, 121, 249),
            rgb(34, 211, 238),
            rgb(229, 231, 235)
        )
        private val BRIGHT_ANSI_COLORS = intArrayOf(
            rgb(156, 163, 175),
            rgb(252, 165, 165),
            rgb(134, 239, 172),
            rgb(253, 224, 71),
            rgb(147, 197, 253),
            rgb(240, 171, 252),
            rgb(103, 232, 249),
            rgb(249, 250, 251)
        )
        private val COLOR_CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

        private fun rgb(red: Int, green: Int, blue: Int): Int {
            return 0xFF000000.toInt() or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
        }
    }
}
