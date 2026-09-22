---
title: Compatibilité des applications
lang: fr
translation_key: compatibility
language_name: Français
description: Porter prend en charge les API de Shizuku utilisées par les applications compatibles. La nécessité du compagnon facultatif dépend de leur méthode de connexion.
---
# Compatibilité des applications
{: #app-compatibility }

Porter prend en charge les API de Shizuku utilisées par les applications compatibles. La nécessité du compagnon facultatif dépend de leur méthode de connexion.

| Prise en charge par votre application | À installer |
| --- | --- |
| Porter directement | Porter |
| Shizuku uniquement | Porter et Porter Compatibility ; Porter remplace Shizuku s’il est installé |
| Les deux, avec un sélecteur de service | Porter, puis choisissez Porter dans l’application |

Le compagnon aide les applications Shizuku existantes à trouver Porter. Porter continue de démarrer le service, d’afficher les demandes d’autorisation et de gérer les accès. Gardez le compagnon installé pour les applications qui en ont besoin.

## Passer de Shizuku à Porter
{: #switch-from-shizuku }

Pour une application qui prend directement en charge Porter, vous pouvez garder Shizuku installé. Démarrez Porter, sélectionnez-le dans l’application et acceptez la nouvelle demande. Si l’application doit redémarrer, forcez son arrêt dans les paramètres Android, puis rouvrez-la.

Pour une application qui ne prend en charge que Shizuku, la version FOSS inclut l’APK Porter Compatibility correspondant :

1. Installez et démarrez Porter. Gardez Shizuku installé tant que vous n’avez pas examiné le remplacement.
2. Ouvrez **Compatibilité Shizuku** depuis l’accueil ou depuis les **Paramètres**.
3. Si Shizuku est installé, choisissez **Remplacer**. Confirmez **Passer à Porter**. Porter arrête Shizuku, remplace son application et reprend automatiquement les décisions d’accès éligibles. S’il ne peut pas arrêter le service, il vous demande de l’arrêter dans Shizuku et de réessayer.
4. Sinon, choisissez **Installer automatiquement**.
5. Revenez à l’application cliente. Acceptez l’accès si vous n’avez pas importé de décision existante. Si elle ne se connecte toujours pas, forcez son arrêt dans les paramètres Android et rouvrez-la.

Porter importe automatiquement les décisions qu’il peut vérifier auprès des applications installées et de leur accès actuel. Les décisions Porter existantes sont prioritaires. Les paramètres de l’application Shizuku et sa configuration d’association ne sont pas importés. Si la base de données des accès est illisible, vous pouvez continuer et autoriser les applications à nouveau. Porter enregistre les décisions d’accès éligibles avant de supprimer Shizuku ; si l’installation échoue, réessayez ou utilisez **Manuel**, puis **Importer les autorisations enregistrées**.

Le remplacement intégré est disponible depuis l’utilisateur Android principal. Si Shizuku est installé pour un autre utilisateur ou profil, traitez cette installation séparément. Porter ne supprime pas automatiquement les applications d’un autre utilisateur.

L’APK du compagnon reste téléchargeable séparément depuis la même version. Pour une installation manuelle, arrêtez et désinstallez Shizuku, installez Porter Compatibility, puis démarrez Porter. Utilisez le remplacement intégré de Porter pour transférer les décisions d’accès éligibles ; ouvrir la boîte de dialogue de remplacement ne suffit pas à enregistrer un import. Les restrictions d’installation de l’appareil peuvent aussi s’appliquer à l’installateur intégré ; l’action **Manuel** ouvre l’installateur d’Android.

Une fois installée, la page d’accueil affiche la version de l’application de compatibilité et le nombre d’applications installées qui passent par elle. Touchez sa carte pour voir les détails, réinstaller la copie incluse ou la désinstaller. Porter tente d’abord de désinstaller via son service et ouvre le désinstallateur d’Android en cas d’échec. La retirer interrompt les applications qui ont besoin de la compatibilité ; celles qui prennent directement en charge Porter continuent de fonctionner. L’écran de compatibilité vérifie automatiquement les changements tant qu’il est ouvert.
## Porter et Shizuku peuvent-ils fonctionner ensemble ?
{: #can-porter-and-shizuku-run-together }

Oui. Les deux peuvent être installés et fonctionner simultanément. Une application qui permet de choisir entre eux se connecte à un seul service à la fois.

**Porter Compatibility ne peut pas être installé en même temps que Shizuku.** Il utilise l’identité Android de Shizuku pour les anciennes applications. Android les considère comme deux installations concurrentes d’une même application. Cela concerne aussi les forks utilisant cette identité.

Pour revenir à Shizuku, désinstallez Porter Compatibility et réinstallez Shizuku. Redémarrez l’application cliente et autorisez son accès dans Shizuku. Vous pouvez garder Porter installé séparément.

## Une application demande toujours Shizuku
{: #an-app-still-asks-for-shizuku }

Les paramètres d’une ancienne application peuvent continuer à afficher Shizuku alors que Porter fournit l’accès. C’est normal.

Le compagnon couvre les méthodes habituelles de détection de Shizuku. Les applications dépendant d’écrans précis, de composants internes ou d’API beaucoup plus anciennes peuvent nécessiter une mise à jour. Si une application ne se connecte pas, indiquez son nom et sa version dans un [signalement à Porter](https://github.com/d4rken-org/porter/issues).
