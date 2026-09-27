# Hébergement HTTPS des releases

Le VPS `188.245.109.137` utilise son Caddy existant pour les certificats publics
et les redirections HTTP vers HTTPS. Les deux blocs de `public-sites.caddy`
s'ajoutent à sa configuration, sans remplacer les autres sites.

Le service interne de `compose.yaml` expose uniquement son port 8080 sur le
réseau Docker `proxy`. Aucun nouveau port public n'est ouvert. Les fichiers
publics sont dans `/opt/docker/nimbyrailsfrance-distribution/public`, montés en
lecture seule dans le serveur. Ne jamais y mettre de secrets, sauvegardes ou
paquets en cours de construction. Préparer les publications hors de ce dossier
avant de les rendre visibles atomiquement.

`nimbyrails-france.fr` affiche une réponse temporaire en attendant le site.
`releases.nimbyrails-france.fr` sert les fichiers ; `/healthz` contrôle seulement
la disponibilité HTTP. Un catalogue absent reste absent : la page d'accueil
ne signifie pas que les publications ou le Hub ont déjà été migrés.

Les certificats sont persistés dans le volume du Caddy public existant. Les
logs du service de fichiers passent par Docker avec rotation (3 × 10 Mo).

Validation/rechargement du Caddy public : `caddy validate` puis `caddy reload`,
avec sauvegarde du Caddyfile avant modification. Ne pas redémarrer toute la
passerelle pour ajouter les sites. Vérifier le certificat, le nom DNS et la
redirection HTTP de chaque domaine après rechargement.
