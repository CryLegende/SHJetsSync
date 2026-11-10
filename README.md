<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHJetsSync

*Temps de jeu de JetsAntiAFKPro partagé entre les serveurs Opale du réseau*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

JetsAntiAFKPro (Jet315, sans dépôt public) compte le temps de jeu. En MySQL, il lit le temps d'un joueur 400 ms après son arrivée et l'écrit à son départ, sans rien attendre. Avec deux serveurs Opale, Velocity connecte le serveur d'arrivée avant de fermer celui de départ. Le serveur d'arrivée pouvait donc lire avant l'écriture de l'autre, repartir de la dernière sauvegarde automatique (jusqu'à 600 s plus tôt), puis écraser la bonne valeur. Un palier `playtime:` situé dans l'intervalle perdu était alors rejoué.

SHJetsSync règle ce passage sans modifier JetsAntiAFKPro. Le jar d'origine reste en place, lu par réflexion (version 3.8.x).

Ce plugin n'a ni commande ni permission. Il se pose sur chaque serveur Opale du réseau, avec SHNetwork, quand JetsAntiAFKPro est en MySQL. Sur un serveur seul, on ne le pose pas.

## Fonctionnement

- **Départ.** Juste avant que JetsAntiAFKPro retire la fiche du joueur (PlayerQuitEvent, LOWEST), SHJetsSync la garde. La tâche de sauvegarde de SHNetwork (`registerSaveTask`) écrit ce temps dans `player_afk_data`, la table de JetsAntiAFKPro. JetsAntiAFKPro écrit la même valeur de son côté.
- **Arrivée.** La connexion n'attend pas le serveur de départ : Velocity ne ferme l'ancienne connexion qu'une fois la nouvelle établie, ce serveur ne sauvegarde donc le joueur qu'après la connexion, et une attente dans AsyncPlayerPreLoginEvent durerait jusqu'au délai maximal. SHJetsSync suit la barrière de passage (`awaitSaved`, au plus `attente-passage-secondes`) sans l'attendre. Dès qu'elle est passée, il relit le temps en base et remplace la fiche de JetsAntiAFKPro si elle a été lue avant et se trouve en retard (elle n'est jamais abaissée).
- **Fiche périmée.** Un joueur reparti avant la fin de sa lecture laissait une fiche dans la mémoire de JetsAntiAFKPro (le cas d'un rebond vers le serveur du spawn). À sa connexion suivante, elle servait jusqu'à la nouvelle lecture, et elle était écrite telle quelle s'il repartait aussitôt. Elle est retirée avant que JetsAntiAFKPro ne s'en serve.
- **Lecture ratée.** JetsAntiAFKPro partage une seule connexion entre ses fils, sans verrou. Aux arrivées en vague, une lecture échoue (« FAILED TO QUERY DATABASE ») : la fiche reste à -1, et la session n'est ni comptée ni sauvée. `verification-ticks` après la connexion, SHJetsSync relit le temps et remplace la fiche.
- **Base.** Celle de JetsAntiAFKPro (`database.mysql` de son config.yml), avec un pool de connexions à part : la connexion de JetsAntiAFKPro n'est jamais touchée. Aucune requête sur le fil principal.

## Ce qui reste

- **Arrêt brutal** (kill -9) : jusqu'à `auto_save_seconds` perdus, comme sur un serveur seul. JetsAntiAFKPro ne descend pas sous 60 s.
- **`/playtime set` et `/playtime add`** visant un joueur de l'autre serveur. `set` écrit la base, puis la valeur est écrasée au prochain enregistrement du serveur du joueur. `add` exige le joueur connecté sur ce serveur. En jeu, SHNetwork peut les relayer (`commandes-distantes`, comportement `relais`). En console, il faut les lancer sur le serveur du joueur.
- **Classement** (`%jetsantiafkpro_top_N...%`) : relu toutes les 600 s par serveur. Le temps d'un joueur connecté ailleurs date de son dernier enregistrement.

## Configuration

<a href="src/main/resources/config.yml">config.yml</a> : `attente-passage-secondes`, `verification-ticks`, `base-de-donnees.delai-ms`. Aucun identifiant.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
