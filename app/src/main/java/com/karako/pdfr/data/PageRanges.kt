package com.karako.pdfr.data

/**
 * Parses a page selection such as `1-3,7,9-e,3`.
 *
 * - Page numbers are 1-based and refer to the document as it is now.
 * - `e` (or `E`) means the last page, so `9-e` is "page 9 through the end".
 * - Whitespace is ignored, including around commas and dashes.
 * - Entries are kept in the order the reader typed them and may repeat,
 *   which is useful for building a reordered or duplicated PDF.
 */
object PageRanges {

    sealed interface Result {
        data class Ok(val pages: IntArray) : Result
        data class Error(
            val reason: Reason,
            val token: String? = null,
            val page: Int? = null,
        ) : Result
    }

    enum class Reason {
        EMPTY,
        EMPTY_ENTRY,
        BAD_TOKEN,
        OUT_OF_RANGE,
        REVERSED,
        TOO_MANY,
    }

    /** Largest selection the parser accepts, to bound memory and work. */
    private const val MAX_PAGES = 50_000

    /**
     * Longest text the page-range fields accept. It is generous enough for a
     * full page list while keeping a paste of a huge document from freezing
     * the dialog.
     */
    const val MAX_INPUT_LENGTH = 500

    private val tokenPattern = Regex("^(?:\\d+|[eE])(?:-(?:\\d+|[eE]))?$")

    fun parse(input: String, pageCount: Int): Result {
        if (pageCount <= 0) return Result.Error(Reason.OUT_OF_RANGE)
        if (input.isBlank()) return Result.Error(Reason.EMPTY)

        val pages = ArrayList<Int>()
        val compact = input.filterNot { it.isWhitespace() }

        for (token in compact.split(',')) {
            if (token.isEmpty()) return Result.Error(Reason.EMPTY_ENTRY)
            if (!tokenPattern.matches(token)) {
                return Result.Error(Reason.BAD_TOKEN, token = token)
            }

            val dash = token.indexOf('-')
            val first = if (dash < 0) token else token.substring(0, dash)
            val last = if (dash < 0) first else token.substring(dash + 1)

            if (numberOrNull(first) == null && !isEnd(first)) {
                return Result.Error(Reason.BAD_TOKEN, token = token)
            }
            if (numberOrNull(last) == null && !isEnd(last)) {
                return Result.Error(Reason.BAD_TOKEN, token = token)
            }

            val start = resolve(first, pageCount)
                ?: return Result.Error(
                    Reason.OUT_OF_RANGE,
                    token = token,
                    page = numberOrNull(first)
                )
            val end = resolve(last, pageCount)
                ?: return Result.Error(
                    Reason.OUT_OF_RANGE,
                    token = token,
                    page = numberOrNull(last)
                )

            if (start > end) return Result.Error(Reason.REVERSED, token = token)

            if (pages.size + (end - start + 1) > MAX_PAGES) {
                return Result.Error(Reason.TOO_MANY)
            }
            for (page in start..end) pages.add(page)
        }

        if (pages.isEmpty()) return Result.Error(Reason.EMPTY)
        return Result.Ok(pages.toIntArray())
    }

    private fun resolve(part: String, pageCount: Int): Int? {
        if (isEnd(part)) return pageCount
        val value = part.toIntOrNull() ?: return null
        return value.takeIf { it in 1..pageCount }
    }

    private fun isEnd(part: String): Boolean = part == "e" || part == "E"

    private fun numberOrNull(part: String): Int? = part.toIntOrNull()
}
