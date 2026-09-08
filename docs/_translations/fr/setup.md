---
title: Installer et démarrer Porter
lang: fr
translation_key: setup
language_name: Français
description: Téléchargez Porter depuis GitHub Releases, puis suivez ces étapes.
---
# Installer et démarrer Porter
{: #install-and-start-porter }

Téléchargez Porter depuis [GitHub Releases](https://github.com/d4rken-org/porter/releases), puis suivez ces étapes.

## Installation
{: #install }

1. Téléchargez l’APK de Porter dans la rubrique **Assets** de la version. Les archives ZIP et TAR du code source ne sont pas des applications Android.
2. Ouvrez l’APK sur votre appareil. Si Android le demande, autorisez votre navigateur ou gestionnaire de fichiers à installer des applications depuis cette source.
3. Ouvrez Porter.

Porter suffit pour les applications qui le prennent directement en charge. Pour une application qui ne prend en charge que Shizuku, installez aussi **Porter Compatibility** de la même version. Désinstallez d’abord Shizuku : le compagnon ne peut pas coexister avec lui. Gardez le compagnon installé tant que vous utilisez ces applications avec Porter. Consultez le [guide de compatibilité](/compatibility).

## Choisir le mode de démarrage
{: #choose-how-to-start }

| Votre appareil | Mode de démarrage |
| --- | --- |
| Android 11 ou ultérieur avec débogage sans fil | [Débogage sans fil](#wireless-debugging) |
| Android 7.0 ou ultérieur et un ordinateur | [Débogage USB](#with-a-computer) |
| Déjà rooté | [Root](#root) |

## Débogage sans fil
{: #wireless-debugging }

Une connexion Wi-Fi et Android 11 ou ultérieur sont nécessaires. Certains fabricants limitent le débogage sans fil ; s’il est indisponible, utilisez un ordinateur.

1. Activez les **Options pour les développeurs** dans les paramètres Android. En général, ouvrez **À propos du téléphone** et touchez sept fois **Numéro de build**. L’emplacement varie selon l’appareil.
2. Dans les options pour les développeurs, activez **Débogage USB** et **Débogage sans fil**. Acceptez la demande d’autorisation du réseau si elle apparaît.
3. Dans Porter, touchez **Association** dans la section de démarrage par débogage sans fil. Autorisez les notifications et, si demandé, l’accès aux appareils à proximité ou au réseau local.
4. Dans les paramètres Android de **Débogage sans fil**, touchez **Associer l’appareil avec un code d’association**. Gardez cette boîte de dialogue ouverte.
5. Développez la notification d’association de Porter et saisissez le code affiché par Android. Attendez la confirmation de l’association.
6. Revenez à Porter et touchez **Démarrer** dans la section du débogage sans fil.
7. Vérifiez que Porter indique qu’il est en cours d’exécution.

Lorsque Porter s’arrête, il laisse les paramètres de débogage Android activés. Vous pouvez les désactiver dans les options pour les développeurs quand vous n’en avez plus besoin.

L’association n’est normalement nécessaire qu’une fois. Le démarrage du service est une étape distincte, à répéter après chaque redémarrage de l’appareil. Si Android oublie l’association, recommencez ces étapes.

Si vous avez choisi la boîte de dialogue intégrée dans **Paramètres**, **Démarrage**, **Méthode d’association**, attendez que le dialogue de Porter détecte le service, puis saisissez-y le code. S’il demande un port, utilisez le port d’association du dialogue de code d’Android, pas le port de connexion de l’écran principal du débogage sans fil.

## Avec un ordinateur
{: #with-a-computer }

1. Installez les [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) de Google sur votre ordinateur.
2. Activez les options pour les développeurs et le **Débogage USB** sur l’appareil Android.
3. Branchez l’appareil avec un câble USB de données. Déverrouillez-le et autorisez la connexion de débogage. N’autorisez que les ordinateurs auxquels vous faites confiance.
4. Ouvrez un terminal dans le dossier Platform-Tools et exécutez `adb devices`. Dans Windows PowerShell, utilisez `./adb.exe devices` ; sous macOS ou Linux, utilisez `./adb devices` si ADB n’est pas dans votre PATH.
5. Dans Porter, ouvrez la section de démarrage avec un ordinateur et touchez **Voir la commande**. Exécutez exactement cette commande sur l’ordinateur, en adaptant si besoin le préfixe de l’exécutable `adb` comme indiqué ci-dessus.
6. Vérifiez que Porter est en cours d’exécution. Vous pouvez ensuite débrancher le câble.

Si plusieurs appareils sont connectés, insérez `-s DEVICE_SERIAL` juste après `adb` dans la commande affichée. Utilisez le numéro de série indiqué par `adb devices` pour l’appareil exécutant Porter.

Après une mise à jour ou une réinstallation, récupérez une nouvelle commande dans Porter : le chemin peut changer. N’utilisez pas une commande copiée de Shizuku ou d’une autre installation.

## Root
{: #root }

Cette méthode concerne les appareils disposant déjà d’un accès root fonctionnel. Installer Porter ne roote pas votre appareil.

1. Ouvrez Porter et touchez **Démarrer** dans la section root.
2. Acceptez la demande de Porter dans votre gestionnaire de root.
3. Vérifiez que Porter est en cours d’exécution et indique root comme mode de démarrage.

Arrêtez le service Porter avant de passer du root au débogage ou inversement.

## Autoriser une application
{: #allow-an-app }

Ouvrez l’application souhaitée, activez son intégration Porter ou Shizuku et acceptez la demande d’accès de Porter. N’autorisez que les applications de confiance : elles peuvent effectuer des opérations avec les droits de débogage ou root de Porter.

Pour retirer l’accès, touchez **Applications** dans Porter et désactivez l’autorisation de l’application. Porter et Shizuku conservent des autorisations distinctes.

Pour suspendre l’accès de toutes les applications, désactivez **Autoriser l’accès des applications** en haut de cet écran. Les autorisations individuelles sont conservées. Réactivez ce réglage pour rétablir l’accès. Les commandes shell déjà lancées peuvent continuer pendant la pause.

Si l’application propose un sélecteur de service, choisissez Porter. Suivez les instructions de l’application. Si le changement nécessite un redémarrage, utilisez **Forcer l’arrêt** dans ses paramètres Android, puis rouvrez-la.

## Arrêter Porter
{: #stop-porter }

Touchez la carte indiquant que Porter est en cours d’exécution, puis **Arrêter Porter**. Les applications connectées perdent l’accès jusqu’au prochain démarrage de Porter.

## Mettre à jour Porter
{: #update-porter }

Installez le nouvel APK par-dessus l’application existante et redémarrez Porter. Si vous utilisez Porter Compatibility, mettez-le à jour depuis la même version. Android exige des signatures concordantes ; consultez les [problèmes d’installation](/troubleshooting#android-wont-install-an-apk) en cas de refus.
