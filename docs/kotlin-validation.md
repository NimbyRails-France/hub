# Validation de la migration Kotlin — 21 septembre 2026

Version préparée : **0.4.0**, non publiée et non installée dans le profil utilisateur.
La branche locale a été avancée de `4a81347` vers `c283106` (Hub publié 0.3.1),
puis la migration a repris les canaux de publication et la découverte des releases.
Les changements Kotlin restent dans l'arbre de travail, sans commit ni push.

## Refonte des profils et de l'interface — vérification complémentaire

`gradlew build createDistributable` réussit après la refonte : **31 tests Kotlin**
et **7 groupes de scénarios Windows**, sans échec. La distribution courante est
`build/gradle/compose/binaries/main/app/NRFHub/NRFHub.exe`. L'installateur dans
`dist/` issu de la migration précédente n'a pas été régénéré pour cette refonte.

Les nouveaux tests couvrent le choix d'un SDK différent pour Développer,
l'activation puis la restauration du SDK et des jonctions, le retour au mode
normal avant de masquer les options locales, les échecs de SDK/jonctions et
la protection d'un SDK installé hors du Hub. Le redémarrage est testé avec un
adaptateur de processus simulé : fermeture normale, refus de fermeture et
absence de nouvelle instance après refus.

Un projet Gradle de test compile réellement un paquet déclaré via son wrapper.
Les empreintes des sources et du SDK, l'échec après un résultat prêt, les chemins
hors projet et la sauvegarde des anciens réglages sont vérifiés. Les scénarios
Windows confirment qu'une préparation locale ne crée aucune jonction du jeu et
n'appelle pas le proxy SDK.

Les tests Compose vérifient la navigation, l'absence de chemins locaux hors mode
développeur, la rubrique SDK séparée des utilitaires et le bouton de redémarrage.
Captures sous `build/gradle/reports/ui/` : `mods.png`, `settings-development.png`
et `sdk-development.png` ; les deux dernières ont été inspectées visuellement.

Ces vérifications n'ont ni installé de mod dans le jeu réel, ni remplacé son SDK,
ni fermé une partie réelle. L'isolation des sauvegardes n'est pas implémentée :
le Hub signale les données partagées avant le premier lancement de développement.
Les anciens paquets préparés restent conservés, sans nettoyage automatique.

## Vérifications de la migration initiale sur Windows

- Build Gradle Kotlin Multiplatform / Compose et génération de l'application autonome avec Java inclus.
- **15 tests Kotlin**, sans échec : validation, compatibilité SDK, versions et canaux, persistance,
  extraction, liens et doublons, réponse et téléchargement tardifs après activation du mode développeur,
  démarrage sans accès réseau dans ce mode, interface Compose.
- Capture de l'interface générée par le test Compose et vérifiée :
  `build/gradle/reports/ui/developer-mode.png`.
- **6 groupes de scénarios Windows** avec faux jeu : installation/mise à jour/retour arrière/désinstallation,
  raccourci TCO, intégrité et compatibilité, jonctions de mod et NRF Loader, reprise des anciennes
  installations, refus d'archives dangereuses, restauration après échec du chargeur SDK,
  préservation des données étrangères derrière une jonction.
- Vérification réseau en lecture seule : découverte de trois projets officiels, validation des
  releases et du manifeste du Hub. Aucun paquet de projet installé.
- Exécution de la CLI de l'application empaquetée avec son runtime Java intégré.
- Contrôle VERSION / changelog / politique de publication : mode « checks only », sans publication.
- Production de `dist/NRFHub-0.4.0-Setup.exe`, de `hub-latest.json` et de `SHA256SUMS.txt`.

Les rapports JUnit/HTML sont sous `build/gradle/test-results/desktopTest` et
`build/gradle/reports/tests/desktopTest`. Les traces d'installation utilisent des
dossiers `build/manager-test-*`. Les logs de migration sont sous `build/kotlin-*.log`.

## Reprise et limites

Les profils 0.2/0.3 et leurs canaux utilisent le même dossier. Les fichiers sources
C/C++ et CMake du Hub ont été retirés. L'adaptateur système PowerShell reste limité
aux jonctions, raccourcis et processus ; la logique des installations est en Kotlin.
Le script d'installation locale du mod a été adapté au Hub Kotlin et active sa
protection développeur ; il n'a pas été exécuté contre le jeu pendant cette migration.

L'installateur est construit, mais n'a pas remplacé le Hub installé. Aucun essai
en jeu, passage complet manuel par la zone de notification, ou mesure de performance
comparative n'est revendiqué. L'ouverture du projet dans IntelliJ n'a pas été testée
manuellement. Cette validation initiale ne couvrait pas Linux/macOS ; les essais
Linux ultérieurs sont détaillés ci-dessous. Les fonctionnalités d'installation
des mods natifs Linux restent incomplètes.

La sauvegarde du code initial est conservée dans
`build/kotlin-migration-baseline/hub-before-kotlin.zip`. `clean` est limité à
`build/gradle` et ne supprime pas cette sauvegarde ni les anciennes captures.

## Extension Linux — 21 septembre 2026

- 34 tests `desktopTest` passent sous Windows et Ubuntu WSL, dont le refus d'une
  bibliothèque `.dll` dans un manifeste Linux et la préservation des cibles de
  liens symboliques. Le scénario de liens Unix n'est exécuté que sous Unix.
- Paquets DEB et RPM construits localement ; DEB installé dans Ubuntu et
  application lancée avec un dossier de données de test isolé.
- Le DEB crée `/usr/share/desktop-directories` avant le script d'installation
  jpackage, nécessaire sur l'environnement WSL minimal utilisé pour l'essai.
- Le workflow de paquets utilise Xvfb pour les tests d'interface Linux. Ce
  workflow distant et le paquet macOS n'ont pas encore été exécutés.

Le lancement graphique du Hub ne valide pas l'installation d'un SDK Linux :
le chargeur, les marqueurs de propriété des profils et les kits de projets
locaux nécessitent encore la fin du portage. Le lancement Steam standard du
jeu sous WSL conserve son problème de conteneur ; le raccourci natif local
`NIMBY Rails Ubuntu RTX` sert actuellement aux essais du jeu.
