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
| Shizuku uniquement | Porter et Porter Compatibility ; désinstallez d’abord Shizuku |
| Les deux, avec un sélecteur de service | Porter, puis choisissez Porter dans l’application |

Le compagnon aide les applications Shizuku existantes à trouver Porter. Porter continue de démarrer le service, d’afficher les demandes d’autorisation et de gérer les accès. Gardez le compagnon installé pour les applications qui en ont besoin.

## Passer de Shizuku à Porter
{: #switch-from-shizuku }

Pour une application qui prend directement en charge Porter, vous pouvez garder Shizuku installé. Démarrez Porter, sélectionnez-le dans l’application et acceptez la nouvelle demande. Si l’application doit redémarrer, forcez son arrêt dans les paramètres Android, puis rouvrez-la.

Pour une application qui ne prend en charge que Shizuku :

1. Arrêtez Shizuku et désinstallez son application de gestion. Les applications qui utilisent Shizuku peuvent rester installées.
2. Installez Porter et l’APK de Porter Compatibility de la même version.
3. Démarrez Porter.
4. Forcez l’arrêt de l’application cliente dans les paramètres Android, rouvrez-la et activez son intégration Shizuku.
5. Acceptez la demande d’accès affichée par Porter.

Les anciennes autorisations Shizuku ne sont pas transférées. Vous choisissez à nouveau les applications autorisées à utiliser Porter.

## Porter et Shizuku peuvent-ils fonctionner ensemble ?
{: #can-porter-and-shizuku-run-together }

Oui. Les deux peuvent être installés et fonctionner simultanément. Une application qui permet de choisir entre eux se connecte à un seul service à la fois.

**Porter Compatibility ne peut pas être installé en même temps que Shizuku.** Il utilise l’identité Android de Shizuku pour les anciennes applications. Android les considère comme deux installations concurrentes d’une même application. Cela concerne aussi les forks utilisant cette identité.

Pour revenir à Shizuku, désinstallez Porter Compatibility et réinstallez Shizuku. Redémarrez l’application cliente et autorisez son accès dans Shizuku. Vous pouvez garder Porter installé séparément.

## Une application demande toujours Shizuku
{: #an-app-still-asks-for-shizuku }

Les paramètres d’une ancienne application peuvent continuer à afficher Shizuku alors que Porter fournit l’accès. C’est normal.

Le compagnon couvre les méthodes habituelles de détection de Shizuku. Les applications dépendant d’écrans précis, de composants internes ou d’API beaucoup plus anciennes peuvent nécessiter une mise à jour. Si une application ne se connecte pas, indiquez son nom et sa version dans un [signalement à Porter](https://github.com/d4rken-org/porter/issues).
