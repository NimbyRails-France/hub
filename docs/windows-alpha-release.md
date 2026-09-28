# Publication Windows avec Woodpecker

Les builds, tests, paquets et publications sont exécutés en CI : Woodpecker sur le VPS,
ou GitHub Actions lorsque le VPS est indisponible.
Aucun binaire construit sur un poste de développement ne doit être téléversé en release.
Les agents Linux utilisent MinGW et Wine pour les DLL Windows ; les applications
Kotlin JVM embarquent un runtime Windows. Aucun paquet Linux n’est publié.

1. Mettre à jour VERSION et la section datée de CHANGELOG.md, ainsi que les
   versions et dépendances liées au produit.
2. Pousser les modifications sur `alpha` avec un sujet ordinaire pour valider
   la chaîne sans publication. Consulter les logs Woodpecker jusqu’au résultat final.
3. Après validation, pousser un commit `release X.Y.Z-alpha.N` sur `alpha`.
   Le publisher crée la release GitHub complète, son catalogue public, puis dépose
   les paquets vérifiés et les manifestes sur `releases.nimbyrails-france.fr`.
   Le catalogue serveur est remplacé après le dépôt complet des fichiers.
   Une erreur de compilation, de test ou de packaging bloque la publication.
4. Vérifier les fichiers sur le serveur et leur découverte par le canal Alpha du Hub.

Les tests Windows exécutés sous Wine ne remplacent pas une recette en situation
réelle sur Windows dans le jeu. Cette recette reste à effectuer par le mainteneur.
Les branches stables et les sauvegardes de jeu ne sont pas modifiées par cette procédure.

Les versions des outils sont verrouillées dans `.woodpecker/toolchains.json`
du SDK avec leurs SHA-256 amont. Les projets consommateurs référencent le SDK
par commit exact dans `.woodpecker/sdk-revision.txt`. Après une modification
des outils partagés, mettre à jour cette référence et attendre leur validation CI.

## Détection des versions et secours GitHub

La publication utilise `.woodpecker/github-release.py` et le secret Woodpecker
`nrf_release_token`. Le token n'entre ni dans les paquets ni dans le Hub. Une
release est d'abord préparée en brouillon, avec tous les fichiers vérifiés, puis
rendue publique. Les fichiers d'une version publiée ne sont jamais remplacés.
Une relance reprend un catalogue manquant sans téléverser à nouveau les binaires.

Chaque dépôt expose aussi une release technique `catalogue` avec `releases.json`.
Elle recense les versions publiées et les empreintes de leurs fichiers. Son tag
non numérique l'exclut des versions installables. Le Hub vérifie le catalogue
public toutes les minutes ; il n'a pas besoin de token ni d'appels réguliers
à l'API GitHub. Ce fichier est mis à jour après chaque publication réussie.

Le miroir NRF vient ensuite ; une interruption de ce miroir n'annule pas une
release GitHub déjà complète. Relancer la même publication permet de reprendre
le dépôt serveur sans remplacer les fichiers GitHub. Activer `time-change` et
`signal-placement` dans Woodpecker avec le même secret. Aucun déploiement ni
publication n'est effectué par les tests locaux du publisher.

## Publication pendant une indisponibilité du VPS

Ajouter le trailer `Release-Runner: github` au commit `release X.Y.Z-alpha.N`.
Le workflow `.github/workflows/windows-release.yml` construit et teste les paquets
Windows dans un runner GitHub jetable, puis publie la release et le catalogue du
Hub avec le `GITHUB_TOKEN` éphémère. Aucun token personnel à installer ou distribuer.
Woodpecker ignore ce commit pour éviter une seconde construction/publication.
Sans ce trailer, la chaîne habituelle Woodpecker reste responsable de la release.
Les sources SDK sont épinglées par commit pour chaque consommateur ; publier le
SDK avant les mods qui utilisent son nouveau kit. Les archives et le plan de
release sont conservés sept jours dans les artefacts Actions pour diagnostic.
Les tests Windows sous Wine ne remplacent pas une validation dans le jeu.
