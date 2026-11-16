# Extensions dans Lemline : décision et plan d'implémentation

Date : 16 novembre 2026

Status: implemented; validation details and environment limitations are recorded below.

Ce document reprend et met à jour « Extensions dans Lemline : proposition d'implémentation ».
Il fixe une architecture et les conventions de Lemline nécessaires pour passer à l'implémentation.
Les sections 12 et 13 définissent les contrats techniques et les traces de référence à traduire en tests.

## 1. Décision

**Préparer les extensions au parsing et les exécuter avec un contrôleur dédié, attaché aux nœuds existants.**

La compilation du workflow construit les blocs `before` et `after` et détermine les extensions candidates
pour chaque tâche à partir de son type. Elle conserve les tâches originales à leurs positions actuelles.
À l'exécution, un `ExtensionController` évalue les conditions et pilote les phases avant, tâche et après.
Son état voyage avec la pile du workflow.

Les blocs ajoutés ont des positions sous `_ext`, par exemple :

```text
/do/0/getUser/_ext/0/before/do/0/sendLog
```

Ce choix ajoute des sous-arbres d'extension à l'arbre d'exécution. Il ne remplace pas chaque tâche par
un wrapper contenant une copie déplacée de la tâche originale.

### Pourquoi ne pas retenir un wrapper au parsing ?

Construire un arbre d'exécution distinct de la définition source est possible. L'affirmation initiale
selon laquelle toute transformation de l'arbre déplacerait nécessairement les références visibles était
trop catégorique : une correspondance entre positions source et positions d'exécution pourrait les préserver.

Un wrapper spécialisé aurait un avantage : ses blocs avant/après seraient naturellement en dehors des
branches d'un `fork` et du corps protégé d'un `try`. Toutefois, dans le moteur actuel :

- `NodePosition` sert à la résolution des nœuds, à la pile sérialisée, aux messages, aux listeners,
  aux erreurs, à l'historique et à la dérivation des identifiants ;
- introduire une position distincte pour le corps original imposerait de gérer cette correspondance
  dans ces différents usages ; conserver deux frames à la même position ne résoudrait pas le problème ;
- le wrapper aurait toujours besoin d'un état spécifique pour conserver les entrées/sorties, appliquer
  les transformations une seule fois, gérer le court-circuit et différer la fin de la tâche ;
- un simple `do` contenant les étapes ajoutées ne convient pas : il transmet la sortie d'un enfant au suivant
  et consomme `exit` à sa propre frontière.

Le wrapper déplacerait donc une partie de la complexité vers l'identité des nœuds et le cycle de vie commun.
Pour cette fonctionnalité, nous retenons la conservation des positions et des processeurs de tâches,
avec des frontières d'extension explicites. La logique des extensions reste regroupée dans un contrôleur,
plutôt que dispersée dans chaque processeur.

## 2. Référentiel et portée

Lemline lit déjà `use.extensions`, mais son moteur ne l'exécute pas encore. Une tâche visée doit pouvoir
exécuter des étapes avant et après son corps, ou être court-circuitée par une extension.

Le référentiel de cette implémentation est le DSL **1.0.0**, cohérent avec les schémas embarqués.
Les liens sont figés sur cette version, car la branche `main` de la spécification évolue indépendamment.

- [Référence DSL : extensions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl-reference.md#extension)
- [Présentation DSL : extensions](https://github.com/open-workflow-specification/specification/blob/v1.0.0/dsl.md#extensions)
- [Schéma DSL 1.0.0](https://github.com/open-workflow-specification/specification/blob/v1.0.0/schema/workflow.yaml)
- [Modèle d'exécution de Lemline](../adr/0002-workflow-execution-model.md)
- [Frontières d'erreur des forks](../adr/0008-fork-error-management.md)
- [Contrats protobuf](../protobuf-messaging.md)

### Contrat défini par la spécification

| Champ | Obligatoire | Rôle |
| --- | --- | --- |
| `extend` | Oui | Type de tâche ciblé |
| `when` | Non | Expression déterminant si l'extension s'applique dans le contexte courant |
| `before` | Non | Liste de tâches exécutées avant la tâche visée |
| `after` | Non | Liste de tâches exécutées après la tâche visée |

Le schéma accepte `call`, `composite`, `emit`, `for`, `listen`, `raise`, `run`, `set`, `switch`, `try`,
`wait` et `all`. La référence textuelle 1.0.0 mentionne aussi `extension`, absent du schéma : nous suivons
le schéma et n'ajoutons pas cette valeur.

La référence décrit explicitement le court-circuit de la tâche étendue par `then: exit` et fournit
un exemple de réponse simulée. L'exemple de journalisation indique que les tâches d'extension ne sont
pas étendues à leur tour et utilise `$task.reference` pour désigner la tâche visée.

Ces exemples éclairent le comportement attendu sans définir tous les détails d'exécution. Les conventions
ci-dessous sont des décisions de Lemline, et non des exigences supplémentaires attribuées à la spécification.

## 3. Sémantique retenue

| Sujet | Décision de Lemline |
| --- | --- |
| Éligibilité | `composite` cible les tâches `do` et `fork` déclarées ; `call` inclut les appels de fonctions réutilisables. |
| Condition de la tâche | Si `if` est faux, aucune extension ne s'exécute. |
| Conditions des extensions | Après la préparation de l'entrée, évaluer tous les `when` une fois par invocation de la tâche, avant tout `before`, puis conserver la sélection. |
| Initialisation du corps | Initialiser l'état spécifique du processeur après les `before`, avec le contexte actualisé ; ne jamais l'initialiser en cas de court-circuit. |
| Ordre | `before 1`, `before 2`, tâche, `after 2`, `after 1`, dans l'ordre des extensions sélectionnées. |
| Entrée des blocs `before` | Chaque bloc reçoit l'entrée transformée de la tâche ; son résultat final est ignoré, sauf court-circuit. |
| Entrée des blocs `after` | Chaque bloc reçoit la sortie finale de la tâche ; son résultat final est ignoré. |
| Flux interne à un bloc | Les étapes du même bloc se transmettent normalement leurs résultats. |
| Contexte | Les exports explicites des extensions modifient le contexte partagé. Ils ne sont pas annulés lors du retour à la tâche. |
| Court-circuit dans `before` | Sauter le corps et tous les blocs d'extension restants ; traiter le résultat du bloc comme la sortie brute de la tâche. |
| Transformations en cas de court-circuit | Appliquer les transformations et validations de sortie de la tâche, ainsi que son export, une seule fois. |
| Erreur | `after` n'est pas un `finally` : une erreur non rattrapée interrompt la séquence. |
| Nouvelle tentative | Rejouer les extensions des tâches effectivement réexécutées, avec une nouvelle évaluation de leurs `when`. |
| Retry du corps d'un `try` | Ne pas rejouer l'extension du `try` lui-même tant que cette invocation du `try` reste active. |
| Sous-workflows | Aucun héritage implicite des extensions du parent ; chaque workflow utilise sa propre définition. |
| Fonctions réutilisables | Les tâches de fonction exécutées dans le workflow restent éligibles, sauf si elles sont exécutées depuis une extension. |
| Récursion des extensions | Aucun descendant d'un bloc d'extension, y compris une fonction appelée par ce bloc, ne reçoit d'extension supplémentaire. |

Un retour de suspension reprend la sélection et la phase sauvegardées. Il ne constitue pas une nouvelle
invocation et ne réévalue ni `if`, ni `input.from`, ni les `when` déjà évalués.

## 4. Préparation au parsing

Construire un plan immuable par définition de workflow : extensions déclarées, blocs préparés et
candidats par nœud. Les expressions dépendant des données restent évaluées à l'exécution.

Les tâches existantes gardent leurs positions. Les blocs ajoutés sont indexés sous la position de leur
tâche propriétaire, avec l'index stable de l'extension dans sa liste de déclaration. Une extension non
sélectionnée à l'exécution ne modifie donc pas les positions des suivantes.

Les nœuds préparés portent une origine explicite permettant de distinguer :

- les tâches déclarées par l'utilisateur ;
- les enveloppes internes, comme la racine, le `do` racine, les blocs synthétiques de `for`, `try`, `catch`
  et `foreach`, ou l'enveloppe d'une fonction ;
- les blocs et tâches d'extension, avec leur propriétaire et leur phase.

La sélection des extensions exclut les enveloppes internes et les sous-arbres d'extension. Elle ne doit
pas dépendre uniquement de recherches de sous-chaînes dans les positions.

Les fonctions distantes et récursives conservent leur résolution différée. Appliquer la même préparation
lors de la construction de leur arbre, sans développer récursivement leurs appels au chargement du workflow.
Les chemins mixtes `_fn` et `_ext` doivent fonctionner dans les deux sens.

Le cache doit être associé à la définition effective du workflow et suivre son remplacement ou son retrait.
Une fonction partagée ne doit pas réutiliser un plan contenant les extensions d'un autre workflow appelant.
Éviter un cache global d'extensions ayant une politique d'invalidation indépendante de `WorkflowCache`.

## 5. Contrôleur et état durable

### Responsabilités

`ExtensionController` pilote les transitions entre phases. Les processeurs existants continuent d'exécuter
leur type de tâche. Le contrôleur reçoit des données immuables et renvoie une transition et un état mis à jour ;
il ne conserve aucun état d'instance dans ses propres champs.

Les points d'intégration restent communs : entrée de tâche, retour de bloc d'extension, résultat du corps
synchrone ou asynchrone, finalisation et propagation des erreurs. `AbstractProcessor` délègue ces décisions
au contrôleur. `StepByStepOrchestrator` respecte les frontières définies à la section suivante.

### État à transporter

Ajouter un état optionnel d'extension sur `StackFrame`, indépendant des états `DoState`, `ForState`,
`TryState`, etc. Il contient les informations nécessaires à la continuation :

| Information | Utilité |
| --- | --- |
| Phase et index courant | Reprendre le bon bloc sans rejouer les précédents |
| Liste des extensions sélectionnées | Ne pas réévaluer `when` après une suspension |
| Entrée brute et entrée transformée de la tâche | Reconstruire `$task.input` et restituer l'entrée après les `before` |
| Sortie brute et sortie finale, lorsqu'elles existent | Reconstruire `$task.output` et restituer le résultat après les `after` |
| Directive de navigation en attente | Appliquer le `then` de la tâche après la fin de ses extensions |

Le message de retour porte séparément `ExtensionReturn` pour distinguer fin normale, court-circuit et
terminaison globale ; ce marqueur n'est pas dupliqué dans la frame.

La position propriétaire et l'instant de début peuvent être retrouvés dans la frame : ne pas dupliquer
les données déjà disponibles. Les définitions et les secrets ne sont pas copiés dans cet état ; ils sont
restaurés depuis les sources habituelles du moteur.

Les champs dépendant d'une phase doivent conserver leur présence, y compris lorsque leur valeur JSON est
`null`. L'absence d'état d'extension conserve le chemin d'exécution actuel.

### Préparation du résultat et finalisation

Séparer les opérations actuellement regroupées dans `completeTask` :

1. Recevoir la sortie brute du corps, ou celle du court-circuit.
2. Appliquer `output.as`, valider la sortie, appliquer et valider `export.as`.
3. Conserver le résultat préparé dans la continuation et le contexte mis à jour dans la pile.
4. Exécuter les blocs `after`, sauf court-circuit.
5. Émettre la fin de la tâche, dépiler sa frame et transmettre le résultat conservé et la directive en attente.

Revenir d'un `after` ne repasse pas par les transformations de l'étape 2. Une tâche n'est pas annoncée
terminée tant que son dernier `after` n'a pas réussi. Une erreur dans un `after` suit le traitement d'erreur
normal et ne doit pas produire préalablement un événement de succès pour la tâche propriétaire.

Les retours d'extension sont interceptés avant `stateWhenReEnteringFromChild` : ils ne doivent pas avancer
l'index d'un `do` ou d'un `for`, ni faire passer un `try` à l'état terminé.

### Sérialisation

Étendre `StackFrameProto` avec un numéro de champ inédit et les types nécessaires, puis mettre à jour
`NodeStackProtobufMapper`. Respecter les règles de présence et les enums `*_UNSPECIFIED = 0`.
Vérifier les formats protobuf binaire et ProtoJSON utilisés dans les messages et les opérations durables.
L'absence du nouveau champ doit rester décodable.
Ajouter également l'état `PendingTaskState` et le retour explicite `ExtensionReturn` aux contrats décrits
en section 12. La sérialisation concerne donc `NodeStackProtobufMapper` et `WorkflowStateProtobufMapper`,
ainsi que la conversion `TaskScheduled.resume()`.

## 6. Frontières de contrôle

### Erreurs dans les extensions

Les erreurs d'extension suivent le mécanisme d'erreur des tâches ordinaires. Le contrôleur ne les masque
pas et ne les transforme pas en succès. Les suspensions et les directives de contrôle restent distinctes
des erreurs métier ou d'exécution.

| Origine de l'erreur | Comportement retenu |
| --- | --- |
| Définition d'extension invalide détectable au chargement | Rejeter la définition lors de sa validation ; ne pas ignorer silencieusement l'extension |
| Expression `when` invalide à l'exécution ou résultat non booléen | Erreur d'expression faisant échouer l'invocation propriétaire ; ne pas interpréter cette erreur comme `false` |
| Erreur non rattrapée dans un `before` | Arrêter les extensions restantes ; ne pas exécuter le corps ni les `after` ; faire échouer le propriétaire |
| Erreur non rattrapée dans le corps de la tâche | Ne pas exécuter les `after` ; suivre la propagation normale de l'erreur |
| Erreur non rattrapée pendant la préparation du résultat | Faire échouer le propriétaire ; ne pas exécuter les `after` |
| Erreur non rattrapée dans un `after` | Arrêter les `after` restants et faire échouer le propriétaire, même si son corps a réussi |
| Erreur rattrapée par un `try` interne à l'extension | Appliquer son catch/retry ; si le bloc se termine normalement, reprendre la séquence de l'extension |
| Échec reçu après une suspension | Même propagation que pour une erreur synchrone, à partir de la phase et des frames sauvegardées |
| Aucun gestionnaire applicable | Faire échouer l'invocation, puis la branche ou le workflow selon les frontières existantes |

Les erreurs de tâches d'extension comprennent notamment les erreurs d'expression, de validation,
les `raise`, les appels externes en échec et les timeouts déjà pris en charge par le moteur. Conserver
le type, le statut et la position de l'erreur d'origine pour les filtres de `catch` et le diagnostic.
Ne pas les remplacer toutes par une erreur générique « extension ».

Comme tous les `when` sont évalués avant le premier `before`, une erreur de sélection empêche toute
exécution de bloc d'extension et du corps pour cette invocation. Le diagnostic doit identifier
l'extension et son `when`. Les gestionnaires internes aux blocs pas encore exécutés ne peuvent pas
rattraper cette erreur ; elle cherche un gestionnaire extérieur au propriétaire, y compris lorsque
celui-ci est lui-même un `try`.

### Portée des retries et effets déjà produits

Un retry interne à un bloc d'extension ne rejoue que le périmètre de ce `try`. En particulier, un retry
local dans un `after` conserve la sortie de la tâche et ne réexécute pas son corps.

Un retry décidé par un `try` extérieur réexécute son corps selon les règles ordinaires de Lemline,
donc potentiellement plusieurs tâches, leurs extensions et l'appel métier déjà réussi. Il n'existe pas
de reprise implicite limitée au `after` fautif lorsqu'un retry porte sur un périmètre plus large.

Exemple : un paiement réussit, puis son `after` de journalisation échoue. Un retry extérieur entourant
le paiement peut effectuer un nouvel appel de paiement. Si seule la journalisation doit être retentée,
son retry doit être placé dans l'extension. Si son échec doit être toléré, son `catch` doit le traiter
explicitement. Les extensions n'ont pas de politique implicite d'ignorance des erreurs.

Les appels externes, événements métier émis et exports de contexte déjà appliqués ne sont pas annulés
par l'échec d'un bloc ultérieur. Les IDs déterministes assurent les garanties existantes de redélivrance ;
ils ne rendent pas automatiquement idempotent un appel métier effectué lors d'une nouvelle tentative.

### `exit`, `end` et sauts nommés

Le bloc de premier niveau d'une extension doit transmettre au contrôleur une issue explicite : fin normale
ou court-circuit. Le comportement ordinaire de `DoProcessor` ne suffit pas, car il absorbe `exit` en quittant
son propre bloc.

Un `exit` dans un bloc imbriqué conserve sa portée locale. Seul celui qui sort du bloc `before` de premier
niveau déclenche le court-circuit de la tâche étendue. Dans un bloc `after` de premier niveau, `exit` arrête
les blocs `after` restants et finalise la tâche avec son résultat conservé.

`end` conserve sa portée de terminaison du workflow ; il ne déclenche pas de blocs `after` supplémentaires
pendant le dépilage. Les sauts nommés restent résolus dans leur séquence de tâches. Le `then` propre à la
tâche étendue reste en attente jusqu'à sa finalisation, y compris lorsqu'elle a été court-circuitée.

### `fork`

Identifier la relation entre le nœud enfant et son parent : branche du corps ou bloc d'extension.
Un retour de `before` ou `after` vers un `fork` ne produit pas `ForkBranchCompleted`.
Une erreur dans l'extension du `fork` ne produit pas `ForkBranchFaulted` pour une branche fictive.

En revanche, les extensions des tâches situées dans une vraie branche restent dans cette branche et
respectent sa frontière d'erreur. Les comportements compétitif et coopératif du fork restent ceux de l'ADR-0008.

### `try`

Le `catch` d'un `try` protège son corps, pas ses propres extensions. Une erreur dans un `before` ou `after`
attaché au `try` cherche donc un gestionnaire extérieur, après les éventuels gestionnaires internes au bloc
d'extension. Une erreur dans l'extension d'une tâche du corps peut être traitée par ce `try`.

Le nettoyage de pile lors d'un retry supprime aussi les états d'extension des tâches abandonnées.
L'état d'extension d'un `try` toujours actif est conservé lorsqu'on réexécute seulement son corps.

## 7. Expressions et identité des tâches

Dans `when` et dans les blocs d'extension, `$task` décrit la tâche propriétaire : son nom, sa référence,
sa définition originale, son entrée brute, son instant de début et sa sortie brute lorsqu'elle existe.
Conserver ce descripteur pendant les suspensions et les appels de fonctions dans l'extension.

Les variables locales du bloc, les données courantes, `$input`, `$output` et `$context` suivent le flux normal
des étapes exécutées. La mise à jour des entrées/sorties d'une étape d'extension ne doit pas écraser
accidentellement le descripteur `$task` de son propriétaire.

Le nœud réellement exécuté garde sa position `_ext` pour le diagnostic, la navigation et les événements.
La tâche propriétaire garde sa position historique. Il faut donc adapter la construction du scope, et non
remplacer globalement la position de l'étape exécutée par celle du propriétaire.

Utiliser `$task.definition.call` et `$task.definition.with`. Le raccourci `$task.call` de l'exemple officiel
de simulation est incohérent avec le descripteur documenté : corriger les exemples de test sans introduire
un alias supplémentaire. Corriger aussi les expressions et endpoints de l'exemple pour qu'il déclenche
effectivement la simulation.

## 8. Listeners, CloudEvents et dashboard

### Écoutes dans les extensions

L'extraction des écoutes doit parcourir les blocs d'extension préparés, en plus des tâches du workflow
et des fonctions. Adapter le cache des filtres, `DefinitionListenService`, `ListenerService` et
`ListenerQueryKey` pour retrouver les positions liées à un propriétaire, y compris sous `_fn`.

Deux extensions ou fonctions ayant des positions relatives semblables ne doivent pas être confondues.
Un test doit vérifier le routage d'un événement jusqu'à une écoute créée dans une extension : un test
du moteur en mémoire seul ne couvre pas cette indexation ni les requêtes SQL.

### Contrat des CloudEvents de cycle de vie

Réutiliser `LifecycleEventHook` et `CloudEventLifecycleHook`, ainsi que les types `task.created`,
`task.started`, `task.completed`, `task.faulted` et `task.retried` existants. Le contrôleur ne crée pas
un canal ni une famille d'événements distincts pour les extensions.

La tâche propriétaire représente son invocation complète, extensions comprises. Les tâches déclarées
dans les blocs d'extension ont chacune leur propre cycle de vie, à leur position `_ext`.

| Moment | Émission attendue |
| --- | --- |
| Planification et entrée de la tâche propriétaire | `task.created` puis `task.started` à sa position originale, sans répétition à chaque changement de phase |
| Exécution d'une tâche de `before` ou `after` | Événements ordinaires à sa position `_ext`, avec ses propres entrée, sortie ou erreur |
| Retour d'une suspension ou d'un bloc d'extension | Aucun nouveau `task.started` pour la même invocation du propriétaire |
| Corps terminé, `after` encore en cours | Aucun `task.completed` du propriétaire |
| Dernier `after` terminé | `task.completed` du propriétaire avec la sortie conservée de la tâche |
| Court-circuit par `before` | `task.completed` du propriétaire avec la sortie simulée après transformation ; aucune exécution du corps ou des blocs sautés |
| Erreur d'extension rattrapée dans cette extension | Échec de l'étape concernée ; le propriétaire peut continuer |
| Erreur d'extension qui fait échouer le propriétaire | `task.faulted` de l'étape et du propriétaire, sans succès prématuré du propriétaire |

Lors du dépilage sur erreur, fermer aussi les invocations abandonnées, même si un `try` extérieur rattrape
l'erreur et permet au workflow de continuer. Cette fermeture doit précéder la perte des frames concernées
et ne pas doubler les événements déjà émis par les chemins d'erreur existants. Sinon, le dashboard peut
laisser une tâche en cours ou la classer comme interrompue alors qu'elle a échoué.

Une nouvelle tentative ouvre une nouvelle tentative logique pour les tâches effectivement rejouées.
Les événements de retry doivent permettre de rattacher les échecs de sous-étapes au bon propriétaire
et au bon essai. Ne pas rejouer le début ou la fin du `try` propriétaire lorsqu'il reste actif.

Le champ `data.task` contient la position de la tâche réellement décrite par l'événement. Le `$task`
utilisé dans les expressions d'une extension peut désigner le propriétaire, mais ne doit jamais être
réutilisé à la place de cette identité pour construire le CloudEvent. Conserver le chemin de l'étape
fautive dans `error.instance` pour localiser précisément une erreur d'extension.

Vérifier que les compteurs de pile et les identifiants dérivés distinguent les extensions, les itérations
et les tentatives, tout en restant déterministes lors de la redélivrance d'une même commande. Il s'agit
d'unicité logique et de déduplication, pas d'une garantie de livraison physique exactement une fois.

Les tâches DSL `emit` contenues dans les extensions continuent d'utiliser le mécanisme normal d'émission
de CloudEvents métier. Leur événement métier et leurs événements de cycle de vie ont des rôles distincts ;
tester aussi qu'un `emit` d'extension ne déclenche pas récursivement une autre extension.

### Transport, analytics et projection

Le parcours reste celui déjà utilisé : hook de cycle de vie, publication, ingestion analytics,
lecture/streaming par le gateway, puis projection dans le dashboard.

`LifecycleAnalyticsService` conserve le CloudEvent complet et ses attributs d'identité de workflow.
La projection du dashboard extrait la position depuis `data.task`. Conserver les attributs
`lemlineworkflowid`, `lemlineworkflownamespace`, `lemlineworkflowname` et `lemlineworkflowversion`.
Les chemins `_ext` restent des chaînes dans les contrats existants ; vérifier leur conservation et
la déduplication des événements dans ce parcours avant de conclure qu'aucune adaptation de contrat
n'est nécessaire. Aucune migration analytics n'est prévue pour le seul ajout de ces chemins.

### Affichage dans le dashboard

Le tableau de bord conserve les lignes des tâches originales et présente les tâches `_ext` comme leurs
sous-étapes. Ce comportement nécessite une adaptation explicite du frontend :

- `workflow-source.ts` : résoudre les segments `_ext/<index>/<phase>` vers la bonne entrée de
  `use.extensions`, puis vers sa liste de tâches ; prendre en charge les chemins mixtes avec `_fn` ;
- afficher le nom de l'extension et la phase « avant » ou « après », sans compter ces segments techniques
  comme des tâches supplémentaires dans le calcul de l'indentation ;
- résoudre le vrai type de chaque étape : un `listen` ou un `wait` d'extension doit être reconnu comme
  une attente, et non comme une tâche de type inconnu ;
- `timeline.ts` : conserver la durée totale du propriétaire jusqu'à la fin des `after`, les entrées/sorties
  du propriétaire et celles de chaque sous-étape, ainsi que les erreurs et tentatives correspondantes ;
- ne pas transformer les blocs internes `_ext`, `before`, `after` et leurs enveloppes `do` en lignes de
  tâches utilisateur ;
- éviter d'associer les sous-étapes d'une itération ou d'une tentative à une autre invocation du même
  propriétaire ;
- maintenir un résultat cohérent pendant le streaming, puis après rechargement de l'historique complet.

La présence de sous-étapes fait actuellement classer une tâche comme groupe dans `timeline.ts`, et les
groupes sont exclus du calcul des périodes d'activité de `buildScale`. Une tâche HTTP étendue ne doit
donc pas voir le temps de son propre corps pris pour une période inactive et artificiellement comprimé.
Adapter cette règle sans additionner deux fois les durées du propriétaire et de ses sous-étapes.
Les intervalles de groupe décrivent l'invocation complète ; ils ne prétendent pas mesurer exclusivement
le temps passé dans son corps.

Ces règles concernent aussi les états en erreur : une extension défaillante doit être visible sous son
propriétaire, et le propriétaire ne doit apparaître ni réussi ni indéfiniment en cours si cette erreur
met fin à son invocation.

## 9. Étapes d'implémentation

1. **Compiler les blocs et candidats.** Étendre `WorkflowParser`, les métadonnées de `Node` et
   `WorkflowCache` ; classifier explicitement les nœuds ; intégrer la préparation différée des fonctions.
2. **Résoudre les positions ajoutées.** Ajouter `_ext` dans `Token`, indexer les blocs préparés et vérifier
   `Workflow.getNode` sur les chemins mixtes avec `_fn`, sans déplacer les nœuds existants.
3. **Introduire la continuation.** Ajouter l'état optionnel dans `StackFrame`, son contrat protobuf et
   ses mappings, `PendingTaskState` et `ExtensionReturn` ; tester les différentes phases, l'absence des
   nouveaux champs et la conversion événement vers commande.
4. **Implémenter le contrôleur.** Brancher entrée, retours d'extension, résultats asynchrones et finalisation ;
   différer l'initialisation du corps après les `before`, séparer préparation du résultat et émission
   de fin, adapter le scope de `$task` et transmettre la pile actualisée au traitement d'erreur.
5. **Traiter les frontières.** Adapter `AbstractProcessor`, `StepByStepOrchestrator` et le nettoyage des retries
   pour distinguer corps et extension ; couvrir les erreurs de sélection/exécution, les retries locaux
   et extérieurs, `exit`, `end`, `fork` et `try`.
6. **Intégrer les consommateurs de positions et le cycle de vie.** Compléter l'indexation et le routage
   des listeners, vérifier les CloudEvents jusqu'à la projection analytics/gateway, puis adapter
   `workflow-source.ts`, `timeline.ts` et leurs tests pour afficher les sous-étapes, états et durées.
7. **Valider la fonctionnalité.** Exécuter la matrice ci-dessous, les vérifications protobuf et les tests
   d'intégration ciblés dans les suites existantes.

## 10. Plan de tests

Utiliser `FullOrchestrator` avec `serde = true` pour les scénarios du moteur : les commandes et événements
passent ainsi par protobuf à chaque transition. Ajouter des reprises explicites après vidage/reconstruction
des caches pour vérifier que la continuation ne dépend pas d'un objet conservé en mémoire.

| Cas | Vérification |
| --- | --- |
| Workflow sans extension | Résultats, positions et comportement existants inchangés |
| Journalisation `extend: all` | Blocs exécutés autour des tâches déclarées, sans récursion ni extension des enveloppes internes |
| Sélection `call`, `composite` et types simples | Correspondance des types, y compris fonctions, `do` et `fork` |
| `when` vrai/faux et `if` faux | Bonne sélection ; absence d'effets lorsque la tâche est ignorée |
| Extension invalide au chargement | Définition rejetée avec un diagnostic ; aucune omission silencieuse |
| `when` en erreur ou non booléen | Erreur d'expression, aucun bloc ni corps exécuté, gestionnaire extérieur approprié |
| Deux extensions | Ordre avant 1, avant 2, tâche, après 2, après 1 |
| Résultats des blocs différents des données de la tâche | Entrée et sortie originales préservées ; exports explicites conservés |
| Simulation avec `exit` | Aucun appel de la tâche originale ; résultat simulé transformé et exporté une fois |
| `exit` direct et imbriqué, `end`, saut nommé | Portées respectées et directive de la tâche conservée |
| Tâche `wait`, HTTP, `listen` ou sous-workflow étendue | Les `after` suivent la véritable fin asynchrone |
| Suspension dans `before` et dans `after` | Phase, données, sélection et `$task` préservés sans rejouer le corps |
| Transformation et export non idempotents | Une seule application pendant la progression normale avant/après |
| Boucle et retours d'enfants | Les blocs d'extension n'avancent pas l'état propre du processeur |
| Extension sur `fork`, dans les deux modes | Aucun retour ou échec de fausse branche ; erreurs de vraies branches inchangées |
| Erreur d'extension dans le corps d'un `try` | Erreur traitée par le `try` approprié |
| Erreur d'extension du `try` lui-même | Gestionnaire extérieur utilisé |
| Échec dans un `before` | Aucun corps ni `after` exécuté ; étapes déjà réussies non annulées |
| Échec dans un `after` parmi plusieurs | Aucun `after` restant exécuté ; sortie préparée conservée pour le diagnostic, pas de succès du propriétaire |
| `try` interne à l'extension | Erreur traitée localement sans faire échouer prématurément le propriétaire |
| Retry local d'un `after` | Seul le périmètre interne est rejoué ; le corps métier est exécuté une seule fois |
| Retry extérieur après échec d'un `after` | Périmètre extérieur rejoué, y compris le corps métier ; nombre d'appels vérifié explicitement |
| Erreur après suspension avec sérialisation | Même catch, mêmes informations d'erreur et même nettoyage qu'en exécution synchrone |
| Filtres de `catch` | Type, statut et position d'origine préservés pour une erreur d'extension |
| Retry | Extensions des tâches rejouées réinitialisées ; extension du `try` actif conservée |
| Fonction dans une extension et extension dans une fonction | Résolution `_fn`/`_ext`, scope et absence de récursion |
| Fonction partagée entre deux workflows | Aucun mélange de leurs extensions dans le cache |
| Sous-workflow | Pas d'héritage des extensions ; scope propre restauré |
| Reprise avec caches reconstruits | Même continuation et mêmes données |
| Initialisation différée d'un `for` | La collection utilise le contexte exporté par les `before`, même après suspension |
| Court-circuit avant initialisation | Aucune expression spécifique au corps ni création de son état ; sortie simulée traitée normalement |
| Retour de bloc explicite | `ExtensionReturn` préservé par `TaskScheduled.resume()`, protobuf et ProtoJSON ; mauvais propriétaire ou mauvaise phase rejetés |
| États invalides | `PendingTaskState` sans extension, phase inconnue et absence de données obligatoires rejetées sans réexécuter le corps |
| Protobuf et ProtoJSON | Aller-retour de toutes les phases, JSON `null`, et ancien message sans état d'extension |
| Redélivrance et nouvelles tentatives | Identifiants stables pour une même commande et distincts pour des invocations différentes |
| Listener dans une extension | Indexation, corrélation et routage réel vers le bon propriétaire |
| Historique | Une fin logique de la tâche après ses `after`, sous-étapes distinctes et références originales conservées |
| Capture des CloudEvents réels | Types, `data.task`, entrée/sortie, attributs de workflow et IDs corrects pour propriétaire et sous-étapes |
| Erreur d'un `after` rattrapée par un `try` extérieur | Sous-étape et propriétaire fermés en échec, aucun succès prématuré, workflow autorisé à continuer |
| `emit` dans une extension | CloudEvent métier émis par le chemin habituel, distinct des événements de cycle de vie |
| Analytics et streaming gateway | Chemins `_ext`, erreurs et sorties conservés ; redélivrance dédupliquée |
| Résolution frontend des extensions | Nom, phase, type, définition et indentation corrects, y compris chemins mixtes avec `_fn` |
| Attente ou appel HTTP étendu dans la timeline | État des sous-étapes correct ; durée totale conservée ; temps du corps non comprimé comme inactivité |
| Boucles et retries dans la timeline | Sous-étapes rattachées à la bonne invocation et au bon essai |
| Streaming puis rechargement | Même état final, mêmes lignes et mêmes résultats |

Capturer les vrais CloudEvents avec `CloudEventLifecycleHook`, et pas uniquement les appels à un hook
factice. Les tests de projection et de timeline doivent utiliser des traces représentatives du contrat
émis par le moteur. Ajouter les cas dans `LifecycleEventHookTest`, les suites analytics/gateway concernées,
`tests/lib/timeline.test.ts` et `tests/hooks/use-watch-workflow-projection.test.ts` ; couvrir aussi les
fonctions de résolution de `workflow-source.ts` dans les tests frontend.

Les tests listeners restent dans les suites existantes du module concerné. Toute modification de requête
ou de repository doit être testée avec PostgreSQL, MySQL et H2. Ne pas créer une organisation de tests
ou un workflow CI spécifique à cette fonctionnalité.

Capturer les logs complets des tests dans `/tmp/lemline-<module>-extensions.log`, puis les inspecter avant
de relancer. Exécuter `buf lint` depuis `lemline-messages-proto` lorsque le contrat est modifié.

## 11. Critères d'achèvement

- Les exemples de journalisation et de simulation fonctionnent avec les corrections de syntaxe documentées.
- Toutes les phases reprennent sur un autre worker à partir du seul message et de la définition chargée.
- Les tâches originales conservent leurs positions et les résultats des extensions ne remplacent pas leurs données.
- Les erreurs, retries et frontières de contrôle satisfont les règles de ce document.
- Les écoutes dans les extensions reçoivent leurs événements et l'historique affiche les sous-étapes correctement.
- Les CloudEvents et leur projection ferment correctement les propriétaires en succès ou en échec,
  sans collision d'identité, et le dashboard restitue les types, phases, tentatives et durées attendus.
- Les suites ciblées et les vérifications de sérialisation passent ; la documentation utilisateur décrit les conventions retenues.

## 12. Contrat technique d'exécution

### 12.1. Frame avant l'exécution du corps

Ajouter `PendingTaskState(startedAt)` comme variante explicite de `NodeState`, avec un scope vide.
Cet état représente une invocation commencée dont le corps n'a pas été initialisé. Il n'est pas un
`TaskState` d'activité et ne doit jamais être passé à un processeur qui attend un `ForState`, `TryState`, etc.

Pour un nœud ayant des extensions candidates, l'ordre d'entrée est le suivant :

1. Construire le descripteur de la tâche et appliquer les règles communes de validation d'entrée,
   `if` et `input.from` dans leur ordre actuel. Une tâche ignorée ne crée pas de continuation d'extension.
2. Créer la frame propriétaire avec `PendingTaskState`, puis évaluer les `when` dans le contexte préparé.
   Cette sélection est une opération synchrone : aucun message ne transporte une sélection partielle.
3. Conserver la sélection complète et lancer le premier `before` applicable. Si aucune extension n'est
   sélectionnée, poursuivre le chemin ordinaire sans répéter le début de la tâche ni les transformations.
4. Après le dernier `before`, reconstruire le scope depuis la pile actualisée et l'entrée transformée
   conservée, puis appeler `stateWhenEnteringFromParent` exactement une fois pour initialiser le corps.
5. Remplacer `PendingTaskState` par l'état spécifique dans la même frame, passer à `BODY` et commencer
   l'exécution du corps sans repasser par `enterFromParent`.

Cette règle s'applique également aux expressions évaluées pendant l'initialisation, comme `for.in`.
Les exports des `before` sont visibles à cette étape. La sélection des extensions, l'entrée transformée
et l'instant de début logique ne sont pas recalculés. Faire accepter cet instant par les créations
d'état concernées, au lieu de lui substituer l'heure de début du corps.

Une erreur de sélection, d'initialisation ou de préparation du résultat utilise la pile courante au
moment de l'erreur. Le `resumeWithHandler` ne doit pas traiter aveuglément ces erreurs avec la pile
d'entrée antérieure à la création ou à la modification de la frame. Adapter la délégation au traitement
d'erreur pour conserver les frames et le contexte effectivement disponibles, sans état mutable global.

### 12.2. Modèle domaine minimal

Les types suivants fixent le contrat ; les imports et les mécanismes de sérialisation suivent les conventions
du module. Ajouter `extension: ExtensionExecutionState? = null` à `StackFrame`.

```kotlin
data class PendingTaskState(override val startedAt: Instant) : NodeState()

enum class ExtensionPhase { BEFORE, BODY, AFTER, READY }

data class ExtensionExecutionState(
    val selectedExtensionIndices: List<Int>,
    val phase: ExtensionPhase,
    val activeExtensionIndex: Int?,
    val rawInput: JsonElement,
    val transformedInput: JsonElement,
    val preparedResult: PreparedTaskResult? = null,
)

data class PreparedTaskResult(
    val rawOutput: JsonElement,
    val transformedOutput: JsonElement,
    val pendingFlowDirective: FlowDirective?,
)

enum class ExtensionReturnKind { COMPLETE, EXIT, END }

data class ExtensionReturn(
    val blockPosition: NodePosition,
    val kind: ExtensionReturnKind,
)
```

`FlowDirective` désigne ici le type domaine de `com.lemline.core.tasks`, pas le type du SDK ni un type protobuf.
Les indices sont ceux de la déclaration `use.extensions`, pas ceux d'une liste filtrée renumérotée.
Le contrôleur dérive le bloc attendu de la position propriétaire, de la phase et de `activeExtensionIndex`.

| Phase | État spécifique de la frame | Données obligatoires et invariant |
| --- | --- | --- |
| `BEFORE` | `PendingTaskState` | Entrées conservées ; index d'une extension sélectionnée possédant un `before` ; aucun résultat préparé |
| `BODY` | État du processeur original | Entrées conservées ; index actif absent ; aucun résultat préparé |
| `AFTER` | État du corps conservé | Résultat préparé présent ; index d'une extension sélectionnée possédant un `after` |
| `READY` | État du corps, ou `PendingTaskState` après court-circuit | Résultat préparé présent ; index actif absent ; seul le contrôleur peut finaliser |

Les transitions sans travail à effectuer, par exemple l'absence de `before` ou de `after`, sont exécutées
dans le même traitement. Ne pas publier un état `BEFORE` ou `AFTER` avec un bloc actif inexistant.
`READY` permet une finalisation explicite, sans imposer un message ou une écriture en base supplémentaire.
La préparation du résultat et la mise à jour du contexte précèdent toute publication d'une continuation `AFTER`.

### 12.3. Retour de bloc et commandes de reprise

Ajouter `extensionReturn: ExtensionReturn? = null` à `WorkflowEvent.TaskScheduled` et à
`WorkflowCommand.ResumeFromTask`. `TaskScheduled.resume()` copie ce champ sans perte.
Le résultat du bloc reste dans `rawInput` de cet événement ou de cette commande ; il n'est pas dupliqué
dans `ExtensionReturn`.

Le retour du `do` synthétique de premier niveau d'un bloc produit :

- `nodePosition` = position du propriétaire ;
- une pile dépilée jusqu'au propriétaire ;
- `extensionReturn.blockPosition` = position du bloc qui vient de se terminer ;
- `extensionReturn.kind` = `COMPLETE`, `EXIT` ou `END` ;
- `flowDirective = null`, car le retour d'extension porte déjà la décision de frontière.

Les blocs imbriqués continuent d'utiliser les directives ordinaires ; ils ne construisent pas ce marqueur.
Un saut nommé est consommé dans sa séquence. Un `END` remonte jusqu'à la frontière d'extension en conservant
sa portée globale. Les échecs suivent le chemin d'erreur existant, sans marqueur de retour réussi.

À réception, le contrôleur vérifie que le propriétaire est la frame courante, qu'il attend le bloc indiqué
et que la phase accepte ce retour. Il consomme le marqueur avant toute mise à jour de l'état spécifique.
Un marqueur incohérent est une erreur interne diagnostiquée ; il ne doit pas relancer le corps par défaut.

| Entrée reçue | État attendu | Traitement |
| --- | --- | --- |
| `ResumeFromTask`, propriétaire absent de la pile | Nouvelle invocation | Chemin d'entrée et sélection décrits en 12.1 |
| `ResumeFromTask` avec retour `COMPLETE` | `BEFORE` | Ignorer le résultat du bloc ; lancer le prochain `before` ou initialiser le corps avec l'entrée conservée |
| `ResumeFromTask` avec retour `EXIT` | `BEFORE` | Préparer le résultat à partir du résultat du bloc ; passer à `READY` sans initialiser le corps ni exécuter les `after` |
| `ResumeFromTask` sans marqueur, propriétaire actif | `BODY` | Retour d'un enfant du corps : appeler le processeur original et conserver sa logique de navigation |
| Résultat synchrone ou `ResumeWithCompletedTask` du propriétaire | `BODY` | Préparer la sortie et l'export une seule fois ; lancer les `after` en ordre inverse ou passer à `READY` |
| `ResumeFromTask` avec retour `COMPLETE` | `AFTER` | Ignorer le résultat du bloc ; lancer le prochain `after` ou passer à `READY` |
| `ResumeFromTask` avec retour `EXIT` | `AFTER` | Passer à `READY` avec le résultat conservé ; sauter les `after` restants |
| `ResumeFromTask` avec retour `END` | `BEFORE` ou `AFTER` | Terminaison globale avec les données reçues, conformément au chemin `end` existant ; ne pas lancer le corps, les blocs restants ou une nouvelle préparation de sortie |
| Finalisation locale | `READY` | Émettre la fin, dépiler la frame et transmettre la sortie et la directive conservées |
| `ResumeWithFaultedTask` ou erreur synchrone | Toute phase concernée | Utiliser le nœud fautif, la pile actualisée et les frontières de la section 6 |

Une reprise asynchrone d'une tâche située dans une extension cible cette tâche, qui est en sommet de pile,
et non directement son propriétaire. Celui-ci reste en `BEFORE` ou `AFTER` jusqu'au retour de son bloc.
Un `ResumeWithCompletedTask` ciblant directement un propriétaire encore en `BEFORE` est incohérent.

Lors d'un retour de bloc, dépiler le bloc et incrémenter le compteur du propriétaire une fois, puis ne
pas l'incrémenter à nouveau lors de la consommation du marqueur. Les changements de phase seuls ne
modifient pas les compteurs. Les retours ordinaires du corps conservent leurs règles actuelles.
Une même commande rejouée doit produire les mêmes compteurs et identifiants ; une nouvelle tentative
du `try` reste distinguée par l'état et les compteurs de son périmètre.

### 12.4. Intégration aux points existants

| Point | Adaptation requise |
| --- | --- |
| `AbstractProcessor.enterFromParent` | Séparer préparation commune, frame en attente et initialisation du corps |
| `AbstractProcessor.reEnterFromChild` | Intercepter le retour d'extension avant le cast de l'état, l'incrément d'index et la navigation du processeur |
| `AbstractProcessor.completeTask` | Séparer préparation du résultat, passage aux `after` et finalisation réelle |
| Frontière du bloc synthétique d'extension | Préserver `exit` et construire `ExtensionReturn` avant la détection de fin de branche de fork/foreach |
| `StepByStepOrchestrator.startTask` et `completeTask` | Router selon la continuation ; ne pas recréer le début de la tâche ou réinitialiser le corps lors d'une reprise |
| `processTaskFaulted` et `hasForkBranchFaulted` | Examiner la relation corps/extension et la phase avant tout cast `TryState` ou traitement de branche |
| `TryProcessor.cleanStateStack` | Supprimer les continuations abandonnées ; conserver celle du `try` toujours actif et les frames ancêtres, notamment le résultat préparé du propriétaire lors d'un retry interne à son `after` |
| `NodeStack` | Fournir des mises à jour immuables de la continuation ; les copies et mises à jour d'état doivent conserver le champ `extension` |
| `Scope.withTask` et helpers d'entrée/sortie | Reconstruire le descripteur propriétaire depuis la frame, sans écraser ses données par celles d'une étape d'extension |
| `TaskScheduled.resume()` et `WorkflowStateProtobufMapper` | Transmettre le retour de bloc jusqu'au prochain worker |

Un propriétaire `try` en phase `BEFORE` a un `PendingTaskState`, pas un `TryState`. Il ne peut pas
intercepter les erreurs de ses propres extensions. En phase `AFTER`, son état est bien un `TryState`,
mais cette règle reste la même. L'éligibilité d'un gestionnaire dépend donc de la frontière traversée,
pas seulement du type de son état.

### 12.5. Contrat protobuf et compatibilité

Au moment de cette rédaction, les ajouts suivants utilisent des numéros libres. Recontrôler les schémas
avant modification si la branche a évolué ; ne jamais déplacer un champ publié pour libérer un numéro.

| Message existant | Ajout |
| --- | --- |
| `StackFrameProto` dans `state/node_stack.proto` | `ExtensionExecutionStateProto extension = 4` |
| `NodeStateProto.state` dans `state/node_state.proto` | `PendingTaskStateProto pending_task_state = 8` |
| `TaskScheduledProto` dans `workflow/events.proto` | `ExtensionReturnProto extension_return = 5` |
| `ResumeFromTaskProto` dans `workflow/commands.proto` | `ExtensionReturnProto extension_return = 5` |

Définir `PendingTaskStateProto` avec `google.protobuf.Timestamp started_at = 1`. Regrouper les autres
nouveaux types dans `internal/state/extensions.proto`, sans dépendance vers les messages de commande
ou d'événement, afin d'éviter les imports circulaires.

| Nouveau message | Champs |
| --- | --- |
| `ExtensionExecutionStateProto` | `repeated int32 selected_extension_indices = 1`, `ExtensionPhaseProto phase = 2`, `optional int32 active_extension_index = 3`, `ExtensionJsonValueProto raw_input_json = 4`, `ExtensionJsonValueProto transformed_input_json = 5`, `PreparedTaskResultProto prepared_result = 6` |
| `PreparedTaskResultProto` | `ExtensionJsonValueProto raw_output_json = 1`, `ExtensionJsonValueProto transformed_output_json = 2`, `FlowDirectiveProto pending_flow_directive = 3` |
| `ExtensionReturnProto` | `string block_position = 1`, `ExtensionReturnKindProto kind = 2` |

Les enums utilisent les préfixes Buf : `EXTENSION_PHASE_PROTO_UNSPECIFIED = 0`, puis `BEFORE = 1`,
`BODY = 2`, `AFTER = 3`, `READY = 4` avec le même préfixe ;
`EXTENSION_RETURN_KIND_PROTO_UNSPECIFIED = 0`, puis `COMPLETE = 1`, `EXIT = 2`, `END = 3` avec ce préfixe.

Les messages imbriqués ont une présence propre. Exiger les entrées et les sorties lorsque leur phase
les requiert : un `google.protobuf.Value` présent contenant JSON `null` ne vaut pas un champ absent.
Exiger aussi la présence de `active_extension_index` en `BEFORE`/`AFTER`, car l'index `0` est valide.
Rejeter les enums inconnus ou `UNSPECIFIED` dans une continuation présente au lieu de deviner une phase.

Un ancien message sans `extension` suit le chemin ordinaire pour les frames déjà actives : ne pas
leur ajouter rétroactivement des `before` ou `after`. Les nouvelles entrées de tâches utilisent le plan
chargé. `PendingTaskState` sans continuation est invalide à une frontière de sérialisation.
Les messages sans `extension_return` conservent leur sens antérieur ; une reprise de propriétaire en
`BEFORE`/`AFTER` sans le marqueur attendu est rejetée.

La lecture d'anciens messages par le nouveau moteur est requise. L'inverse n'est pas garanti : un ancien
worker ne sait pas exécuter `_ext` ni la nouvelle variante de `NodeState`. Déployer les workers capables
de ces contrats avant d'activer les workflows utilisant les extensions ; un déploiement mixte ne doit
pas leur confier ces exécutions.

## 13. Traces de référence pour les tests

Ces traces fixent les valeurs et les frontières à vérifier. Les navigations internes sans décision
d'extension peuvent être regroupées dans une ligne du tableau. Chaque commande et événement de moteur
traverse néanmoins protobuf dans les tests. Les CloudEvents indiqués concernent les tâches déclarées ;
les créations précèdent leurs démarrages, même lorsqu'ils sont regroupés dans le tableau.

Notation : `R` est la frame racine, `D` le bloc `do` principal ; une pile est écrite de la racine au sommet.
Les noms abrégés de CloudEvents désignent les types existants, sans nouveau type d'événement.

### 13.1. Suspension dans un `before`, puis initialisation d'un `for`

Fixture :

- entrée `I = {"keep":"input"}`, contexte initial `{}` ;
- tâche `iterate`, position `P = /do/0/iterate`, dont `for.in` lit `${ $context.items }` ;
- une extension `extend: for` à l'index `0`, avec un `before` contenant `pause` (`wait`), puis
  `chooseItems` (`set` produisant `{"items":[1,2]}` et exportant ces éléments dans le contexte) ;
- corps de la boucle : tâche `remember` produisant `{"seen": $item, "keep": .keep}` ;
- aucun `after`, aucune transformation supplémentaire de sortie du propriétaire.

`B = P/_ext/0/before/do`, `W = B/0/pause`, `U = B/1/chooseItems`.

| Commande ou transition | Pile après traitement | Phase et données du propriétaire | Émission ou assertion |
| --- | --- | --- | --- |
| Première entrée dans `P` | `[R,D,P]` | `PendingTaskState`, `BEFORE`, sélection `[0]`, index `0`, entrées `I` | Début logique de `P`, planification de `B` ; `for.in` non évalué |
| Entrée dans `B`, puis `W` | `[R,D,P,B,W]` | Inchangée | `created/started(W)`, puis `WaitStarted(W)` |
| Sérialisation et reconstruction des caches | `[R,D,P,B,W]` | Identique, pas de `ForState` | Aucune nouvelle exécution ni émission de début de `P` |
| `ResumeWithCompletedTask` de `W` | `[R,D,P,B]` | Toujours `BEFORE` | `completed(W)`, retour ordinaire à `B` |
| Retour à `B`, puis exécution de `U` | `[R,D,P,B]` | Entrées de `P` toujours `I` ; contexte `{"items":[1,2]}` | `created/started/completed(U)` ; résultat du bloc `{"items":[1,2]}` |
| Fin de `B` | `[R,D,P]` | Toujours `BEFORE` jusqu'à consommation du retour | `TaskScheduled(P, extensionReturn = COMPLETE(B))`, résultat du bloc dans `rawInput` |
| Conversion et réception de `ResumeFromTask(P)` | `[R,D,P]` avant entrée du corps | Remplacement par `ForState`, phase `BODY`, collection `[1,2]`, index de boucle `0` (aucun enfant encore exécuté) | `for.in` évalué une seule fois avec le contexte actualisé ; entrée du corps = `I`, pas le résultat du bloc |
| Deux itérations normales | Frames du corps sous `[R,D,P]` | `BODY` ; état de boucle évoluant normalement | Deux invocations de `remember` avec `seen = 1`, puis `2` |
| Fin du corps et préparation du résultat | `[R,D,P]` | `READY`, résultat final `{"seen":2,"keep":"input"}` | Aucun second passage par `input.from` ou les `when` |
| Finalisation de `P` | `[R,D]` | Continuation de `P` dépilée | Un `completed(P)` logique avec ce résultat |

Vérifier le scope `$task` dans `W` et `U` : il décrit `P`, alors que leurs CloudEvents décrivent leurs
propres positions. Rejouer la commande de fin de `W` à partir du même état doit reproduire les mêmes IDs.
Une variante avec `exit` dans le `before` doit produire la sortie simulée sans jamais évaluer `for.in`.

### 13.2. Échec d'un `after` et retry extérieur

Fixture :

- `G = /do/0/guarded` est un `try` autorisant un retry pour une erreur de statut `503` ;
- son corps `T = G/try` contient un appel HTTP `P = T/0/charge` ;
- une extension `audit` d'index `0` cible cet appel et définit uniquement un `after` ;
- `A = P/_ext/0/after/do` contient l'appel `L = A/0/writeAudit` ;
- le mock du paiement renvoie `{"paymentId":"p-1"}`, puis `{"paymentId":"p-2"}` ;
- le mock de l'audit échoue une fois avec une erreur `503`, puis réussit avec `{"logged":true}`.

| Commande ou transition | Pile après traitement | Continuation et résultat | CloudEvents et assertions |
| --- | --- | --- | --- |
| Entrée de `G`, de `T`, puis de `P` | `[R,D,G,T,P]` | `G.attemptIndex = 0` ; `P` passe directement à `BODY`, sélection `[0]` | Début de `G` et `P` ; un seul appel de paiement à ce stade |
| `ResumeWithCompletedTask(P)` | `[R,D,G,T,P]` | Résultat préparé `p-1`, phase `AFTER`, index `0` | Planification de `A`, aucun `completed(P)` |
| Entrée dans `A`, puis `L` | `[R,D,G,T,P,A,L]` | Résultat `p-1` conservé | `created/started(L)`, appel d'audit |
| `ResumeWithFaultedTask(L, 503)` | `[R,D,G]` après traitement par `G` | Frames `T`, `P`, `A`, `L` abandonnées ; `G.attemptIndex = 1` | `faulted(L)` et `faulted(P)` avant perte des frames, aucun `completed(P)` ; `TaskRetryScheduled` vers `T` et notification de retry |
| Reprise du retry après sérialisation | `[R,D,G,T]`, puis nouvelle frame `P` | Nouvelle sélection et nouvel état de `P` ; aucune continuation `AFTER` de l'essai précédent | Nouvelle tentative de `P` ; `G` ne redémarre pas |
| Deuxième appel de paiement réussi | `[R,D,G,T,P]` | Résultat préparé `p-2`, phase `AFTER` | Deux appels de paiement au total ; toujours pas de succès prématuré de `P` |
| Deuxième audit réussi et fin de `A` | `[R,D,G,T,P]` | Retour explicite `COMPLETE(A)` avec `{"logged":true}` | `completed(L)` ; résultat du propriétaire toujours `p-2` |
| Consommation du retour, puis finalisation de `P` | `[R,D,G,T]` | `READY`, puis continuation dépilée | `completed(P)` avec `{"paymentId":"p-2"}` |
| Fin de `T`, de `G` et du workflow | `[R]` à la fin | Sortie finale `{"paymentId":"p-2"}` | `G` et workflow terminés normalement |

La notification de retry existante porte sur le périmètre `T`. La projection du dashboard doit rattacher
le retry à la chaîne fautive concernée, notamment au propriétaire `P` et à sa sous-étape `L`, et ne pas
se limiter à la dernière erreur la plus profonde. Vérifier deux tentatives cohérentes et aucune invocation
laissée en cours après l'échec du premier audit.

Dans la variante avec retry placé dans un `try` à l'intérieur de `A`, les frames de `P` et son résultat
préparé restent présents pendant le retry : un seul appel de paiement, deux appels d'audit et un seul
succès final de `P`. Cette différence de nombre d'appels fait partie des assertions obligatoires.

## 14. Implementation clarifications

- Wire maps `google.protobuf.Value` to nullable `Any?`, collapsing absent values and JSON null. The four presence-sensitive continuation values therefore use `ExtensionJsonValueProto { google.protobuf.Value value = 1; }`. Presence belongs to the enclosing message: an empty present wrapper means JSON null; an absent wrapper is rejected. This adjustment preserves the domain contract and the proposed field numbers.
- The existing `ForProcessor` initializes its index to `0`, before scheduling its first child. The reference trace preserves that convention; it does not require changing iteration semantics for existing workflows.
- The dashboard source and timeline implementation from PR #69 is now merged into `main`, which is the base of the extensions PR.

- The current `InstanceMessageCodec` persists `pb64:`-prefixed protobuf bytes, despite the older repository policy describing ProtoJSON DB payloads. Extension continuations use that existing codec without a storage migration. Canonical ProtoJSON interoperability is checked independently with Buf-generated fixtures. The wrapper's presence is authoritative: an empty `ExtensionJsonValueProto` represents JSON null in Wire, while an absent wrapper is invalid for required data.
- Listener indexing now uses a definition-specific function reference in the stored filter key, while the serialized message retains its exact execution position. Legacy suffix keys remain readable; new registrations cannot collide merely because two functions share a relative task path. No table or column is added.
- Error unwinding also closes previously abandoned ordinary try-body blocks. This intentionally adds their missing `task.faulted` event; workflow results and control flow are unchanged.

- A fresh events worker hydrates remote-function listener filters only for active, persisted function registrations. This preserves lazy remote resolution. While remote references remain unresolved, ingestion performs an additional distinct-key lookup scoped to the arrival namespace; it does not scan or deserialize workflow payloads. Resolution failures use the normal message retry path.
- Remote function resolution errors encountered during workflow navigation now enter the caller's error boundary, including a local try inside an extension. They no longer escape the orchestrator before extension cleanup.

### Validation record

The implementation is rebased onto `main` after dashboard PR #69 merged. The new base has the same source tree as the previously tested dashboard branch; only the dependency notes changed during the rebase.

- Kotlin sources and Wire generated messages compile with the repository's Kotlin/Wire versions and JVM 17 bytecode target.
- The complete core/common test selection passes, including the existing workflow execution fixtures, lifecycle replay tests, and the new extension cases. Targeted serialization tests cover all phases, absent fields, index zero, JSON null, explicit returns, and a canonical ProtoJSON fixture produced by Buf.
- Listener repository and routing scenarios run in the existing H2/PostgreSQL/MySQL suites. H2 and service tests pass locally; Docker-dependent PostgreSQL/MySQL executions are skipped by the existing `RequiresDocker` condition in this environment.
- Transport and actual durable `pb64:` payload round trips pass, as do analytics payload and gateway streaming tests.
- Dashboard tests, TypeScript/production build, and ESLint pass. Source lookup, local/outer retries, loop ownership, streaming/reload, and active HTTP body durations are covered.
- `buf lint`, `buf build`, and `buf breaking` against the starting branch pass.
- Gradle cannot initialize its local lock/socket services in this sandbox. The checks above were run with the cached Kotlin compiler, Wire generator, and JUnit Platform/Kotest launchers. Full logs are in `/tmp/lemline-<module>-extensions.log`. A normal Gradle run and live PostgreSQL/MySQL validation remain required before merge.

### Runner end-to-end coverage

`ExtensionTaskTestCases` adds 14 shared workflow scenarios to the existing InMemory, Kafka, RabbitMQ,
and PGMQ execution suites (56 runner test instances), as well as `FullOrchestrator` with `serde = true`.
The runner scenarios exercise command/event transport, durable wait/retry/listener/parent/fork services,
and lifecycle ingestion into analytics. HTTP activities use the existing mock configuration mechanism.

Assertions cover frozen selection and block ordering, delayed loop initialization, short circuits,
prepared HTTP results across an after suspension, actual listener delivery, child-workflow completion,
mixed `_fn`/`_ext` paths after cache eviction, local versus outer retry invocation counts, both fork modes,
and error origin/owner closure after suspension. The lifecycle assertions read the persisted analytics
history, including subsequent pages, and wait for late event batches instead of treating workflow
completion alone as proof that its tasks were closed.

Run these cases through the normal runner suite, without a separate CI workflow:

```bash
set -o pipefail
./gradlew :lemline-runner:test --tests '*ExtensionExecutionTest' \
  --tests '*AnalyticsWorkflowResultAwaiterTest' 2>&1 | tee /tmp/lemline-runner-extensions.log
```

Validation after adding these cases: 762 core/common tests pass (6 existing skips), including all 14
shared extension scenarios; the runner test sources compile and the three analytics-awaiting harness
tests pass. Execution of the Quarkus runner scenarios remains unverified here: the Gradle wrapper is
blocked opening its lock file by sandbox permissions. These results do not replace a successful run
of the broker suites.
