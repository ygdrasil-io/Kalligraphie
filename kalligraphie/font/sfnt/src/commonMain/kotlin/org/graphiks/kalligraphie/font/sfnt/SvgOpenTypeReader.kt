package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.PaintGraphProfile

/**
 * One fully normalized SVG-in-OpenType paint result.
 *
 * A result contains only portable paint data. It never exposes the SVG document, an XML object,
 * a URI, or a renderer resource.
 */
public sealed interface SvgGlyphPaint {
    /** The SVG document is present but has no paintable ink in the supported subset. */
    public data object Empty : SvgGlyphPaint

    /** A complete profile-compatible portable paint graph. */
    public data class Paint(
        /** Normalized graph ready for a portable consumer. */
        public val paint: org.graphiks.kalligraphie.api.GlyphPaintIR,
    ) : SvgGlyphPaint
}

/**
 * Immutable, profile-certified SVG-in-OpenType data for one TrueType face.
 *
 * The value owns no source bytes. It can safely outlive parsing and be shared by detached render
 * assets. [glyphPaint] returns `null` when no SVG document covers the requested glyph and an
 * explicit [SvgGlyphPaint.Empty] when a covered document has no ink.
 */
public class SvgOpenTypeData internal constructor(
    records: List<SvgOpenTypeDocumentRecord>,
) {
    private val records: List<SvgOpenTypeDocumentRecord> = records.toList()

    /**
     * Returns the normalized result for [glyphId], or `null` when no SVG document covers it.
     *
     * This operation is read-only, deterministic, and safe for concurrent calls.
     */
    public fun glyphPaint(glyphId: GlyphId): SvgGlyphPaint? =
        records.firstOrNull { record -> glyphId.value in record.firstGlyphId..record.lastGlyphId }?.paint
}

/**
 * Decodes the deliberately small, safe SVG-in-OpenType subset implemented by Kalligraphie.
 *
 * Only SVG table version 0 with raw UTF-8 documents is accepted. Supported documents contain
 * `svg`, `g`, and self-closing `path` elements; `g` may contain `translate` and `scale`
 * transforms; paths may use `M`, `L`, `H`, `V`, `C`, `S`, and `Z` commands (and their relative forms)
 * with a solid `#RRGGBB` fill or `fill="none"` for an explicitly inkless path. All transforms are
 * applied to the portable path coordinates.
 * Scripts, network or external references, entities, animation, compressed documents, XML
 * declarations, gradients, clips, strokes, opacity, masks, and every unlisted element or
 * attribute are rejected before any [SvgOpenTypeData] is returned.
 */
public object SvgOpenTypeReader {
    /**
     * Decodes and validates every document in an OpenType `SVG ` table.
     *
     * [profile] bounds source bytes, document records, transform operations, graph nodes, paths,
     * depth, and path geometry before publication. The operation is all-or-nothing: malformed or
     * unsupported content returns a typed failure and no partial data. The returned value contains
     * no source XML and is safe to retain after the caller releases the source buffer.
     *
     * @param svgTable exact bytes of the OpenType `SVG ` table.
     * @param glyphCount glyph count from the owning TrueType face.
     * @param profile complete consumer capability and resource limits.
     * @return fully normalized portable data, or a typed invalid-data, unsupported, or limit error.
     */
    public fun read(
        svgTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
    ): FontOperationResult<SvgOpenTypeData> {
        if (glyphCount <= 0) return invalid("font.svg.invalid-glyph-count", "SVG glyph count must be positive.")
        if (svgTable.size > profile.limits.maxSourceBytes) {
            return limit("SVG source-byte limit exceeded.")
        }
        if (svgTable.size < SVG_HEADER_LENGTH) return invalid("font.svg.truncated", "SVG table header is truncated.")
        if (readUInt16(svgTable, 0)?.toInt() != SVG_TABLE_VERSION) {
            return invalid("font.svg.unsupported-version", "Only SVG table version 0 is supported.")
        }
        val documentListOffset = readUInt32(svgTable, 2)?.toLong()
            ?: return invalid("font.svg.truncated", "SVG document-list offset is truncated.")
        if (documentListOffset < SVG_HEADER_LENGTH || documentListOffset > Int.MAX_VALUE ||
            checkedRangeEnd(documentListOffset, 2L, svgTable.size) == null
        ) {
            return invalid("font.svg.invalid-document-list-offset", "SVG document-list offset is outside the table body.")
        }
        if (readUInt32(svgTable, 6) != 0u) {
            return invalid("font.svg.invalid-reserved", "SVG table reserved field must be zero.")
        }
        val documentListStart = documentListOffset.toInt()
        val documentCount = readUInt16(svgTable, documentListStart)?.toInt()
            ?: return invalid("font.svg.truncated", "SVG document-list count is truncated.")
        if (documentCount == 0) return invalid("font.svg.empty-document-list", "SVG document-list must not be empty.")
        if (documentCount > profile.limits.maxSvgDocuments) return limit("SVG document-record limit exceeded.")
        val recordsStart = documentListStart + DOCUMENT_LIST_HEADER_LENGTH
        if (checkedRangeEnd(recordsStart, documentCount * DOCUMENT_RECORD_LENGTH, svgTable.size) == null) {
            return invalid("font.svg.truncated", "SVG document records are truncated.")
        }

        val records = ArrayList<SvgOpenTypeDocumentRecord>(documentCount)
        var previousLastGlyphId = -1
        var cumulativeDocumentBytes = 0L
        val transformBudget = SvgTransformBudget(profile.limits.maxSvgTransformOperations)
        repeat(documentCount) { recordIndex ->
            val offset = recordsStart + recordIndex * DOCUMENT_RECORD_LENGTH
            val firstGlyphId = readUInt16(svgTable, offset)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG first glyph ID is truncated.")
            val lastGlyphId = readUInt16(svgTable, offset + 2)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG last glyph ID is truncated.")
            val documentOffset = readUInt32(svgTable, offset + 4)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document offset is truncated.")
            val documentLength = readUInt32(svgTable, offset + 8)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document length is truncated.")
            if (firstGlyphId > lastGlyphId || lastGlyphId >= glyphCount || firstGlyphId <= previousLastGlyphId) {
                return invalid("font.svg.invalid-glyph-range", "SVG document glyph ranges must be sorted, disjoint, and owned by the face.")
            }
            val sourceOffset = documentListOffset + documentOffset
            val sourceEnd = checkedRangeEnd(sourceOffset, documentLength, svgTable.size)
                ?: return invalid("font.svg.invalid-document-range", "SVG document bytes exceed the table.")
            if (documentLength == 0L) return invalid("font.svg.empty-document", "SVG document bytes must not be empty.")
            if (documentLength > profile.limits.maxSourceBytes.toLong() - cumulativeDocumentBytes) {
                return limit("SVG cumulative document-byte limit exceeded.")
            }
            cumulativeDocumentBytes += documentLength
            val document = svgTable.copyOfRange(sourceOffset.toInt(), sourceEnd)
            if (document.size >= 2 && document[0] == GZIP_MAGIC_0 && document[1] == GZIP_MAGIC_1) {
                return unsupported("Compressed SVG-in-OpenType documents are not supported.")
            }
            val xml = try {
                document.decodeToString(throwOnInvalidSequence = true)
            } catch (_: IllegalArgumentException) {
                return invalid("font.svg.invalid-utf8", "SVG document is not valid UTF-8.")
            }
            val paint = when (val parsed = SvgDocumentParser(profile, transformBudget).parse(xml)) {
                is FontOperationResult.Success -> parsed.value
                is FontOperationResult.Failure -> return parsed
                is FontOperationResult.Cancelled -> return parsed
            }
            records += SvgOpenTypeDocumentRecord(firstGlyphId, lastGlyphId, paint)
            previousLastGlyphId = lastGlyphId
        }
        return FontOperationResult.Success(SvgOpenTypeData(records))
    }

    /**
     * Returns whether the complete table can be normalized by the implemented safe subset.
     *
     * Catalogues use this conservative check before advertising an SVG paint route. Exact caller
     * limits are still applied by [read] while acquiring the render asset.
     */
    public fun hasStructurallyValidVersionZeroTable(svgTable: ByteArray, glyphCount: Int): Boolean =
        read(svgTable, glyphCount, capabilityProfile) is FontOperationResult.Success
}

internal data class SvgOpenTypeDocumentRecord(
    val firstGlyphId: Int,
    val lastGlyphId: Int,
    val paint: SvgGlyphPaint,
)

private class SvgDocumentParser(
    private val profile: PaintGraphProfile,
    private val transformBudget: SvgTransformBudget,
) {
    private val paths = mutableListOf<GlyphPaintNode.Path>()

    fun parse(xml: String): FontOperationResult<SvgGlyphPaint> {
        if (xml.contains("<!") || xml.contains("<?") || xml.contains('&')) {
            return unsupported("SVG declarations, entities, and processing instructions are not supported.")
        }
        val stack = ArrayDeque<SvgElement>()
        var rootSeen = false
        var cursor = 0
        while (cursor < xml.length) {
            val nextTag = xml.indexOf('<', cursor)
            if (nextTag < 0) {
                if (xml.substring(cursor).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is not supported.")
                break
            }
            if (xml.substring(cursor, nextTag).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is not supported.")
            val endTag = xml.findTagEnd(nextTag + 1) ?: return invalid("font.svg.truncated-xml", "SVG markup is truncated.")
            val token = xml.substring(nextTag + 1, endTag).trim()
            cursor = endTag + 1
            if (token.isEmpty()) return invalid("font.svg.invalid-element", "SVG contains an empty element.")
            if (token.startsWith('/')) {
                val name = token.drop(1).trim()
                if (name !in setOf("svg", "g") || stack.lastOrNull()?.name != name) {
                    return invalid("font.svg.invalid-close", "SVG element nesting is invalid.")
                }
                stack.removeLast()
                continue
            }
            val selfClosing = token.endsWith('/')
            val content = if (selfClosing) token.dropLast(1).trimEnd() else token
            val nameEnd = content.indexOfFirst { character -> character.isWhitespace() }.let { index -> if (index < 0) content.length else index }
            val name = content.substring(0, nameEnd)
            val attributes = parseAttributes(content.substring(nameEnd))
                ?: return invalid("font.svg.invalid-attribute", "SVG attributes must be quoted and unique.")
            when (name) {
                "svg" -> {
                    if (rootSeen || stack.isNotEmpty() || selfClosing || attributes.keys.any { key -> key !in SVG_ATTRIBUTES }) {
                        return unsupported("Only one non-empty root svg element with declared attributes is supported.")
                    }
                    if (attributes["xmlns"] != SVG_NAMESPACE) return unsupported("SVG root must declare the SVG namespace.")
                    rootSeen = true
                    stack.addLast(SvgElement("svg", AffineTransform.identity))
                }

                "g" -> {
                    if (stack.isEmpty() || selfClosing || attributes.keys.any { key -> key != "transform" }) {
                        return unsupported("SVG group attributes other than transform are not supported.")
                    }
                    if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                    val local = if ("transform" in attributes) {
                        when (val parsed = parseTransform(attributes.getValue("transform"))) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    } else {
                        AffineTransform.identity
                    }
                    val parent = stack.last().transform
                    stack.addLast(SvgElement("g", parent.then(local)))
                }

                "path" -> {
                    if (stack.isEmpty() || !selfClosing || attributes.keys.any { key -> key !in PATH_ATTRIBUTES }) {
                        return unsupported("Only self-closing paths with d and fill attributes are supported.")
                    }
                    val pathData = attributes["d"] ?: return invalid("font.svg.missing-path-data", "SVG path is missing d data.")
                    val fill = attributes["fill"] ?: "#000000"
                    if (fill == "none") continue
                    val color = parseColor(fill) ?: return unsupported("Only #RRGGBB SVG fills are supported.")
                    val pathsAfterAppend = paths.size + 1
                    val nodesAfterAppend = if (pathsAfterAppend == 1) pathsAfterAppend else pathsAfterAppend + 1
                    if (paths.size >= profile.limits.maxPaths || nodesAfterAppend > profile.limits.maxNodes) {
                        return limit("SVG path or paint-node limit exceeded.")
                    }
                    val path = when (val parsed = parsePath(pathData, stack.last().transform)) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    paths += GlyphPaintNode.Path(path, color)
                }

                else -> return unsupported("SVG element $name is not supported.")
            }
        }
        if (!rootSeen || stack.isNotEmpty()) return invalid("font.svg.unclosed-element", "SVG root or group element is not closed.")
        if (paths.isEmpty()) return FontOperationResult.Success(SvgGlyphPaint.Empty)
        val nodes = ArrayList<GlyphPaintNode>(paths.size + 1)
        nodes += paths
        val rootNode = if (paths.size == 1) {
            0
        } else {
            nodes += GlyphPaintNode.Group(paths.indices.toList())
            nodes.lastIndex
        }
        val paint = org.graphiks.kalligraphie.api.GlyphPaintIR(profile.schemaVersion, rootNode, nodes)
        if (!profile.accepts(paint)) return unsupported("SVG paint graph exceeds the selected profile.")
        return FontOperationResult.Success(SvgGlyphPaint.Paint(paint))
    }

    private fun parseTransform(value: String): FontOperationResult<AffineTransform> {
        var cursor = 0
        var transform = AffineTransform.identity
        while (cursor < value.length) {
            cursor = value.skipWhitespace(cursor)
            if (cursor == value.length) break
            val nameStart = cursor
            while (cursor < value.length && value[cursor].isLetter()) cursor += 1
            val name = value.substring(nameStart, cursor)
            cursor = value.skipWhitespace(cursor)
            if (cursor >= value.length || value[cursor] != '(') {
                return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            }
            val close = value.indexOf(')', cursor + 1)
            if (close < 0) return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            val numbers = SvgNumberCursor(value.substring(cursor + 1, close)).allNumbers()
                ?: return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            val next = when (name) {
                "translate" -> if (numbers.size in 1..2) AffineTransform.translate(numbers[0], numbers.getOrElse(1) { 0.0 }) else {
                    return invalid("font.svg.invalid-transform", "SVG translate transform has invalid operands.")
                }

                "scale" -> if (numbers.size in 1..2) AffineTransform.scale(numbers[0], numbers.getOrElse(1) { numbers[0] }) else {
                    return invalid("font.svg.invalid-transform", "SVG scale transform has invalid operands.")
                }

                else -> return unsupported("SVG transform $name is not supported.")
            }
            if (!transformBudget.tryConsume()) return limit("SVG transform-operation limit exceeded.")
            transform = try {
                transform.then(next)
            } catch (_: IllegalArgumentException) {
                return invalid("font.svg.invalid-transform", "SVG group transform exceeds the portable coordinate domain.")
            }
            cursor = close + 1
        }
        return FontOperationResult.Success(transform)
    }

    private fun parsePath(data: String, transform: AffineTransform): FontOperationResult<GlyphPaintPath> {
        val cursor = SvgNumberCursor(data)
        val commands = mutableListOf<RawPathCommand>()
        var activeCommand: Char? = null
        var current = Point(0.0, 0.0)
        var contourStart = Point(0.0, 0.0)
        var contourOpen = false
        var previousCubicControl2: Point? = null
        while (cursor.hasRemaining()) {
            val next = cursor.peek()
            if (next != null && next.isLetter()) {
                activeCommand = next
                cursor.advance()
            }
            val command = activeCommand ?: return invalid("font.svg.path-command", "SVG path data is missing a command.")
            val relative = command.isLowerCase()
            when (command.lowercaseChar()) {
                'm' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        val point = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG move command is invalid.")
                        if (contourOpen) commands += RawPathCommand.Close
                        if (count == 0) {
                            commands += RawPathCommand.MoveTo(point)
                            contourStart = point
                            contourOpen = true
                        } else {
                            commands += RawPathCommand.LineTo(point)
                        }
                        current = point
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG move command requires coordinates.")
                    activeCommand = if (relative) 'l' else 'L'
                }

                'l' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG line command requires a move command.")
                        val point = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG line command is invalid.")
                        commands += RawPathCommand.LineTo(point)
                        current = point
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG line command requires coordinates.")
                }

                'h' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG horizontal line requires a move command.")
                        val value = cursor.number() ?: return invalid("font.svg.path-number", "SVG horizontal line is invalid.")
                        current = Point(if (relative) current.x + value else value, current.y)
                        commands += RawPathCommand.LineTo(current)
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG horizontal line requires coordinates.")
                }

                'v' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG vertical line requires a move command.")
                        val value = cursor.number() ?: return invalid("font.svg.path-number", "SVG vertical line is invalid.")
                        current = Point(current.x, if (relative) current.y + value else value)
                        commands += RawPathCommand.LineTo(current)
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG vertical line requires coordinates.")
                }

                'c' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG cubic curve requires a move command.")
                        val control1 = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        val control2 = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        val endpoint = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        commands += RawPathCommand.CubicTo(control1, control2, endpoint)
                        current = endpoint
                        previousCubicControl2 = control2
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG cubic curve requires coordinates.")
                }

                's' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG smooth cubic curve requires a move command.")
                        val control1 = previousCubicControl2?.let { previous ->
                            Point(2.0 * current.x - previous.x, 2.0 * current.y - previous.y)
                        } ?: current
                        val control2 = cursor.point(relative, current)
                            ?: return invalid("font.svg.path-number", "SVG smooth cubic curve is invalid.")
                        val endpoint = cursor.point(relative, current)
                            ?: return invalid("font.svg.path-number", "SVG smooth cubic curve is invalid.")
                        commands += RawPathCommand.CubicTo(control1, control2, endpoint)
                        current = endpoint
                        previousCubicControl2 = control2
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG smooth cubic curve requires coordinates.")
                }

                'z' -> {
                    if (!contourOpen) return invalid("font.svg.path-order", "SVG close command requires a move command.")
                    commands += RawPathCommand.Close
                    current = contourStart
                    contourOpen = false
                    previousCubicControl2 = null
                    activeCommand = null
                }

                else -> return unsupported("SVG path command $command is not supported.")
            }
            if (commands.size > profile.outlineProfile.maxPoints * 2 + profile.outlineProfile.maxContours) {
                return limit("SVG path-command limit exceeded.")
            }
        }
        if (contourOpen) commands += RawPathCommand.Close
        if (commands.isEmpty()) return invalid("font.svg.empty-path", "SVG path data must contain commands.")
        return try {
            FontOperationResult.Success(GlyphPaintPath(commands.map { command -> command.materialize(transform) }))
        } catch (_: IllegalArgumentException) {
            invalid("font.svg.invalid-path", "SVG path coordinates exceed the portable path domain.")
        }
    }
}

private data class SvgElement(val name: String, val transform: AffineTransform)

private class SvgTransformBudget(
    private val maximum: Int,
) {
    private var consumed: Int = 0

    fun tryConsume(): Boolean {
        if (consumed >= maximum) return false
        consumed += 1
        return true
    }
}

private data class Point(val x: Double, val y: Double)

private sealed interface RawPathCommand {
    data class MoveTo(val point: Point) : RawPathCommand
    data class LineTo(val point: Point) : RawPathCommand
    data class CubicTo(val control1: Point, val control2: Point, val endpoint: Point) : RawPathCommand
    data object Close : RawPathCommand
}

private fun RawPathCommand.materialize(transform: AffineTransform): GlyphPaintPathCommand = when (this) {
    is RawPathCommand.MoveTo -> transform.apply(point).let { point -> GlyphPaintPathCommand.MoveTo(point.x, point.y) }
    is RawPathCommand.LineTo -> transform.apply(point).let { point -> GlyphPaintPathCommand.LineTo(point.x, point.y) }
    is RawPathCommand.CubicTo -> {
        val control1 = transform.apply(control1)
        val control2 = transform.apply(control2)
        val endpoint = transform.apply(endpoint)
        GlyphPaintPathCommand.CubicTo(control1.x, control1.y, control2.x, control2.y, endpoint.x, endpoint.y)
    }
    RawPathCommand.Close -> GlyphPaintPathCommand.Close
}

private class SvgNumberCursor(
    private val source: String,
) {
    private var index = 0

    fun hasRemaining(): Boolean {
        index = source.skipSeparators(index)
        return index < source.length
    }

    fun peek(): Char? {
        index = source.skipSeparators(index)
        return source.getOrNull(index)
    }

    fun advance() {
        index += 1
    }

    fun hasNumber(): Boolean {
        index = source.skipSeparators(index)
        return source.getOrNull(index)?.let { character -> character.isDigit() || character == '+' || character == '-' || character == '.' } == true
    }

    fun number(): Double? {
        index = source.skipSeparators(index)
        val start = index
        if (source.getOrNull(index) in setOf('+', '-')) index += 1
        var digits = 0
        while (source.getOrNull(index)?.isDigit() == true) {
            index += 1
            digits += 1
        }
        if (source.getOrNull(index) == '.') {
            index += 1
            while (source.getOrNull(index)?.isDigit() == true) {
                index += 1
                digits += 1
            }
        }
        if (digits == 0) return null
        if (source.getOrNull(index) in setOf('e', 'E')) {
            val exponent = index
            index += 1
            if (source.getOrNull(index) in setOf('+', '-')) index += 1
            val exponentDigits = index
            while (source.getOrNull(index)?.isDigit() == true) index += 1
            if (exponentDigits == index) {
                index = exponent
            }
        }
        return source.substring(start, index).toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    fun point(relative: Boolean, origin: Point): Point? {
        val x = number() ?: return null
        val y = number() ?: return null
        return if (relative) Point(origin.x + x, origin.y + y) else Point(x, y)
    }

    fun allNumbers(): List<Double>? {
        val values = mutableListOf<Double>()
        while (hasRemaining()) values += number() ?: return null
        return values
    }
}

private data class AffineTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun then(next: AffineTransform): AffineTransform = AffineTransform(
        a = a * next.a + c * next.b,
        b = b * next.a + d * next.b,
        c = a * next.c + c * next.d,
        d = b * next.c + d * next.d,
        e = a * next.e + c * next.f + e,
        f = b * next.e + d * next.f + f,
    ).also { transform -> require(transform.values.all(Double::isFinite)) { "SVG transform is not finite." } }

    fun apply(point: Point): Point = Point(a * point.x + c * point.y + e, b * point.x + d * point.y + f)

    val values: List<Double>
        get() = listOf(a, b, c, d, e, f)

    companion object {
        val identity: AffineTransform = AffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double): AffineTransform = AffineTransform(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double): AffineTransform = AffineTransform(x, 0.0, 0.0, y, 0.0, 0.0)
    }
}

private fun parseAttributes(source: String): Map<String, String>? {
    val attributes = linkedMapOf<String, String>()
    var index = 0
    while (index < source.length) {
        index = source.skipWhitespace(index)
        if (index == source.length) break
        val nameStart = index
        while (source.getOrNull(index)?.let { character -> character.isLetterOrDigit() || character in setOf(':', '_', '-') } == true) index += 1
        if (nameStart == index) return null
        val name = source.substring(nameStart, index)
        index = source.skipWhitespace(index)
        if (source.getOrNull(index) != '=') return null
        index = source.skipWhitespace(index + 1)
        val quote = source.getOrNull(index)?.takeIf { character -> character == '\'' || character == '"' } ?: return null
        index += 1
        val valueStart = index
        while (source.getOrNull(index) != quote) {
            val character = source.getOrNull(index) ?: return null
            if (character == '<' || character == '&') return null
            index += 1
        }
        val value = source.substring(valueStart, index)
        index += 1
        if (attributes.put(name, value) != null) return null
    }
    return attributes
}

private fun String.findTagEnd(start: Int): Int? {
    var quote: Char? = null
    for (index in start until length) {
        val character = this[index]
        if (quote != null) {
            if (character == quote) quote = null
        } else if (character == '\'' || character == '"') {
            quote = character
        } else if (character == '>') {
            return index
        }
    }
    return null
}

private fun String.skipWhitespace(start: Int = 0): Int {
    var index = start
    while (getOrNull(index)?.isWhitespace() == true) index += 1
    return index
}

private fun String.skipSeparators(start: Int): Int {
    var index = start
    while (getOrNull(index)?.let { character -> character.isWhitespace() || character == ',' } == true) index += 1
    return index
}

private fun parseColor(value: String): GlyphColor? {
    if (value.length != 7 || value.firstOrNull() != '#') return null
    val red = value.substring(1, 3).toIntOrNull(16) ?: return null
    val green = value.substring(3, 5).toIntOrNull(16) ?: return null
    val blue = value.substring(5, 7).toIntOrNull(16) ?: return null
    return GlyphColor(red, green, blue)
}

private fun requireOpenContour(open: Boolean): Unit? = if (open) Unit else null

private fun invalid(code: String, message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table("SVG ")))

private fun unsupported(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table("SVG ")))

private fun limit(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table("SVG ")))

private val capabilityProfile: PaintGraphProfile = PaintGraphProfile(
    acceptedNodeKinds = listOf(
        org.graphiks.kalligraphie.api.GlyphPaintNodeKind.PATH,
        org.graphiks.kalligraphie.api.GlyphPaintNodeKind.GROUP,
    ),
    acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
    limits = org.graphiks.kalligraphie.api.PaintGraphLimits(
        maxNodes = 1_000_000,
        maxReferences = 1_000_000,
        maxDepth = 1_024,
        maxSourceBytes = 16 * 1024 * 1024,
        maxPaths = 1_000_000,
        maxSvgDocuments = 4_096,
        maxSvgTransformOperations = 65_536,
    ),
    outlineProfile = org.graphiks.kalligraphie.api.OutlineProfile(
        maxBytes = 64 * 1024 * 1024,
        maxContours = 1_000_000,
        maxPoints = 1_000_000,
        maxCompositeDepth = 1,
        maxCompositeComponents = 1,
    ),
)

private const val SVG_TABLE_VERSION: Int = 0
private const val SVG_HEADER_LENGTH: Int = 10
private const val DOCUMENT_LIST_HEADER_LENGTH: Int = 2
private const val DOCUMENT_RECORD_LENGTH: Int = 12
private const val SVG_NAMESPACE: String = "http://www.w3.org/2000/svg"
private val SVG_ATTRIBUTES: Set<String> = setOf("xmlns", "id")
private val PATH_ATTRIBUTES: Set<String> = setOf("d", "fill")
private const val GZIP_MAGIC_0: Byte = 0x1F
private const val GZIP_MAGIC_1: Byte = 0x8B.toByte()
