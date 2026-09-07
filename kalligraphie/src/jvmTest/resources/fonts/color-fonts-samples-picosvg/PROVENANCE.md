# Provenance du jeu de données de test SVG-in-OpenType

- Fichier : `samples-picosvg.ttf`.
- Source immuable : dépôt
  [`googlefonts/color-fonts`](https://github.com/googlefonts/color-fonts),
  commit `0046ea4c3b69e9fbbe464c2594816894e3aa5e4b`,
  `fonts/samples-picosvg.ttf`.
- URL de téléchargement figée :
  `https://raw.githubusercontent.com/googlefonts/color-fonts/0046ea4c3b69e9fbbe464c2594816894e3aa5e4b/fonts/samples-picosvg.ttf`.
- Empreinte SHA-256 :
  `c55758a47ce0c0493eed2ba4a7ec131eed44649ab38a22ce318371427f841470`.
- Licence : Apache-2.0 ; voir `LICENSE.md`.
- Format audité : TrueType avec table `SVG ` version 0, documents SVG UTF-8
  non compressés et géométrie TrueType dans `glyf`.

## Oracle indépendant

Le document SVG couvrant le glyph ID 27 déclare un groupe `glyph27` transformé
par `matrix(120 0 0 120 37.5 -950)`. Son unique chemin est
`M9,5 A4 4 0 1 1 1,5 A4 4 0 1 1 9,5 Z` et il utilise le gradient radial `g1` :
centre `(5,5)`, rayon `4`, arrêts `0.1` or et `0.95` rouge. Ces données ont été
extraites hors de l’implémentation testée avec un lecteur structural indépendant
en Python 3.14.7 (`struct`, entiers big-endian) et recoupées avec `xxd`
2025-01-14 sur les octets de la table `SVG `.

Les cas malveillants remplacent dans cette même fonte la balise du chemin de
`glyph27` par `use` de même longueur, puis réduisent séparément la limite de
profondeur. Ils vérifient le refus explicite et non l'obtention d'une sortie
fabriquée par l'implémentation testée. Une validation humaine explicite de cet
oracle reste requise avant de présenter ce jeu de données comme audité pour une
publication.
