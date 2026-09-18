# Manifestes de releases NRF

Depuis le Hub 0.3.0, le catalogue central est abandonné. Chaque release installe ses propres fichiers à partir de son asset `project.json`. Le fichier historique `catalog.json` est conservé figé pour les anciens clients ; le workflow de synchronisation horaire a été retiré.
Un projet contient `id`, `name`, `kind` (`sdk`, `tco`, `native-mod`), `version`
au format `X.Y.Z`, `X.Y.Z-alpha.N` ou `X.Y.Z-beta.N`, `url`, `sha256`, `size`, `rootFolder`, `gameSha256`.
Le ZIP contient un unique dossier racine nommé `rootFolder`.
Les URL de projets doivent pointer vers des releases HTTPS de NimbyRails-France.

Le TCO déclare `sdkMin` et `sdkMaxExclusive`. Le SDK est installé en premier.
Un mod natif déclare `modId`, identique au nom de sa jonction dans le dossier
des mods. Son dossier contient `mod.txt`. La compatibilité native des scripts
doit être testée avant de publier leur hash de jeu dans le manifeste.

Un mod C++ conserve `kind: native-mod` et déclare `loaderApi: 1`, `sdkMin` et
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
son module C++ ; désactiver seulement ses textures dans le jeu ne le désactive pas.

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
