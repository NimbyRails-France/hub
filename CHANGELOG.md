# Changelog

## [Unreleased]

## [0.4.1-alpha.4] - 2026-09-28

- Logo du Hub partagé entre la fenêtre, la barre latérale, la zone de notification, les raccourcis et l'installateur Windows.
- Mods locaux : SDK utilisé pour compiler le paquet affiché dans la liste et dans sa fiche, avec le kit de la prochaine compilation et le SDK actif dans le jeu.
- Page SDK : téléchargement vérifié des kits Kotlin publiés et sélection automatique du kit pour les compilations locales.
- Construction d'un projet SDK Windows depuis ses sources : lecture de `VERSION`, tests, paquet local et kit Kotlin préparés ensemble.
- Recompilation demandée aux mods après un changement de kit ; vérification que leurs DLL SDK correspondent au runtime sélectionné, même à version identique.
- Préparation des paquets dans un dossier neuf possible avec le jeu ouvert ; activation du profil toujours effectuée jeu fermé.

## [0.4.1-alpha.3] - 2026-09-27

### Distribution sur le serveur NRF
- Catalogue, historique SDK et téléchargements depuis `https://releases.nimbyrails-france.fr`, sans appel à l'API GitHub ni jeton utilisateur.
- Canaux Stable/Bêta/Alpha conservés ; contrôle de la taille, de l'empreinte SHA-256 et de l'appartenance du fichier à sa version.
- Publications atomiques sur le VPS : les fichiers complets sont disponibles avant la mise à jour du catalogue.
- Historique des releases Windows transféré sur le serveur ; GitHub conservé temporairement pour permettre la mise à jour des anciens Hub.

## [0.4.1-alpha.2] - 2026-09-27

### Installation Windows
- Remplacement complet des fichiers du programme à chaque installation, y compris lors d'une réinstallation de la même version.
- Retrait des anciennes DLL Qt et des fichiers obsolètes ; restauration des composants manquants sans toucher aux paramètres, journaux ou projets externes.
- Sauvegarde transactionnelle, restauration après échec et récupération d'une installation interrompue.
- Refus de nettoyer un dossier étranger ou une jonction ; journal de migration persistant.
- Ajout de tests du véritable installateur sous Wine dans Woodpecker. Pour cette alpha, leur exécution est exceptionnellement désactivée à la demande du mainteneur ; compilation et empaquetage seulement.
- Métadonnées éditeur « NimbyRails France ». La signature Authenticode reste à configurer ; cette version n'annonce pas un éditeur certifié.

## [0.4.1-alpha.1] - 2026-09-27

### Windows alpha
- Nouveau Hub Kotlin avec profils Jouer/Développer, versions locales et catalogue par canal.
- Réparation vérifiée du chargeur SDK avec sauvegarde et reprise après interruption.
- Journaux centralisés et export ZIP incluant Windows, jeu, projets, DLL, versions et empreintes SHA-256.
- Installateur Inno Setup compatible avec les options de mise à jour des anciens Hub Windows.
- Sélectionner Alpha pour le Hub et séparément pour le SDK, le mod et le TCO. Les projets locaux restent protégés en mode développeur.
- Première recette en jeu de cette alpha encore à réaliser. Aucun paquet Linux.

## [0.4.0]

### Nouveautés
- Navigation séparée pour les mods, utilitaires, SDK, téléchargements et paramètres ; les chemins et fonctions locales apparaissent uniquement en mode développeur.
- Profils Jouer / Développer : sélection d'une origine par projet et d'un SDK de test, avec restauration du SDK et des mods habituels à la sortie du mode développeur.
- Compilation via le wrapper Gradle du projet, SDK Kotlin sélectionnable et préparation séparée ; aucun lancement silencieux d'un ancien résultat après un échec.
- Redémarrage de NIMBY Rails par fermeture normale, puis activation du profil ; aucun arrêt forcé du jeu.
- Le catalogue reste disponible en développement ; l'application automatique des mises à jour est suspendue et les versions locales sont protégées.
- Un paquet local peut être installé avec son manifeste et son archive ZIP, sans publication GitHub.
- L'interface est reconstruite avec Compose ; le projet Kotlin Multiplatform se développe dans IntelliJ avec Gradle.

### Compatibilité
- Reprise des installations et des réglages existants, y compris les canaux Stable, Bêta et Alpha de chaque projet.
- Conservation de l'installation Windows, du retour à la version précédente, des jonctions du jeu et des raccourcis.
- L'application Windows autonome inclut Java ; Qt et les outils C++ ne sont plus requis.

## [0.3.1] - 2026-09-18

### Améliorations
- Vérification renforcée des mises à jour avant leur mise à disposition.
- Présentation des nouveautés et des corrections plus claire dans les notes de version.

## [0.3.0] - 2026-09-18

### Nouveautés
- Choisissez indépendamment une version stable, bêta ou alpha pour chaque application et pour le Hub.
- Consultez les nouveautés de chaque version avant de l’installer.
- Les nouvelles versions disponibles sont désormais retrouvées directement auprès des projets officiels.

### Améliorations
- La version stable reste sélectionnée par défaut.
- Vous pouvez revenir manuellement à une version antérieure après confirmation.
- Les téléchargements sont vérifiés avant l’installation. Vos applications déjà installées restent visibles en cas d’indisponibilité du service.

## [0.2.3] - 2026-09-18

Hub 0.2.3 : installation, mise a jour, retour arriere et retrait des DLL de mods declares, affichage des projets locaux, prise en charge du SDK 0.7.2 et catalogue SFR. Reconnaissance des SDK recents installes par les anciens Hubs.

Mettre a jour le Hub, puis le SDK, avant d installer Signalisation francaise realiste. Validation : tests du Hub, gestionnaire et migration des installations anterieures.

## [0.2.2] - 2026-09-15

## Correction
- Ajout du raccourci Nimby TCO dans le menu Demarrer pour les installations gerees par le Hub.
- Raccourci maintenu lors des mises a jour et retours a la version precedente, et retire a la desinstallation.

Pour une installation TCO existante, reinstaller TCO depuis le Hub ou effectuer sa prochaine mise a jour cree le raccourci.

Validation : tests du gestionnaire (installation, mise a jour, rollback, raccourcis et desinstallation) et 2 tests CTest reussis.

## [0.2.1] - 2026-09-15

## Fenêtre et notifications

- Webhooks GitHub actifs pour les releases SDK, TCO et Hub, transmis au Hub par ntfy.
- Vérification directe des manifestes officiels, contrôles SHA-256 et compatibilité conservés.
- Notifications Windows : nouvelles versions des projets installés, opérations terminées, redémarrage du Hub disponible.
- Fermer la fenêtre masque le Hub dans la zone de notification. Quitter arrête réellement le programme.
- Réduction classique, plein écran avec F11, retour en fenêtre avec Échap.
- Relancer le raccourci restaure la fenêtre déjà ouverte.
- Reconnexion automatique et vérifications périodiques en secours.

Installer NRFHub-0.2.1-Setup.exe. Les réglages existants sont conservés.

Validation : deux tests CTest réussis, tests du gestionnaire réussis, trois pings GitHub reçus par le client, manifestes SDK/TCO vérifiés, installation Windows et lancement testés.

Correctif 0.2.1 : contourne le cache des redirections GitHub latest afin de détecter les versions immédiatement après un événement webhook. Une version locale plus récente que le manifeste est correctement affichée à jour.

## [0.2.0] - 2026-09-15

## Fenêtre et notifications

- Webhooks GitHub actifs pour les releases SDK, TCO et Hub, transmis au Hub par ntfy.
- Vérification directe des manifestes officiels, contrôles SHA-256 et compatibilité conservés.
- Notifications Windows : nouvelles versions des projets installés, opérations terminées, redémarrage du Hub disponible.
- Fermer la fenêtre masque le Hub dans la zone de notification. Quitter arrête réellement le programme.
- Réduction classique, plein écran avec F11, retour en fenêtre avec Échap.
- Relancer le raccourci restaure la fenêtre déjà ouverte.
- Reconnexion automatique et vérifications périodiques en secours.

Installer NRFHub-0.2.0-Setup.exe. Les réglages existants sont conservés.

Validation : deux tests CTest réussis, tests du gestionnaire réussis, trois pings GitHub reçus par le client, manifestes SDK/TCO vérifiés, installation Windows et lancement testés.

## [0.1.0] - 2026-09-15

Première version de NimbyRails France Hub pour Windows x64.

- Catalogue connecté aux releases SDK et TCO de l'organisation.
- Dossier d'installation choisi par projet ; SDK avec chargeur SDL et TCO avec sa DLL compatible.
- Contrôle du hash du jeu, des dépendances, des téléchargements et des archives.
- Mises à jour automatiques des projets installés lorsque le Hub est ouvert et que le jeu/TCO sont fermés.
- Sauvegarde de la version précédente, retour arrière et désinstallation.
- Prise en charge des mods natifs avec dossier personnalisé et jonction vers le dossier du jeu ; aucun mod n'est encore publié dans ce catalogue.
- Mise à jour du Hub à sa fermeture via hub-latest.json.

Pour commencer, télécharger NRFHub-0.1.0-Setup.exe. Une ancienne installation manuelle du chargeur SDK doit être retirée avec son installateur avant de l'adopter dans le Hub. Les installateurs sont actuellement non signés.
