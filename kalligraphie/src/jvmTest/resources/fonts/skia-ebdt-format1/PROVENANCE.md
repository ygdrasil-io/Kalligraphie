# Provenance du jeu de données de test Skia EBDT format 1

- Fichier : `ebdt_fmt1.ttf`.
- Source immuable : dépôt
  [`google/skia`](https://chromium.googlesource.com/skia/), commit
  `4b24321eb36cac92020d8154307de78ecf1d1e50`,
  `resources/fonts/ebdt_fmt1.ttf`.
- URL de téléchargement figée :
  `https://chromium.googlesource.com/skia/+/4b24321eb36cac92020d8154307de78ecf1d1e50/resources/fonts/ebdt_fmt1.ttf?format=TEXT`.
- Empreinte SHA-256 :
  `e99cebed4d9421bc89964b9dc6a3bedfc6a286029d64336a07844708cce76274`.
- Licence : BSD-3-Clause ; voir `LICENSE.md`.
- Format déclaré par cette fixture : TrueType avec `EBLC` version 2.0 et `EBDT`
  version 2.0, index-subtable format 1, image format 1, profondeur d'un bit.

## Oracle indépendant

L'oracle décrit la table EBDT, pas une sortie de l'implémentation testée :

- `cmap` associe U+1F600 au glyph ID 3 ;
- le strike exact 16 × 16 fournit une image de 13 × 13 pixels, origine `(0,13)`
  et avance horizontale 12 ;
- ses treize lignes de masque alpha sont
  `.............`, `....#####....`, `..#########..`, `.##########..`,
  `.###########.`, `.###########.`, `############.`, `.###########.`,
  `.###########.`, `.###########.`, `..#########..`, `...#######...`,
  `.....##......`.

Les valeurs ont été lues hors de l'implémentation testée avec un lecteur
structurel indépendant basé sur Python 3.14.7 et `struct` (entiers big-endian),
puis contrôlées contre les octets EBDT au moyen de `xxd` 2025-01-14. Une
validation humaine explicite de cet oracle reste requise avant de présenter ce
jeu de données comme audité pour une publication.
