---
title: Instalar e iniciar Porter
lang: es
translation_key: setup
language_name: Español
description: Descarga Porter desde GitHub Releases y sigue estos pasos.
---
# Instalar e iniciar Porter
{: #install-and-start-porter }

Descarga Porter desde [GitHub Releases](https://github.com/d4rken-org/porter/releases) y sigue estos pasos.

## Instalación
{: #install }

1. Descarga el APK de Porter de la sección **Assets** de la versión. Los archivos ZIP y TAR de código fuente no son apps de Android.
2. Abre el APK en tu dispositivo. Si Android lo solicita, permite que tu navegador o gestor de archivos instale apps de esta fuente.
3. Abre Porter.

Para las apps que admiten Porter directamente, basta con Porter. Si una app solo admite Shizuku, instala también **Porter Compatibility** de la misma versión. Desinstala Shizuku primero: el complemento no puede instalarse junto a él. Mantén el complemento instalado mientras uses esas apps con Porter. Consulta la [guía de compatibilidad](/compatibility).

## Elige cómo iniciarlo
{: #choose-how-to-start }

| Tu dispositivo | Método de inicio |
| --- | --- |
| Android 11 o posterior con depuración inalámbrica | [Depuración inalámbrica](#wireless-debugging) |
| Android 7.0 o posterior y un ordenador | [Depuración por USB](#with-a-computer) |
| Ya tiene root | [Root](#root) |

## Depuración inalámbrica
{: #wireless-debugging }

Necesitas una conexión Wi-Fi y Android 11 o posterior. Algunos fabricantes restringen la depuración inalámbrica; si no está disponible, usa un ordenador.

1. Activa las **Opciones para desarrolladores** en los ajustes de Android. Normalmente debes abrir **Información del teléfono** y tocar **Número de compilación** siete veces. La ubicación varía según el dispositivo.
2. En Opciones para desarrolladores, activa **Depuración por USB** y **Depuración inalámbrica**. Acepta la autorización de red de Android si aparece.
3. En Porter, toca **Vinculación** en la sección de inicio mediante depuración inalámbrica. Permite las notificaciones y, si se solicita, el acceso a dispositivos cercanos o a la red local.
4. Abre los ajustes de **Depuración inalámbrica** de Android y toca **Emparejar dispositivo con código de sincronización**. Mantén el diálogo abierto.
5. Expande la notificación de vinculación de Porter e introduce el código que muestra Android. Espera a que la vinculación termine correctamente.
6. Vuelve a Porter y toca **Iniciar** en la sección de depuración inalámbrica.
7. Comprueba que Porter indique que está en ejecución.

Porter deja activados los ajustes de depuración de Android cuando se detiene. Puedes desactivarlos en Opciones para desarrolladores cuando ya no necesites acceso de depuración.

Normalmente solo necesitas vincular el dispositivo una vez. Iniciar el servicio es un paso independiente que debes repetir tras reiniciar el dispositivo. Si Android olvida la vinculación, repite estos pasos.

Si seleccionaste el diálogo dentro de la app en **Ajustes**, **Inicio**, **Método de vinculación**, espera a que el diálogo de Porter descubra el servicio e introduce allí el código. Si solicita un puerto, usa el puerto de vinculación del diálogo de código de Android, no el puerto de conexión de la pantalla principal de depuración inalámbrica.

## Con un ordenador
{: #with-a-computer }

1. Instala las [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) de Google en tu ordenador.
2. Activa las Opciones para desarrolladores y la **Depuración por USB** en el dispositivo Android.
3. Conéctalo con un cable USB de datos. Desbloquéalo y aprueba la conexión de depuración. Autoriza solo ordenadores de confianza.
4. Abre un terminal en la carpeta Platform-Tools y ejecuta `adb devices`. En Windows PowerShell usa `./adb.exe devices`; en macOS o Linux, usa `./adb devices` si ADB no está en tu PATH.
5. En Porter, busca la sección de inicio mediante un ordenador y toca **Ver comando**. Ejecuta ese comando exacto en tu ordenador, ajustando el prefijo del ejecutable `adb` como se indica arriba si es necesario.
6. Comprueba que Porter esté en ejecución. Después puedes desconectar el cable.

Si hay varios dispositivos conectados, inserta `-s DEVICE_SERIAL` inmediatamente después de `adb` en el comando mostrado. Usa el número de serie que muestra `adb devices` para el dispositivo donde se ejecuta Porter.

Obtén un comando nuevo de Porter tras actualizarlo o reinstalarlo: la ruta puede cambiar. No uses un comando de inicio copiado de Shizuku o de otra instalación.

## Root
{: #root }

Este método es para dispositivos que ya tienen acceso root funcional. Instalar Porter no rootea el dispositivo.

1. Abre Porter y toca **Iniciar** en la sección de inicio con root.
2. Aprueba la solicitud de Porter en tu gestor de root.
3. Comprueba que Porter esté en ejecución y muestre root como modo de inicio.

Detén el servicio de Porter antes de cambiar entre root y acceso de depuración.

## Autorizar una app
{: #allow-an-app }

Abre la app que quieras usar, activa su integración con Porter o Shizuku y aprueba la solicitud de acceso de Porter. Autoriza solo apps de confianza: pueden realizar tareas con el acceso de depuración o root de Porter.

Para retirar el acceso, toca **Aplicaciones** en Porter y desactiva la autorización de esa app. Porter y Shizuku mantienen autorizaciones independientes.

Para pausar el acceso de todas las apps, desactiva **Permitir el acceso de las apps** en la parte superior de esa pantalla. Las autorizaciones individuales se conservan. Vuelve a activarlo para reanudar el acceso. Los comandos de shell ya iniciados pueden continuar durante la pausa.

Si la app tiene un selector de servicio, elige Porter. Sigue las instrucciones de la propia app. Si cambiar la selección requiere reiniciarla, usa **Forzar detención** en los ajustes de la app en Android y vuelve a abrirla.

## Detener Porter
{: #stop-porter }

Toca la tarjeta que indica que Porter está en ejecución y elige **Detener Porter**. Las apps conectadas pierden el acceso hasta que vuelvas a iniciar Porter.

## Actualizar Porter
{: #update-porter }

Instala el APK más reciente sobre la app existente e inicia Porter de nuevo. Si usas Porter Compatibility, actualízalo desde la misma versión. Android exige firmas coincidentes para las actualizaciones; consulta los [problemas de instalación](/troubleshooting#android-wont-install-an-apk) si no lo permite.
