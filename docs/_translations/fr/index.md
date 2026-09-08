---
title: Porter
lang: fr
translation_key: index
language_name: Français
description: Porter est un fork minimaliste et maintenu de Shizuku qui donne aux applications Android un accès ADB via les API de Shizuku, avec une prise en charge facultative du root.
---
# L’accès ADB pour vos applications
{: #adb-access-for-your-apps }

Porter est un fork minimaliste et maintenu de [Shizuku](https://github.com/thedjchi/Shizuku) qui donne aux applications Android un accès ADB via les API de Shizuku, avec une prise en charge facultative du root.

Vous choisissez quelles applications peuvent l’utiliser.

## Pour les utilisateurs
{: #for-users }

1. Téléchargez Porter depuis [GitHub Releases](https://github.com/d4rken-org/porter/releases).
2. Suivez le [guide d’installation et de démarrage](/setup).
3. Ouvrez une application compatible et autorisez sa demande d’accès à Porter.

Android 7.0 ou version ultérieure est requis. À partir d’Android 11, le débogage sans fil permet de démarrer Porter sans ordinateur. Les appareils plus anciens nécessitent un ordinateur ou un accès root.

Aide complémentaire : [compatibilité des applications](/compatibility) et [dépannage](/troubleshooting).

## Vous utilisez déjà Shizuku ?
{: #already-using-shizuku }

Porter possède sa propre identité d’application et peut fonctionner en parallèle de Shizuku. Les applications qui ne reconnaissent que Shizuku nécessitent le compagnon facultatif Porter Compatibility, qui remplace l’application Shizuku installée.

[Trouvez la configuration adaptée à vos applications](/compatibility).

## Pourquoi Porter existe
{: #why-porter-exists }

J’utilise Shizuku dans mes applications [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) et [Butler](https://github.com/d4rken-org/butler). L’application Shizuku originale n’étant plus activement maintenue, j’ai créé Porter pour proposer une alternative minimaliste, stable et maintenue aux applications qui ont besoin d’un accès ADB.

## Pour les développeurs
{: #for-developers }

Les autres développeurs sont invités à prendre directement en charge l’autorisation de Porter. [Ajoutez la prise en charge directe de Porter à votre application](/developers).

## Besoin d’aide ?
{: #need-help }

Consultez le [dépannage](/troubleshooting) ou [signalez un problème](https://github.com/d4rken-org/porter/issues).

Porter poursuit Shizuku de manière indépendante, à partir du travail de [RikkaApps](https://github.com/RikkaApps/Shizuku), de [thedjchi](https://github.com/thedjchi/Shizuku) et de leurs contributeurs.
