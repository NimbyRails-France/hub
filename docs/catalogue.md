# Manifestes de releases NRF

Depuis le Hub 0.3.0, le catalogue central est abandonné. Chaque release installe ses propres fichiers à partir de son asset `project.json`. Le fichier historique `catalog.json` est conservé figé pour les anciens clients ; le workflow de synchronisation horaire a été retiré.
Un projet contient `id`, `name`, `kind` (`sdk`, `tco`, `native-mod`), `version`
au format `X.Y.Z`, `X.Y.Z-alpha.N` ou `X.Y.Z-beta.N`, `url`, `sha256`, `size`, `rootFolder`, `gameSha256`.
Le ZIP contient un unique dossier racine nommé `rootFolder`.
Les URL de projets doivent pointer vers des releases HTTPS de NimbyRails-France.

## Étiquette à côté du nom

Le champ facultatif `developmentStatus` décrit l’état du projet dans le Hub :

```json
"developmentStatus": "in-development"
```

- `in-development` affiche **En cours de développement** (anglais : **In development**).
- `stable` affiche **Stable**.
- Sans ce champ, aucune étiquette n’est affichée. Un ancien Hub ignore le champ ;
  un Hub qui reçoit une valeur future inconnue n’affiche aucune étiquette.

Pour un mod Kotlin, ajoutez ce champ dans `mod.json`, puis reconstruisez le paquet
avec le kit SDK 0.9 qui le prend en charge. Le plugin le recopie dans `project.json`.
Les nouveaux projets créés par le Hub commencent avec `in-development`.
Pour le SDK et le TCO officiels, renseignez le même champ à la racine de
`release-channels.json` : leurs générateurs le recopient dans `project.json`.
Les autres générateurs de paquets peuvent le fournir directement dans leur
manifeste. Le statut est conservé avec l’installation, y compris hors ligne.

L’étiquette apparaît dans la bibliothèque et à côté du titre, dans les profils
Jouer et Développer. Elle ne change ni le nom, ni le canal de mises à jour, ni les
prérequis SDK. Une version `0.1.0` est stable pour la sélection des mises à jour ;
son étiquette dépend uniquement de `developmentStatus`. Modifier un fichier source
local ne modifie pas les manifestes déjà publiés.

**English:** Set the optional `developmentStatus` in your Kotlin mod’s `mod.json`
to `in-development` or `stable`, then rebuild with a supporting SDK 0.9 kit.
The generated `project.json` carries the status beside the project name in both
Hub profiles. Other package generators can include the same field directly in
their release manifest; the official SDK and TCO read it from `release-channels.json`.
Missing or unknown values show no badge. The status is
independent of the release channel and SDK requirements and survives offline use.

Le canal stable reçoit uniquement les versions stables. Les canaux alpha et
bêta choisissent la plus récente entre leur propre canal et les stables, parmi
les releases proposant un paquet pour la plateforme courante. Par exemple,
`2.0.0` remplace `2.0.0-beta.3`, mais pas `2.1.0-beta.1`. La préférence de canal
reste conservée pour les prochaines préversions ; le manifeste garde le canal
réel de la version distribuée. Cette règle s'applique aussi aux mises à jour du Hub.

Le TCO déclare `sdkMin` et `sdkMaxExclusive`. Le SDK est installé en premier.
Un mod natif déclare `modId`, identique au nom de sa jonction dans le dossier
des mods. Son dossier contient `mod.txt`. La compatibilité native des scripts
doit être testée avant de publier leur hash de jeu dans le manifeste.

Un mod chargé par le SDK, y compris un mod Kotlin/Native, conserve `kind: native-mod` et déclare `loaderApi: 1`, `sdkMin` et
`sdkMaxExclusive`. Le champ `module` nomme sa DLL, par exemple
`SignalisationFrancaiseRealisteMod.dll`, fournie à la racine. Le gestionnaire
valide ce nom et écrit `nrf-mod.ini` (`[NRFMod]`, `library=<module>`). Il crée
une seconde jonction `<jeu>/NRFMods/<id>` vers son dossier d'installation,
en plus de la jonction des ressources. Le paquet SDK compatible déclare aussi
`loaderApi: 1`. Les anciens SDK ne satisfont pas cette dépendance.
Les exports V1 sont `DWORD WINAPI NRFMod_StartV1(void*)` et
`DWORD WINAPI NRFMod_StopV1(void*)`, appelés avec `nullptr`, hors `DllMain`.
Start retourne 0 (initialisé) ou 4 (déjà initialisé), Stop retourne 0 (arrêté).
Les autres codes signalent un échec. Aucun objet C++ ne traverse cette ABI.
Le démarrage précède le chargement de la partie : ne pas supposer la simulation disponible.
Le module doit arrêter ses tâches avant de retourner de Stop. Les DLL restent
chargées jusqu'à la fermeture du jeu. Retirer un projet du Hub désactive aussi
son module ; désactiver seulement ses textures dans le jeu ne le désactive pas.

Le paquet SDK pour le Hub contient le kit à sa racine et `loader/` avec le
proxy SDL, le SDK, sa dépendance et le script `install-proxy.ps1`. Il ne contient
ni SDL originale, ni exécutable du jeu, ni sauvegarde.

Le paquet TCO contient l'EXE et ses dépendances à sa racine. Le gestionnaire
y crée `.nrf-project.json` avec l'identité, la version et le chemin choisi.
Seuls les dossiers portant cette identité peuvent être remplacés ou retirés.

`hub-latest.json` suit le format de l'updater (`schema`, `product:"NRFHub"`,
`platform:"windows-x64"`, `version`, `url` de l'installateur, `sha256`, `size`).
Les releases TCO utilisent le même format avec `product:"NimbyTco"`.

Le Hub vérifie que la version correspond au tag de la release, que le fichier est un asset de ce même dépôt et de ce même tag, que sa taille correspond et, lorsque GitHub fournit son digest, que son SHA-256 correspond aussi. Les tags de préversion doivent être publiés avec le statut GitHub prerelease. Aucun jeton GitHub n’est embarqué dans l’application.
