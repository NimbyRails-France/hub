# NimbyRails France Hub

Gestionnaire Windows x64 des projets **NimbyRails-France** : SDK, TCO et mods
natifs publiés dans le catalogue. Version 0.2.2.

## Installer

Télécharger `NRFHub-0.2.2-Setup.exe` dans les
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
  Les projets locaux absents du catalogue sont affichés comme non publiés.

Une ancienne installation du SDK effectuée hors du Hub doit être retirée avec
son installateur original avant la première installation gérée. Le Hub ne
s'approprie pas un dossier existant. Fermer le jeu et le TCO avant installation.

## Mises à jour automatiques

Le catalogue public `catalog.json` référence les assets des releases des
dépôts [sdk](https://github.com/NimbyRails-France/sdk),
[tco](https://github.com/NimbyRails-France/tco) et de ce dépôt.
Le Hub reçoit les événements des releases par webhook GitHub via ntfy, puis vérifie les manifestes officiels. Il consulte aussi le catalogue au démarrage et toutes les 15 minutes.
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

Le Hub lui-même utilise `hub-latest.json` : téléchargement automatique, contrôle
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
Ne pas remplacer un asset d'une ancienne version. Publier le manifeste de
chaque updater en dernier. Voir `docs/catalogue.md` pour le format des projets.

Les sources sont publiques. Aucune licence générale du projet n'est accordée
par ce fichier ; les bibliothèques tierces conservent leurs licences respectives.

## Projet CLion indépendant

Profils Debug/Release et configurations Run/Debug : [guide CLion](docs/clion.md).
