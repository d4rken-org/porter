---
title: Porter
lang: es
translation_key: index
language_name: Español
description: Porter es una bifurcación minimalista y mantenida de Shizuku que proporciona acceso ADB a las apps de Android mediante las API de Shizuku, con soporte opcional para root.
---
# Acceso ADB para tus apps
{: #adb-access-for-your-apps }

Porter es una bifurcación minimalista y mantenida de [Shizuku](https://github.com/thedjchi/Shizuku) que proporciona acceso ADB a las apps de Android mediante las API de Shizuku, con soporte opcional para root.

Tú eliges qué apps pueden usarlo.

## Para usuarios
{: #for-users }

1. Descarga Porter desde [GitHub Releases](https://github.com/d4rken-org/porter/releases).
2. Sigue la [guía de instalación e inicio](/setup).
3. Abre una app compatible y aprueba su solicitud de acceso a Porter.

Se requiere Android 7.0 o posterior. En Android 11 o posterior, la depuración inalámbrica permite iniciar Porter sin un ordenador. Los dispositivos más antiguos necesitan un ordenador o root.

Más ayuda: [compatibilidad de apps](/compatibility) y [solución de problemas](/troubleshooting).

## ¿Ya usas Shizuku?
{: #already-using-shizuku }

Porter tiene su propia identidad de aplicación y puede ejecutarse junto a Shizuku. Las apps que solo reconocen Shizuku necesitan el complemento opcional Porter Compatibility, que sustituye a la app de Shizuku instalada.

[Encuentra la configuración adecuada para tus apps](/compatibility).

## Por qué existe Porter
{: #why-porter-exists }

Uso Shizuku en mis propias apps, [SD Maid SE](https://github.com/d4rken-org/sdmaid-se) y [Butler](https://github.com/d4rken-org/butler). La app original de Shizuku dejó de mantenerse activamente, así que creé Porter para ofrecer una alternativa minimalista, estable y mantenida a las apps que necesitan acceso ADB.

## Para desarrolladores
{: #for-developers }

Invitamos a otros desarrolladores a admitir directamente el permiso de Porter. [Añade soporte directo para Porter a tu app](/developers).

## ¿Necesitas ayuda?
{: #need-help }

Consulta la [solución de problemas](/troubleshooting) o [comunica un problema](https://github.com/d4rken-org/porter/issues).

Porter es una continuación independiente de Shizuku, basada en el trabajo de [RikkaApps](https://github.com/RikkaApps/Shizuku), [thedjchi](https://github.com/thedjchi/Shizuku) y sus colaboradores.
