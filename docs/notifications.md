# Notifications et fenêtre — Hub Kotlin 0.4

La croix masque la fenêtre si une zone de notification est disponible. Cliquer
sur l'icône ou relancer le Hub restaure la fenêtre existante. Le bouton Réduire
reste disponible ; F11 bascule le plein écran, Échap restaure l'état précédent.
Le menu Fenêtre ou le menu de l'icône permet de quitter complètement le Hub.
Une transaction d'installation en cours doit se terminer avant de quitter.

Le mode développeur conserve le catalogue GitHub et le relais ntfy, mais suspend
l'application automatique des mises à jour. Les changements de mode invalident
les anciennes opérations réseau ; leurs résultats tardifs ne peuvent pas
remplacer une sélection locale. Les paquets de développement ont des destinations
séparées et ne sont jamais ciblés par une mise à jour distante.

Le Hub découvre les dépôts officiels puis leurs releases,
au démarrage et toutes les 15 minutes. Il sélectionne le canal enregistré pour
chaque projet et pour le Hub, sans catalogue central ni basculement entre canaux.
Le flux HTTPS `https://ntfy.sh/nrf-hub-releases-v1-67e49b30/json` déclenche aussi
une vérification, au plus une fois toutes les cinq minutes. Après une déconnexion, le délai
augmente de 2 secondes à 5 minutes. Les vérifications reportées pendant une
opération sont reprises par le contrôle périodique.

Le sujet ntfy est public et le relais voit l'adresse IP du client. Ses événements
ne sont pas authentifiés et ne fournissent jamais d'instructions d'installation :
le Hub ne télécharge que les URL issues de manifestes officiels validés.
Les tailles, SHA-256, chemins et versions restent contrôlés.

Une mise à jour du Hub est téléchargée seulement pour l'application Windows
empaquetée et si les mises à jour automatiques sont actives. Elle est appliquée
à la sortie ou avec Redémarrer. Le mode développeur bloque aussi une mise à jour
déjà prête. Aucun service ni démarrage automatique Windows n'est ajouté.

## Tests

`gradlew desktopTest` couvre la politique du mode développeur, l'ignorance d'une
réponse tardive, la persistance et les contrôles de l'interface Compose.
Après `gradlew prepareRuntime`, la CLI `fr.nimby.hub.MainKt --network-test` vérifie
les vrais manifestes GitHub, sans installation ni abonnement durable au relais.
Les scénarios Windows du gestionnaire utilisent exclusivement des dossiers de test.
