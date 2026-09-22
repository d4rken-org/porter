---
title: Solución de problemas
lang: es
translation_key: troubleshooting
language_name: Español
description: Es normal tener que iniciar Porter de nuevo tras reiniciar el dispositivo si usas acceso de depuración. Abre Porter y utiliza tu método de inicio. La vinculación mediante depuración inalámbrica no inicia por sí sola el servicio.
---
# Solución de problemas
{: #troubleshooting }

## Porter no está en ejecución
{: #porter-is-not-running }

Es normal tener que iniciar Porter de nuevo tras reiniciar el dispositivo si usas acceso de depuración. Abre Porter y utiliza tu [método de inicio](/setup). La vinculación mediante depuración inalámbrica no inicia por sí sola el servicio.

## El inicio automático no funciona
{: #automatic-start-does-not-work }

Inicia Porter manualmente una vez después de instalarlo antes de usar **Iniciar al arrancar**. Un inicio correcto mediante depuración concede el permiso de ajustes de Android necesario para los siguientes inicios automáticos. Si una notificación menciona `WRITE_SECURE_SETTINGS`, inicia Porter con un ordenador y vuelve a intentarlo.

El inicio automático sigue dependiendo de que Android proporcione acceso de depuración y permita a Porter ejecutarse en segundo plano. Si falla, utiliza un método manual.

## La vinculación inalámbrica no termina
{: #wireless-pairing-does-not-finish }

- Mantén el dispositivo conectado a Wi-Fi y comprueba que la depuración inalámbrica esté activada.
- Permite las notificaciones de Porter para introducir el código. Permite también el acceso a dispositivos cercanos o a la red local si Android lo solicita.
- Mantén abierto el diálogo de código de Android mientras introduces el código en la notificación de Porter. Si caduca, abre un diálogo nuevo.
- Si introduces el código en el diálogo de Porter, copia el puerto de vinculación del diálogo de código, no el puerto de conexión de la pantalla principal de depuración inalámbrica.
- Si una VPN o una restricción de la red local bloquea la detección, prueba una red que permita la comunicación entre dispositivos.

Si se rechazó un código, elige **Reintentar** en la notificación. Si falta la notificación o sigue mostrando un código antiguo, vuelve a **Vinculación** en Porter y elige **Reiniciar la vinculación**. Cierra el diálogo de código antiguo de Android, abre uno nuevo e introduce el código nuevo. No necesitas borrar la caché ni los datos de Porter.

En Android TV, Porter muestra un resultado cuando la vinculación se completa, falla o se agota el tiempo. Elige **Reintentar** tras un fallo y vuelve a activar el servicio de accesibilidad de vinculación si se te pide. Si Porter no se abre automáticamente, ábrelo para ver el resultado guardado. Tras una vinculación correcta queda el paso independiente **Iniciar**.

Las vinculaciones nuevas aparecen como **Porter** en la lista de dispositivos vinculados de Android. Una entrada existente puede seguir llamándose **shizuku** hasta que la olvides y vincules de nuevo. Cambiar el nombre mostrado no obliga a sustituir la clave guardada, y una vinculación existente puede seguir funcionando. Esta etiqueta es independiente de las insignias de la API de Shizuku en la lista de apps de Porter.

Si la depuración inalámbrica no está disponible o no es fiable, [inicia Porter desde un ordenador](/setup#with-a-computer).

## El ordenador no encuentra el dispositivo
{: #the-computer-cannot-find-the-device }

Ejecuta `adb devices`. Si aparece `unauthorized`, desbloquea el dispositivo y aprueba la solicitud de depuración. Si no aparece nada, comprueba la depuración USB, prueba un cable USB de datos y otro puerto USB, y comprueba si necesitas el controlador USB del fabricante.

Si el comando de inicio indica que falta un archivo, copia un comando nuevo desde **Ver comando** en la app Porter instalada.

## Porter se detiene repetidamente
{: #porter-keeps-stopping }

Comprueba primero si el dispositivo se ha reiniciado o Android ha desactivado la depuración. Inicia Porter de nuevo si es necesario.

Si se detiene sin reiniciar el dispositivo, revisa los ajustes de batería y ejecución en segundo plano del fabricante para Porter. Permite la actividad en segundo plano si está restringida. Los cambios de red y las modificaciones de Android del fabricante pueden afectar al acceso de depuración.

Comunica las detenciones repetidas indicando el modelo, la versión de Android, el método de inicio y qué ocurrió justo antes de detenerse Porter.

## Una app no puede conectarse
{: #an-app-cannot-connect }

1. Confirma que Porter esté en ejecución.
2. Comprueba [si la app necesita Porter Compatibility](/compatibility).
3. Si tiene selector de servicio, elige Porter, fuerza la detención de la app en los ajustes de Android y ábrela de nuevo.
4. Activa la integración en la app y aprueba la solicitud de Porter.
5. Revisa la app en **Aplicaciones** de Porter y comprueba que **Permitir el acceso de las apps** esté activado.

Si usas el complemento, ambos APK de Porter deben proceder de la misma fuente de publicación y tener certificados de firma coincidentes. Eliminar el complemento impide que los clientes antiguos utilicen Porter.

## Android no instala un APK
{: #android-wont-install-an-apk }

Si instalas a mano el APK independiente de **Porter Compatibility**, elimina Shizuku primero. El complemento no puede actualizar una instalación de Shizuku con otra firma, aunque Android reconozca la misma identidad de aplicación. El reemplazo integrado de Porter desinstala Shizuku por ti.

En Porter, una compilación de desarrollo antigua puede tener una firma diferente a la versión pública. Android no permite instalar una sobre la otra. Desinstalar la anterior también elimina sus datos; anota tu configuración antes de hacerlo. Reinstala desde la fuente elegida y autoriza tus apps de nuevo.

## Una advertencia de instalación en el televisor no se puede seleccionar con el mando
{: #a-tv-installation-warning-cannot-be-selected-with-the-remote }

La advertencia de Play Protect y sus botones **Más detalles** o **Instalar de todos modos** pertenecen al instalador de Android. Porter no puede cambiar su comportamiento de foco con el mando, y la advertencia por sí sola no indica por qué Google ha marcado un APK.

Comprueba que el APK proceda de la [página de versiones de Porter](https://github.com/d4rken-org/porter/releases). Si decides continuar después de leer la advertencia, un ratón USB o Bluetooth puede permitirte seleccionar esos botones. Si ya tienes una conexión ADB autorizada con el televisor, también puedes instalar desde ese ordenador el APK descargado:

```sh
adb -s TV_SERIAL install -r /ruta/al/porter-compat.apk
```

Sustituye `TV_SERIAL` por la entrada del televisor que muestra `adb devices` y usa la ruta real del APK descargado. Android puede seguir bloqueando la instalación o pedir una confirmación. Esto no corrige los controles de Play Protect ni garantiza que se permita la instalación.

Si la instalación sigue bloqueada, comunica el texto exacto de la advertencia, el modelo del televisor, la versión de Android y la versión de Porter Compatibility. Las apps con soporte directo de Porter no necesitan el APK de compatibilidad.

## El acceso está permitido, pero una operación falla
{: #access-is-allowed-but-an-operation-still-fails }

El acceso de depuración es más limitado que root. Las versiones de Android y los fabricantes imponen restricciones adicionales, y Porter no puede habilitar todas las operaciones de las apps. Comprueba también los requisitos de la app cliente.

En dispositivos Xiaomi/POCO con MIUI, las Opciones para desarrolladores pueden incluir un interruptor independiente **Depuración USB (Ajustes de seguridad)**. Activar solo la depuración USB normal puede dejar restringidas las operaciones de gestión de apps. Activa el ajuste adicional si quieres usarlas y reinicia Porter. El nombre y la disponibilidad varían según la versión del sistema.

Algunos sistemas OPPO/OnePlus incluyen **Supervisión de permisos** en Opciones para desarrolladores, que restringe el acceso de depuración. Desactivarlo puede permitir la operación, pero cambia la protección del fabricante. Estas soluciones proceden de la [guía original de Shizuku](https://shizuku.rikka.app/guide/setup/); aún no se han verificado con Porter en dispositivos físicos.

## La versión mostrada durante la ejecución es diferente
{: #the-version-shown-while-running-is-different }

**Versión**, en **Ajustes**, muestra la versión de la app Porter. Toca la tarjeta del servicio para abrir **Servicio**. Ahí aparecen la app instalada y el servicio en ejecución con sus nombres de versión, códigos de versión e identificadores de compilación, además de la versión compatible de la API de Shizuku. Para compartir datos de diagnóstico, [graba un registro de depuración](#report-a-problem) en **Ajustes**, **Ayuda y soporte**. La versión de la API describe la compatibilidad, no el número de versión de Porter. Si hay una compilación distinta aparece **Actualización del servicio disponible**. Las funciones compatibles siguen disponibles; abrir Porter no reinicia el servicio. Toca **Actualizar servicio** en la pantalla Servicio desde el usuario principal de Android y confirma para aplicar la compilación instalada con los privilegios del servicio en ejecución, sin un ordenador ni una nueva conexión de depuración inalámbrica. Las apps conectadas se desconectan brevemente y los permisos se conservan. Si el reemplazo falla, usa **Reintentar actualización** mientras el servicio anterior siga en ejecución, o inícialo de nuevo con tu método de inicio habitual.

En **Ajustes**, **Actualizar el servicio automáticamente** comprueba de forma opcional si hay un servicio en ejecución con otra compilación después de actualizar la app Porter. Está desactivado de forma predeterminada. Android puede retrasar el trabajo en segundo plano; el botón de actualización manual sigue disponible. Un servicio detenido permanece detenido. Las actualizaciones fallidas o interrumpidas se muestran en la pantalla de inicio, incluso con el watchdog activado.

## Comunicar un problema
{: #report-a-problem }

Toca el icono de ajustes en Porter y abre **Ayuda y soporte**. Puedes contactar por correo electrónico, visitar la [comunidad de Discord](https://discord.gg/5hXXgwKNgm) o [abrir una incidencia](https://github.com/d4rken-org/porter/issues).

Para incluir un registro, elige **Grabar registro de depuración**, reproduce el problema y pulsa **Detener la grabación**. Selecciona la grabación en el formulario de contacto o compártela desde **Registros de depuración guardados**. Los registros permanecen en tu dispositivo hasta que los compartes. Pueden contener nombres de apps, detalles del dispositivo y acciones realizadas mediante Porter.

Incluye:

- Versión de Porter y si Porter Compatibility está instalado.
- Modelo del dispositivo y versión de Android.
- Cómo iniciaste Porter: depuración inalámbrica, ordenador o root.
- La app afectada y su versión.
- Qué hiciste, qué esperabas y qué ocurrió.

Revisa las capturas y los registros para detectar información personal antes de adjuntarlos. No incluyas códigos de vinculación inalámbrica ni claves privadas.
