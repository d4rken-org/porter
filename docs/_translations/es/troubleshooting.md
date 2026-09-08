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

Si instalas **Porter Compatibility**, elimina Shizuku primero. El complemento no puede actualizar una instalación de Shizuku con otra firma, aunque Android reconozca la misma identidad de aplicación.

En Porter, una compilación de desarrollo antigua puede tener una firma diferente a la versión pública. Android no permite instalar una sobre la otra. Desinstalar la anterior también elimina sus datos; anota tu configuración antes de hacerlo. Reinstala desde la fuente elegida y autoriza tus apps de nuevo.

## El acceso está permitido, pero una operación falla
{: #access-is-allowed-but-an-operation-still-fails }

El acceso de depuración es más limitado que root. Las versiones de Android y los fabricantes imponen restricciones adicionales, y Porter no puede habilitar todas las operaciones de las apps. Comprueba también los requisitos de la app cliente.

En dispositivos Xiaomi/POCO con MIUI, las Opciones para desarrolladores pueden incluir un interruptor independiente **Depuración USB (Ajustes de seguridad)**. Activar solo la depuración USB normal puede dejar restringidas las operaciones de gestión de apps. Activa el ajuste adicional si quieres usarlas y reinicia Porter. El nombre y la disponibilidad varían según la versión del sistema.

Algunos sistemas OPPO/OnePlus incluyen **Supervisión de permisos** en Opciones para desarrolladores, que restringe el acceso de depuración. Desactivarlo puede permitir la operación, pero cambia la protección del fabricante. Estas soluciones proceden de la [guía original de Shizuku](https://shizuku.rikka.app/guide/setup/); aún no se han verificado con Porter en dispositivos físicos.

## La versión mostrada durante la ejecución es diferente
{: #the-version-shown-while-running-is-different }

**Versión**, en **Ajustes**, muestra la versión de la app Porter. Toca la tarjeta que indica que Porter está en ejecución para ver la versión instalada, la del servicio Porter en ejecución y la de la API compatible de Shizuku. La versión de la API describe la compatibilidad, no el número de versión de Porter. Si Porter pide reiniciar el servicio tras una actualización, detenlo e inícialo de nuevo.

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
