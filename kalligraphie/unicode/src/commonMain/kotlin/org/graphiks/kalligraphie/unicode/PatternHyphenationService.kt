package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.HyphenationMinimums
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.HyphenationServiceIdentity

/**
 * Portable pattern-based (Liang) hyphenation service.
 *
 * The service parses one pattern set at construction, versioned by
 * [HyphenationServiceIdentity]. Hyphenation is computed with the standard
 * Liang algorithm over `a..z` and `.` pattern tokens: every pattern is
 * matched against `.word.`, observed digit weights raise the matching
 * boundary score, and odd scores become break points. The service is pure
 * and deterministic: the same identity, pattern set, and inputs always
 * produce the same output, on every platform and call.
 */
public class PatternHyphenationService(
    patterns: List<String>,
    /** Identity of this immutable service and its data revision. */
    public override val identity: HyphenationServiceIdentity,
    /** Default per-side minimums applied when the caller does not override them. */
    public val minimums: HyphenationMinimums = HyphenationMinimums.default,
) : HyphenationService {
    private val parsed: List<Pattern> = patterns
        .filter { line -> line.isNotBlank() && !line.startsWith("%") }
        .map(::parsePattern)

    init {
        require(this.parsed.isNotEmpty()) { "A hyphenation service requires a non-empty pattern set." }
    }

    override fun hyphenation(
        word: List<Int>,
        language: String,
        hyphenmins: HyphenationMinimums,
    ): List<Int> {
        if (!identity.languagesSnapshot.contains(language)) return emptyList()
        val lower = word.map { scalar ->
            when (scalar) {
                in 0x41..0x5A -> scalar + 0x20
                in 0x61..0x7A -> scalar
                else -> return emptyList()
            }
        }
        val length = lower.size
        if (length < hyphenmins.left + hyphenmins.right) return emptyList()
        val text = CharArray(length + 2)
        text[0] = '.'
        lower.forEachIndexed { index, scalar -> text[index + 1] = scalar.toChar() }
        text[length + 1] = '.'
        val textString = text.concatToString()
        val breaks = IntArray(textString.length + 1) { 0 }
        parsed.forEach { pattern ->
            var fromIndex = 0
            while (true) {
                val match = textString.indexOf(pattern.letters, fromIndex)
                if (match < 0) break
                pattern.weights.forEach { (offset, value) ->
                    val position = match + offset
                    if (position < breaks.size && breaks[position] < value) breaks[position] = value
                }
                fromIndex = match + 1
            }
        }
        // A word break after word letter index `k - 1` maps to text boundary `k + 1`
        // because the text is prefixed with one dot.
        val result = mutableListOf<Int>()
        for (k in hyphenmins.left..(length - hyphenmins.right)) {
            if (breaks[k + 1] % 2 == 1) result += k
        }
        return result
    }

    private data class Pattern(
        val letters: String,
        /** (boundary offset in text coordinates, digit weight) pairs. */
        val weights: List<Pair<Int, Int>>,
    )

    private fun parsePattern(line: String): Pattern {
        val letters = StringBuilder()
        val weights = mutableListOf<Pair<Int, Int>>()
        line.forEach { character ->
            when {
                character == '.' || character in 'a'..'z' -> letters.append(character)
                character in '0'..'9' -> weights += letters.length to (character - '0')
            }
        }
        return Pattern(letters.toString(), weights)
    }
}
