À propos de ce fork
===================

Ceci est un fork de [marian-m12l/studio](https://github.com/marian-m12l/studio), maintenu de façon
indépendante et orienté vers la **robustesse à l'usage réel** : que l'appareil soit détecté de
manière fiable, que les échecs soient rapportés honnêtement, et que ce que STUdio écrit sur la carte
soit progressivement durci. Le projet amont connaît peu d'activité depuis quelque temps ; ce fork en
garde l'objectif, la licence et l'attribution, et y ajoute des correctifs, des tests et un travail de
caractérisation.

**C'est un travail en cours, pas un produit fini.** Plusieurs parties du chemin d'écriture restent
ouvertes, et rien ici ne prétend survivre à une coupure de courant. Lisez *État actuel* et *Limites*
ci-dessous avant de décider si cela convient à votre usage.

## Ce que ce fork change

Regroupé par domaine plutôt que changement par changement. Le détail est dans `TESTING.md` et dans
l'historique des commits.

**Appareil et transport**

- La recherche de partition attend que le système monte l'appareil au lieu d'abandonner au bout de
  dix secondes, et un débranchement annule une recherche en cours.
- Une voie de supervision perdue n'est plus rapportée comme un transfert échoué.
- Le contexte libusb a un propriétaire unique, avec initialisation et arrêt idempotents, et les
  tâches de détection survivent aux échecs transitoires au lieu de mourir silencieusement.
- Les descripteurs de fichiers sont libérés sur tous les chemins de sortie, pas seulement le nominal.

**Index des packs et système de fichiers**

- Un `.pi` dont la taille n'est pas un multiple entier d'enregistrements de 16 octets est rejeté au
  lieu d'être transformé en UUID de pack fabriqué.
- Une entrée d'index dont le contenu est absent ne rend plus l'appareil entier illisible : les autres
  packs restent listables.
- La vérification d'espace libre est en `long` de bout en bout et compte ce que le transfert est
  connu pour ajouter — le remplissage du chiffrement, le fichier de démarrage, la croissance de
  l'index et sa copie temporaire — au lieu de sommer les tailles source en tronquant sur un `int`.
- Le fichier temporaire `.pi.new` est créé en exclusivité, supprimé de nouveau en cas d'échec
  contrôlé, et un `.pi.new` déjà présent est refusé plutôt qu'écrasé.
- Le temporaire est synchronisé, puis installé par un unique `Files.move(ATOMIC_MOVE)` : l'index
  n'est plus réécrit sur place. Si le déplacement atomique n'est pas supporté, l'opération échoue —
  il n'y a délibérément pas de repli sur la copie non atomique précédente.
- Un envoi crée son dossier `.content` en exclusivité. Un dossier déjà présent sous ce nom est
  **refusé, jamais écrit ni vidé** : ce peut être le résidu d'un envoi interrompu, d'une suppression
  qui n'a pas abouti, ou d'un autre outil, et rien ne permet de trancher. Le refus ne lit rien,
  n'écrit rien et ne supprime rien.

**Bibliothèque et conversions**

- Un fichier converti n'est plus envoyé à l'appareil au seul motif qu'il est le plus récent présent.
  Quand la bibliothèque contient à la fois quelque chose que l'appareil sait lire et quelque chose
  qui pourrait être converti, STUdio demande lequel vous voulez — sauf s'il peut prouver qu'ils
  correspondent.
- Les nouvelles conversions enregistrent ce à partir de quoi elles ont été faites. Avant de réutiliser
  l'une d'elles, STUdio relit la source et le fichier converti et les compare à ce qu'il avait
  enregistré : seule une correspondance transfère sans demander. Une différence, ou tout ce qu'il ne
  peut pas établir, fait poser la question.
- Les conversions faites avant l'existence de ce mécanisme ne portent pas cet enregistrement : elles
  ne sont jamais supposées correspondre. Elles font poser la question, une fois, et les reconvertir
  en produit une qui ne la posera plus.

**Tests**

- Une chaîne d'intégration continue exécutant la suite Java sur Linux et Windows, plus la suite
  JavaScript.
- Des tests de caractérisation et de spécification couvrant les métadonnées, l'index, la détection et
  le chemin d'écriture.
- Une suite FAT32 optionnelle, exercée à la main sur un volume jetable — voir `TESTING.md`.
- Quatre sessions documentées d'opérations sur appareil réel, sur deux appareils — voir
  `FIELD-VALIDATION.md`.

## État actuel

En cours de travail, et **pas terminé**. Ce qui est décrit ci-dessus est implémenté et testé dans les
conditions documentées dans `TESTING.md`. Ce qui reste ouvert, et connu comme tel :

- un envoi qui échoue en cours de route laisse un dossier `.content` orphelin que rien ne nettoie ;
- cet orphelin fait ensuite **refuser** tout envoi ultérieur du même pack, ce qui est délibéré et
  expliqué dans l'erreur — mais STUdio n'offre toujours aucun moyen supporté de résoudre l'état
  laissé ;
- `deletePack` retire l'entrée d'index avant le contenu : une suppression qui échoue laisse donc le
  contenu en place, déréférencé ;
- le pilote sait lister les dossiers `.content` que l'index ne référence pas, mais rien ne l'appelle :
  un appareil n'est pas examiné à la recherche d'états partiels au branchement, et l'interface web ne
  les montre jamais. Rien n'agit dessus non plus — ce listage est en lecture seule par conception.

Utilisez le protocole opératoire de `FIELD-VALIDATION.md` si vous écrivez sur un appareil réel.

## Limites

- **Aucune résistance aux coupures n'est revendiquée ni démontrée.** Les améliorations ci-dessus
  concernent les échecs ordinaires — les erreurs retournées par le système de fichiers — pas une
  coupure de courant ni un retrait physique en cours d'écriture.
- `force()` demande au système d'exploitation de pousser un fichier vers la carte. Ce n'est pas une
  preuve que les octets ont atteint la mémoire flash : le contrôleur de la carte peut acquitter plus
  tôt, et rien ici ne permet de l'observer.
- `ATOMIC_MOVE` est atomique vis-à-vis du système de fichiers. Ce n'est pas une garantie face à une
  coupure de courant.
- FAT32, qui est ce qu'utilise un appareil, n'a pas de journal.
- L'incident de corruption FAT consigné dans `FIELD-VALIDATION.md` est une **hypothèse forensique**,
  pas une cause démontrée. Rien dans ce dépôt n'établit ce qui l'a provoqué.

## Tests

À l'heure où ces lignes sont écrites : **282 tests Java** dans la suite standard et **57 tests
JavaScript**, tous verts, sur Linux comme sur Windows. `TESTING.md` porte les comptes à jour, le
chiffre de la suite FAT32 optionnelle et ses réserves, et fait autorité — les nombres cités ici se
périmeront avant lui.

Deux réserves à connaître avant de lire quoi que ce soit dans ces nombres :

- **L'intégration continue n'exerce jamais FAT32.** Un exécuteur hébergé n'a pas de tel volume : les
  tests optionnels y sont ignorés et sont validés à la main sur un VHD jetable.
- Les résultats de terrain proviennent de quatre sessions, une machine, deux appareils. Ils ne se
  généralisent pas à d'autres révisions de micrologiciel, d'autres cartes ou d'autres versions de
  Windows.

`TESTING.md` décrit comment tout exécuter, ce qui est couvert, et ce qui ne l'est délibérément pas.

## Pour commencer

La première pré-release publique de ce fork est disponible sous la version
[`0.4.3-fork.1`](https://github.com/antoinevalentinHA/studio/releases/tag/0.4.3-fork.1). C'est une
**pré-release** : elle est publiée pour élargir la validation au-delà des appareils utilisés pendant
le développement, non parce que le travail serait terminé. Lisez d'abord *État actuel* et *Limites*
ci-dessus.

Le lien de téléchargement des instructions amont ci-dessous pointe toujours vers la construction
d'upstream, qui ne contient **aucune** des modifications décrites ici.

**Télécharger la pré-release.** Prenez `studio-web-ui-0.4.3-fork.1-dist.zip` dans
[cette release](https://github.com/antoinevalentinHA/studio/releases/tag/0.4.3-fork.1),
décompressez-la, puis lancez le script de démarrage correspondant à votre plate-forme. Les prérequis
et le reste de la procédure sont ceux d'amont, décrits sous *Utilisation* ci-dessous.

**Ou construire depuis les sources.** Les prérequis amont s'appliquent sans changement — Java JDK 11+
pour l'exécuter, Maven 3+ pour le construire — mais clonez **ce** dépôt-ci et non celui qui est nommé
dans *Pour les développeurs* :

```
git clone https://github.com/antoinevalentinHA/studio.git
cd studio
mvn clean install
```

Cela produit la même **archive de distribution** dans `web-ui/target/`.

## Relation avec le projet amont

Fondé sur STUdio, de [@marian-m12l](https://github.com/marian-m12l), dont le travail de rétro
ingénierie est ce sur quoi tout ceci repose. La licence, l'attribution et les avertissements sont
inchangés et reproduits ci-dessous. Ce fork pourra être rebasé sur le projet amont si celui-ci
redevient actif ; d'ici là, les changements ci-dessus sont maintenus ici.

---

La suite de ce fichier est le README amont, conservé tel quel sauf là où il dirait quelque chose de
faux pour ce fork. Ce qui est annoté : le badge de release et le lien de téléchargement, qui
désignent des constructions amont ; l'URL de clonage, qui est le dépôt amont et non celui-ci ; la
règle déterminant quel fichier est transféré, que ce fork a changée ; et les listes de formats
d'assets, dont les astérisques ont disparu lors d'une réécriture amont alors que la phrase qui les
expliquait est restée, décrivant ainsi des marqueurs absents. Rien d'autre n'y est modifié.

---

[![Release amont](https://img.shields.io/github/v/release/marian-m12l/studio?label=release%20amont)](https://github.com/marian-m12l/studio/releases/latest)

*Ce badge et les liens de téléchargement ci-dessous désignent les constructions **amont**, qui ne
contiennent pas les changements de ce fork. La pré-release propre à ce fork est
[`0.4.3-fork.1`](https://github.com/antoinevalentinHA/studio/releases/tag/0.4.3-fork.1) — voir
[Pour commencer](#pour-commencer).*

> [!WARNING]
> Le support pour les appareils V3 a été ajouté grâce à la communauté ! :partying_face:
> 
> :warning: L'implémentation dans ce dépôt reste très peu testée à ce jour ! Conservez des copies de sauvegarde et soyez prêt à devoir réinitialiser la boîte à histoires en cas de soucis. :warning:

STUdio - Story Teller Unleashed
===============================

[Instructions in english](README.md)

Créez et transférez vos propres packs d'histoires de et vers la Fabrique à Histoires Lunii\*.


PRÉAMBULE
---------

Ce logiciel s'appuie sur mes propres recherches de rétro ingénierie, limitées à la collecte des informations nécessaires
à l'interopérabilité avec la Fabrique à Histoires Lunii\*, et ne distribue aucun contenu protégé.

**EN UTILISANT CE LOGICIEL, VOUS EN ASSUMEZ LE RISQUE**. Malgré mes efforts pour que l'utilisation de ce logiciel soit
sûre, il est distribué **SANS AUCUNE GARANTIE** et pourrait endommager votre appareil.

\* Lunii et "ma fabrique à histoires" sont des marques enregistrées de Lunii SAS. Je ne suis (et ce travail n'est) en aucun cas affilié à Lunii SAS.


UTILISATION
-----------

### Prérequis

* Java JDK 11+
* Sur Windows, cette application nécessite que le pilote _libusb_ soit installé. Le moyen le plus simple pour cela est
  d'installer le logiciel officiel Luniistore\* (mais il ne doit pas être exécuté en même temps que STUdio).

### Installation

* **Téléchargez** [la dernière release amont](https://github.com/marian-m12l/studio/releases/latest)
— il s'agit de la construction d'upstream, qui ne contient pas les changements de ce fork ; pour ce
fork, prenez plutôt l'archive de
[sa propre pré-release](https://github.com/antoinevalentinHA/studio/releases/tag/0.4.3-fork.1) —
(ou [construisez l'application](#pour-commencer)).
* **Décompressez** l'archive de distribution
* **Exécutez le script de lancement** : `studio-linux.sh`, `studio-macos.sh` ou `studio-windows.bat` selon votre
plate-forme. Vous devrez probablement rendre ce fichier exécutable d'abord.
* S'il ne s'ouvre pas automatiquement, **ouvrez un navigateur** et saisissez l'url `http://localhost:8080` pour charger
l'interface web.

Note: Évitez d'exécuter le script en tant que superutilisateur/administrateur, ce qui pourrait créer des problèmes de permissions.

### Utiliser l'application

L'interface web est composée de deux écrans :

* La bibliothèque d'histoires, qui permet de gérer la bibliothèque locale et de transférer de / vers la Fabrique à Histoire\* 
* L'éditeur d'histoire, pour créer ou modifier un pack d'histoire

#### Bibliothèque locale d'histoires et transfert de/vers l'appareil

L'écran de la bibliothèque d'histoires affiche toujours votre bibliothèque locale. Il s'agit des packs d'histoires situés
sur votre ordinateur (dans un répertoire `.studio` spécifique à chaque utilisateur). **Trois formats de fichier** peuvent
être présents dans votre bibliothèque :
* `Brut` est le format officiel supporté par les **appareils plus anciens** (firmware v1.x -- ces appareils utilisent un protocole USB bas-niveau)
* `FS` est le format officiel supporté par les **nouveaux appareils** (firmware v2.x -- ces appareils apparaîssent comme un stockage amovible)
* `Archive` est un format officieux, utilisé uniquement par STUdio dans l'**éditeur** d'histoires

La **conversion** d'un pack d'histoires est automatique lors d'un transfert, ou peut être invoquée manuellement.
Les variantes d'un pack d'histoires donné sont regroupées dans l'interface pour une meilleure lisibilité. **Le fichier
le plus récent** (mis en avant par l'interface) est transféré vers l'appareil.

*Ce fork a changé cette règle.* La date ne décide plus : quand la bibliothèque contient à la fois un
fichier que l'appareil sait lire et quelque chose qui pourrait être converti, ce fork demande lequel
vous voulez, sauf s'il peut prouver que le premier a été produit à partir du second. Voir
[Ce que ce fork change](#ce-que-ce-fork-change).

Quand l'appareil est branché, un panneau apparaît sur la gauche, affichant les métadonnées et les packs d'histoires de
l'appareil. Glisser et déposer un pack depuis ou vers l'appareil commencera le transfert.

#### Éditeur d'histoire

L'écran de l'éditeur d'histoire affiche l'histoire en cours de modification. Par défaut, un exemple est affiché, dont le but est de proposer un modèle d'utilisation correcte.

Un pack est composé de quelques métadonnées et du diagramme décrivant les différentes étapes de l'histoire :

* Les nœuds de scène permettent d'afficher une image et/ou de jouer un son
* Les nœuds d'action permettent de passer d'une scène à la suivante, et de gérer les options disponibles

L'éditeur supporte plusieurs formats de fichiers pour l'audio et les images.

##### Images

Les fichiers image peuvent utiliser les formats suivants :
* PNG
* JPEG
* BMP (24-bits)

**Les dimensions doivent être 320x240**. Les images peuvent être en couleurs, bien que certaines couleurs ne seront
certainement pas affichées fidèlement par l'écran situé derrière le boîtier en plastique. Gardez à l'esprit que la
couleur du boîtier peut changer.

##### Audio

Les fichiers audio peuvent utiliser les formats suivants :
* MP3
* OGG/Vorbis 
* WAVE (16-bits signés, mono, 32000 Hz)

Les fichiers MP3 et OGG doivent, eux, être échantillonnés à 44100Hz.

##### Conversion lors du transfert

Les formats ci-dessus sont ceux qu'accepte l'**éditeur**. Ce qui arrive sur l'**appareil** est plus
restreint, et dépend du format de pack transféré :

* un pack `Raw` (firmware v1.x) contient des images BMP 24 bits et de l'audio WAVE (16 bits signés,
  mono, 32000 Hz) ;
* un pack `FS` (firmware v2.x) contient des images BMP 4 bits encodées en RLE et de l'audio MP3
  (mono, 44100 Hz).

Les fichiers qui ne sont pas déjà dans la forme attendue sont convertis au moment de préparer le
transfert. Cela ne concerne pas que les formats compressés : vers un pack `FS`, un BMP 24 bits est
ré-encodé en BMP 4 bits/RLE, et un MP3 qui n'est pas mono/44100 Hz est ré-encodé lui aussi (un MP3
conservé tel quel voit ses tags ID3 retirés).

À l'inverse, sur le chemin `Raw`, un BMP ou un WAVE est écrit tel qu'il se présente, sans que sa
profondeur de bits ni ses paramètres d'échantillonnage soient vérifiés. Respecter les contraintes
listées plus haut vous revient donc ; ce n'est pas la conversion qui le fera.

#### Wiki

Pour plus d'informations, y compris un guide d'utilisation illustré (merci à
[@appenzellois](https://github.com/appenzellois)), consultez
[le wiki du projet](https://github.com/marian-m12l/studio/wiki/Documentation).


POUR LES DÉVELOPPEURS
---------------------

### Prérequis

* Maven 3+

### Building the application

* Cloner ce dépôt : `git clone https://github.com/marian-m12l/studio.git`
— il s'agit du dépôt **amont** ; pour construire ce fork, clonez plutôt
`https://github.com/antoinevalentinHA/studio.git`, voir [Pour commencer](#pour-commencer)
* Construire l'application : `mvn clean install`

Ceci créera **l'archive de distribution** dans `web-ui/target/`.


APPLICATIONS TIERCES
--------------------

Si vous avez aimé STUdio, vous aimerez aussi :
* [Moiki](https://moiki.fr/) est un outil en ligne de création d'histoires interactives, qui peuvent être exportées
vers STUdio (développé par [@kaelhem](https://github.com/kaelhem))
* [mhios (Mes Histoires Interactives Open Stories)](https://github.com/sebbelese/mhios)) était une bibliothèque ouverte, en ligne,
d'histoires interactives (développé par [@sebbelese](https://github.com/sebbelese))


LICENCE
-------

Ce projet est distribué sous la licence **Mozilla Public License 2.0**. Les termes de la licence sont dans le
fichier `LICENSE`.

La bibliothèque `vorbis-java`, ainsi que la classe `VorbisEncoder` sont distribuées par Xiph.org Foundation. Les termes
de la licence se trouvent dans le fichier `LICENSE.vorbis-java`.

Le package `com.jhlabs.image` est distribué par Jerry Huxtable sous la licence Apache License 2.0. Les termes
de la licence se trouvent dans le fichier `LICENSE.jhlabs`.
