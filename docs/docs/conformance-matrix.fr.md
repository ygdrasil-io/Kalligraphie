# Matrice de conformité

Kalligraphie porte son contrat de conformité portable, son corpus standard et
son oracle de référence dans le module non publié `:kalligraphie:conformance`.
Ce module n'est pas un artefact consommateur : il existe pour déclarer ce que
chaque plateforme de référence implémente et pour comparer les observables que
ces plateformes publient.

Le contrat s'exprime par un petit ensemble de types. `ComparisonClass` et
`ComparisonQuantity` définissent comment un résultat portable est comparé ;
`CanonicalUnit` et `CanonicalScale` fixent l'unité de comparaison numérique
entre plateformes ; `Tolerance` et `ReferenceToleranceCatalog` déclarent des
tolérances numériques justifiées ; `DiagnosticComparison` compare les séquences
de diagnostics ; `PortableCapability`, `PortableCapabilityIdentity` et
`currentPortableCapabilityIdentity()` déclarent la surface de capacités de la
plateforme ; `ConformanceScenario`, `StandardConformanceCorpus`,
`ConformanceObservation`, `CanonicalEnvelope` et `observableEnvelope()`
décrivent et encodent les résultats du corpus ; et `ReferenceDivergenceRecord`
consigne les divergences bornées.

Cette page publie la matrice qui en résulte : quelles étapes portables chaque
plateforme de référence expose, comment les résultats sont comparés, quelles
divergences sont bornées, et ce qui reste une limite connue.

## Matrice des capacités

| Plateforme | Analyse Unicode | Shaping (façonnage) | Mise en page de bout en bout | Variantes de représentation des glyphes | Décodage | Annulation/atomicité | Route de vérification |
| --- | --- | --- | --- | --- | --- | --- | --- |
| JVM | Présent | Présent | Présent | Présent | Présent | Présent | `:kalligraphie:conformance:jvmTest` |
| iOS (Kotlin/Native) | Absent | Absent | Absent | Présent | Présent | Présent | `:kalligraphie:conformance:iosSimulatorArm64Test` |
| Android | Absent | Absent | Absent | Présent | Présent | Présent | `:kalligraphie:conformance:connectedAndroidDeviceTest` ; tâche déclarée `:kalligraphie:conformance:mediumPhoneAndroidDeviceTest` |

La disponibilité d'une capacité est déclarée, jamais déduite de la compilation.
La JVM déclare la surface de référence complète (`jvm-reference`). Apple et
Android déclarent l'analyse Unicode, le shaping (façonnage) et la mise en page de
bout en bout `absent`, et la route de représentation des glyphes présente
(`portable-glyph`). Chaque `PortableCapability` est déclarée exactement une fois
; `PortableCapabilityIdentity` expose `presenceOf`, `canonicalFingerprint` et
`absenceDiagnostics`, et `blockingDiagnostic(scenario)` renvoie le diagnostic
déterministe `conformance.portable-capability-absent` pour la première capacité
indisponible requise par un scénario, au lieu de produire une observation.

Le décodage et l'annulation/atomicité sont des comportements portables, non des
capacités contrôlées par une porte (gate) : chaque plateforme de référence les
exécute. Leurs observables sont invariants selon la plateforme, si bien que les
mêmes enveloppes observables sont vérifiées sur JVM, iOS et Android. La seule
différence inter-plateformes admissible est l'identité de capacités déclarée.
Cette matrice ne revendique aucune conformité de shaping (façonnage) ni de mise
en page de bout en bout sur Apple ou Android.

## Corpus et observables invariants

`StandardConformanceCorpus` définit quatre scénarios de décodage — `ascii`,
`multibyte-utf8`, `surrogate-utf16` et `malformed-utf8` — exécutés par l'oracle
de référence sur chaque cible compilée. Chacun produit une
`ConformanceObservation` (scénario, empreinte de capacités, scalaires décodés,
largeurs en unités source, codes de diagnostic), et `CanonicalEnvelope` l'encode
sous la version de schéma 1 comme une enveloppe textuelle déterministe, ligne par
ligne, sans dépendance de sérialisation.
`ConformanceObservation.observableEnvelope()` retire l'empreinte de capacités
afin de comparer directement les observables invariants selon la plateforme.
Comme ces observables sont invariants, les mêmes enveloppes tiennent d'une
plateforme à l'autre, même lorsque l'identité de capacités déclarée diffère.

## Classes de comparaison

`ComparisonClass` porte trois valeurs, et `ComparisonQuantity` déclare celle qui
s'applique à chaque observable :

- `BIT_IDENTICAL` — égalité exacte d'entiers ou d'octets, donc identique bit à
  bit. Les quantités sont `CODEPOINT_BOUNDARY`, `GRAPHEME_BOUNDARY`,
  `CLUSTER_INDEX`, `GLYPH_INDEX`, `CARET_INTEGER_POSITION`, `LINE_INDEX`,
  `FRAGMENT_INDEX`, `DIAGNOSTIC_IDENTITY` et `DIAGNOSTIC_ORDER`.
- `STRUCTURALLY_IDENTICAL` — même décision ou même ordre, sans être
  nécessairement égal octet par octet : structurellement identique. Les
  quantités sont `LINE_BREAK_DECISION`, `FALLBACK_CANDIDATE_ORDER` et
  `BIDI_RESOLUTION`.
- `NUMERIC_TOLERANCE` — géométrie flottante comparée sous une tolérance
  déclarée dans l'unité canonique. Les quantités sont `ADVANCE`, `ORIGIN`, `BOX`
  et `CARET_FRACTIONAL_POSITION`.

`ComparisonQuantity.effectiveClass(scope)` conditionne la classe déclarée par la
portée de la comparaison : une quantité identique bit à bit comparée entre
profils d'implémentation différents n'est que structurellement identique, car
l'implémentation sous-jacente n'est pas la même. Au sein d'un même profil, la
classe déclarée s'applique telle quelle. `DiagnosticComparison` compare les
séquences de diagnostics sous le contrat identique bit à bit et distingue
`Match`, `OrderDivergence` (mêmes diagnostics, ordre d'émission différent) et
`Mismatch`.

## Tolérances

`Tolerance` déclare une tolérance numérique pour un observable flottant. Chaque
tolérance porte sa portée (`ToleranceScope.INTRA_PLATFORM` ou `CROSS_PLATFORM`),
son unité (`ToleranceUnit.Canonical` ou `ToleranceUnit.RouteNative`), un écart
maximal strictement positif et une justification non vide liée à l'unité. Une
tolérance inter-plateformes doit être exprimée dans l'unité canonique ; seule une
comparaison intra-plateforme peut employer une unité native de route. Une
tolérance n'est jamais un epsilon global et n'est jamais élargie pour masquer une
régression.

Le corpus de référence n'exige aujourd'hui aucune tolérance numérique.
`ReferenceToleranceCatalog.tolerances` est vide parce que le corpus portable ne
produit que des résultats de décodage identiques bit à bit : chaque observable
est un entier (une valeur scalaire ou une largeur en unités source) ou un code de
diagnostic typé, donc rien n'est comparé sous une tolérance numérique. La cible
canonique, tant qu'elle demeure la seule unité canonique, est
`CanonicalUnit.FONT_DESIGN_UNIT`, accessible via `CanonicalScale`. Des tolérances
seront ajoutées, avec une justification en unité canonique, lorsque la géométrie
portable deviendra observable et que l'oracle de référence pourra les calibrer.

## Divergences bornées

`ReferenceDivergenceRecord` consigne six divergences bornées — analyse Unicode,
shaping (façonnage) et mise en page de bout en bout sur iOS et Android. Chacune
porte une justification liée au contrat plutôt qu'une tolérance choisie pour
masquer une régression.

| Plateforme | Capacité | Divergence | Justification |
| --- | --- | --- | --- |
| iOS | Analyse Unicode | Pas encore portable sur Apple. | L'analyse Unicode portable est portée par le chantier d'analyse dédié. |
| iOS | Shaping (façonnage) | Pas encore portable sur Apple. | Le shaping portable est porté par le chantier dédié aux liaisons HarfBuzz. |
| iOS | Mise en page de bout en bout | Ne peut s'exécuter sur Apple sans analyse et shaping portables. | La mise en page de bout en bout dépend de l'analyse et du shaping portables. |
| Android | Analyse Unicode | Pas encore portable sur Android. | L'analyse Unicode portable est portée par le chantier d'analyse dédié. |
| Android | Shaping (façonnage) | Pas encore portable sur Android. | Le shaping portable est porté par le chantier dédié aux liaisons HarfBuzz. |
| Android | Mise en page de bout en bout | Ne peut s'exécuter sur Android sans analyse et shaping portables. | La mise en page de bout en bout dépend de l'analyse et du shaping portables. |

## Limites connues

- L'analyse Unicode portable et le shaping (façonnage) portable sont portés par
  des chantiers distincts : les fournisseurs de fontes système (#58) et les
  liaisons HarfBuzz vers kffi (#59). Tant qu'ils n'ont pas abouti, Apple et
  Android ne peuvent exécuter les étapes d'analyse Unicode, de shaping
  (façonnage) ni de mise en page de bout en bout.
- `iosArm64` est compilé mais exécuté sans appareil sur les runners (exécuteurs)
  hébergés ; la route de vérification Kotlin/Native est
  `iosSimulatorArm64Test`.
- L'APK de test Android exerce actuellement la façade portable publique plutôt
  que le corpus partagé `commonTest`, car `androidDeviceTest` n'hérite pas de
  `commonTest`.
- La tâche Gradle Managed Device
  `:kalligraphie:conformance:mediumPhoneAndroidDeviceTest` est déclarée pour la
  CI mais n'a pas encore été exécutée en CI ; la route Android a été validée en
  local sur un émulateur via `connectedAndroidDeviceTest`.
- `:kalligraphie:conformance` est volontairement non publié : le contrat, le
  corpus et l'oracle sont de l'outillage de test, non une dépendance
  consommateur.
