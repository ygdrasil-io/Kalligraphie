# Provenance de la fixture Bungee Color

- Fichier : `BungeeColor-Regular.ttf`.
- Source immuable : dépôt
  [`google/fonts`](https://github.com/google/fonts), commit
  `5e35378e6bda803962ee6fd257e444a7d459660d`,
  `ofl/bungeecolor/BungeeColor-Regular.ttf`.
- URL de téléchargement figée :
  `https://raw.githubusercontent.com/google/fonts/5e35378e6bda803962ee6fd257e444a7d459660d/ofl/bungeecolor/BungeeColor-Regular.ttf`.
- Empreinte SHA-256 :
  `cf21a786e54f43694f4edbb51a38f81331a4c3414217c524c8cb2d091aa7fd63`.
- Licence : SIL Open Font License 1.1 ; copyright The Bungee Project Authors,
  David Jonathan Ross, conformément à `OFL.txt` du dépôt source.
- Format vérifié structurellement : TrueType avec `COLR` version 0 et `CPAL`
  version 0, neuf palettes de deux entrées.

## Oracle indépendant

L'oracle a été extrait avant l'ajout du test par un lecteur structural Python
3.14.7 (`struct`, entiers big-endian), sans appeler l'implémentation testée :

- `cmap` associe `U+0041` à glyph ID 43 ;
- le record COLR 43 référence les records de couche 86 et 87, dans cet ordre,
  pour les glyph IDs 292 et 293 avec les indices CPAL 0 et 1 ;
- la palette CPAL 0 donne les RGBA `(201,9,0,255)` et `(255,149,128,255)` ;
- la palette CPAL 1 donne les RGBA `(255,255,255,255)` et
  `(232,232,231,255)`.

Les données sont recoupées avec les tables publiées dans la révision figée de
Google Fonts. Une validation humaine explicite de cet oracle reste requise
avant toute publication qui revendiquerait cette validation.
