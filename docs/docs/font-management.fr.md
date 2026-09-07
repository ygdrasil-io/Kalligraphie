# Gestion des fontes

Kalligraphie propose des catalogues de fontes embarquées et de fontes système
JVM via `org.graphiks:kalligraphie`. Les contrats publics restent portables,
mais le parcours exécutable de référence est limité à la machine virtuelle Java
(JVM). L’utilisateur ouvre un catalogue immuable, sélectionne un enregistrement
de face stable, crée une instance, acquiert une ressource de rendu détachable,
puis résout un glyphe certifié sans analyser lui-même OpenType ni retenir une
ressource de renderer (moteur de rendu).

Le périmètre fonctionnel supporté est volontairement étroit :

- cible JVM de référence uniquement ;
- fontes TrueType SFNT statiques uniquement : `0x00010000` et `true` ;
- des sources OpenType embarquées, avec l’index de face `0` pour chaque source,
  ou un instantané immuable des fichiers `.ttf` trouvés dans les répertoires de
  fontes système pris en charge par la JVM ;
- `LAYOUT_ONLY` pour la table `cmap` (correspondance entre caractères et
  glyphes) et les métriques ;
- `RENDERABLE` avec un profil compatible choisi dans l’ordre déclaré :
  `OutlineProfile` version `1`, `PaintGraphProfile` version `1` ou
  `BitmapProfile` version `1` ;
- contours TrueType exprimés en unités de conception (unités internes de la
  fonte), avec des métriques mises à l’échelle séparément en `LayoutUnit` ;
- graphes de peinture COLR/CPAL version `0`, composés de couches de contours
  pleins dans l’ordre de la source, avec palette CPAL et couleur de premier
  plan sélectionnées explicitement ;
- documents OpenType `SVG ` version `0` non compressés, réduits à des chemins
  bornés, transformations matricielles, remplissages pleins et dégradés
  linéaires ou radiaux ; le document SVG source n’est jamais retourné ;
- représentations bitmap (images matricielles) EBLC version `2`, index de
  sous-table version `1`, et EBDT version `2`, image format `1` à un bit,
  normalisés en pixels `Alpha8` dans l’espace colorimétrique sRGB ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

Aucun bridge natif (pont vers une API de plateforme) n’est encore implémenté.
`NativeHandleProfile`, COLR version `1`, SVG compressé, scripts, liens,
animation, filtres, masques, clips, images et texte SVG, codecs bitmap autres
que l’image EBDT format `1`, CFF/CFF2, collections, variations, styles
synthétiques, API GPU et rastériseur sont refusés avant la certification. Les
limites de profil bornent les octets source, nœuds et références de graphe,
profondeur et géométrie SVG, tables et enregistrements bitmap, dimensions,
pixels individuels et cumulés du strike (taille matricielle), et octets
décodés. Une route demandée n’est jamais remplacée silencieusement par une
route native ou moins fidèle.

```kotlin
fun <T> success(result: FontOperationResult<T>): T = when (result) {
    is FontOperationResult.Success -> result.value
    is FontOperationResult.Failure -> error(result.error.message)
    is FontOperationResult.Cancelled -> error("cancelled")
}

val catalog = success(Kalligraphie.embedded(bytes, provenance))
val resolver = success(catalog.openAssetResolver())
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
val certified = success(asset.resolveGlyphCertified(FontGlyphRequest(GlyphId(36))))
val sameCertifiedRepresentation = success(asset.resolveCertifiedGlyph(certified.certificate))
val detached = success(asset.detach())
asset.close()
resolver.close()
val stillUsable = success(detached.resolveCertifiedGlyph(certified.certificate))
detached.close()
```

L’exemple utilise un profil de contour ; une demande de peinture ou bitmap suit
le même cycle de vie avec son propre profil explicite et ses limites. Il montre
volontairement un certificat : `resolveCertifiedGlyph(...)` accepte uniquement
la clé de ressource, le glyphe, le profil, la version de schéma, la route, la variante
de rendu et la génération de catalogue exacts qui l’ont produit. Fermer un
gestionnaire de ressources ou une ressource de rendu est idempotent (répéter la
fermeture produit le même résultat). Une opération déjà admise peut terminer,
les acquisitions suivantes renvoient `font.resource-closed`, et une ressource
détachée possède les données immuables nécessaires à une résolution ultérieure.
`JvmFontCatalogs.openSystemFontCatalog()` suit la même séquence pour un
instantané de fonte installée ; chaque appel crée une nouvelle génération de
provider (fournisseur).

Hors périmètre de l’API de ligne éditable : césure, justification, écriture
verticale, pixels de rendu, API GPU, TTC/OTC, CFF/CFF2, variations, styles
synthétiques et fallback (repli) de fontes système. Son mode `RENDERABLE`
reste limité aux contours ; utilisez le parcours autonome ci-dessus pour les
représentations COLR/CPAL, SVG et bitmap prises en charge.

## Lignes Unicode éditables exactes

La cible JVM de référence fournit aussi un parcours sans interface graphique
pour une seule ligne éditable non renvoyée à la ligne. `Kalligraphie.decodeUtf8(...)`
ou `Kalligraphie.decodeUtf16(...)` crée un `TextSnapshot` immuable.
`JvmEditableLineFacade`, disponible uniquement sur la JVM, analyse ensuite
Unicode, résout les runs (séquences homogènes) de script et BiDi
(bidirectionnel), compose chaque run avec son backend (moteur d’exécution)
HarfBuzz embarqué, puis positionne la ligne finale.

```kotlin
val decoded = Kalligraphie.decodeUtf8(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf8(editorBytes)),
)
val result = JvmEditableLineFacade.layout(
    JvmEditableLineFacadeRequest(
        snapshot = decoded.snapshot,
        font = instance,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

La direction, la langue, la politique de fonctionnalités OpenType (features),
les surcharges de fonctionnalités, les métriques de ligne et le mode de
publication sont des entrées obligatoires. Le script et la direction résolue
de chaque run proviennent de l’analyse Unicode épinglée et sont transmis
explicitement à chaque demande de composition. Le résultat est un
`EditableLineResult` : en cas de succès, il contient les glyphes composés et
positionnés, les relations texte-vers-clusters-vers-glyphes, la navigation de
caret (repère d’insertion) logique et visuelle, la géométrie de sélection et
le hit-testing (test de point) déterministe.

Pour obtenir `RENDERABLE`, remplacez `LayoutOnly` par
`EditableLineMaterialization.Renderable` et fournissez un gestionnaire ouvert,
une variante et un `OutlineProfile`. Chaque glyphe final publié porte alors un
certificat de route outline (contour) lié à son `FontRenderAssetKey` exact. Le
gestionnaire reste la propriété de l’appelant ; la façade ne l’emprunte que
pendant l’appel synchrone.

Le backend HarfBuzz 14.3.0 embarqué est l’implémentation de référence JVM. Ses
ressources Linux et macOS x64/arm64 sont épinglées, vérifiées par hash
(empreinte cryptographique) et jamais recherchées dans les bibliothèques du
système. Les contrats publics ne contiennent ni type JNI ni type natif.
Android et Apple ne possèdent pas encore d’adapter (adaptateur de plateforme)
de composition exécutable : ce parcours ne doit donc pas être considéré comme
conforme sur ces plateformes.

## Repli déterministe entre fontes

`EmbeddedFontCatalog` peut capturer plusieurs sources OpenType auditées dans
une `FontCatalogGeneration` (génération immuable du catalogue).
`FontResolutionPolicySnapshot` associe à cette génération un ordre total de
candidats versionné et une face explicite de dernier recours.
`ExactEditableLineLayouter.layout(MultiFontEditableLineRequest)` dérive des
unités de repli à partir de l’analyse réelle des grappes de graphèmes Unicode,
attribue chaque unité à une seule face, puis compose le contexte contigu
affecté.

En mode `LAYOUT_ONLY`, un candidat doit couvrir et composer toute l’unité. En
mode `RENDERABLE`, il doit aussi matérialiser chaque glyphe final composé dans
le profil de contour demandé. Les candidats rejetés sont placés dans une
blacklist (liste d’exclusion) propre à l’opération et ne sont jamais réessayés
silencieusement pour la même unité et le même profil. Chaque
`PositionedGlyphRun` publié identifie sa `FontInstanceKey` (clé d’instance de
fonte) réelle ; chaque glyphe rendable porte un certificat lié à sa clé d’asset
(ressource) et à sa génération exactes. Un gestionnaire peut rouvrir cette clé
uniquement dans la génération capturée ; un asset détaché reste utilisable de
façon indépendante après la fermeture de son gestionnaire d’origine.

Hors périmètre de l’API de ligne éditable : césure,
justification, écriture verticale, rendu en pixels, API GPU, TTC/OTC,
CFF/CFF2, variations, styles synthétiques, COLR, SVG, glyphes matriciels et
fontes système. Consultez [Paragraphes éditables](editable-paragraphs.md) pour
le parcours multiligne JVM.
