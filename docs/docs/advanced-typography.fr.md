# Typographie avancée et contenu dérivé

Ce guide documente le parcours consommateur des comportements typographiques
avancés publiés par `org.graphiks:kalligraphie`. Chaque comportement listé est
un comportement réel de chaîne de traitement (`pipeline`) : il façonne du vrai texte avec de vraies
polices et publie de la géométrie observable via `JvmEditableParagraphFacade`
(ou le contrat portable `ParagraphLayouter`).

Tout le contenu dérivé et synthétique partage une règle : **il ne crée jamais
de position documentaire**. Les repères d’insertion (`carets`), la sélection,
le hit-testing (test de point) et la copie consultent toujours le
`TextSnapshot` (instantané immuable du texte) ; les glyphes synthétiques
s'ancrent à de vraies frontières de cet instantané et portent une
`GlyphProvenance` expliquant pourquoi ils existent.

## Provenance (origine des glyphes)

`PositionedGlyph.provenance` classe chaque glyphe final :

- `GlyphProvenance.Direct(sourceRange)` : glyphe façonné directement depuis
  les scalaires de `sourceRange` (défaut pour le façonnage ordinaire) ;
- `GlyphProvenance.Derived(sourceRange, role)` : glyphe dérivé d'une vraie
  plage source via une transformation (césure souple, césure automatique,
  espacement de justification) ;
- `GlyphProvenance.Synthetic(anchor, role)` : glyphe synthétique ancré à la
  frontière réelle `anchor` (ancre : conduite de tabulation, marqueur
  d'ellipsis — points de suspension —, kashida — allongement typographique
  arabe —).

Les rôles (`GlyphProvenanceRole`) couvrent césure souple, césure automatique,
kashida, conduite de tabulation, ellipsis et espacement de justification. La
provenance est un type valeur avec égalité structurelle : les mises en page
(`layouts`) restent comparables.

## Césure

`HyphenationMode` sur la requête de paragraphe distingue `NONE`, `MANUAL`
(césure souple) et `AUTO` (césure souple + `HyphenationService` versionné).

- Une césure souple (`U+00AD`) est invisible quand la ligne ne coupe pas à cet
  endroit : son glyphe porte un avancement nul et conserve ses frontières de
  caret réelles.
- Quand la ligne coupe exactement à la frontière de la césure souple, un
  trait d'union visible est publié avec `GlyphProvenance.Derived` de rôle
  `SOFT_HYPHEN`, dérivé du scalaire source.
- En mode `AUTO`, un service immuable et déterministe calcule les coupures de
  mot. Le service de référence est `JvmPatternHyphenationService.english()` :
  correspondance par motifs de Liang sur `hyph-en-us.pat.txt` vérifié par
  empreinte numérique (voir `PROVENANCE.md` de la ressource). Son identité de
  répétition comprend le fournisseur, la révision des motifs, les langues
  prises en charge et les minimums gauche/droite.
- Une coupure automatique conserve chaque glyphe source direct. Elle publie en
  plus un unique trait d'union synthétique (`AUTOMATIC_HYPHEN`), ancré à la
  frontière source retenue ; ce marqueur ne crée ni texte ni caret.
- En mode `AUTO` sans service, le layout reste valide sans césure automatique
  et émet le diagnostic structuré `layout.hyphenation-service-absent`.

## Justification et kashida

`ParagraphPositioningPolicy(alignment = JUSTIFY, justificationMode = ...)`
répartit l'étendue restante sur les glyphes finaux :

- `INTER_WORD` étend les glyphes espaces (`GlyphProvenance.Derived`,
  `JUSTIFICATION_SPACING`) ;
- `INTER_CHARACTER` étend les intervalles entre caractères ;
- `KASHIDA` insère de vrais glyphes tatweel de la police uniquement dans les
  intervalles qui sont des possibilités de liaison Unicode valides du texte
  arabe. Il ne traverse jamais un espace, du texte non arabe, ni une lettre
  qui ne peut pas se lier à la lettre suivante. L'absence de tatweel utilisable
  dégrade déterministiquement en espacement avec le
  diagnostic `layout.kashida-unavailable` ;
- `AUTO` sélectionne selon le contexte de script (mots, caractères CJK,
  kashida).

La justification ne modifie jamais le texte source, les limites de clusters
(agrégats de caractères) ni les positions éditoriales.

## Taquets et conduites

`TabStop(position, alignment, alignmentCharacter, leader)` supporte les
alignements `START`, `END`, `CENTER` et `DECIMAL` sur l'axe inline. Un taquet
`DECIMAL` centre le caractère décimal du champ sur le taquet ; un champ sans
ce caractère est aligné à droite. `leader` répète un scalaire en contenu
synthétique (provenance `TAB_LEADER`) entre le contenu précédent et le taquet.
Le scalaire `U+0009` reste un vrai caractère avec exactement deux positions de
caret.

Les champs sont définis dans l'ordre source logique entre deux scalaires de
tabulation adjacents, puis mesurés sur l'ensemble de leurs séquences (`runs`)
de façonnage visuelles. Une police de repli (`fallback`), un script ou une
frontière BiDi (bidirectionnelle) ne réinitialisent donc ni leur alignement ni
le choix du taquet.

## Ellipsis (troncature)

`OverflowPolicy.Ellipsis(side, marker)` tronque le contenu qui ne tient pas :

- `INLINE_END` conserve le plus grand préfixe qui tient avec le marqueur ;
- `INLINE_START` conserve le plus grand suffixe ;
- `MIDDLE` conserve un préfixe et un suffixe autour du marqueur.

Si aucune grappe source ne tient avec le marqueur, les trois variantes publient
le marqueur seul et décrivent toute la plage source comme masquée.

La ligne publiée conserve la plage source complète : les scalaires masqués
publient des glyphes supprimés à avancement nul, le marqueur est un glyphe
synthétique unique (rôle `ELLIPSIS`) ancré à la frontière de troncature, et le
résultat porte `ParagraphTruncation(hiddenRange, anchor, side)` décrivant
exactement le contenu masqué. Les jonctions BiDi (bidirectionnelles) conservent
leur sémantique normale de candidats dupliqués ; le marqueur n'ajoute jamais
de caret.

## Objets inline (dans la ligne)

`InlineObjectSnapshot` associe chaque scalaire `U+FFFC` à une
`InlineObjectDefinition` détenue par le consommateur (identité opaque, largeur,
hauteur, décalage de baseline — ligne de base —, alignement). Le moteur avance le curseur de la
largeur de l'objet, publie un `PositionedInlineObject` avec son rectangle
physique, et laisse le rendu au consommateur. Les frontières de caret existent
exactement autour du scalaire ; un test de positionnement au centre de l'objet
retourne le candidat de frontière le plus proche ; une sélection traversant
l'objet inclut son rectangle ; la copie lit toujours le `U+FFFC` depuis le
`TextSnapshot`. Une entrée portant une autre révision d'instantané, une
frontière hors de la plage source demandée ou un scalaire autre que `U+FFFC`
est rejetée comme entrée invalide, au lieu d'être ignorée.

## Recertification des glyphes finaux

En mode rendable, `GlyphMaterializationCertificate` est produit **après**
chaque transformation — kashida, substitution de trait d'union, conduite de
tabulation, marqueur d'ellipsis — sur l'identifiant de glyphe final. Chaque
glyphe publié porte son certificat et sa clé de ressource de rendu (`asset`), y compris les
glyphes synthétiques.

La validation de repli conserve seulement une preuve locale à l'opération : la
clé de ressource, l'identifiant de glyphe et la route acceptée. Lorsque ces
trois valeurs correspondent encore après les transformations, la certification
réemploie cette preuve au lieu de matérialiser de nouveau le même glyphe. La
preuve ne contient ni IR ni handle (poignée d'accès), n'est publiée ni dans
`EditableLine` ni dans `TextLayout`, et est détruite au retour de l'appel de
composition.

## Écriture verticale

`ParagraphConstraints.writingMode` sélectionne la composition verticale
physique :

- `VERTICAL_RL` avance les glyphes de haut en bas et les colonnes de droite à
  gauche ;
- `VERTICAL_LR` avance les glyphes de haut en bas et les colonnes de gauche à
  droite.

Kalligraphie façonne chaque séquence verticale de haut en bas avec les
fonctionnalités OpenType `vert` et `vrt2`. `TextOrientation.MIXED` applique aux
scalaires de base des grappes de graphèmes étendues les données
`Vertical_Orientation` (orientation verticale) d’UTR #50, épinglées à Unicode
16.0 : le contenu idéographique reste droit, tandis que le latin ordinaire
reçoit la rotation publiée `LayoutAffineTransform` d’un quart de tour horaire.
`UPRIGHT` et `SIDEWAYS` remplacent cette règle pour toutes les grappes.

Le `PositionedGlyph` final porte un avancement physique sur `y` et une
transformation visible par le renderer (moteur de rendu) ; Kalligraphie ne rend
toujours aucun pixel. Les carets sont horizontaux dans une colonne verticale,
et la géométrie de sélection comme le hit-testing (test de point) emploient les
mêmes axes physiques. Ni une rotation ni une substitution OpenType ne crée de
`TextIndex`.

Chaque glyphe final sélectionné lit sa métrique verticale `vhea`/`vmtx`. Avec
`VerticalMetricsPolicy.REQUIRE_FONT_METRICS`, une fonte sans table utilisable
fait échouer la composition. `SYNTHESIZE_IF_UNAVAILABLE` utilise un em pour
cette fonte et publie `layout.vertical-metrics-synthesized`. Le jeu de données
de test (`fixture`) CJK exerce de vraies tables `vhea`/`vmtx` ; la fixture latine exerce ce
repli explicite.

## Égalité incrémentale

`incremental == full` tient sur ces comportements : une édition qui modifie la
césure, la justification ou les objets inline produit des lignes observables
identiques entre la session incrémentale et la façade complète (mêmes plages,
glyphes avec provenance, origines, carets et rectangles d'objets).

La même garantie couvre les deux modes d’écriture verticaux. Une continuation
et un checkpoint (point de reprise) incrémental retiennent le curseur physique
de l’axe de bloc, sans supposer que la ligne suivante s’obtient en augmentant
`y`. Une réutilisation n'est permise que si toute la configuration de
composition observable est identique : positionnement et taquets, identité du
service de césure, objets inline, orientation et politique de métriques
verticales en font partie.

## Composition dans des régions

Les entrées typographiques avancées ci-dessus participent aussi à l’identité
exacte des valeurs `FlowContinuation` et `FlowLayoutState` incrémentales. La
composition dans des régions refuse une réutilisation quand elle ne peut pas
prouver toute la configuration du texte, de la typographie, de la région, de
la fragmentation et du paragraphe. Consultez les
[Paragraphes éditables](editable-paragraphs.fr.md#composer-dans-des-regions-avec-exclusions)
pour le parcours consommateur et la frontière de propriété de l’application.
