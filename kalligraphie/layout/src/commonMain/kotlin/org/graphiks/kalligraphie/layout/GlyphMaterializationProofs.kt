package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute

/**
 * Operation-local evidence that a live asset has already resolved exact glyph routes.
 *
 * This store retains only immutable keys and routes. It is never published and therefore cannot
 * retain an asset, resolver, layout, or catalogue after the composing operation returns.
 */
internal class GlyphMaterializationProofs {
    private val routesByAsset = mutableMapOf<FontRenderAssetKey, MutableMap<GlyphId, GlyphMaterializationRoute>>()

    fun find(
        instance: FontInstance,
        materialization: EditableLineMaterialization.Renderable,
        glyphIds: Collection<GlyphId>,
    ): GlyphMaterializationProof? = routesByAsset.entries.firstNotNullOfOrNull { (assetKey, routes) ->
        if (
            assetKey.fontInstanceKey == instance.key &&
            assetKey.generation == materialization.resolver.generation &&
            assetKey.variant == materialization.renderVariant.key &&
            (assetKey.variantSnapshot ?: FontRenderVariantSnapshot.default) == materialization.renderVariant &&
            assetKey.representationProfile in materialization.requirements.acceptedProfiles &&
            glyphIds.all(routes::containsKey)
        ) {
            GlyphMaterializationProof(assetKey, routes.toMap())
        } else {
            null
        }
    }

    fun record(assetKey: FontRenderAssetKey, routes: Map<GlyphId, GlyphMaterializationRoute>) {
        if (routes.isEmpty()) return
        routesByAsset.getOrPut(assetKey, ::mutableMapOf).putAll(routes)
    }
}

/** Immutable projection of one operation-local asset proof. */
internal data class GlyphMaterializationProof(
    val assetKey: FontRenderAssetKey,
    val routes: Map<GlyphId, GlyphMaterializationRoute>,
)
