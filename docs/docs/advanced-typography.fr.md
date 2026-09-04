# Typographie avancée et contenu dérivé

Ce guide documente le parcours consommateur des comportements typographiques
avancés publiés par `org.graphiks:kalligraphie`. Chaque comportement listé est
un comportement réel de pipeline : il façonne du vrai texte avec de vraies
polices et publie de la géométrie observable via `JvmEditableParagraphFacade`
(ou le contrat portable `ParagraphLayouter`).

Tout le contenu dérivé et synthétique partage une règle : **il ne crée jamais
de position documentaire**. Carets, sélection, hit-testing (test de
positionnement) et copie consultent toujours le `TextSnapshot` ; les glyphes
synthétiques s'ancrent à de vraies frontières du snapshot et portent une
`GlyphProvenance` expliquant pourquoi ils existent.

## Provenance (origine des glyphes)

`PositionedGlyph.provenance` classe chaque glyphe final :

- `GlyphProvenance.Direct(sourceRange)` : glyphe façonné directement depuis
  les scalaires de `sourceRange` (défaut pour le façonnage ordinaire) ;
- `GlyphProvenance.Derived(sourceRange, role)` : glyphe dérivé d'une vraie
  plage source via une transformation (césure souple, césure automatique,
  espacement de justification) ;
- `GlyphProvenance.Synthetic(anchor, role)` : glyphe synthétique ancré à la
  frontière réelle `anchor` (conduite de tabulation, marqueur d'ellipsis,
  kashida).

Les rôles (`GlyphProvenanceRole`) couvrent césure souple, césure automatique,
kashida, conduite de tabulation, ellipsis et espacement de justification. La
provenance est un type valeur avec égalité structurelle : les layouts restent
comparables.

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
  empreinte numérique (voir `PROVENANCE.md` de la ressource).
- En mode `AUTO` sans service, le layout reste valide sans césure automatique
  et émet le diagnostic structuré `layout.hyphenation-service-absent`.

## Justification et kashida

`ParagraphPositioningPolicy(alignment = JUSTIFY, justificationMode = ...)`
répartit l'étendue restante sur les glyphes finaux :

- `INTER_WORD` étend les glyphes espaces (`GlyphProvenance.Derived`,
  `JUSTIFICATION_SPACING`) ;
- `INTER_CHARACTER` étend les intervalles entre caractères ;
- `KASHIDA` insère de vrais glyphes tatweel de la police dans les intervalles
  de texte arabe, chacun synthétique avec rôle `KASHIDA` ; l'absence de
  tatweel utilisable dégrade déterministiquement en espacement avec le
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

## Ellipsis (troncature)

`OverflowPolicy.Ellipsis(side, marker)` tronque le contenu qui ne tient pas :

- `INLINE_END` conserve le plus grand préfixe qui tient avec le marqueur ;
- `INLINE_START` conserve le plus grand suffixe ;
- `MIDDLE` conserve un préfixe et un suffixe autour du marqueur.

La ligne publiée conserve la plage source complète : les scalaires masqués
publient des glyphes supprimés à avancement nul, le marqueur est un glyphe
synthétique unique (rôle `ELLIPSIS`) ancré à la frontière de troncature, et le
résultat porte `ParagraphTruncation(hiddenRange, anchor, side)` décrivant
exactement le contenu masqué. Les jonctions BiDi conservent leur sémantique
normale de candidats dupliqués ; le marqueur n'ajoute jamais de caret.

## Objets inline

`InlineObjectSnapshot` associe chaque scalaire `U+FFFC` à une
`InlineObjectDefinition` détenue par le consommateur (identité opaque, largeur,
hauteur, décalage de baseline, alignement). Le moteur avance le curseur de la
largeur de l'objet, publie un `PositionedInlineObject` avec son rectangle
physique, et laisse le rendu au consommateur. Les frontières de caret existent
exactement autour du scalaire ; un test de positionnement au centre de l'objet
retourne le candidat de frontière le plus proche ; une sélection traversant
l'objet inclut son rectangle ; la copie lit toujours le `U+FFFC` depuis le
`TextSnapshot`.

## Recertification des glyphes finaux

En mode rendable, `GlyphMaterializationCertificate` est produit **après**
chaque transformation — kashida, substitution de trait d'union, conduite de
tabulation, marqueur d'ellipsis — sur l'identifiant de glyphe final. Chaque
glyphe publié porte son certificat et sa clé d'asset de rendu, y compris les
glyphes synthétiques.

## Égalité incrémentale

`incremental == full` tient sur ces comportements : une édition qui modifie la
césure, la justification ou les objets inline produit des lignes observables
identiques entre la session incrémentale et la façade complète (mêmes plages,
glyphes avec provenance, origines, carets et rectangles d'objets).

## Limites connues

- Les modes d'écriture verticaux (`vertical-rl` / `vertical-lr`, orientation
  UTR #50, métriques `vhea`/`vmtx`) ne sont **pas** implémentés dans cette
  version. La composition horizontale reste le seul mode d'écriture pris en
  charge ; ce point est la seule capacité restante de typographie avancée.
- Les flow regions, exclusions et la pagination appartiennent à un ticket
  ultérieur.
