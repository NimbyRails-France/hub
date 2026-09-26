# Publication Windows avec Woodpecker

Les builds, tests, paquets et publications sont exécutés sur le VPS par Woodpecker.
Aucun binaire construit sur un poste de développement ne doit être téléversé en release.
Les agents Linux utilisent MinGW et Wine pour les DLL Windows ; les applications
Kotlin JVM embarquent un runtime Windows. Aucun paquet Linux n’est publié.

1. Mettre à jour VERSION et la section datée de CHANGELOG.md, ainsi que les
   versions et dépendances liées au produit.
2. Pousser les modifications sur `alpha` avec un sujet ordinaire pour valider
   la chaîne sans publication. Consulter les logs Woodpecker jusqu’au résultat final.
3. Après validation, pousser un commit `release X.Y.Z-alpha.N` sur `alpha`.
   Le publisher du VPS crée la prérelease, ses paquets et les manifestes Hub.
   Une erreur de compilation, de test ou de packaging bloque la publication.
4. Vérifier les assets de la prérelease et leur découverte par le canal Alpha du Hub.

Les tests Windows exécutés sous Wine ne remplacent pas une recette en situation
réelle sur Windows dans le jeu. Cette recette reste à effectuer par le mainteneur.
Les branches stables et les sauvegardes de jeu ne sont pas modifiées par cette procédure.

Les versions des outils sont verrouillées dans `.woodpecker/toolchains.json`
du SDK avec leurs SHA-256 amont. Les projets consommateurs référencent le SDK
par commit exact dans `.woodpecker/sdk-revision.txt`. Après une modification
des outils partagés, mettre à jour cette référence et attendre leur validation CI.
