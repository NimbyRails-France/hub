# Développer le Hub en Kotlin

Le Hub 0.4 est un projet Kotlin Multiplatform + Compose Multiplatform. Ouvrir
`build.gradle.kts` dans IntelliJ IDEA, choisir le wrapper Gradle et un JDK 21 ou
22. Le compilateur cible Java 21. Le premier build télécharge les dépendances.
Qt, CMake et un compilateur C++ ne sont plus nécessaires.

```powershell
.\gradlew.bat run                 # application de bureau
.\gradlew.bat build               # compilation et tests
.\gradlew.bat prepareRuntime      # CLI compatible avec scripts/manage.ps1
.\gradlew.bat createDistributable # application autonome, Java inclus
powershell -ExecutionPolicy Bypass -File package.ps1 # installateur Windows
```

Les sorties se trouvent sous `build/gradle`. `clean` ne touche que ce dossier,
pas les anciens tests et sauvegardes conservés ailleurs sous `build`.

## Organisation

- `src/commonMain/.../model` : projets, réglages, profils, validation et politique des mises à jour.
- `src/commonMain/.../ui` : interface Compose commune.
- `src/desktopMain/.../HubController.kt` : coordination des opérations et état observable.
- `src/desktopMain/.../network` : catalogue, releases, téléchargements et relais ntfy.
- `src/desktopMain/.../install` : extraction, installation, compilation locale et activation des profils.
- `src/desktopMain/.../storage` : réglages atomiques et empreintes.
- `src/desktopMain/.../platform` : intégration du bureau et de Windows.
- `src/desktopMain/.../update` : mise à jour du Hub empaqueté.
- `src/commonTest` et `src/desktopTest` : tests Kotlin ; `tests/manage-tests.ps1` : scénarios Windows avec faux jeu.

La cible Gradle `desktop` utilise la JVM : l'interface et les règles communes
sont portables Windows/Linux/macOS. Les distributions se construisent sur leur
OS respectif. Seul Windows est validé dans cette migration ; l'installation du
SDK, du chargeur et des mods reste une fonctionnalité Windows. Android, iOS et
le Web ne sont pas des cibles de ce Hub de bureau.

Le code applicatif ne contient plus de C++. Un petit adaptateur PowerShell
embarqué gère les jonctions NTFS, les raccourcis COM et les processus du jeu.
Les décisions, transactions et validations sont en Kotlin. Le chargeur du SDK
conserve son propre `install-proxy.ps1`. Les scripts racine servent au build et
à l'empaquetage ; `scripts/manage.ps1` est un lanceur compatible de la CLI Kotlin.

## Interface et emplacements

La navigation sépare **Mods**, **Utilitaires**, **SDK**, **Téléchargements** et
**Paramètres**. Le SDK et son chargeur ont leur propre rubrique ; ils ne sont
pas classés parmi les utilitaires comme le TCO. Les journaux sont accessibles
dans Téléchargements, sans encombrer les fiches des projets.

Les paramètres définissent le dossier du jeu et les destinations des nouvelles
installations de mods, d'utilitaires et de SDK. Changer ces valeurs ne déplace
pas les projets déjà installés. Le mode développeur révèle les réglages suivants :

| Réglage | Rôle |
| --- | --- |
| Projets locaux de mods | Dossier de départ pour sélectionner un projet source |
| Projets locaux d'utilitaires | Dossier de départ pour sélectionner un outil local |
| Installation de développement | Paquets préparés et copies utilisés pendant les essais |
| Kit SDK Kotlin pour compiler | Dossier contenant `sdk.json`, `klib/`, `bridge/` et `bin/` |
| Exécutable IntelliJ IDEA | Programme utilisé par **Ouvrir dans IntelliJ** |

Les chemins locaux et commandes de compilation restent masqués tant que le mode
développeur est désactivé. Le dossier source ne devient jamais une destination
d'installation. Les destinations de développement sont vérifiées pour ne pas
recouvrir les sources, le kit SDK, le jeu ou les installations habituelles.

## Jouer et Développer

Activer le mode développeur rend disponible le sélecteur **Jouer / Développer**
et sélectionne Développer. Le SDK et les mods choisis sont activés lors de la
préparation du lancement, ou avec **Appliquer** lorsque la bascule est en attente.
Le profil demandé et le profil réellement actif sont affichés séparément.

| | Jouer | Développer |
| --- | --- | --- |
| Mods et outils | Installations habituelles | Version habituelle ou projet/paquet local pour chaque projet |
| SDK du jeu | SDK habituel | SDK habituel, autre version publiée ou paquet SDK local |
| Fichiers modifiables des distributions | Dossiers habituels | Copies séparées dans l'installation de développement |
| Mises à jour | Politique automatique habituelle | Catalogue disponible ; application automatique suspendue |
| Sauvegardes et réglages globaux du jeu | Données habituelles | Données encore partagées, signalées avant le premier lancement |

**Désactiver le mode développeur restaure automatiquement le SDK et les mods du
profil Jouer.** Tant que le jeu est ouvert, la sortie reste en attente et les
options locales restent visibles. Le Hub effectue la restauration une fois le
jeu fermé ; en cas d'échec, il affiche la raison et permet de réessayer.

Une seule version de chaque mod et un seul SDK sont activés à la fois. La
validation porte sur tous les mods sélectionnés et leurs contraintes SDK,
ainsi que sur l'unicité de `id` et `modId`. Un SDK convenant à un seul mod mais
incompatible avec un autre bloque le lancement avec la raison correspondante.

Les choix d'origine, la version du SDK de test, les chemins et les résultats
préparés persistent au redémarrage du Hub. Les canaux Stable/Bêta/Alpha restent
indépendants des profils. Pour mettre à jour ou retirer une installation
habituelle, revenir à Jouer ; les copies de développement ne sont jamais des
cibles de mise à jour distante.

## Choisir le SDK

La rubrique SDK distingue **SDK habituel**, **SDK actif** et **SDK pour les
essais**. **Choisir une autre version publiée** consulte les releases du canal
SDK sélectionné. **Préparer** télécharge et valide la version retenue dans le
dossier de développement sans remplacer le SDK habituel.

**Importer un SDK local** sélectionne le `project.json` de distribution et son
ZIP. Le paquet doit inclure le chargeur et `loader/install-proxy.ps1`, comme un
paquet SDK publié. Un dépôt C++ brut ou le seul kit Kotlin ne suffit pas à
installer un chargeur dans le jeu.

Le **kit SDK Kotlin pour compiler** est un chemin distinct : les projets Gradle
le reçoivent via `-PnrfSdkDir=<chemin>`. Il peut donc différer du SDK installé
pour jouer. Pour lancer un mod compilé par le Hub, la version de ce kit doit
correspondre à celle du SDK choisi pour les essais. Le Hub contrôle aussi les
contraintes du mod et le manifeste `sdk.json`.

## Ajouter et compiler un projet local

Dans Mods ou Utilitaires, **Ajouter un projet local** sélectionne le dossier
source. Pour un projet utilisant le plugin Gradle du SDK, le `mod.json` complet
fournit l'identité (`id`, `name`, `modId`, `module`), la version, `language`,
l'intervalle SDK et `gameSha256`. Le Hub peut l'ajouter avant sa première
compilation, sans archive ni manifeste de distribution préalable.
Les projets existants peuvent encore fournir `project.json`, `dist/project.json`
ou les métadonnées d'un manifeste connu du catalogue.
Le Hub conserve les contraintes du jeu et du SDK du manifeste, sans inventer
une compatibilité.

Le parcours Kotlin/Native déjà utilisé par Signalisation française réaliste
est reconnu : wrapper Gradle dans le projet, `language: kotlin-native` dans
`mod.json`, tâche `packageMod`, ZIP sous `build/gradle/distributions/` portant
le nom `<modId>-<version>-windows-x64.zip` pour le manifeste source complet.
Le plugin du SDK génère aussi `build/gradle/distributions/project.json` après
avoir créé et vérifié le paquet. Aucun script PowerShell du mod n'est requis.

Pour un autre projet Gradle, déclarer les chemins et la tâche dans
`hub-local.json`, à la racine du projet :

```json
{
  "manifest": "dist/project.json",
  "task": "packageMod",
  "archive": "build/gradle/distributions/MonMod-{version}-windows-x64.zip"
}
```

Les chemins sont relatifs au projet ; les sorties hors de ce dossier sont
refusées. Le manifeste de distribution reste nécessaire pour l'identité, le
dossier racine du ZIP et les compatibilités. La taille et le SHA-256 du paquet
sont recalculés après une compilation réussie. Un projet sans tâche Gradle
reste ouvrable dans son IDE : compiler avec son outillage puis utiliser
**Importer un paquet local**. Le Hub ne prétend pas compiler les projets
CMake/C++ du SDK ou du TCO via Gradle.

**Compiler** lance le wrapper Java du projet avec la tâche déclarée, sans
commande d'installation ni de publication ajoutée par le Hub. Un JDK accessible
via `JAVA_HOME` est requis dans l'application empaquetée. Les tâches du projet
restent les mêmes depuis IntelliJ ; le Hub ne remplace pas le SDK pour construire
le mod. Le journal Gradle est consultable dans Téléchargements.

Le Hub prépare le paquet dans un nouveau dossier puis valide son intégrité,
son contenu et sa compatibilité avec le jeu. Une compilation ou préparation
échouée invalide l'état prêt à tester : le lancement ne réutilise pas un ancien
binaire silencieusement. Les empreintes des sources et du kit SDK sont
revérifiées avant le lancement ; une modification impose de recompiler.

Retirer un projet du profil supprime son association, jamais son dépôt source.
Les anciens paquets et copies gérés restent conservés sur disque ; le nettoyage
automatique des anciennes préparations n'est pas encore fourni.

## Activation, restauration et redémarrage du jeu

L'activation change ensemble les jonctions des ressources, les jonctions
`<jeu>/NRFMods/<id>` et le proxy du SDK. Les distributions habituelles restent
intactes. Le Hub vérifie que le jeu et les processus concernés sont fermés,
refuse les jonctions étrangères et journalise l'opération. En cas d'échec, il
restaure le SDK et les jonctions précédents sans lancer le jeu.

Après une interruption nécessitant une récupération, les paramètres proposent
**Restaurer l'activation précédente**. Le journal reste conservé jusqu'à une
restauration réussie ; une installation étrangère bloque cette récupération
au lieu d'être écrasée.

Le bouton principal affiche **Lancer NIMBY Rails**, puis **Redémarrer NIMBY
Rails** lorsque le jeu est détecté. Après confirmation de sauvegarde, le
redémarrage demande une fermeture normale par la fenêtre du jeu, attend jusqu'à
deux minutes, active le profil puis relance l'exécutable. Il ne tue jamais le
processus. Si la fermeture est refusée ou expire, aucune bascule ni nouvelle
instance n'est lancée. Fermer les utilitaires utilisant le SDK avant la bascule.

Les fichiers de configuration contenus dans les distributions sont copiés pour
les essais. En revanche, les sauvegardes et réglages globaux du jeu restent
partagés : une confirmation l'indique au premier lancement de développement.
Utiliser une copie manuelle de la partie. Aucun mécanisme d'isolation complète
ou de copie automatique des sauvegardes n'est annoncé.

## Canaux et reprise

Windows réutilise `%LOCALAPPDATA%/NimbyRailsFrance/NRFHub/settings.json` et les
enregistrements `.nrf-project.json` des versions Qt. Les JSON avec BOM restent
lisibles. Un profil invalide bloque le démarrage sans l'écraser. La migration
sauvegarde les anciens réglages dans `settings.before-profiles.json` ; elle ne
déplace pas les installations. Les anciens profils développeur gardent une
protection contre les remplacements, à lever explicitement après vérification
des installations dans Paramètres.

La découverte interroge les dépôts officiels puis leurs releases paginées,
réutilise les réponses ETag et respecte les limites GitHub. Chaque release
fournit son manifeste et ses assets dans le même dépôt et le même tag. Aucune
préversion n'est choisie sur Stable.

Pour utiliser des réglages de Hub distincts :

```powershell
.\gradlew.bat run --args="--data-dir C:/Temp/NRFHub-test"
```

`--data-dir` isole les réglages du Hub, pas les fichiers du jeu ni ses parties.
Pour les tests d'installation, utiliser un faux jeu comme les scénarios de test
du dépôt. La mise à jour du Hub est réservée à l'application Windows empaquetée,
pas aux sessions IntelliJ/Gradle, et attend la fin des opérations en cours.

Compiler, importer, préparer et lancer depuis le Hub ne déclenchent aucune
publication. Les tâches déclarées par les projets doivent elles aussi être
limitées à la construction de leurs paquets.
