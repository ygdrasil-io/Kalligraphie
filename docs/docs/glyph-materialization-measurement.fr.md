# Mesure de matérialisation des glyphes

Kalligraphie fournit un runner (programme de mesure) JVM opt-in (activé
explicitement) pour la matérialisation portable des glyphes. Il vit dans les
sources de test : ce n’est ni un test fonctionnel de latence, ni un résultat de
benchmark (mesure comparative) publié. Il exécute les fixtures (données de test
fixes) COLR/CPAL, SVG-in-OpenType et EBDT format 1 auditées et versionnées, à
travers le parcours public catalogue, resolver (résolveur), instance, asset
(ressource de rendu) et `resolveGlyph(...)`.

Le runner enregistre neuf profils, dans cet ordre :

- normalisation COLR v0 / CPAL v0 froide et chaude ;
- normalisation SVG-in-OpenType froide et chaude ;
- décodage bitmap EBLC v2 / EBDT v2 format 1 froid et chaud ;
- sélection de palette CPAL 0 vers palette 1 ;
- pression par clé de profil SVG, éviction LRU (least recently used, moins
  récemment utilisé) puis nouvelle résolution ;
- annulation coopérative pendant une matérialisation COLR réelle à deux couches.

Un échantillon froid commence avant la création du catalogue embarqué et se
termine après consommation de la représentation immuable retournée. Un
échantillon chaud crée et alimente son catalogue, resolver, instance et asset
avant le chronomètre ; il mesure seulement `resolveGlyph(...)` et la
consommation du résultat. La fermeture de l’asset et du resolver est exclue des
deux intervalles. Le profil de palette commence avant l’acquisition de l’asset
palette 1 après une amorce palette 0. Le profil de pression comprend l’amorce,
cinq clés SVG certifiées distinctes et la résolution finale. Le profil
d’annulation mesure l’entrée de l’appel jusqu’au retour d’annulation typé et,
séparément, le premier signal d’annulation intervenant pendant l’opération.

## Exécution reproductible

Le rapport doit être écrit hors du dépôt. `--rerun-tasks` empêche un ancien
résultat Gradle de masquer une mesure explicitement demandée.

```bash
env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/tmp/kalligraphie-glyph-materialization.md \
  ./gradlew :kalligraphie:jvmTest \
  --tests org.graphiks.kalligraphie.GlyphMaterializationBenchmarkTest.runsEveryConfiguredMaterializationProfileOnlyWhenExplicitlyEnabled \
  --rerun-tasks --no-daemon
```

Utilisez un warmup (préchauffage) et deux itérations seulement pour un smoke run
(exécution de fumée). Il vérifie que chaque route peut produire un rapport,
mais ne permet pas de comparaison.

## Contenu du rapport et limites

Chaque rapport consigne le commit (révision) mesuré, la machine, l’OS,
l’architecture, la JVM, les empreintes SHA-256 des fixtures, le corpus, la
route exacte, la frontière chronométrée, l’état du cache (mémoire interne de
réutilisation), le warmup, le nombre d’itérations, les percentiles nearest-rank
(rang supérieur), les allocations du thread (fil d’exécution) de mesure et une
variation signée du tas JVM relevée après les demandes de GC (ramasse-miettes)
documentées. Il inclut aussi les octets source fournis au catalogue pendant
l’intervalle, les octets et pixels bitmap décodés, ainsi que le nombre de
nœuds de peinture normalisés.

Les octets source sont la taille du buffer de fixture donné au catalogue
portable ; ce ne sont pas des compteurs d’entrées/sorties fichier. Un profil
chaud rapporte zéro octet source car son catalogue est volontairement ouvert
avant la frontière chronométrée. Les routes portables de ce runner n’exposent
pas de frontière fiable de comptabilité de mémoire ou d’allocations natives :
ces champs indiquent donc explicitement `unavailable` plutôt qu’une estimation
de plateforme. Le champ de mémoire JVM retenue est une observation du tas pour
ce runner, non une comptabilité du cache ou de toute la mémoire du processus ;
il peut être négatif après GC.

Le runner n’impose aucun seuil de latence. `check` ne lance aucune mesure sans
la variable d’environnement opt-in et ce travail n’ajoute ni renderer (moteur
de rendu), ni rasterizer (moteur de pixellisation), ni API GPU, ni bridge
(pont) natif.
