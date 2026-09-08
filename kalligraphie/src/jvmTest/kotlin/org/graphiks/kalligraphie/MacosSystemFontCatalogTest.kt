package org.graphiks.kalligraphie

import java.nio.file.Files
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import org.graphiks.kalligraphie.api.GlyphRepresentationProfileKey
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class MacosSystemFontCatalogTest {
    @Test
    fun capturesRealMacosFontsInDistinctGenerationsAndRejectsTheWrongResolver() {
        if (!System.getProperty("os.name").startsWith("Mac")) return

        val first = success(MacosSystemFontCatalog.open())
        val second = success(MacosSystemFontCatalog.open())
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())

        assertEquals("macos-system-opentype", first.generation.provider.value)
        assertNotEquals(first.generation, second.generation)

        val selected = assertNotNull(first.faces.firstNotNullOfOrNull { record ->
            if (!record.capabilities.outline) return@firstNotNullOfOrNull null
            val face = (first.resolveFace(record.id, FontAccessRequirementsSnapshot.layoutOnly()) as? FontOperationResult.Success)?.value
                ?: return@firstNotNullOfOrNull null
            val instance = (face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))) as? FontOperationResult.Success)?.value
                ?: return@firstNotNullOfOrNull null
            val glyphId = (instance.resolveGlyph(0x41) as? FontOperationResult.Success)?.value?.glyphId
                ?: return@firstNotNullOfOrNull null
            glyphId.takeIf { it.value != 0 }?.let { record.id to it }
        })
        val face = success(first.resolveFace(selected.first, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val firstResolver = success(first.openAssetResolver())
        val secondResolver = success(second.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(firstResolver, FontRenderVariantKey.default, requirements))
            try {
                val representation = success(asset.resolveGlyph(FontGlyphRequest(selected.second)))
                assertIs<GlyphRepresentation.Outline>(representation)
                assertIs<FontError.IncompatibleCatalogGeneration>(
                    assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key)).error,
                )
            } finally {
                asset.close()
            }
        } finally {
            firstResolver.close()
            secondResolver.close()
        }
    }

    @Test
    fun preservesPortableIdentityAcrossGenerationsForAnUnchangedControlledRoot() {
        if (!System.getProperty("os.name").startsWith("Mac")) return

        val root = Files.createTempDirectory("kalligraphie-system-font-identity")
        try {
            Files.write(root.resolve("fixture.ttf"), minimalTrueTypeFont(glyphCount = 1, tables = emptyMap()))
            val options = MacosSystemFontCatalogOptions(
                roots = listOf(root.toString()),
                maxPathsToVisit = 2,
                maxFaces = 1,
                maxSourceBytes = 4_096,
                maxTotalSourceBytes = 4_096,
            )

            val first = success(MacosSystemFontCatalog.open(options))
            val second = success(MacosSystemFontCatalog.open(options))

            assertEquals(first.faces.single().id, second.faces.single().id)
            assertNotEquals(first.generation, second.generation)
        } finally {
            Files.deleteIfExists(root.resolve("fixture.ttf"))
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun preservesPortableAssetIdentityAcrossGenerationsButRequiresTheOwningResolverForReopening() {
        if (!System.getProperty("os.name").startsWith("Mac")) return

        val root = Files.createTempDirectory("kalligraphie-system-font-asset-identity")
        try {
            Files.write(root.resolve("BungeeColor-Regular.ttf"), resourceBytes("/fonts/bungee-color/BungeeColor-Regular.ttf"))
            val options = MacosSystemFontCatalogOptions(
                roots = listOf(root.toString()),
                maxPathsToVisit = 2,
                maxFaces = 1,
                maxSourceBytes = 16 * 1024 * 1024,
                maxTotalSourceBytes = 16 * 1024 * 1024,
            )
            val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile())
            val firstCatalog = success(MacosSystemFontCatalog.open(options))
            val secondCatalog = success(MacosSystemFontCatalog.open(options))
            val firstFace = success(firstCatalog.resolveFace(firstCatalog.faces.single().id, requirements))
            val secondFace = success(secondCatalog.resolveFace(secondCatalog.faces.single().id, requirements))
            val firstInstance = success(firstFace.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
            val secondInstance = success(secondFace.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
            val glyph = success(firstInstance.resolveGlyph(0x41)).glyphId
            val firstResolver = success(firstCatalog.openAssetResolver())
            val secondResolver = success(secondCatalog.openAssetResolver())

            try {
                assertEquals(glyph, success(secondInstance.resolveGlyph(0x41)).glyphId)
                val firstAsset = success(firstInstance.acquireRenderAsset(firstResolver, FontRenderVariantKey.default, requirements))
                val secondAsset = success(secondInstance.acquireRenderAsset(secondResolver, FontRenderVariantKey.default, requirements))
                try {
                    assertEquals(firstAsset.key.semanticIdentity, secondAsset.key.semanticIdentity)
                    assertNotEquals(firstAsset.key, secondAsset.key)
                    assertEquals(
                        GlyphRepresentationKey(
                            firstAsset.key,
                            glyph,
                            FontRenderVariantKey.default,
                            GlyphRepresentationProfileKey.outline(outlineProfile()),
                        ),
                        GlyphRepresentationKey(
                            secondAsset.key,
                            glyph,
                            FontRenderVariantKey.default,
                            GlyphRepresentationProfileKey.outline(outlineProfile()),
                        ),
                    )
                    assertIs<GlyphRepresentation.Outline>(success(firstAsset.resolveGlyph(FontGlyphRequest(glyph))))
                    assertIs<FontError.IncompatibleCatalogGeneration>(
                        assertIs<FontOperationResult.Failure>(secondResolver.reopen(firstAsset.key)).error,
                    )
                } finally {
                    firstAsset.close()
                    secondAsset.close()
                }
            } finally {
                firstResolver.close()
                secondResolver.close()
            }
        } finally {
            Files.deleteIfExists(root.resolve("BungeeColor-Regular.ttf"))
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun stopsBeforeReadingAValidSystemFontWhenTheDiscoveryBudgetEndsAtTheRoot() {
        if (!System.getProperty("os.name").startsWith("Mac")) return

        val root = Files.createTempDirectory("kalligraphie-system-font-budget")
        try {
            Files.write(root.resolve("valid.ttf"), minimalTrueTypeFont(glyphCount = 1, tables = emptyMap()))

            val result = MacosSystemFontCatalog.open(
                MacosSystemFontCatalogOptions(
                    roots = listOf(root.toString()),
                    maxPathsToVisit = 1,
                    maxFaces = 1,
                    maxSourceBytes = 4_096,
                    maxTotalSourceBytes = 4_096,
                ),
            )

            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
        } finally {
            Files.deleteIfExists(root.resolve("valid.ttf"))
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun doesNotAdvertiseAnOutlineRouteWhenAParsedFontLacksGlyfData() {
        val source = minimalTrueTypeFont(glyphCount = 1, tables = emptyMap()).also { bytes ->
            replaceTableTag(bytes, "glyf", "JUNK")
        }
        val catalog = success(Kalligraphie.embedded(source, FontSourceProvenance("No glyf route")))
        val result = catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile()))

        assertFalse(catalog.faces.single().capabilities.outline)
        assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(result).error)
    }

    private fun outlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 16_384,
        maxPoints = 1_000_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 16_384,
    )

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Fixture font resource is missing: $path" }.use { input -> input.readBytes() }

    private fun replaceTableTag(font: ByteArray, expected: String, replacement: String) {
        val tableCount = ((font[4].toInt() and 0xFF) shl 8) or (font[5].toInt() and 0xFF)
        repeat(tableCount) { index ->
            val offset = 12 + index * 16
            if ((0 until 4).all { tagIndex -> font[offset + tagIndex].toInt().toChar() == expected[tagIndex] }) {
                replacement.forEachIndexed { tagIndex, character -> font[offset + tagIndex] = character.code.toByte() }
                return
            }
        }
        error("Missing $expected table in synthetic source.")
    }
}
