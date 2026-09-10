# Mesure de matérialisation des glyphes

Kalligraphie fournit un runner (programme de mesure) JVM opt-in (activé
explicitement) pour la matérialisation portable des glyphes. Il vit dans les
sources de test : ce n’est ni un test fonctionnel de latence, ni un résultat de
benchmark (mesure comparative) publié. Il exécute les fixtures (données de test
fixes) COLR/CPAL, SVG-in-OpenType, EBDT format 1 et Liberation Sans TrueType
auditées et versionnées, à travers les parcours publics catalogue, resolver
(résolveur), instance, asset (ressource de rendu) et `resolveGlyph(...)`.

Le runner enregistre vingt-sept profils, dans cet ordre :

- normalisation COLR v0 / CPAL v0 froide et chaude ;
- normalisation SVG-in-OpenType froide et chaude ;
- décodage bitmap (image matricielle) EBLC v2 / EBDT v2 format 1 froid et chaud ;
- sélection de palette CPAL 0 vers palette 1 ;
- pression par clé de profil SVG, éviction LRU (least recently used, moins
  récemment utilisé) puis nouvelle résolution ;
- annulation coopérative pendant une matérialisation COLR réelle à deux couches.
- parcours consommateur public `RENDERABLE` froid et chaud avec un glyphe latin
  Bungee Color ;
- parcours consommateur public `RENDERABLE` froid et chaud avec un paragraphe
  BiDi (bidirectionnel) mêlant Bungee Color latin et le fallback (police de
  repli) hébreu Liberation Sans.
- sessions incrémentales réutilisables froides et chaudes pour les mêmes
  paragraphes mono-police et BiDi multi-police ;
- étapes portables TrueType froides et chaudes de préparation, correspondance
  texte-glyphe, métriques, contours et détachement sur un paragraphe d’éditeur
  Liberation Sans stable.

Pour les six profils directs historiques, un échantillon froid commence avant
la création du catalogue embarqué et se termine après consommation de la
représentation immuable retournée. Leur échantillon chaud crée et alimente le
catalogue, le resolver, l’instance et l’asset avant le chronomètre ; il mesure
seulement `resolveGlyph(...)` et la consommation du résultat. La fermeture de
l’asset et du resolver est exclue uniquement de ces deux intervalles directs.
Le profil de palette commence avant l’acquisition de l’asset palette 1 après une
amorce palette 0. Le profil de pression comprend l’amorce, cinq clés SVG
certifiées distinctes et la résolution finale. Le profil d’annulation mesure
l’entrée de l’appel jusqu’au retour d’annulation typé et, séparément, le premier
signal d’annulation intervenant pendant l’opération.

Les profils consommateur froids incluent la création du catalogue et du
resolver, puis s’arrêtent lorsque la façade publique de paragraphe a produit et
consommé un layout (mise en page) dont tous les glyphes finaux portent un
certificat de matérialisation. Les profils consommateur chauds gardent catalogue
et resolver ouverts, amorcent le cache (mémoire interne de réutilisation) de
représentations portables par un premier layout hors mesure, puis chronomètrent
la même frontière de façade publique. La façade JVM ouvre et ferme
volontairement son backend (moteur interne) de shaping
(façonnage) documenté à chaque appel : ces profils chauds mesurent donc la
réutilisation du cache d’assets, jamais une réutilisation cachée du backend.

Chaque échantillon consommateur rapporte aussi le maximum d’assets possédés par
l’opération simultanément vivants, leur borne conservatrice en octets, les
ouvertures d’asset distinctes et les preuves de glyphes finaux réutilisées après
une matérialisation antérieure dans la même opération. Ces valeurs sont des
mesures de scénario reconstruites depuis les certificats immuables et les
estimations du provider (fournisseur). Elles ne constituent ni une contrainte
de temps, ni la preuve d’un algorithme particulier de cache ou de pool (réserve
réutilisable).

## Sessions HarfBuzz réutilisables

`SessionColdSingleFont`, `SessionWarmSingleFont`, `SessionColdMixedBidi` et
`SessionWarmMixedBidi` utilisent `JvmIncrementalParagraphLayoutSession`. Les
deux côtés amorcent les assets du catalogue et du resolver hors chronomètre.
Un échantillon froid ouvre sa session dans l’intervalle mesuré ; un échantillon
chaud conserve une session et son backend HarfBuzz, amorcés par un layout hors
mesure. Chaque échantillon fournit une nouvelle version de texte, compose le
paragraphe entier et consomme des glyphes certifiés. Les fermetures de session
et de resolver sont exclues de ces intervalles.

Les champs de session rapportent les octets source copiés dans les buffers
natifs retenus pendant l’échantillon, l’estimation HarfBuzz retenue en fin
d’échantillon et la réutilisation d’un backend existant (0 froid, 1 chaud,
déterminée par le cycle de vie du runner). Le compte froid provient des octets
inactifs de la session : ces petites fixtures tiennent dans la politique par
défaut sans éviction. Les échantillons chauds ne demandent aucune nouvelle copie.
Ce champ est distinct des octets source fournis au catalogue.

`JvmPreparedFontCachePolicy` borne les entrées, les octets source, les octets
natifs estimés et leur somme, fontes actives et inactives comprises. L’admission
est réservée sous verrou avant l’allocation native ; seules les fontes inactives
sont évincées. Une admission impossible retourne `FontError.ResourceLimitExceeded`
sans layout partiel. Cette politique est indépendante du pool d’assets de rendu
et du cache de layout incrémental. `preparedFontCacheUsage` fournit un instantané
immuable lisible avant composition et après fermeture. Le backend fermé libère
immédiatement les fontes inactives, puis les actives à leur dernière restitution
de lease (emprunt de ressource), sans réouverture.

L’estimateur `harfbuzz-14.3.0-4x-source-plus-256k-v1` compte 256 Kio plus quatre
fois la longueur source pour les objets HarfBuzz, accélérateurs et caches retenus ;
le buffer source est compté séparément. Cette estimation prudente et versionnée
est une charge de politique, pas un compteur d’allocation instrumenté ni une borne
prouvée pour toute fonte. Les buffers de shaping temporaires, copies JVM, métadonnées
de l’allocateur, bibliothèque partagée et RSS (mémoire résidente du processus)
en sont exclus. Les octets réellement alloués et comptes d’allocations natifs
restent `unavailable`. Latences, allocations du thread et variations du tas sont
des mesures observées ; l’estimation native ne mesure jamais la mémoire totale
du processus.

## Étapes portables TrueType

Les dix profils TrueType portables supplémentaires utilisent Liberation Sans
Regular et ce paragraphe exact : « Readable typography keeps words,
punctuation, carets, and 0123456789 responsive while an editor changes text. »
Ses scalaires Unicode sont calculés une fois avant les opérations warm (chaudes)
chronométrées. Une mesure cold (froide) repart au contraire de l’état neuf
précisé par sa frontière. Les cinq paires mesurent :

- la préparation : la mesure froide couvre la capture du catalogue embarqué,
  la résolution de la face et la création d’instance depuis un nouveau
  catalogue ; la mesure chaude répète résolution et création depuis un unique
  catalogue déjà capturé ;
- le mapping (correspondance) texte-glyphe : la mesure froide crée une nouvelle
  instance avant de résoudre tout le paragraphe ; la mesure chaude résout la
  même séquence sur une instance préparée et consomme chaque identifiant de
  glyphe retourné ;
- les métriques : la mesure froide crée l’instance, effectue le mapping, puis
  lit l’avance et les limites de chaque glyphe ; la mesure chaude lit les mêmes
  champs sur une séquence de glyphes déjà résolue ;
- les contours : la mesure froide crée un resolver et un asset attaché avant de
  résoudre chaque glyphe non nul distinct ; la mesure chaude réutilise un asset
  amorcé hors chronomètre et consomme l’identifiant, les unités par cadratin, les
  limites, le nombre de contours et le nombre de commandes de chaque contour
  produit ;
- le détachement : la mesure froide crée puis détache un asset, ferme son
  propriétaire attaché et résout ensuite le glyphe 36 au moyen du handle
  (poignée de ressource) détaché ; la mesure chaude conserve un propriétaire
  attaché amorcé, répète des cycles indépendants de détachement, résolution et
  fermeture du handle détaché, puis ferme le propriétaire après les échantillons.

Chaque resolver, asset attaché et asset détaché possédé est fermé dans un
chemin `finally` (garanti même en cas d’échec). Les profils froids incluent la
préparation nommée par leur frontière ; les profils chauds préparent ou amorcent
cet état hors chronomètre. Chaque étape rapporte des observations de latence
p50, p95 et p99 positives, l’état des allocations du thread (fil d’exécution)
mesuré, une
observation de la mémoire JVM retenue, les octets source et l’empreinte SHA-256
de Liberation Sans avec celles des autres fixtures. La mémoire native retenue
et les allocations natives restent explicitement `unavailable` (indisponibles),
car l’API portable n’expose aucune frontière de comptabilité fiable pour ces
valeurs.

## Exécution reproductible

Le rapport doit être écrit hors du dépôt. `--rerun-tasks` empêche un ancien
résultat Gradle de masquer une mesure explicitement demandée.

```bash
env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/tmp/kalligraphie-glyph-materialization.md \
  ./gradlew :kalligraphie:glyphMaterializationMeasurement \
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
(rang supérieur), les allocations du thread de mesure et une
variation signée du tas JVM relevée après les demandes de GC (ramasse-miettes)
documentées. Il inclut aussi les octets source fournis au catalogue pendant
l’intervalle, les octets et pixels bitmap décodés, ainsi que le nombre de
nœuds de peinture normalisés.

Les quatre champs d’assets d’opération sont disponibles pour les profils
consommateurs publics de paragraphe et de session. Les profils directs de glyphes et
les étapes TrueType portables les indiquent comme `unavailable` (indisponibles),
car ces routes n’exécutent pas une composition de paragraphe bornée par une
opération.

Les octets source sont la taille du buffer (tampon mémoire) de fixture donné au catalogue
portable ; ce ne sont pas des compteurs d’entrées/sorties fichier. Un profil
chaud rapporte zéro octet source car son catalogue est volontairement ouvert
avant la frontière chronométrée. Les routes portables de ce runner n’exposent
pas de frontière fiable de comptabilité de mémoire ou d’allocations natives :
ces champs indiquent donc explicitement `unavailable` plutôt qu’une estimation
de plateforme. Le champ de mémoire JVM retenue est une observation du tas pour
ce runner, non une comptabilité du cache ou de toute la mémoire du processus ;
il peut être négatif après GC.

Le runner n’impose aucun seuil de latence. `check` exclut la tâche de mesure,
même si la variable opt-in est définie, et ce travail n’ajoute ni renderer (moteur
de rendu), ni rasterizer (moteur de pixellisation), ni API GPU, ni bridge
(pont) natif.
