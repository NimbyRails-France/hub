# Catalogue NRF — schéma 1

Le document contient `schema: 1`, `projects: []` et `hub` (release du Hub).
Un projet contient `id`, `name`, `kind` (`sdk`, `tco`, `native-mod`), `version`
au format x.y.z, `url`, `sha256`, `size`, `rootFolder`, `gameSha256`.
Le ZIP contient un unique dossier racine nommé `rootFolder`.
Les URL de projets doivent pointer vers des releases HTTPS de NimbyRails-France.

Le TCO déclare `sdkMin` et `sdkMaxExclusive`. Le SDK est installé en premier.
Un mod natif déclare `modId`, identique au nom de sa jonction dans le dossier
des mods. Son dossier contient `mod.txt`. La compatibilité native des scripts
doit être testée avant de publier leur hash de jeu dans le catalogue.

Le paquet SDK pour le Hub contient le kit à sa racine et `loader/` avec le
proxy SDL, le SDK, sa dépendance et le script `install-proxy.ps1`. Il ne contient
ni SDL originale, ni exécutable du jeu, ni sauvegarde.

Le paquet TCO contient l'EXE et ses dépendances à sa racine. Le gestionnaire
y crée `.nrf-project.json` avec l'identité, la version et le chemin choisi.
Seuls les dossiers portant cette identité peuvent être remplacés ou retirés.

`hub-latest.json` suit le format de l'updater (`schema`, `product:"NRFHub"`,
`platform:"windows-x64"`, `version`, `url` de l'installateur, `sha256`, `size`).
Les releases TCO utilisent le même format avec `product:"NimbyTco"`.
