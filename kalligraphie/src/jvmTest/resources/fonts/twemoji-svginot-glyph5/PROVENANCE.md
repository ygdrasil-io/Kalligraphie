# Provenance de la fixture SVG-in-OpenType

- Source : `13rac1/twemoji-color-font`, release `v15.1.0` ; archive
  `TwitterColorEmoji-SVGinOT-15.1.0.zip`.
- Archive source SHA-256 :
  `9075de7a1c9dd660782d02b5c5be1c1524e16db13a6d7d4264b9aabbd056b692`.
- Fonte source SHA-256 :
  `42d4b2a827c9ed557dff88c2f4723cb93985003a81dee76797324d66d04a57b8`.
- Licence : CC-BY-4.0 ; voir `LICENSE.md`.
- Transformation : sous-ensemble de la fonte source conservant le glyph ID 5,
  puis renuméroté en glyph ID 1 par FontTools 4.55.0 et lxml 5.3.0, exécutés
  avec Python 3.14.7. La commande était :
  `pyftsubset TwitterColorEmoji-SVGinOT.ttf --gids=5 --output-file=TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf`.
- Empreinte SHA-256 du sous-ensemble décodé :
  `3321f267b8a242d96c0790ac31becc74b7f57656762037e16f465be1adcd84d2`.
- Oracle : le document SVG de glyph ID 1 contient un groupe avec deux
  transformations `translate`, une mise à l'échelle et deux chemins cubiques
  pleins de couleur `#31373D`. Les valeurs attendues dans le test sont
  calculées directement depuis ces attributs et les nombres du document, sans
  appeler le lecteur SVG de Kalligraphie.
## Validation humaine

Un mainteneur a vérifié la provenance, la licence, les empreintes et l’oracle
attendu de cette fixture, puis a validé son utilisation comme référence de
test. Cette validation porte sur le sous-ensemble SVG consigné ci-dessus et
reste indépendante du lecteur SVG de Kalligraphie.
