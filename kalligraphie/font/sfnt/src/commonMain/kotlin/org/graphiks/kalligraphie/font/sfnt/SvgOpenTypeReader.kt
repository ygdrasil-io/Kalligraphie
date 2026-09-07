package org.graphiks.kalligraphie.font.sfnt

import kotlin.math.roundToInt
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintBrush
import org.graphiks.kalligraphie.api.GlyphPaintGradientSpread
import org.graphiks.kalligraphie.api.GlyphPaintGradientStop
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphPaintTransform
import org.graphiks.kalligraphie.api.PaintGraphProfile

/**
 * Decodes the supported, safe SVG-in-OpenType subset into a portable paint graph.
 *
 * The implementation accepts only SVG table version 0 with uncompressed UTF-8 documents. It
 * normalizes `path`, `g` matrix transforms, `linearGradient`, and `radialGradient` elements;
 * the supported path commands are `M`, `L`, `H`, `V`, `Q`, `C`, `A`, and `Z` in either case.
 * It rejects scripts, entities, declarations, links, external resources, animation, CSS,
 * `use`, clips, masks, filters, strokes, and every undeclared element or attribute that can
 * affect the selected glyph. Raw SVG bytes never appear in a successful result.
 */
public object SvgOpenTypeReader {
    /**
     * Decodes the SVG document covering [glyphId] under [profile]'s exact resource limits.
     *
     * @return `null` when no SVG document covers the glyph, a complete graph that [profile]
     * accepts, cancellation without partial output, or a typed malformed-data, limit, or
     * unsupported-profile failure. Compressed SVG documents are explicitly unsupported rather
     * than transparently decompressed.
     */
    public fun readGlyph(
        svgTable: ByteArray,
        glyphId: GlyphId,
        profile: PaintGraphProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GlyphPaintIR?> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (svgTable.size > profile.limits.maxSourceBytes) return limit("SVG table source-byte limit exceeded.")
        if (svgTable.size < SVG_HEADER_LENGTH) return invalid("font.svg.truncated", "SVG table header is truncated.")
        if (readUInt16(svgTable, 0)?.toInt() != SVG_VERSION_ZERO) return unsupported("Only SVG table version 0 is supported.")
        val indexOffset = readUInt32(svgTable, 2)?.toLong()
            ?: return invalid("font.svg.truncated", "SVG document-index offset is truncated.")
        checkedRangeEnd(indexOffset, 2L, svgTable.size)
            ?: return invalid("font.svg.truncated", "SVG document index is truncated.")
        val documentCount = readUInt16(svgTable, indexOffset.toInt())?.toInt()
            ?: return invalid("font.svg.truncated", "SVG document count is truncated.")
        checkedRangeEnd(indexOffset + 2L, documentCount.toLong() * SVG_DOCUMENT_RECORD_LENGTH, svgTable.size)
            ?: return invalid("font.svg.truncated", "SVG document records are truncated.")

        var selected: SvgDocumentRecord? = null
        repeat(documentCount) { index ->
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val offset = indexOffset.toInt() + 2 + index * SVG_DOCUMENT_RECORD_LENGTH
            val startGlyph = readUInt16(svgTable, offset)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG document glyph range is truncated.")
            val endGlyph = readUInt16(svgTable, offset + 2)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG document glyph range is truncated.")
            val documentOffset = readUInt32(svgTable, offset + 4)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document offset is truncated.")
            val documentLength = readUInt32(svgTable, offset + 8)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document length is truncated.")
            if (endGlyph < startGlyph) return invalid("font.svg.invalid-glyph-range", "SVG document glyph range is reversed.")
            if (indexOffset > Long.MAX_VALUE - documentOffset) {
                return invalid("font.svg.invalid-offset", "SVG document offset overflows.")
            }
            val absoluteDocumentOffset = indexOffset + documentOffset
            checkedRangeEnd(absoluteDocumentOffset, documentLength, svgTable.size)
                ?: return invalid("font.svg.truncated", "SVG document exceeds the SVG table.")
            if (glyphId.value in startGlyph..endGlyph) {
                if (selected != null) return invalid("font.svg.overlapping-document", "More than one SVG document covers the requested glyph.")
                selected = SvgDocumentRecord(absoluteDocumentOffset, documentLength)
            }
        }
        val document = selected ?: return FontOperationResult.Success(null)
        if (document.length > profile.limits.maxCompressedSvgBytes.toLong()) return limit("SVG document compressed-byte limit exceeded.")
        val end = checkedRangeEnd(document.offset, document.length, svgTable.size)
            ?: return invalid("font.svg.truncated", "SVG document exceeds the SVG table.")
        val bytes = svgTable.copyOfRange(document.offset.toInt(), end)
        if (bytes.size > profile.limits.maxDecompressedSvgBytes) return limit("SVG document decoded-byte limit exceeded.")
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return unsupported("Compressed SVG documents are not supported.")
        }
        val documentText = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            return invalid("font.svg.invalid-utf8", "SVG document is not valid UTF-8.")
        }
        if (documentText.contains("<!") || documentText.contains("<?") || documentText.contains('&')) {
            return unsupported("SVG declarations, entities, and processing instructions are not supported.")
        }
        return parseDocument(documentText, glyphId, profile, cancellationToken)
    }

    private fun parseDocument(
        document: String,
        glyphId: GlyphId,
        profile: PaintGraphProfile,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphPaintIR?> {
        val gradients = LinkedHashMap<String, SvgGradientBuilder>()
        val transformStack = ArrayList<GlyphPaintTransform>()
        val elementStack = ArrayList<String>()
        val nodes = ArrayList<GlyphPaintNode>()
        val paintedRoots = ArrayList<Int>()
        var activeGradient: SvgGradientBuilder? = null
        var documentDepth = 0
        var cursor = 0
        var sawRoot = false
        var sawTargetGroup = false

        while (cursor < document.length) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val opening = document.indexOf('<', cursor)
            if (opening < 0) {
                if (document.substring(cursor).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is unsupported.")
                break
            }
            if (document.substring(cursor, opening).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is unsupported.")
            val tagEnd = findTagEnd(document, opening + 1) ?: return invalid("font.svg.truncated", "SVG tag is unterminated.")
            val token = parseTag(document.substring(opening + 1, tagEnd))
                ?: return invalid("font.svg.invalid-tag", "SVG tag syntax is invalid.")
            cursor = tagEnd + 1
            if (token.name in FORBIDDEN_SVG_ELEMENTS) return unsupported("SVG element ${token.name} is forbidden.")
            if (token.attributes.keys.any { attribute -> attribute == "href" || attribute == "xlink:href" }) {
                return unsupported("SVG links and external resources are forbidden.")
            }

            if (token.closing) {
                if (elementStack.removeLastOrNull() != token.name) return invalid("font.svg.mismatched-tag", "SVG tags are not properly nested.")
                documentDepth -= 1
                when (token.name) {
                    "linearGradient", "radialGradient" -> {
                        val gradient = activeGradient ?: return invalid("font.svg.gradient", "SVG gradient close has no open gradient.")
                        gradients[gradient.id] = gradient
                        activeGradient = null
                    }

                    "g" -> if (transformStack.isNotEmpty()) transformStack.removeLast()
                }
                continue
            }

            if (!sawRoot) {
                if (token.name != "svg") return invalid("font.svg.root", "SVG document must begin with an svg element.")
                sawRoot = true
            }
            documentDepth += 1
            if (documentDepth > profile.limits.maxSvgDepth) return limit("SVG element-depth limit exceeded.")
            if (!token.selfClosing) elementStack += token.name

            when (token.name) {
                "svg" -> if (!token.attributes.keys.all { it == "xmlns" || it == "xmlns:xlink" || it == "version" }) {
                    return unsupported("SVG root has an unsupported attribute.")
                }

                "defs" -> requireNoAttributes(token)
                    ?: return unsupported("SVG defs attributes are unsupported.")

                "linearGradient", "radialGradient" -> {
                    if (activeGradient != null) return unsupported("Nested SVG gradients are unsupported.")
                    activeGradient = parseGradient(token) ?: return unsupported("SVG gradient attributes are unsupported.")
                    if (token.selfClosing) {
                        val gradient = activeGradient
                        gradients[gradient.id] = gradient
                        activeGradient = null
                        documentDepth -= 1
                    }
                }

                "stop" -> {
                    if (!token.selfClosing) return invalid("font.svg.stop", "SVG stop must be self-closing.")
                    val gradient = activeGradient ?: return unsupported("SVG stop outside a gradient is unsupported.")
                    val stop = parseStop(token) ?: return unsupported("SVG stop attributes are unsupported.")
                    if (gradient.stops.size >= profile.limits.maxSvgGradientStops) return limit("SVG gradient-stop limit exceeded.")
                    if (gradient.stops.lastOrNull()?.offset?.let { it > stop.offset } == true) {
                        return invalid("font.svg.gradient-stop-order", "SVG gradient stops are not ordered.")
                    }
                    gradient.stops += stop
                    documentDepth -= 1
                }

                "g" -> {
                    val id = token.attributes["id"]
                    val isTarget = id == "glyph${glyphId.value}"
                    if (transformStack.isNotEmpty() || isTarget) {
                        if (!token.attributes.keys.all { it == "id" || it == "transform" }) {
                            return unsupported("SVG group attributes are unsupported.")
                        }
                        val parsedTransform = token.attributes["transform"]?.let(::parseMatrix)
                        if (token.attributes.containsKey("transform") && parsedTransform == null) {
                            return unsupported("Only SVG matrix transforms are supported.")
                        }
                        val transform = parsedTransform ?: GlyphPaintTransform.identity
                        transformStack += (transformStack.lastOrNull()?.followedBy(transform) ?: transform)
                        sawTargetGroup = sawTargetGroup || isTarget
                    }
                    if (token.selfClosing) {
                        if (transformStack.isNotEmpty()) transformStack.removeLast()
                        documentDepth -= 1
                    }
                }

                "path" -> {
                    val currentTransform = transformStack.lastOrNull()
                    if (currentTransform != null) {
                        val path = parsePath(token.attributes["d"] ?: return invalid("font.svg.path", "SVG path has no d attribute."), profile)
                            ?: return invalid("font.svg.path", "SVG path data is invalid.")
                        val brush = parseBrush(token, gradients) ?: return unsupported("SVG path paint attributes are unsupported.")
                        val localTransform = token.attributes["transform"]?.let(::parseMatrix)
                        if (token.attributes.containsKey("transform") && localTransform == null) {
                            return unsupported("Only SVG matrix transforms are supported.")
                        }
                        val transform = currentTransform.followedBy(localTransform ?: GlyphPaintTransform.identity)
                        val pathIndex = nodes.size
                        nodes += GlyphPaintNode.Path(path, brush)
                        val root = if (transform == GlyphPaintTransform.identity) {
                            pathIndex
                        } else {
                            nodes += GlyphPaintNode.Transform(pathIndex, transform)
                            nodes.lastIndex
                        }
                        paintedRoots += root
                        if (nodes.size > profile.limits.maxNodes) return limit("SVG paint-node limit exceeded.")
                    }
                    if (!token.selfClosing) return invalid("font.svg.path", "SVG path must be self-closing.")
                    documentDepth -= 1
                }

                else -> return unsupported("SVG element ${token.name} is unsupported.")
            }
        }
        if (elementStack.isNotEmpty() || documentDepth != 0) return invalid("font.svg.mismatched-tag", "SVG document ends with unclosed elements.")
        if (!sawRoot || !sawTargetGroup) return invalid("font.svg.glyph-group", "SVG document does not contain the requested glyph group.")
        if (paintedRoots.isEmpty()) return FontOperationResult.Success(null)
        val root = if (paintedRoots.size == 1) paintedRoots.single() else {
            nodes += GlyphPaintNode.Group(paintedRoots)
            nodes.lastIndex
        }
        val graph = GlyphPaintIR(schemaVersion = profile.schemaVersion, rootNode = root, nodes = nodes)
        if (!profile.accepts(graph)) return unsupported("The SVG paint graph exceeds or is incompatible with the requested profile.")
        return FontOperationResult.Success(graph)
    }

    private fun requireNoAttributes(token: SvgTag): Unit? = if (token.attributes.isEmpty()) Unit else null

    private fun parseGradient(token: SvgTag): SvgGradientBuilder? {
        val id = token.attributes["id"] ?: return null
        if (id.isBlank() || token.attributes["gradientUnits"] != "userSpaceOnUse") return null
        val allowed = when (token.name) {
            "linearGradient" -> setOf("id", "x1", "y1", "x2", "y2", "gradientUnits", "gradientTransform", "spreadMethod")
            "radialGradient" -> setOf("id", "cx", "cy", "r", "fx", "fy", "gradientUnits", "gradientTransform", "spreadMethod")
            else -> return null
        }
        if (!token.attributes.keys.all(allowed::contains)) return null
        val transform = token.attributes["gradientTransform"]?.let(::parseMatrix) ?: GlyphPaintTransform.identity
        val spread = token.attributes["spreadMethod"]?.let(::parseSpread) ?: GlyphPaintGradientSpread.PAD
        val number: (String) -> Double? = { key -> token.attributes[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) }
        return when (token.name) {
            "linearGradient" -> SvgGradientBuilder.Linear(
                id,
                number("x1") ?: return null,
                number("y1") ?: return null,
                number("x2") ?: return null,
                number("y2") ?: return null,
                transform,
                spread,
            )

            "radialGradient" -> SvgGradientBuilder.Radial(
                id,
                number("cx") ?: return null,
                number("cy") ?: return null,
                number("r")?.takeIf { it > 0.0 } ?: return null,
                number("fx") ?: token.attributes["cx"]!!.toDoubleOrNull() ?: return null,
                number("fy") ?: token.attributes["cy"]!!.toDoubleOrNull() ?: return null,
                transform,
                spread,
            )

            else -> null
        }
    }

    private fun parseStop(token: SvgTag): GlyphPaintGradientStop? {
        if (!token.attributes.keys.all { it == "offset" || it == "stop-color" || it == "stop-opacity" }) return null
        val offset = token.attributes["offset"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return null
        val color = token.attributes["stop-color"]?.let(::parseColor) ?: return null
        val opacity = token.attributes["stop-opacity"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: 1.0
        return GlyphPaintGradientStop(offset, color.copy(alpha = (color.alpha * opacity).roundToInt()))
    }

    private fun parseBrush(token: SvgTag, gradients: Map<String, SvgGradientBuilder>): GlyphPaintBrush? {
        if (!token.attributes.keys.all { it == "id" || it == "d" || it == "fill" || it == "fill-rule" || it == "transform" }) return null
        if (token.attributes["fill-rule"] !in setOf(null, "nonzero")) return null
        if (token.attributes.containsKey("transform") && parseMatrix(token.attributes.getValue("transform")) == null) return null
        val fill = token.attributes["fill"] ?: "black"
        if (fill == "none") return null
        val reference = fill.takeIf { it.startsWith("url(#") && it.endsWith(')') }?.substring(5, fill.length - 1)
        return when {
            reference != null -> gradients[reference]?.toBrush()
            fill.startsWith("url(") -> null
            else -> parseColor(fill)?.let(GlyphPaintBrush::Solid)
        }
    }

    private fun parsePath(value: String, profile: PaintGraphProfile): GlyphPaintPath? {
        val parser = SvgPathParser(value, profile.limits.maxSvgPathCommands)
        return parser.parse()
    }

    private fun parseColor(value: String): GlyphColor? = when (value.lowercase()) {
        "black" -> GlyphColor(0, 0, 0)
        "white" -> GlyphColor(255, 255, 255)
        "red" -> GlyphColor(255, 0, 0)
        "gold" -> GlyphColor(255, 215, 0)
        "green" -> GlyphColor(0, 128, 0)
        "darkblue" -> GlyphColor(0, 0, 139)
        "skyblue" -> GlyphColor(135, 206, 235)
        "midnightblue" -> GlyphColor(25, 25, 112)
        "purple" -> GlyphColor(128, 0, 128)
        else -> parseHexColor(value)
    }

    private fun parseHexColor(value: String): GlyphColor? {
        if (!value.startsWith('#')) return null
        val digits = value.removePrefix("#")
        return when (digits.length) {
            3 -> digits.map { "$it$it".toIntOrNull(16) ?: return null }.let { GlyphColor(it[0], it[1], it[2]) }
            6 -> GlyphColor(
                digits.substring(0, 2).toIntOrNull(16) ?: return null,
                digits.substring(2, 4).toIntOrNull(16) ?: return null,
                digits.substring(4, 6).toIntOrNull(16) ?: return null,
            )
            else -> null
        }
    }

    private fun parseSpread(value: String): GlyphPaintGradientSpread? = when (value) {
        "pad" -> GlyphPaintGradientSpread.PAD
        "repeat" -> GlyphPaintGradientSpread.REPEAT
        "reflect" -> GlyphPaintGradientSpread.REFLECT
        else -> null
    }

    private fun parseMatrix(value: String): GlyphPaintTransform? {
        if (!value.startsWith("matrix(") || !value.endsWith(')')) return null
        val values = value.substring(7, value.length - 1).split(',', ' ').filter(String::isNotBlank)
        if (values.size != 6) return null
        val numbers = values.map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
        return GlyphPaintTransform(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
    }

    private fun findTagEnd(document: String, start: Int): Int? {
        var quote: Char? = null
        for (index in start until document.length) {
            val character = document[index]
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

    private fun parseTag(raw: String): SvgTag? {
        val value = raw.trim()
        val closing = value.startsWith('/')
        val content = if (closing) value.drop(1).trim() else value
        val selfClosing = !closing && content.endsWith('/')
        val body = if (selfClosing) content.dropLast(1).trimEnd() else content
        if (body.isEmpty()) return null
        var cursor = 0
        fun skipWhitespace() {
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
        }
        fun readName(): String? {
            val start = cursor
            while (cursor < body.length && (body[cursor].isLetterOrDigit() || body[cursor] in "_:-")) cursor++
            return body.substring(start, cursor).takeIf(String::isNotBlank)
        }
        val name = readName() ?: return null
        if (closing) return if (cursor == body.length) SvgTag(name, true, false, emptyMap()) else null
        val attributes = LinkedHashMap<String, String>()
        while (cursor < body.length) {
            skipWhitespace()
            if (cursor == body.length) break
            val attribute = readName() ?: return null
            skipWhitespace()
            if (cursor >= body.length || body[cursor++] != '=') return null
            skipWhitespace()
            if (cursor >= body.length || body[cursor] !in "\"'") return null
            val quote = body[cursor++]
            val start = cursor
            while (cursor < body.length && body[cursor] != quote) cursor++
            if (cursor == body.length) return null
            val attributeValue = body.substring(start, cursor++)
            if (attributes.put(attribute, attributeValue) != null) return null
        }
        return SvgTag(name, false, selfClosing, attributes)
    }

    private fun invalid(code: String, message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table("SVG ")))

    private fun limit(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table("SVG ")))

    private fun unsupported(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table("SVG ")))
}

private class SvgPathParser(
    private val source: String,
    private val commandLimit: Int,
) {
    private var cursor: Int = 0
    private var currentX: Double = 0.0
    private var currentY: Double = 0.0
    private val commands = ArrayList<GlyphPaintPathCommand>()

    fun parse(): GlyphPaintPath? {
        var command: Char? = null
        while (true) {
            skipSeparators()
            if (cursor >= source.length) break
            if (source[cursor].isLetter()) command = source[cursor++] else if (command == null) return null
            val currentCommand = command
            when (currentCommand.lowercaseChar()) {
                'm' -> if (!parseMove(currentCommand.isLowerCase())) return null
                'l' -> if (!parseLine(currentCommand.isLowerCase())) return null
                'h' -> if (!parseHorizontal(currentCommand.isLowerCase())) return null
                'v' -> if (!parseVertical(currentCommand.isLowerCase())) return null
                'q' -> if (!parseQuadratic(currentCommand.isLowerCase())) return null
                'c' -> if (!parseCubic(currentCommand.isLowerCase())) return null
                'a' -> if (!parseArc(currentCommand.isLowerCase())) return null
                'z' -> {
                    if (!add(GlyphPaintPathCommand.Close)) return null
                    command = null
                }
                else -> return null
            }
        }
        if (commands.isEmpty() || commands.first() !is GlyphPaintPathCommand.MoveTo) return null
        if (commands.last() !is GlyphPaintPathCommand.Close && !add(GlyphPaintPathCommand.Close)) return null
        return GlyphPaintPath(commands)
    }

    private fun parseMove(relative: Boolean): Boolean {
        val first = pair(relative) ?: return false
        if (commands.isNotEmpty() && commands.last() !is GlyphPaintPathCommand.Close && !add(GlyphPaintPathCommand.Close)) return false
        if (!add(GlyphPaintPathCommand.MoveTo(first.first, first.second))) return false
        while (hasNumber()) {
            val point = pair(relative) ?: return false
            if (!add(GlyphPaintPathCommand.LineTo(point.first, point.second))) return false
        }
        return true
    }

    private fun parseLine(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val point = pair(relative) ?: return false
            if (!add(GlyphPaintPathCommand.LineTo(point.first, point.second))) return false
            count++
        }
        return count > 0
    }

    private fun parseHorizontal(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val x = number() ?: return false
            currentX = if (relative) currentX + x else x
            if (!add(GlyphPaintPathCommand.LineTo(currentX, currentY))) return false
            count++
        }
        return count > 0
    }

    private fun parseVertical(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val y = number() ?: return false
            currentY = if (relative) currentY + y else y
            if (!add(GlyphPaintPathCommand.LineTo(currentX, currentY))) return false
            count++
        }
        return count > 0
    }

    private fun parseQuadratic(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val control = pair(relative) ?: return false
            val end = pair(relative) ?: return false
            if (!add(GlyphPaintPathCommand.QuadraticTo(control.first, control.second, end.first, end.second))) return false
            count++
        }
        return count > 0
    }

    private fun parseCubic(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val control1 = pair(relative) ?: return false
            val control2 = pair(relative) ?: return false
            val end = pair(relative) ?: return false
            if (!add(GlyphPaintPathCommand.CubicTo(control1.first, control1.second, control2.first, control2.second, end.first, end.second))) return false
            count++
        }
        return count > 0
    }

    private fun parseArc(relative: Boolean): Boolean {
        var count = 0
        while (hasNumber()) {
            val radiusX = number() ?: return false
            val radiusY = number() ?: return false
            val rotation = number() ?: return false
            val largeArc = flag() ?: return false
            val sweep = flag() ?: return false
            val end = pair(relative) ?: return false
            if (radiusX < 0.0 || radiusY < 0.0) return false
            if (!add(GlyphPaintPathCommand.ArcTo(radiusX, radiusY, rotation, largeArc, sweep, end.first, end.second))) return false
            count++
        }
        return count > 0
    }

    private fun pair(relative: Boolean): Pair<Double, Double>? {
        val x = number() ?: return null
        val y = number() ?: return null
        currentX = if (relative) currentX + x else x
        currentY = if (relative) currentY + y else y
        return currentX to currentY
    }

    private fun flag(): Boolean? = number()?.takeIf { it == 0.0 || it == 1.0 }?.let { it == 1.0 }

    private fun hasNumber(): Boolean {
        skipSeparators()
        return cursor < source.length && !source[cursor].isLetter()
    }

    private fun number(): Double? {
        skipSeparators()
        val start = cursor
        if (cursor < source.length && source[cursor] in "+-") cursor++
        var digits = 0
        while (cursor < source.length && source[cursor].isDigit()) {
            cursor++
            digits++
        }
        if (cursor < source.length && source[cursor] == '.') {
            cursor++
            while (cursor < source.length && source[cursor].isDigit()) {
                cursor++
                digits++
            }
        }
        if (digits == 0) return null
        if (cursor < source.length && source[cursor] in "eE") {
            cursor++
            if (cursor < source.length && source[cursor] in "+-") cursor++
            val exponentStart = cursor
            while (cursor < source.length && source[cursor].isDigit()) cursor++
            if (cursor == exponentStart) return null
        }
        return source.substring(start, cursor).toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    private fun skipSeparators() {
        while (cursor < source.length && (source[cursor].isWhitespace() || source[cursor] == ',')) cursor++
    }

    private fun add(command: GlyphPaintPathCommand): Boolean {
        if (commands.size >= commandLimit) return false
        commands += command
        return true
    }
}

private sealed class SvgGradientBuilder(
    val id: String,
    val transform: GlyphPaintTransform,
    val spread: GlyphPaintGradientSpread,
) {
    val stops: MutableList<GlyphPaintGradientStop> = ArrayList()

    abstract fun toBrush(): GlyphPaintBrush?

    class Linear(
        id: String,
        private val x1: Double,
        private val y1: Double,
        private val x2: Double,
        private val y2: Double,
        transform: GlyphPaintTransform,
        spread: GlyphPaintGradientSpread,
    ) : SvgGradientBuilder(id, transform, spread) {
        override fun toBrush(): GlyphPaintBrush? = stops.takeIf(List<*>::isNotEmpty)?.let {
            GlyphPaintBrush.LinearGradient(x1, y1, x2, y2, it, spread, transform)
        }
    }

    class Radial(
        id: String,
        private val cx: Double,
        private val cy: Double,
        private val radius: Double,
        private val fx: Double,
        private val fy: Double,
        transform: GlyphPaintTransform,
        spread: GlyphPaintGradientSpread,
    ) : SvgGradientBuilder(id, transform, spread) {
        override fun toBrush(): GlyphPaintBrush? = stops.takeIf(List<*>::isNotEmpty)?.let {
            GlyphPaintBrush.RadialGradient(cx, cy, radius, fx, fy, it, spread, transform)
        }
    }
}

private data class SvgDocumentRecord(val offset: Long, val length: Long)

private data class SvgTag(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attributes: Map<String, String>,
)

private const val SVG_HEADER_LENGTH = 10
private const val SVG_VERSION_ZERO = 0
private const val SVG_DOCUMENT_RECORD_LENGTH = 12
private val FORBIDDEN_SVG_ELEMENTS = setOf(
    "script", "animate", "animateMotion", "animateTransform", "set", "audio", "video", "image",
    "foreignObject", "filter", "mask", "clipPath", "style", "use", "text",
)
