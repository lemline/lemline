# VISION — Lemline

Document stratégique de référence pour les décisions de périmètre produit, de priorisation et de nommage.
Fonction première : garde-fou anti-over-engineering. En cas de doute sur une décision, revenir ici.

## Problème

Orchestrer des processus métier distribués force aujourd'hui à choisir entre deux maux : la chorégraphie
peer-to-peer (fragile, sans visibilité, logique métier éparpillée) ou un orchestrateur dédié (une plateforme
de plus à opérer, une base de données sollicitée à chaque étape). Les équipes qui possèdent déjà un broker
et une base de données paient deux fois.

## Ce qu'on construit

Un runtime d'exécution de la spec Serverless Workflow DSL v1.0 qui tourne **sur l'infra existante de
l'utilisateur** : workers stateless, état porté dans les messages, base de données utilisée seulement quand
c'est indispensable (timers, retries, jointures parallèles). Autour du runner : une gateway gRPC d'ingress,
un sink analytics et un dashboard d'observabilité.

**Test d'appartenance au périmètre** — une fonctionnalité est dans le périmètre si et seulement si :
(a) elle est requise par la spec Serverless Workflow, ou
(b) elle rend l'exécution plus fiable, observable ou opérable.
Tout le reste est hors périmètre par défaut, jusqu'à besoin démontré par un utilisateur réel.

## Utilisateur cible

Une équipe backend qui possède déjà un broker (Kafka, RabbitMQ, PGMQ) et une base (PostgreSQL, MySQL),
écrit ses workflows en YAML/JSON, lit des logs et des métriques. Pas un utilisateur no-code.

**Test décisif** : « Cette équipe peut-elle mettre Lemline en production sans opérer un composant d'infra
qu'elle n'opérait pas déjà ? » Si une décision produit rend la réponse « non », la décision est mauvaise.

## Principes de conception

1. **La fiabilité prime sur les features.** Entre une nouvelle capacité et un correctif de fiabilité ou
   d'observabilité, le correctif gagne. Toujours.
2. **Échec bruyant.** Aucun chemin d'erreur ne peut se terminer sans log, métrique ou état persisté.
   Un `catch` silencieux est un bug, pas un choix de style.
3. **Base de données minimale.** Toute feature ajoutant des accès DB sur le chemin nominal d'exécution
   doit justifier pourquoi le message ne suffit pas.
4. **Pas d'abstraction spéculative.** Pas d'interface, de SPI ou de mécanisme de plugin sans au moins
   deux implémentations concrètes existantes ou immédiatement planifiées.
5. **La spec nomme.** Le vocabulaire de la spec Serverless Workflow (workflow, task, instance, listen,
   fork…) prime sur tout vocabulaire inventé, dans le code comme dans les APIs.

## Non-goals explicites

- **Pas de SaaS hébergé.** Aucun control plane cloud, aucune offre managée, aucun composant qui suppose
  un service opéré par Lemline. Lemline est auto-hébergé, point.
- **Pas d'infra embarquée.** Lemline ne fournit, n'embarque et ne recommande aucun broker ni base de
  données maison. Toute proposition du type « et si on bundlait X » est refusée d'office.

## Définition du succès

**Métrique nord : zéro échec silencieux.** 100 % des instances démarrées atteignent un état terminal
observable (completed, faulted, cancelled), ou leur blocage est diagnosticable via les logs et métriques
standard, sans debugger. Tout arrêt anormal d'un runner et toute perte de message sont comptés et visibles.
Corollaire d'arbitrage : une release qui ajoute des features mais dégrade cette métrique est un échec.
