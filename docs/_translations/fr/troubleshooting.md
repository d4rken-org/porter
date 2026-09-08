---
title: Dépannage
lang: fr
translation_key: troubleshooting
language_name: Français
description: Avec l’accès par débogage, il est normal de devoir relancer Porter après un redémarrage de l’appareil. Ouvrez Porter et utilisez votre mode de démarrage. L’association sans fil ne démarre pas à elle seule le service.
---
# Dépannage
{: #troubleshooting }

## Porter n’est pas en cours d’exécution
{: #porter-is-not-running }

Avec l’accès par débogage, il est normal de devoir relancer Porter après un redémarrage de l’appareil. Ouvrez Porter et utilisez votre [mode de démarrage](/setup). L’association sans fil ne démarre pas à elle seule le service.

## Le démarrage automatique ne fonctionne pas
{: #automatic-start-does-not-work }

Démarrez Porter manuellement une fois après l’installation avant de compter sur **Lancer au démarrage**. Un démarrage réussi par débogage accorde l’autorisation de paramètres Android nécessaire aux démarrages automatiques suivants. Si une notification mentionne `WRITE_SECURE_SETTINGS`, démarrez Porter avec un ordinateur, puis réessayez.

Le démarrage automatique dépend toujours de la disponibilité du débogage dans Android et de l’autorisation d’exécuter Porter en arrière-plan. En cas d’échec, utilisez un démarrage manuel.

## L’association sans fil n’aboutit pas
{: #wireless-pairing-does-not-finish }

- Restez connecté au Wi-Fi et vérifiez que le débogage sans fil est activé.
- Autorisez les notifications de Porter pour saisir le code. Autorisez aussi les appareils à proximité ou le réseau local si Android le demande.
- Gardez le dialogue de code Android ouvert pendant la saisie dans la notification Porter. Si le code expire, ouvrez un nouveau dialogue.
- Dans le dialogue intégré de Porter, utilisez le port d’association du dialogue de code, pas le port de connexion de l’écran principal du débogage sans fil.
- Si un VPN ou une restriction du réseau bloque la détection, essayez un réseau autorisant la communication entre appareils.

Si le débogage sans fil est indisponible ou instable, [démarrez depuis un ordinateur](/setup#with-a-computer).

## L’ordinateur ne trouve pas l’appareil
{: #the-computer-cannot-find-the-device }

Exécutez `adb devices`. Si `unauthorized` apparaît, déverrouillez l’appareil et acceptez la demande de débogage. Si rien ne s’affiche, vérifiez le débogage USB, essayez un câble USB de données et un autre port, puis vérifiez si le pilote USB du fabricant est nécessaire.

Si la commande de démarrage signale un fichier absent, copiez une nouvelle commande depuis **Voir la commande** dans Porter.

## Porter s’arrête régulièrement
{: #porter-keeps-stopping }

Vérifiez d’abord si l’appareil a redémarré ou si Android a désactivé le débogage. Relancez Porter si nécessaire.

Si l’appareil reste allumé, vérifiez les réglages du fabricant concernant la batterie et l’exécution en arrière-plan de Porter. Autorisez cette activité si elle est limitée. Les changements de réseau et les adaptations d’Android par le fabricant peuvent affecter le débogage.

Signalez les arrêts répétés avec le modèle, la version Android, le mode de démarrage et ce qui s’est passé juste avant l’arrêt.

## Une application ne peut pas se connecter
{: #an-app-cannot-connect }

1. Vérifiez que Porter est en cours d’exécution.
2. Vérifiez [si l’application nécessite Porter Compatibility](/compatibility).
3. Si elle propose un sélecteur de service, choisissez Porter, forcez son arrêt dans les paramètres Android et rouvrez-la.
4. Activez son intégration et acceptez la demande de Porter.
5. Vérifiez l’application dans **Applications** de Porter et assurez-vous que **Autoriser l’accès des applications** est activé.

Avec le compagnon, les deux APK doivent provenir de la même source de publication et avoir des certificats de signature concordants. Désinstaller le compagnon empêche les anciens clients d’utiliser Porter.

## Android refuse d’installer un APK
{: #android-wont-install-an-apk }

Pour installer **Porter Compatibility**, désinstallez d’abord Shizuku. Le compagnon ne peut pas mettre à jour une installation Shizuku signée différemment, même si Android reconnaît la même identité d’application.

Pour Porter, une ancienne version de développement peut avoir une signature différente de la version publique. Android refuse alors l’installation par-dessus. Désinstaller l’ancienne version supprime aussi ses données ; notez votre configuration avant de le faire. Réinstallez depuis la source souhaitée et autorisez à nouveau vos applications.

## L’accès est autorisé, mais une opération échoue
{: #access-is-allowed-but-an-operation-still-fails }

Le débogage offre moins de droits que le root. Android et les fabricants imposent des limites supplémentaires ; Porter ne peut pas rendre toutes les opérations possibles. Vérifiez aussi les exigences de l’application cliente.

Sur Xiaomi/POCO avec MIUI, les options pour les développeurs peuvent proposer **Débogage USB (paramètres de sécurité)** séparément. Le débogage USB ordinaire peut laisser la gestion des applications restreinte. Activez ce réglage supplémentaire pour ces opérations, puis redémarrez Porter. Son nom et sa disponibilité varient selon le système.

Certains systèmes OPPO/OnePlus proposent **Surveillance des autorisations** dans les options pour les développeurs. Ce réglage limite le débogage. Le désactiver peut permettre l’opération, mais modifie une protection du fabricant. Ces solutions viennent du [guide Shizuku original](https://shizuku.rikka.app/guide/setup/) et n’ont pas encore été vérifiées avec Porter sur des appareils physiques.

## La version affichée pendant l’exécution est différente
{: #the-version-shown-while-running-is-different }

L’entrée **Version** dans **Paramètres** indique la version de l’application Porter. Touchez la carte du service en cours d’exécution pour voir la version installée, celle du service Porter actif et celle de l’API Shizuku compatible. La version API décrit la compatibilité, pas la version de Porter. Si Porter demande un redémarrage du service après une mise à jour, arrêtez-le et redémarrez-le.

## Signaler un problème
{: #report-a-problem }

Touchez l’icône des paramètres dans Porter, puis **Aide et assistance**. Contactez l’assistance par e-mail, rejoignez la [communauté Discord](https://discord.gg/5hXXgwKNgm) ou [ouvrez un signalement](https://github.com/d4rken-org/porter/issues).

Pour joindre un journal, choisissez **Enregistrer un journal de débogage**, reproduisez le problème, puis **Arrêter l’enregistrement**. Sélectionnez l’enregistrement dans le formulaire de contact ou partagez-le depuis **Journaux de débogage enregistrés**. Les journaux restent sur votre appareil jusqu’au partage. Ils peuvent contenir des noms d’applications, des informations sur l’appareil et des actions effectuées via Porter.

Indiquez :

- La version de Porter et la présence de Porter Compatibility.
- Le modèle de l’appareil et la version Android.
- Le mode de démarrage : sans fil, ordinateur ou root.
- L’application concernée et sa version.
- Vos actions, le résultat attendu et le résultat obtenu.

Vérifiez les captures et journaux avant de les joindre afin de retirer les informations personnelles. N’incluez ni codes d’association sans fil ni clés privées.
