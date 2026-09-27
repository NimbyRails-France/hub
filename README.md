# NimbyRails France Hub

Hub **0.4.1-alpha.3** en Kotlin Multiplatform et Compose : catalogue, SDK, TCO et mods
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
Les projets et paquets sont servis par `https://releases.nimbyrails-france.fr`.
Le Hub n'utilise plus l'API GitHub pour découvrir ou télécharger les versions.
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
les 15 minutes et lorsqu'une nouvelle génération du catalogue NRF est détectée
par une requête conditionnelle périodique. Seuls les manifestes officiels sont utilisés.
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
la version du programme et `CHANGELOG.md`. Woodpecker sur le VPS lance les tests,
compile Kotlin et produit l'installateur Windows avec un runtime Windows vérifié.
Les tests du paquet passent sous Wine ; la recette dans le jeu reste distincte.
Aucun paquet Linux n'est publié. Voir [la procédure alpha](docs/windows-alpha-release.md).

La publication conserve la règle du commit `release X.Y.Z`, sur la branche du
canal correspondant, avec des notes datées. Après un build Windows réussi, la
release est préparée en brouillon avec tous ses fichiers, puis publiée. Un commit
ordinaire ne publie rien. Aucun fichier d'une version déjà publiée n'est remplacé.


## Journaux et réparation Windows

Dans **Téléchargements → Ouvrir le dossier des journaux**, le Hub ouvre
`%LOCALAPPDATA%/NimbyRailsFrance/logs/hub`. `hub.log` conserve les messages,
les erreurs complètes (causes et échecs de restauration compris), les versions
et les chemins des installations. Chaque entrée est écrite en UTF-8 et le
fichier est fermé immédiatement. Les heures du fichier sont en UTC.
Le journal reste disponible après fermeture ; rotation à 2 Mio avec cinq
archives `hub.log.1` à `hub.log.5`. L'écran conserve les 500 derniers messages
de la session. Les journaux peuvent contenir des chemins personnels.
Le mode `--manage` écrit `manager.log` dans le même dossier `logs/hub`.
`--data-dir` déplace les réglages et sauvegardes de réparation ; les journaux restent dans la racine commune. `NRF_LOG_DIR` permet de changer cette racine pour tous les composants.

Si l'installation signale **Unexpected file contents: …/SDL3.dll**, ne pas
remplacer cette DLL manuellement. Elle peut être un chargeur déjà installé,
une DLL modifiée ou une autre version du jeu. Le nouveau contrôle affiche les
empreintes attendue et présente, ainsi que les fichiers de chargeur détectés.

Jeu fermé, utiliser **Paramètres → Réparer le chargeur SDK**. La réparation :

1. Valide le manifeste `NimbyRailsFranceSDK-install.json` (formats 1 et 2),
   l'exécutable du jeu, toutes les DLL déclarées et la SDL d'origine sauvegardée.
2. Copie tous les fichiers concernés dans `NRFHub/repairs/sdk-<identifiant>` et
   vérifie les empreintes des copies. Aucun script ni DLL de l'ancienne
   installation n'est exécuté pour réparer.
3. Enregistre `NRFHub/sdk-repair.json`, remplace SDL atomiquement par la copie
   d'origine reconnue, puis retire uniquement les fichiers du manifeste dont
   le contenu est toujours vérifié. Les fichiers étrangers restent intacts.
4. Conserve la sauvegarde et retire le journal de réparation après succès.
   Réappliquer ensuite le profil, même s'il était déjà sélectionné, ou
   réinstaller le SDK depuis le catalogue. Les réglages des profils sont conservés.

Si la réparation est interrompue (DLL verrouillée, fermeture du processus),
le journal reste présent : reprendre le même bouton après fermeture du jeu.
Les installations et activations restent bloquées tant que ce journal existe.
Si un fichier a changé entre-temps, la reprise refuse de l'écraser.
Un manifeste absent/incohérent, un ancien format non reconnu, une sauvegarde
invalide ou une version du jeu différente exigent un diagnostic avec le journal ;
il n'existe pas de bouton pour forcer une DLL inconnue.

`profile-activation.json` reste distinct : il permet de restaurer une bascule
Jouer/Développer interrompue. Restaurer cette activation avant de réparer SDL.
Si une ancienne distribution SDK est rangée dans le dossier du jeu, la réparation
conserve cette distribution ; pour les futures mises à jour, désinstaller cette
ancienne distribution via le Hub puis choisir un emplacement SDK hors du jeu.

Validation automatisée : `gradlew.bat check`. Les tests couvrent la rotation et
les accents du journal, les chaînes d'exceptions, le diagnostic SDL, la
réparation et sa reprise, les refus sans écriture, le retour arrière après échec
d'installation et la réactivation d'un même profil après réparation. Le jeu
n'est jamais lancé par ces tests.


## Logs de production

Le Hub propose **Téléchargements → Exporter les logs NRF** : un ZIP local
regroupe les journaux du Hub, du SDK/chargeur, des mods, du TCO et du banc,
ainsi qu'un résumé des versions. Aucun envoi automatique, aucune sauvegarde de
jeu ni fichier de réglages n'est inclus. Les logs peuvent contenir des chemins
personnels et des identifiants d'objets.

Les composants Windows écrivent sous `%LOCALAPPDATA%/NimbyRailsFrance/logs`,
chacun dans son dossier ; le Hub utilise `%LOCALAPPDATA%/NimbyRailsFrance/logs/hub`.
Rotation et regroupement des erreurs répétées limitent le volume. Le TCO et le
banc disposent aussi d'un bouton pour ouvrir leurs journaux.
Voir [le contrat de diagnostic](../sdk/docs/production-diagnostics.md) pour les
emplacements, la rétention, les tests et les limites en cas de crash natif.
