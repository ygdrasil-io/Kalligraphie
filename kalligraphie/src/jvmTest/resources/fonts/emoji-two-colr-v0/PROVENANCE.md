# Provenance de la fixture EmojiTwo COLRv0

- Fichier : `EmojiTwoCOLRv0.ttf`.
- Source immuable : dépôt
  [`Emoji-COLRv0/Emoji-COLRv0`](https://github.com/Emoji-COLRv0/Emoji-COLRv0),
  commit `1b81dcf46545252bdc82f6c6309335fb1c73b8c9`,
  `fonts/EmojiTwoCOLRv0.ttf`.
- URL de téléchargement figée :
  `https://raw.githubusercontent.com/Emoji-COLRv0/Emoji-COLRv0/1b81dcf46545252bdc82f6c6309335fb1c73b8c9/fonts/EmojiTwoCOLRv0.ttf`.
- Empreinte SHA-256 :
  `b5ba9f3a70f5d674f85d12f3f32e846f396965753aaed5b3d1e2ffee3fe94ef4`.
- Licence : CC-BY-4.0 ; attribution EmojiTwo / EmojiOne 2.2 / Ranks.com et
  communauté EmojiTwo, comme détaillé dans `LICENSE.md`.
- Format vérifié structurellement : TrueType avec `COLR` version 0 et `CPAL`
  version 0.

## Oracle indépendant

L’oracle est l’encodage OpenType de la fonte, relu directement dans les tables
avant l’ajout du test :

- `cmap` associe U+1F600 à glyph ID 1443 ;
- le record COLR 1443 référence, dans cet ordre, les glyph IDs 2650, 10717,
  10718, 10719, 10720 et 10721 ;
- leurs indices CPAL sont 1182, 265, 162, 1102, 1233 et 265 ;
- la palette CPAL 0 donne respectivement les RGBA `(255,221,103,255)`,
  `(102,78,39,255)`, `(76,53,38,255)`, `(255,113,127,255)`,
  `(255,255,255,255)` et `(102,78,39,255)`.

Ces valeurs ont été extraites hors de l’implémentation testée par un lecteur
structural Python 3.14.7 (`struct`, entiers big-endian) et recoupées avec
Fontconfig 2.18.3 pour l’identité `EmojiTwo COLRv0 Regular`.

## Validation humaine

Un mainteneur a vérifié la provenance, la licence, l’empreinte et l’oracle
attendu de cette fixture, puis a validé son utilisation comme référence de
test. Cette revue reste indépendante de l’interpréteur COLR de Kalligraphie.
