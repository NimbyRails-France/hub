# Notifications et fenêtre (Hub 0.2)

- La croix masque le Hub dans la zone de notification Windows. Les téléchargements et contrôles continuent.
- Le bouton Windows Réduire conserve le fonctionnement habituel de la barre des tâches.
- Cliquez sur l’icône NRF pour restaurer la fenêtre. Relancer le raccourci restaure également le Hub existant.
- Menu **Fenêtre > Plein écran / Fenêtre**, ou **F11**. **Échap** quitte le plein écran. La fenêtre revient à son état normal ou maximisé précédent.
- **Fenêtre > Quitter le Hub**, ou **Quitter** dans le menu de l’icône, arrête réellement le programme. Cette action attend la fin des opérations en cours.
- Quand une mise à jour du Hub est prête, cliquez sur **Redémarrer le Hub pour appliquer**. Masquer la fenêtre ne redémarre pas le programme.
- Les notifications Windows annoncent une nouvelle version d’un projet installé, la fin d’une opération et la mise à jour du Hub prête. Windows peut masquer ces notifications selon ses réglages.

## Livraison des événements

Les trois dépôts publics `NimbyRails-France/sdk`, `tco` et `hub` ont un webhook GitHub actif pour les événements `release` :

```
https://ntfy.sh/nrf-hub-releases-v1-67e49b30?template=yes&message=release&cache=no
```

Le relais ntfy transforme le JSON GitHub en un simple signal `release`. Le Hub maintient une connexion HTTPS sortante au flux JSON du même sujet. Aucun port entrant, compte ntfy ou service Windows supplémentaire n’est nécessaire.

Le relais reçoit les événements des dépôts publics et l’adresse IP des clients abonnés. Le sujet est public : **ses événements ne sont pas authentifiés et ne sont jamais des instructions d’installation**. Le Hub ignore tout contenu reçu, puis relit uniquement les manifestes des releases officielles sur GitHub. Les empreintes SHA-256, tailles et contraintes de compatibilité restent vérifiées avant installation.

Les SDK/TCO sont vérifiés directement via `releases/latest/download/project.json`, sans attendre la synchronisation horaire du catalogue. Le Hub utilise son propre manifeste `hub-latest.json`. Les mods restent dans le catalogue validé.

Les événements rapprochés sont regroupés avec au plus une vérification par minute. Une installation en cours reporte le contrôle. La connexion se rétablit automatiquement avec un délai croissant de 2 secondes à 5 minutes. Le contrôle périodique des projets toutes les 15 minutes et du Hub toutes les 6 heures reste actif en secours.

Le Hub doit être lancé, éventuellement masqué. **Quitter** arrête aussi la réception ; au prochain lancement, une vérification complète récupère les versions éventuellement manquées. Il n’est pas lancé automatiquement au démarrage Windows.

Références : [webhooks GitHub](https://docs.github.com/en/rest/repos/webhooks), [publication et modèles ntfy](https://docs.ntfy.sh/publish/#message-templating), [flux JSON ntfy](https://docs.ntfy.sh/subscribe/api/).

## Vérification

`ctest --test-dir build --output-on-failure` teste la validation du catalogue, le passage plein écran/normal/maximisé et la fermeture/restauration depuis la zone de notification.

Pour un test réel, lancer `NRFHub.exe --network-test`, puis envoyer un ping depuis les paramètres du webhook GitHub durant les 25 secondes du test. Le programme utilise un profil de test distinct et réussit uniquement s’il reçoit un événement du relais et valide les deux manifestes officiels SDK/TCO. Ce test dépend du réseau et ne fait pas partie des tests hors ligne.
