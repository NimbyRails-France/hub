# Hébergement HTTPS des releases

Le VPS `188.245.109.137` utilise son Caddy existant pour les certificats publics
et les redirections HTTP vers HTTPS. Les deux blocs de `public-sites.caddy`
s'ajoutent à sa configuration, sans remplacer les autres sites.

Le service interne de `compose.yaml` expose uniquement son port 8080 sur le
réseau Docker `proxy`. Aucun nouveau port public n'est ouvert. Les fichiers
publics sont dans `/opt/docker/nimbyrailsfrance-distribution/storage/public`, montés en
lecture seule dans le serveur. Ne jamais y mettre de secrets, sauvegardes ou
paquets en cours de construction. Préparer les publications hors de ce dossier
avant de les rendre visibles atomiquement.

`nimbyrails-france.fr` affiche une réponse temporaire en attendant le site.
`releases.nimbyrails-france.fr` sert les fichiers ; `/healthz` contrôle seulement
la disponibilité HTTP. Un catalogue absent reste absent : la page d'accueil
ne constitue pas une validation des paquets.

Le catalogue est `/v1/catalog.json` ; les fichiers immuables sont sous
`/releases/PROJET/vVERSION/`. Le publisher `.woodpecker/distribution.py` reçoit
uniquement les fichiers construits par la CI et leur plan SHA-256. Il sérialise
les publications et remplace le catalogue après le dépôt des fichiers. Son
volume inscriptible est limité à `storage`, séparé de la configuration Caddy.
Les lecteurs publics n'ont aucun jeton. Le Hub refuse les redirections et les
fichiers qui ne correspondent pas au projet/version annoncé.

Les pipelines publient encore sur GitHub pour la transition des anciens Hub,
puis déposent directement leurs paquets sur le serveur. Le Hub alpha.3 consulte
uniquement le serveur NRF. Les archives historiques sans manifeste restent
accessibles sur leur page mais ne sont pas proposées comme installables.

Les certificats sont persistés dans le volume du Caddy public existant. Les
logs du service de fichiers passent par Docker avec rotation (3 × 10 Mo).

Validation/rechargement du Caddy public : `caddy validate` puis `caddy reload`,
avec sauvegarde du Caddyfile avant modification. Ne pas redémarrer toute la
passerelle pour ajouter les sites. Vérifier le certificat, le nom DNS et la
redirection HTTP de chaque domaine après rechargement.
