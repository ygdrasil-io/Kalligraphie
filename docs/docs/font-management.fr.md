# Gestion des fontes

Kalligraphie propose une prise en charge de fontes TrueType embarquées via
`org.graphiks:kalligraphie`, uniquement sur la cible de référence de la
machine virtuelle Java (JVM). Les contrats publics restent portables, mais
cette prise en charge exécutable est limitée à la JVM. L’utilisateur de la
bibliothèque fournit des octets SFNT capturés à `Kalligraphie.embedded(...)`,
sélectionne un enregistrement de face stable, crée une instance de fonte, puis utilise une
ressource de rendu pour matérialiser une représentation portable de glyphe.

Le périmètre fonctionnel supporté est volontairement étroit :

- cible JVM de référence uniquement ;
- fontes TrueType SFNT statiques uniquement : `0x00010000` et `true` ;
- des sources OpenType embarquées, avec l’index de face `0` pour chaque source ;
- `LAYOUT_ONLY` pour la table `cmap` (correspondance entre caractères et
  glyphes) et les métriques ;
- `RENDERABLE` avec un `OutlineProfile`, un `PaintGraphProfile` ou un
  `BitmapProfile` de version de schéma `1`, si la face sélectionnée déclare la
  route correspondante ;
- contours `glyf` exprimés en unités de conception (unités internes de la
  fonte), avec des métriques mises à l’échelle séparément en `LayoutUnit` ;
- graphes de peinture COLR version 0 et CPAL version 0, composés de contours
  pleins, de groupes ordonnés, d’une sélection exacte de palette CPAL et d’une
  couleur de premier plan explicite ;
- table SVG-in-OpenType version 0 avec documents UTF-8 bruts uniquement :
  éléments `svg`, `g` et `path` auto-fermants ; transformations `translate` et
  `scale` ; commandes de chemin `M`, `L`, `H`, `V`, `C`, `S` et `Z` ; et
  remplissages opaques `#RRGGBB`. Les scripts, ressources externes, entités,
  animations, compression, gradients, clips (découpes), masques, contours tracés et
  attributs non déclarés sont refusés avant publication d’une ressource ;
- strikes bitmap (images matricielles, tailles bitmap exactes) EBLC version 2 / EBDT version 2,
  avec sous-table d’index format 1 et image format 1 uniquement : alpha un bit
  aligné sur les octets, décodé en `ALPHA_8` sRGB, pour un strike demandé à
  l’identique ;
- ressources de rendu détachées qui restent utilisables après la fermeture du
  gestionnaire propriétaire ou de la ressource attachée.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceId = catalog.faces.single().id
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

L’accès aux glyphes pour le rendu exige un profil de représentation explicite. Fermer
un gestionnaire de ressources ou une ressource de rendu est idempotent (répéter
la fermeture produit le même résultat). Les nouvelles acquisitions après
fermeture renvoient `font.resource-closed` ; une ressource détachée conserve les
données immuables requises par `resolveGlyph(...)`.

Sur macOS, l’artefact JVM expose aussi `MacosSystemFontCatalog.open()`. Il
capture, sous limites, les fichiers `.ttf` réguliers dans un instantané
portable et utilise les mêmes routes que les fontes embarquées. Il n’expose pas
de handle (poignée) CoreText et ne déclare pas de prise en charge de `.otf` ni
de `.ttc`.

Hors périmètre : TTC/OTC, CFF/CFF2, variations, styles synthétiques, versions
de COLR autres que 0, contenu SVG hors du sous-ensemble déclaré, codecs et
formats bitmap autres que la route EBLC/EBDT déclarée, ajustement des contours
aux pixels (hinting), rastérisation, moteurs natifs de gestion des fontes et
descripteurs de fonte propres à la plateforme.

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
