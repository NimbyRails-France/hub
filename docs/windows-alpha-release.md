# Publication Windows avec Woodpecker

Les builds, tests, paquets et publications sont ex?cut?s sur le VPS par Woodpecker.
Aucun binaire construit sur un poste de d?veloppement ne doit ?tre t?l?vers? en release.
Les agents Linux utilisent MinGW et Wine pour les DLL Windows ; les applications
Kotlin JVM embarquent un runtime Windows. Aucun paquet Linux n?est publi?.

1. Mettre ? jour VERSION et la section dat?e de CHANGELOG.md, ainsi que les
   versions et d?pendances li?es au produit.
2. Pousser les modifications sur `alpha` avec un sujet ordinaire pour valider
   la cha?ne sans publication. Consulter les logs Woodpecker jusqu?au r?sultat final.
3. Apr?s validation, pousser un commit `release X.Y.Z-alpha.N` sur `alpha`.
   Le publisher du VPS cr?e la pr?release, ses paquets et les manifestes Hub.
   Une erreur de compilation, de test ou de packaging bloque la publication.
4. V?rifier les assets de la pr?release et leur d?couverte par le canal Alpha du Hub.

Les tests Windows ex?cut?s sous Wine ne remplacent pas une recette en situation
r?elle sur Windows dans le jeu. Cette recette reste ? effectuer par le mainteneur.
Les branches stables et les sauvegardes de jeu ne sont pas modifi?es par cette proc?dure.
