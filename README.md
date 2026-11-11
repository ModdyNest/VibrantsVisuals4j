# Vibrants Visuals

Mod Fabric pour **Minecraft Java 26.3** qui reproduit au plus près le mode graphique **Vibrant Visuals** de Minecraft: Bedrock Edition.

> Dépendance requise : **Fabric API**.

## Fonctionnalités

| Effet | Option | Où |
|---|---|---|
| Ombres directionnelles pixelisées (soleil et lune) | Ombres | Vibrants Visuals |
| Ombres colorées (slime, miel, glace) | incluses dans Ombres | — |
| Ombres des entités, des blocs spéciaux et du joueur (y compris en 1re personne) | incluses dans Ombres | — |
| Caustiques sous l'eau | Caustiques | Vibrants Visuals |
| Brouillard volumétrique | Brouillard volumétrique | Vibrants Visuals |
| Rayons de lumière | Rayons de lumière | Vibrants Visuals |
| Bloom | Bloom | Vibrants Visuals |
| Tone mapping ACES | Tone mapping | Vibrants Visuals |
| Ombres des nuages | Ombres des nuages | Préférences |

- Le preset graphique **« Vibrants Visuals »** se place entre *Fabuleux* et *Personnalisé*. Il applique les réglages de *Fabuleux*, active tous les effets et passe le rendu sur **Vulkan**. Ce changement demande un redémarrage.
- Modifier un effet à la main passe le preset sur *Personnalisé*. Les presets *Rapide*, *Détaillé* et *Fabuleux* désactivent tous les effets.
- Les réglages sont enregistrés dans `config/vibrants-visuals.properties`.

## Comment ça marche

### Vue d'ensemble

Le jeu dessine le monde normalement. Le mod ajoute deux étapes :

1. **Avant le rendu principal** (`LevelRenderer.addMainPass`), il dessine la scène vue depuis le soleil dans des **cartes d'ombre**.
2. **Après le rendu du monde, avant la main** (`GameRenderer.renderLevel`), il applique une **passe plein écran**. Elle lit l'image et sa profondeur, recalcule la position de chaque pixel dans le monde, puis applique tous les effets (`shaders/post/vibrant_visuals.fsh`).

Tout passe par l'API de rendu du jeu (`com.mojang.renderpearl`), donc le mod fonctionne aussi bien sur OpenGL que sur Vulkan.

> Particularité de cette version : la profondeur est **inversée**. Elle est effacée à 0, et un objet proche a une valeur plus grande. Toutes les matrices et comparaisons du mod en tiennent compte.

### Ombres

**Carte d'ombre** (`render/ShadowRenderer.java`)
- C'est une texture de profondeur de 4096×4096 qui couvre 96 blocs autour du joueur. Elle est vue depuis le soleil avec une projection orthographique.
- **Terrain** : le mod réutilise les morceaux de terrain que le jeu a déjà préparés pour l'image (`ChunkSectionsToRender.renderLayers`). Il les dessine avec ses propres pipelines, qui n'écrivent que la profondeur et ne masquent aucune face.
- **Entités et blocs spéciaux** (coffres, têtes, golems…) : le mod réexécute les modèles opaques et translucides préparés par le jeu. Il utilise pour ça `FeatureRenderDispatcher.PreparedFrame` avec la projection du soleil.
- **Modèles translucides** : ils n'écrivent normalement pas la profondeur, ce qui est le cas de la peau du joueur. Ils sont remplacés à la volée par un pipeline qui l'écrit.

**Ombre du joueur en 1re personne** (`render/FirstPersonShadow.java`)
- Dans cette vue, le jeu ne prépare pas le modèle du joueur. Le mod le prépare lui-même dans une liste à part, que seule la passe d'ombre dessine.

**Effet pixelisé à la Bedrock**
- Avant le test d'ombre, la position de chaque pixel est arrondie à la grille **1/16 de bloc** de la face. Les bords d'ombre suivent ainsi les pixels des textures.
- L'origine de la carte d'ombre est calée sur sa propre grille. La direction du soleil avance par **paliers de 0,5°**. Ces deux réglages empêchent les ombres de scintiller ou de « dériver ».
- Comme sur Bedrock, l'angle du soleil ne descend pas sous **10°**.

**Éviter l'auto-ombrage (« shadow acne »)**
- Plusieurs décalages se combinent :
  - un décalage le long de la normale, qui grandit quand la lumière est rasante ;
  - un biais de profondeur calculé dans le shader ;
  - un biais matériel dans les pipelines d'ombre.
- La normale est recalculée à partir de la profondeur, en prenant toujours le voisin situé sur la même surface. Ça évite les liserés sombres au bord des feuilles.
- Sur les faces de blocs, la normale est arrondie à l'axe le plus proche.
- Les zones à l'ombre sont assombries et légèrement **bleutées**, car seul le ciel les éclaire, comme sur Bedrock.

**Ombres colorées**
- Une seconde carte, en couleur et en 2048×2048, reçoit les blocs translucides choisis : slime, miel, glace et eau.
- Le shader reconnaît ces blocs grâce à la position de leur texture dans l'atlas.
- Seule la surface la plus proche du soleil est gardée, donc le cube intérieur du slime n'assombrit pas l'ombre une seconde fois.
- L'eau ne teinte pas la lumière : elle sert seulement à marquer les zones immergées pour les caustiques.

**Ombres des nuages**
- Le shader lance un rayon vers le soleil jusqu'à l'altitude des nuages, puis lit la texture vanilla `clouds.png`.
- Il reprend exactement le défilement du jeu : 12 blocs par pixel de texture et 0,03 bloc par tick.

### Caustiques
- Elles ne s'affichent que sur les surfaces **sous l'eau** et **éclairées** par le soleil, ce qui inclut le sable derrière une cascade.
- Le motif est projeté depuis la surface de l'eau dans la direction de la lumière. Il s'atténue avec la profondeur, comme la lumière, qui bleuit au passage.
- La texture `caustics.png` de Bedrock n'étant pas libre de droits, le motif est **généré dans le shader**. Deux couches de tailles différentes sont superposées pour cacher la répétition.

### Brouillard volumétrique, rayons de lumière, bloom, tone mapping
- **Brouillard** : sa densité dépend de l'altitude et augmente avec la pluie. Il est plus lumineux face au soleil, ce qu'on appelle la diffusion de Mie.
- **Rayons de lumière** : un flou radial part de la position du soleil à l'écran et ne garde que les pixels de ciel.
- **Bloom** : les zones très lumineuses débordent légèrement sur leurs voisines.
- **Tone mapping** : la courbe ACES (Narkowicz) donne le rendu « filmique » de Bedrock.
- Les lignes de debug, comme les bordures de chunks, n'écrivent plus la profondeur quand le brouillard est activé. Sinon il les prendrait pour des murs.

## Structure

```
src/client/java/com/lucachinou/client/
├── VibrantsVisualsOptions.java        options et sauvegarde
├── render/
│   ├── VibrantsVisualsRenderer.java   passe plein écran et paramètres envoyés au shader
│   ├── ShadowRenderer.java            cartes d'ombre (terrain, entités, ombres colorées)
│   └── FirstPersonShadow.java         ombre du joueur en 1re personne
└── mixin/                             points d'accroche dans le jeu
src/client/resources/assets/vibrants-visuals/shaders/
├── core/shadow.fsh                    rendu des cartes d'ombre
└── post/vibrant_visuals.fsh           tous les effets
```

## Limites connues
- Pas de textures PBR (relief, reflets, matériaux).
- Pas de reflets sur l'eau ni d'effet de fenêtre de Snell.
- Seul ce qui est chargé autour de la caméra projette une ombre, dans un rayon de 96 blocs.
- L'ombre des nuages est plate : elle ne reproduit pas le volume des nuages « Détaillés ».

## Compiler

```bash
./gradlew build       # le .jar est dans build/libs/
./gradlew runClient   # lance le jeu de test
```
