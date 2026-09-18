# Changelog

## [Unreleased]

- Les scripts de packaging utilisent la version du projet au lieu de numéros écrits en dur.

- Validation Woodpecker et contrôle de cohérence des versions et du changelog.

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
