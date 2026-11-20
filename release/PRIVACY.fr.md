# Politique de confidentialité de Titan

*En vigueur le 19 novembre 2026. Également disponible en [English](PRIVACY.md), [español](PRIVACY.es.md) et [português (Brasil)](PRIVACY.pt-BR.md). En cas de divergence, la version anglaise fait foi.*

Titan est un carnet d’entraînement pour Android. Il est gratuit, open source et développé
par des bénévoles. Cette politique couvre l’application Titan (`app.titan.gym`) telle
qu’elle est publiée sur Google Play et compilée depuis son dépôt source.

## En bref

- Titan **ne collecte rien**. Pas de compte, pas de statistiques d’usage, pas de rapports
  de plantage, pas de publicité.
- L’application **ne peut pas se connecter à Internet**. Elle ne demande pas la permission
  Android `INTERNET` : le système ne la laisse donc ouvrir aucune connexion réseau.
- Tout ce que vous enregistrez reste **sur votre téléphone**, dans le stockage privé de
  l’application.
- Vos données ne quittent le téléphone que lorsque **vous** les exportez, vers l’endroit que
  **vous** choisissez.
- Vous pouvez **tout supprimer** à tout moment depuis l’application.

## Ce que l’application enregistre, et où

Titan conserve sur votre appareil ce dont un carnet d’entraînement a besoin :

| Quoi | Exemples |
| --- | --- |
| Séances | séances, exercices, séries, charges, répétitions, effort (RPE), temps de repos, notes |
| Votre bibliothèque et vos modèles | exercices personnalisés, notes sur les exercices, modèles de séance |
| Données corporelles | poids, masse grasse, mensurations (tour de taille, bras, etc.) |
| Progression | records, points d’expérience, niveau, enchaînements, quêtes, succès, objets obtenus |
| Paramètres | unités, thème, objectif, niveau, minuteur de repos, jours et heure des rappels, barre et disques |

Ces données sont stockées dans la base de données et les fichiers de paramètres privés de
l’application, qu’Android ne laisse lire à aucune autre application.

Si vous activez la **Sauvegarde auto hebdomadaire** (Paramètres › Sauvegarde & restauration),
l’application écrit aussi, une fois par semaine, un fichier de sauvegarde dans son propre
dossier de sauvegardes, celui où écrit **Exporter une sauvegarde**. Elle garde les trois plus
récents et supprime les plus anciens. Sous Android 11 et versions ultérieures, qui
interdisent l’accès des autres applications à ce dossier, il se trouve dans le stockage
partagé de l’appareil (`Android/data/app.titan.gym/files/backups`). Sous Android 8 à 10, où
d’autres applications ayant accès au stockage pourraient lire cet emplacement, le dossier se
trouve plutôt dans le stockage interne privé de l’application, et une sauvegarde n’en sort
que lorsque vous utilisez **Partager** ou **Enregistrer une copie…** dans Paramètres ›
Sauvegarde & restauration. Ces fichiers restent sur le téléphone, et Android les supprime
lorsque vous désinstallez l’application.

### Données de santé

Le poids, la masse grasse et les mensurations sont des données de santé. Titan ne s’en sert
que pour une chose : vous montrer vos propres mesures et leur évolution. Elles ne sont jamais
transmises, vendues, partagées ni utilisées à des fins publicitaires.

**Santé Connect (Health Connect).** Titan peut échanger des séances avec Santé Connect, le
stockage de santé d’Android situé sur votre téléphone, qu’utilisent aussi d’autres applis de
sport. L’échange est désactivé tant que vous ne le connectez pas dans Paramètres › Données &
confidentialité › Santé Connect et ne l’autorisez pas dans la fenêtre de Santé Connect, et
vous pouvez le déconnecter à tout moment, là ou dans Santé Connect. Une fois connecté :

- Quand vous terminez une séance, Titan l’écrit dans Santé Connect comme une session
  d’activité physique : son type, son début et sa fin, son nom et ses notes. Vos séries, charges
  et répétitions ne sont pas écrites.
- Quand vous choisissez **Importer les 30 derniers jours**, Titan lit les séances que d’autres
  applis ont enregistrées dans Santé Connect au cours des 30 derniers jours et les ajoute à votre
  historique.

Titan ne lit et n’écrit que des sessions d’activité physique. Il ne lit ni n’écrit votre poids,
votre masse grasse, votre fréquence cardiaque ni aucune autre donnée de Santé Connect. Santé
Connect se trouve sur votre téléphone : rien de tout cela n’en sort. Les données que Titan lit
dans Santé Connect ne servent qu’à vous montrer votre propre historique, et ne sont jamais
transmises, vendues, partagées ni utilisées à des fins publicitaires. Ce que Titan écrit dans
Santé Connect peut y être lu par les autres applis que vous y avez autorisées, selon leurs
propres conditions ; vous pouvez le consulter et le supprimer dans Santé Connect.

## Ce que l’application ne fait pas

- **Aucune collecte de données.** Ni le projet Titan ni personne d’autre ne reçoit vos
  données. Nous n’avons aucun serveur qui pourrait les recevoir.
- **Aucun accès au réseau.** L’application ne demande pas la permission `INTERNET`. Elle
  demande `ACCESS_NETWORK_STATE`, ajoutée par la bibliothèque de planification de tâches
  d’Android qu’elle utilise. Cette permission lui permet seulement de savoir si le téléphone
  est en ligne ; elle ne permet ni d’envoyer ni de recevoir quoi que ce soit.
- **Aucun SDK tiers qui collecte des données.** Aucune bibliothèque de statistiques, de
  rapports de plantage, de publicité ou de pistage. La liste complète des bibliothèques est
  dans l’application, sous Paramètres › À propos › Licences open source.
- **Aucune sauvegarde dans le cloud.** L’application exclut ses données de la sauvegarde
  automatique d’Android sur Google Drive et du transfert d’un appareil à l’autre : votre
  historique n’est pas copié hors du téléphone à votre insu.
- **Aucun compte ni identifiant publicitaire.** L’application ne sait pas qui vous êtes.

## Quand vos données quittent le téléphone

Uniquement quand vous le demandez. **Exporter une sauvegarde** (Paramètres › Sauvegarde &
restauration) écrit toutes vos données, données corporelles comprises, dans un fichier JSON.
Vous choisissez ensuite d’en enregistrer une copie quelque part ou de la partager avec une
autre application. Dès lors, cette copie est traitée par le service auquel vous l’avez
envoyée, selon ses propres conditions. Le fichier n’est pas chiffré : conservez-le dans un
endroit de confiance.

**Restaurer** lit un fichier de sauvegarde que vous choisissez et remplace par son contenu les
données du téléphone. Rien n’est envoyé.

## Notifications

Les rappels d’entraînement, l’alerte de fin de repos, la note de séance inachevée et les
notifications de niveau et de récompenses sont toutes créées sur le téléphone. Aucune n’est
envoyée par un serveur. Vous pouvez désactiver chacune d’elles dans Paramètres ›
Notifications ou dans les paramètres d’Android.

## Permissions

| Permission | Pourquoi l’application la demande |
| --- | --- |
| `POST_NOTIFICATIONS` | Pour afficher les notifications ci-dessus. Android 13 et plus vous le demande d’abord. |
| `SCHEDULE_EXACT_ALARM` (Android 12 et antérieur) / `USE_EXACT_ALARM` | Pour terminer un repos à la seconde près, et déclencher un rappel d’entraînement à l’heure choisie. |
| `RECEIVE_BOOT_COMPLETED` | Pour reprogrammer vos rappels et la sauvegarde hebdomadaire après un redémarrage du téléphone. |
| `health.READ_EXERCISE`, `health.WRITE_EXERCISE` | Pour importer des séances depuis Santé Connect et y écrire celles que vous terminez, comme décrit dans § Données de santé. Santé Connect vous le demande d’abord, et aucune n’est accordée si vous ne le connectez pas. |
| `VIBRATE` | Pour vibrer à la fin d’un repos. |
| `WAKE_LOCK`, `FOREGROUND_SERVICE`, `ACCESS_NETWORK_STATE` | Ajoutées par la bibliothèque de planification de tâches d’Android (WorkManager), qui exécute la sauvegarde hebdomadaire. Aucune ne donne accès au réseau. |
| `app.titan.gym.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Ajoutée par la bibliothèque AndroidX core. Elle empêche d’autres applications d’envoyer des messages internes à l’application. |

## Supprimer vos données

- **Paramètres › Supprimer toutes les données** efface tout ce que l’application a enregistré
  et remet chaque paramètre à sa valeur par défaut. C’est irréversible.
- **Désinstaller l’application** supprime tout également, sauvegardes automatiques comprises.
- **Les fichiers exportés** que vous avez enregistrés ou partagés sont hors de l’application.
  Supprimez-les là où vous les avez placés.

Comme nous ne détenons aucune copie de vos données, nous n’avons rien à supprimer, et vous
n’avez aucune demande à nous adresser.

## Enfants

Titan ne s’adresse pas aux enfants de moins de 13 ans, et ne collecte sciemment aucune
donnée auprès de qui que ce soit, quel que soit son âge.

## Modifications de cette politique

Si cette politique change, la nouvelle version est publiée à cette adresse avec une nouvelle
date d’entrée en vigueur. Toutes les versions précédentes sont conservées dans l’historique
public du code source du projet. Un changement qui amènerait l’application à collecter ou à
envoyer des données serait annoncé dans les notes de la version concernée et demanderait votre
consentement dans l’application.

## Contact

Titan est développé publiquement. Pour toute question sur cette politique ou sur votre vie
privée, écrivez à [contact.titan.app@gmail.com](mailto:contact.titan.app@gmail.com).
