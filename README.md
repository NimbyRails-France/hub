# NimbyRails France Hub

Hub **0.4.0** en Kotlin Multiplatform et Compose : catalogue, SDK, TCO et mods
NimbyRails-France. Le projet s'ouvre dans IntelliJ IDEA et se compile avec Gradle.

## Développer

JDK 21 installé, wrapper Gradle fourni. Aucun Qt, CMake ou compilateur C++ requis.

```powershell
.\gradlew.bat run
.\gradlew.bat build
.\gradlew.bat createDistributable
```

Guide : [IntelliJ, architecture et mode développeur](docs/kotlin-intellij.md).
L'application autonome inclut Java. `package.ps1` produit l'installateur Windows
Inno Setup dans `dist/` ; il conserve l'identité d'installation du Hub 0.2.
Les projets sont découverts directement dans les releases officielles GitHub.
Chaque projet et le Hub conservent leur choix Stable, Bêta ou Alpha ; il n'y a
pas de basculement implicite vers un autre canal.

## Mode développeur

Les rubriques **Mods**, **Utilitaires**, **SDK**, **Téléchargements** et
**Paramètres** séparent la bibliothèque, les outils, les opérations et les chemins.
Activer **Mode développeur** dans les paramètres révèle les projets locaux et
le choix **Jouer / Développer**. Chaque mod peut utiliser sa version habituelle
ou un projet local ; le SDK de test peut être une autre version publiée ou un
paquet local. Le SDK et les mods habituels sont restaurés à la désactivation,
jeu fermé. Les sources et installations habituelles restent intactes.

Le Hub appelle les tâches Gradle du projet avec le kit SDK choisi, puis prépare
une installation de développement distincte. Une compilation échouée bloque
le lancement de l'ancien résultat. Le catalogue reste consultable ; l'application
automatique des mises à jour est suspendue en mode développeur.
**Importer un paquet local** accepte un `project.json` et son ZIP validés.

**Redémarrer NIMBY Rails** demande au jeu de se fermer normalement avant de
basculer le profil et de le relancer, sans arrêt forcé. Les sauvegardes et
réglages globaux du jeu restent partagés : utiliser une copie de partie pour
les essais. Les anciens paquets de développement sont conservés sur disque.

## Installer des projets

Choisir le dossier de `NIMBYRails.exe`, puis installer le SDK avant les mods/TCO.
Le Hub demande le dossier parent de chaque nouveau projet. Fermer le jeu, le TCO
et le chargeur avant de modifier une installation.

- **SDK** : kit et chargeur SDL, avec sauvegarde de la SDL originale par l'installateur du SDK.
- **TCO** : exécutable et SDK compatible, raccourci dans le menu Démarrer.
- **Mods** : dossier choisi, jonctions vers les mods du jeu et `NRFMods` pour les modules `loaderApi: 1`.
- **Paquets locaux** : affichés même lorsqu'ils ne sont pas publiés dans le catalogue.

Les téléchargements sont vérifiés par taille et SHA-256. L'extraction rejette les
chemins dangereux, doublons et liens. Les dossiers non gérés sont protégés.
La version précédente est conservée ; la restaurer suspend les mises à jour
automatiques. Conserver les données personnelles hors des fichiers de distribution.

Les anciens profils `%LOCALAPPDATA%/NimbyRailsFrance/NRFHub/settings.json` et les
registres `.nrf-project.json` sont repris. Un profil corrompu n'est pas écrasé.
Un SDK installé hors du Hub doit être retiré avec son installateur original avant
une première installation gérée.

## Synchronisation et bureau

Le catalogue et les releases sont contrôlés au démarrage, toutes
les 15 minutes et sur signal du relais ntfy. Les événements du relais ne sont que
des demandes de vérification : seuls les manifestes officiels sont utilisés.
Hors mode développeur, les projets installés sont mis à jour si l'option automatique est active et si
jeu, processus et dépendances sont compatibles. La mise à jour du Hub empaqueté
est vérifiée puis appliquée à la sortie, ou avec le bouton de redémarrage.

La croix masque la fenêtre si une zone de notification est disponible. Le menu
**Quitter le Hub** arrête réellement le programme. F11 bascule le plein écran,
Échap le quitte. Aucun service ou démarrage automatique Windows n'est installé.
Voir [notifications](docs/notifications.md) et [format du catalogue](docs/catalogue.md).

## Plateformes et vérification

Interface Compose et règles communes portables sur les bureaux Windows/Linux/macOS.
L'installation du SDK et des mods est propre à Windows. Cette migration est
validée sur Windows ; aucune prise en charge mobile ou Web n'est annoncée.

`gradlew build` lance les tests Kotlin et, sur Windows, les scénarios d'installation
avec faux jeu. Les tests ne modifient pas une installation réelle de NIMBY Rails.
Voir [validation de la migration](docs/kotlin-validation.md).

Les sources sont publiques. Aucune licence générale du projet n'est accordée
par ce fichier ; les bibliothèques tierces conservent leurs licences respectives.

## Intégration continue et publication

`VERSION` alimente Gradle et l'installateur ; le contrôle de release vérifie aussi
la version du programme et `CHANGELOG.md`. La CI Windows GitHub Actions lance
les tests, génère l'installateur et conserve les artefacts. Woodpecker effectue
les tests portables sous Linux. L'ancien cross-build Qt/MinGW a été retiré.

La publication conserve la règle du commit `release X.Y.Z`, sur la branche du
canal correspondant, avec des notes datées. Après un build Windows réussi, la
release est préparée en brouillon avec tous ses fichiers, puis publiée. Un commit
ordinaire ne publie rien. Aucun fichier d'une version déjà publiée n'est remplacé.
