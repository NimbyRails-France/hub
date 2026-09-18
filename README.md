# NimbyRails France Hub

Gestionnaire Windows x64 des projets **NimbyRails-France** : SDK, TCO et mods
natifs publiés dans les releases GitHub officielles. Version 0.3.0.

## Installer

Télécharger `NRFHub-0.3.0-Setup.exe` dans les
[releases](https://github.com/NimbyRails-France/hub/releases).
Choisir le dossier contenant `NIMBYRails.exe`, puis installer le SDK avant le TCO.
Le bouton d'installation demande le dossier parent de chaque projet.

- **SDK** : kit de développement et chargeur SDL installés ensemble ; le chargeur
  est placé dans le dossier du jeu et la SDL originale est sauvegardée.
- **TCO** : inclut sa propre DLL SDK compatible ; vérifie sa version et son ABI
  au démarrage. Les installations gérées par le Hub désactivent l'updater autonome du TCO.
  Un raccourci **Nimby TCO** est créé dans le menu Démarrer, sous **NimbyRails France Hub**,
  à l'installation, à la mise à jour ou au retour à la version précédente ; il est retiré à la désinstallation.
- **Mods natifs** : installés dans le dossier choisi, avec une jonction depuis
  le dossier de mods du jeu. Les projets déclarant `loaderApi: 1` fournissent
  également une DLL nommée dans le champ `module` : le Hub les enregistre dans `<jeu>/NRFMods/` et le
  NRF Loader les initialise automatiquement. Un loader compatible est requis.
  Les projets locaux sans release disponible sont affichés comme non publiés.

Une ancienne installation du SDK effectuée hors du Hub doit être retirée avec
son installateur original avant la première installation gérée. Le Hub ne
s'approprie pas un dossier existant. Fermer le jeu et le TCO avant installation.

## Mises à jour automatiques

Le Hub découvre les dépôts publics de NimbyRails-France puis consulte leurs releases GitHub, sans lire de catalogue central. Chaque ligne propose Stable, Bêta ou Alpha ; le choix est enregistré indépendamment. Le Hub lui-même dispose aussi de son sélecteur. Le site et le bot ne sont pas des applications installables dans le Hub.

Le tag, le canal et le statut prerelease doivent être cohérents. La release retenue est la plus haute version du canal possédant son manifeste. L'absence de version ou une erreur réseau est affichée ; aucune installation ne part d'une réponse non vérifiée. Une préversion n'est jamais proposée sur Stable, et le passage vers une version plus ancienne reste manuel.

Le Hub reçoit les événements des releases par webhook GitHub via ntfy, puis vérifie les releases officielles. Il les consulte aussi au démarrage et toutes les 15 minutes. Les rafales du relais sont regroupées (au plus une vérification toutes les cinq minutes). Les réponses API utilisent les ETag ; une limitation GitHub déclenche une attente au lieu d'une boucle de requêtes.

Les projets installés sont mis à jour automatiquement lorsque le jeu et le TCO
sont fermés et que les dépendances sont compatibles. Le Hub doit être lancé, éventuellement masqué dans la zone de notification ;
aucun service caché ni démarrage automatique Windows n'est installé.

Chaque téléchargement est vérifié par taille et SHA-256, puis extrait dans un
dossier de préparation. Les chemins sortant de l'archive et les liens sont rejetés.
La version précédente reste disponible avec « Revenir à la version précédente ».
Ce retour suspend les mises à jour automatiques pour éviter de réinstaller la
version annulée. Les réglages du Hub sont conservés dans les données locales utilisateur.
Les fichiers de distribution d'un projet sont remplacés ; garder les données
personnelles hors de ces dossiers ou dans les emplacements prévus par le projet.

Le Hub lui-même recherche `hub-latest.json` dans la release de son canal : téléchargement automatique, contrôle
d'intégrité, installation lorsque vous choisissez Quitter, ou bouton de redémarrage immédiat.
La confiance repose sur HTTPS et les dépôts de l'organisation. Les exécutables
ne sont pas signés avec un certificat Authenticode de publication.

La compatibilité du jeu est validée sur son SHA-256, plus strict qu'un simple
numéro de version. Un binaire inconnu bloque l'installation ; cela ne signifie
pas que toutes les fonctions du jeu sont exposées par le SDK expérimental.

## Fenêtre et notifications

La croix masque le Hub dans la zone de notification. Son icône permet de restaurer la fenêtre ou de quitter complètement. Le bouton Réduire fonctionne normalement. **F11** bascule en plein écran ; **Échap** revient en fenêtre. Les nouvelles versions et les opérations terminées déclenchent une notification Windows.

Voir [le fonctionnement du relais et les tests](docs/notifications.md).

## Développement

Qt 6.11.2, MinGW x64 et CMake. `build.ps1` construit et déploie les dépendances Qt.
`package.ps1 -Iscc <chemin>` produit l'installateur Inno Setup et son manifeste.
`ctest --test-dir build --output-on-failure` et
`powershell -File tests/manage-tests.ps1` exécutent les validations.
Les tests du gestionnaire utilisent un faux jeu et des archives temporaires.

## Publication

Publier les assets SDK et TCO dans leurs nouvelles releases, puis modifier
`catalog.json` avec leurs URL, versions, tailles et empreintes exactes.
Ne pas remplacer un asset d'une ancienne version. Préparer tous les fichiers en brouillon, puis publier la release complète. Publier le manifeste de
chaque updater en dernier. Voir `docs/catalogue.md` pour le format des projets.

Les sources sont publiques. Aucune licence générale du projet n'est accordée
par ce fichier ; les bibliothèques tierces conservent leurs licences respectives.

## Projet CLion indépendant

Profils Debug/Release et configurations Run/Debug : [guide CLion](docs/clion.md).

## Versions, changelog et notifications

- La version de référence est dans `VERSION`. Elle doit correspondre à `CMakeLists.txt` ou à `package.json` et son lockfile, selon le projet.
- Documenter les changements dans `CHANGELOG.md`, sous `[Unreleased]` pendant le développement, puis dans une section `## [X.Y.Z] - AAAA-MM-JJ` au moment de publier.
- Après une CI réussie, créer le tag `vX.Y.Z` sur le commit vérifié et publier sa release GitHub avec les notes de cette section (`python .woodpecker/check-release.py --notes`). Joindre les artefacts construits avec l'outillage habituel lorsqu'ils sont nécessaires.
- Les builds Woodpecker sont annoncés dans le salon Discord `1550478726557470791`. Seules les releases GitHub publiées, versionnées et avec des notes sont annoncées dans `1549088597594873907`. Un push ou un tag seul ne publie aucune annonce de mise à jour.
- La CI refuse les incohérences de versions et les tags sans changelog daté. Les releases en brouillon ne sont pas annoncées. Une correction des notes modifie l'annonce existante.

Woodpecker compile Windows x64 avec MinGW et exécute les tests CTest autonomes sous Wine. Cela ne remplace pas les essais dans le jeu ni la validation native Windows des installateurs et scripts PowerShell.

## Canaux de publication

**Stable** : `vX.Y.Z` (release normale). **Bêta** : `vX.Y.Z-beta.N`. **Alpha** : `vX.Y.Z-alpha.N` (ces deux dernières sont des prereleases GitHub). `N` commence à 1. Le Hub mémorise un canal par projet, stable par défaut, sans basculer vers un autre canal si aucune release n’existe. Un retour vers une version plus ancienne nécessite une installation manuelle.

`VERSION` et le manifeste portent la version complète ; la version CMake garde seulement `X.Y.Z`. Publier le ZIP et son `project.json` dans la **même release**, avec son changelog. Pour le Hub lui-même, publier l’installateur et `hub-latest.json`. Le manifeste donne la taille, le SHA-256, le dossier racine et les règles de compatibilité. Aucun catalogue central ne doit être modifié.

La politique est dans `release-channels.json`. Le contrôle `.woodpecker/check-release.py` refuse les autres canaux. Une release de test n’est jamais marquée comme dernière version stable.
