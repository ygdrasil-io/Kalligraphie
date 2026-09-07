package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DetachedRenderAssetContractTest {
    @Test
    fun attachedAssetRetainsItsResourceAfterResolverClose() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)

        try {
            assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())

            val representation = success(
                opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
            )
            assertIs<GlyphRepresentation.Outline>(representation)
        } finally {
            opened.asset.close()
        }
    }

    @Test
    fun detachedAssetResolvesAfterResolverAndAttachedHandleClose() {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val attached = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        val detached = success(attached.detach())

        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())

        val attachedResult = attached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)
        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(attachedResult).error)

        val representation = success(detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
    }

    @Test
    fun detachedAssetDoesNotDependOnCatalogOrAttachedOwner() {
        val detached = detachedAssetAfterOwnersClose()

        val representation = success(detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline

        assertEquals(36, outline.glyphId)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
        assertIs<FontOperationResult.Success<Unit>>(detached.close())
    }

    @Test
    fun closingDetachedAssetDoesNotCloseAttachedAsset() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        val detached = success(opened.asset.detach())

        assertIs<FontOperationResult.Success<Unit>>(detached.close())
        val representation = success(opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))

        assertIs<GlyphRepresentation.Outline>(representation)
        assertIs<FontOperationResult.Success<Unit>>(opened.asset.close())
        assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())
    }

    @Test
    fun closedResolverRejectsNewAttachedAssets() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())

        val result = opened.instance.acquireRenderAsset(
            opened.resolver,
            FontRenderVariantKey.default,
            FontAccessRequirementsSnapshot.renderable(outlineProfile()),
        )

        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun instanceKeysAreStableAndDistinctBySourceAndSize() {
        val bytes = fixtureBytes()
        val face = faceFor(bytes)
        val sameA = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key
        val sameB = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key
        val differentSize = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1024f)))).key
        val mutated = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        val differentSource = success(faceFor(mutated).instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key

        assertEquals(sameA, sameB)
        assertNotEquals(sameA, differentSize)
        assertNotEquals(sameA, differentSource)
    }

    @Test
    fun rejectsNonPositiveInstanceSizeAsTypedFailure() {
        val result = faceFor(fixtureBytes()).instantiate(FontInstanceDescriptor(LayoutUnit(0f)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.invalid-instance-descriptor", failure.error.code)
    }

    @Test
    fun cancelledOutlineResolutionPublishesNoPartialRepresentation() {
        val asset = openRenderableFont(fixtureBytes(), 2048f).asset

        val result = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.cancelled)

        assertIs<FontOperationResult.Cancelled>(result)
    }

    @Test
    fun coldOutlinePreparationObservesCancellationAndCanRetry() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        val asset = opened.asset
        var checks = 0
        val cancellationToken = CancellationToken { checks++ >= 10 }

        try {
            val cancelled = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), cancellationToken)

            assertIs<FontOperationResult.Cancelled>(cancelled)
            assertTrue(checks > 10)

            val representation = success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
            assertIs<GlyphRepresentation.Outline>(representation)
        } finally {
            asset.close()
            opened.resolver.close()
        }
    }

    @Test
    fun resolutionAlreadyInFlightCompletesWhileConcurrentCloseRejectsTheNextResolution() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        val enteredResolution = CountDownLatch(1)
        val continueResolution = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val inFlight = executor.submit<FontOperationResult<GlyphRepresentation>> {
                opened.asset.resolveGlyph(
                    FontGlyphRequest(GlyphId(36)),
                    BlockingCancellationToken(enteredResolution, continueResolution),
                )
            }
            assertTrue(enteredResolution.await(5, TimeUnit.SECONDS), "outline resolution did not begin")

            assertIs<FontOperationResult.Success<Unit>>(opened.asset.close())
            continueResolution.countDown()

            assertIs<GlyphRepresentation.Outline>(success(inFlight.get(5, TimeUnit.SECONDS)))
            val afterClose = assertIs<FontOperationResult.Failure>(
                opened.asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none),
            )
            assertIs<FontError.ResourceClosed>(afterClose.error)
        } finally {
            continueResolution.countDown()
            executor.shutdownNow()
            opened.asset.close()
            opened.resolver.close()
        }
    }

    @Test
    fun restrictiveOutlineProfileReturnsTypedLimitFailureThroughPublicRoute() {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile(maxContours = 1))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))

        val result = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals("font.resource-limit-exceeded", failure.error.code)
        assertEquals("font.resource-limit-exceeded", failure.diagnostics.single().code)
    }

    private fun openRenderableFont(bytes: ByteArray, size: Float): DetachedFontResources {
        val catalog = catalogFor(bytes)
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        val asset = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        return DetachedFontResources(resolver, instance, asset)
    }

    private fun detachedAssetAfterOwnersClose(): FontRenderAssetHandle {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val attached = success(
            instance.acquireRenderAsset(
                resolver,
                FontRenderVariantKey.default,
                FontAccessRequirementsSnapshot.renderable(outlineProfile()),
            ),
        )
        val detached = success(attached.detach())

        resolver.close()
        attached.close()
        return detached
    }

    private fun faceFor(bytes: ByteArray): FontFace =
        catalogFor(bytes).let { catalog ->
            success(catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()))
        }

    private fun catalogFor(bytes: ByteArray): FontCatalogSnapshot =
        success(Kalligraphie.embedded(bytes, FontSourceProvenance(declaredName = "Liberation Sans Regular")))

    private fun outlineProfile(
        maxBytes: Int = 1_000_000,
        maxContours: Int = 256,
        maxPoints: Int = 16_384,
        maxCompositeDepth: Int = 8,
        maxCompositeComponents: Int = 256,
    ): OutlineProfile =
        OutlineProfile(
            maxBytes = maxBytes,
            maxContours = maxContours,
            maxPoints = maxPoints,
            maxCompositeDepth = maxCompositeDepth,
            maxCompositeComponents = maxCompositeComponents,
        )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

private data class DetachedFontResources(
    val resolver: FontAssetResolverHandle,
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
)

private class BlockingCancellationToken(
    private val enteredResolution: CountDownLatch,
    private val continueResolution: CountDownLatch,
) : CancellationToken {
    override fun isCancellationRequested(): Boolean {
        enteredResolution.countDown()
        check(continueResolution.await(5, TimeUnit.SECONDS)) { "test did not release the in-flight resolution" }
        return false
    }
}
