# Validation 0.1.0

Document historique de la version native. Pour le Hub Kotlin 0.4, consulter
[la validation de migration](kotlin-validation.md).

- SDK : 13 tests natifs, dont ABI publique et vérification de version.
- TCO : contrats des mises à jour, rendu des signaux et invalidation ; SDK 0.5
  rejeté avec code 3, SDK 0.6 accepté avec code 0.
- Hub : validation du catalogue et tests du gestionnaire sur des archives locales.
- Gestionnaire : installation, mise à jour, retour arrière, désinstallation,
  hash invalide, jeu incompatible, dossier non géré, traversée de chemin ZIP,
  jonction d'un mod natif vers un dossier personnalisé et retrait de la jonction.

Ces essais n'écrivent pas dans une sauvegarde ni dans les fichiers du jeu réel.
Le chargement SDL conserve les contrôles du script d'installation du SDK ; le
Hub refuse une installation du SDK existante non gérée au lieu de l'écraser.
