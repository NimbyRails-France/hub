# Installation et réparation Windows

La mise à jour automatique du Hub fonctionne dans les profils Jouer et
Développer, même lorsque les outils développeur sont activés. Elle respecte
le réglage général des mises à jour automatiques. Les protections du SDK et
des mods en développement restent indépendantes de la mise à jour du Hub.

L'installateur utilise la même procédure pour une installation neuve, une mise
à jour et une réinstallation de la même version. Il reconstruit les fichiers
du programme même si le lanceur, la JVM ou des dossiers ont été supprimés.

Le dossier du programme contient uniquement des fichiers remplaçables. Les
réglages sont dans `%LOCALAPPDATA%\NimbyRailsFrance\NRFHub`, les journaux dans
`%LOCALAPPDATA%\NimbyRailsFrance\logs`. Les projets et le jeu restent hors de
la transaction. Ne pas choisir un dossier de données comme destination.

Avant copie, les anciens composants sont déplacés dans le dossier voisin
`NimbyRailsFranceHub.nrf-rollback`. Le journal de transaction distingue une
sauvegarde partielle d'une sauvegarde terminée. Un échec restaure les fichiers
précédents ; après interruption du processus, relancer l'installateur reprend
cette récupération. Après réussite, la sauvegarde est supprimée. Les fichiers
`uninsNNN.*` sont gérés séparément par Inno Setup.

Le nettoyage refuse les liens/jonctions et les dossiers non vides qui ne sont
pas identifiés comme une installation du Hub. Les migrations sont consignées
dans `%LOCALAPPDATA%\NimbyRailsFrance\logs\hub\installer-migration.log`.
L'installateur conserve aussi le journal Inno dans ce même dossier :

- `installer-latest.log` : dernière tentative, à transmettre en cas d'échec ;
- `installer-VERSION-DATE-HEURE.log` : historique séparé de chaque tentative.

Ces copies contiennent les messages Inno, la version de l'installateur, Windows,
les chemins, l'espace disponible lorsqu'il est lisible, la progression, la
présence des composants et l'issue de la restauration. Elles sont actualisées
aux étapes principales puis juste avant la fermeture, y compris après un échec
ou une annulation. Les dernières lignes ajoutées par Inno après ce callback
restent dans le journal original de `%TEMP%` (ou du chemin `/LOG=...`), indiqué
dans chaque copie. Si l'installateur est tué brutalement, la dernière copie peut
être incomplète : récupérer aussi ce journal original.

In case of failure, send `%LOCALAPPDATA%\NimbyRailsFrance\logs\hub\installer-latest.log`.
Each attempt also keeps a separate timestamped log, including installation errors
and rollback diagnostics. The original Inno log path is recorded inside it.

Référence : [journal Inno et constante `{log}`](https://jrsoftware.org/ishelp/topic_consts.htm).

Woodpecker exécute le véritable installateur Windows sous Wine : installation
neuve, anciens fichiers Qt et inconnus, réinstallation, dossiers manquants,
dossier programme supprimé, reprise interrompue et refus d'un dossier étranger.
Les fichiers installés sont comparés au paquet neuf par SHA-256 et le profil
utilisateur témoin doit rester identique. Cela ne valide pas les pilotes
graphiques d'un PC Windows réel.

## DLL du SDK et récupération

Le contrôle du Hub et le script du SDK doivent être mis à jour ensemble pour
la réutilisation de DLL préexistantes. Les anciennes archives conservent leur
ancien script et peuvent encore refuser ces fichiers.

| Situation | Comportement |
| --- | --- |
| DLL absente | Le SDK l'installe et enregistre son empreinte. |
| DLL préexistante identique au paquet | Réutilisation ; le manifeste v3 la marque comme partagée. Elle reste après retrait ou réparation. |
| Ancienne DLL reconnue par empreinte | Sauvegarde sous `DLL.nrf-before-sdk`, remplacement et enregistrement dans `replacedFiles` ; restauration des octets précédents au retrait ou à la réparation. |
| DLL préexistante différente ou absente du paquet | Refus avec chemin et empreintes ; aucune suppression fondée sur le nom. |
| Mise à jour d'une DLL appartenant au SDK | Retrait vérifié de l'ancienne installation, installation du nouveau paquet ; le Hub restaure la distribution précédente en cas d'échec. |
| DLL appartenant au SDK disparue | Retrait/réparation possible si le manifeste et une SDL originale vérifiable sont disponibles. |
| SDL déjà restaurée par Steam | Réparation des restes enregistrés, sans exiger une seconde copie originale si la SDL actuelle est vérifiée. |
| Installation interrompue | Le manifeste provisoire écrit avant les copies permet à la réparation de vérifier les fichiers présents et les copies temporaires. |
| Réparation interrompue | Reprise du journal et des sauvegardes vérifiées hors du jeu. |
| Fichier modifié, jeu changé, sauvegarde invalide ou manifeste ambigu | Refus avant mutation ; diagnostic et conservation des fichiers. |

Le périmètre comprend la SDL proxy, la DLL SDK, pthread, les bridges Texture,
Signal UI, Automatic Driving, Construction, Clock et Metadata. La SDL originale
reste un cas distinct : elle n'est jamais remplacée par une DLL quelconque.
Les DLL étrangères au SDK ne sont pas supprimées.

La migration reconnue initialement est `libwinpthread-1.dll` d'empreinte
`1179c0c0ed77abb4aa92a14db97f369cdf364d810167d251b0dfe466db004c21`,
identique à la dépendance du paquet SDK 0.6.6. Le nom seul ne suffit pas.
Ajouter une empreinte à cette liste exige une identification préalable du fichier.

Une réparation rétablit un état permettant la réinstallation ; elle ne restaure
pas un fichier mis en quarantaine par l'antivirus et ne désactive pas sa protection.

## Éditeur et signature

`AppPublisher` et `VersionInfoCompany` identifient le produit comme
« NimbyRails France ». Ce sont des métadonnées, pas une preuve d'identité.
L'éditeur vérifié de Windows exige une signature Authenticode par une identité
validée. Le certificat/service et ses accès doivent être configurés dans la CI,
jamais committés dans le dépôt. Les paquets actuels restent non signés tant que
ce service n'est pas configuré. SmartScreen peut encore demander confirmation
pour un nouveau fichier même s'il porte une signature reconnue.

Références : [Inno : étapes de l'installation](https://jrsoftware.org/ishelp/topic_scriptevents.htm),
[Microsoft : réputation SmartScreen](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation).
