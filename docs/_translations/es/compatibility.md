---
title: Compatibilidad de apps
lang: es
translation_key: compatibility
language_name: Español
description: Porter admite las API de Shizuku que utilizan las apps compatibles. La necesidad del complemento opcional depende de cómo se conecte cada app.
---
# Compatibilidad de apps
{: #app-compatibility }

Porter admite las API de Shizuku que utilizan las apps compatibles. La necesidad del complemento opcional depende de cómo se conecte cada app.

| Qué admite tu app | Qué instalar |
| --- | --- |
| Porter directamente | Porter |
| Solo Shizuku | Porter y Porter Compatibility; Porter sustituye a Shizuku si está instalado |
| Ambos, con selector de servicio | Porter; después selecciona Porter en la app |

El complemento ayuda a las apps existentes de Shizuku a encontrar Porter. Porter sigue iniciando el servicio, mostrando las solicitudes de permiso y gestionando las autorizaciones. Mantén el complemento instalado mientras uses apps que lo necesiten.

## Cambiar desde Shizuku
{: #switch-from-shizuku }

Para una app con soporte directo de Porter, puedes mantener Shizuku instalado. Inicia Porter, selecciónalo en la app y aprueba la nueva solicitud. Una app que estaba conectada a Shizuku sigue con Shizuku hasta que se reinicia, así que fuerza su detención en los ajustes de Android y ábrela de nuevo.

Para una app que solo admite Shizuku, la versión FOSS incluye el APK de Porter Compatibility correspondiente:

1. Instala e inicia Porter. Mantén Shizuku instalado hasta que hayas revisado el reemplazo.
2. Abre **Compatibilidad con Shizuku** desde la pantalla de inicio o desde **Ajustes**.
3. Si Shizuku está instalado, elige **Reemplazar**. Confirma **Cambiar a Porter**. Porter detiene Shizuku, sustituye su app y transfiere automáticamente las decisiones de acceso aptas. Si Porter no puede detener el servicio, te pide que lo detengas en Shizuku y lo intentes de nuevo.
4. En caso contrario, elige **Instalar automáticamente**.
5. Vuelve a la app cliente. Aprueba el acceso si no importaste una decisión existente. Si la app sigue sin conectarse, fuerza su detención en los ajustes de Android y ábrela de nuevo.

Porter importa automáticamente las decisiones que puede comprobar frente a las apps instaladas y su acceso actual. Las decisiones existentes de Porter tienen prioridad. Los ajustes de la app de Shizuku y su configuración de vinculación no se importan. Si no se puede leer la base de datos de accesos, puedes continuar y autorizar las apps de nuevo. Porter guarda las decisiones de acceso aptas antes de eliminar Shizuku; si la instalación falla, reinténtalo o usa **Manual** y luego **Importar autorizaciones guardadas**.

El reemplazo integrado está disponible desde el usuario principal de Android. Si Shizuku está instalado para otro usuario o perfil, resuelve esa instalación por separado. Porter no elimina automáticamente las apps de otro usuario.

El APK del complemento sigue disponible como descarga independiente de la misma versión. Para la instalación manual, detén y desinstala Shizuku, instala Porter Compatibility e inicia Porter. Usa el reemplazo integrado de Porter para transferir las decisiones de acceso aptas; abrir el diálogo de reemplazo por sí solo no guarda ninguna importación. Las restricciones de instalación del dispositivo también pueden afectar al instalador integrado; la acción **Manual** abre el instalador de Android.

Una vez instalado, la pantalla de inicio muestra la versión de la app de compatibilidad y cuántas apps instaladas se conectan a través de ella. Toca su tarjeta para ver detalles, reinstalar la copia incluida o desinstalarla. Porter intenta desinstalar primero mediante su servicio y abre el desinstalador de Android si eso falla. Quitarla interrumpe las apps que necesitan compatibilidad; las apps con soporte directo de Porter siguen funcionando. La pantalla de compatibilidad comprueba los cambios automáticamente mientras está abierta.
## ¿Pueden ejecutarse Porter y Shizuku a la vez?
{: #can-porter-and-shizuku-run-together }

Sí. Porter y Shizuku pueden estar instalados y ejecutarse a la vez. Una app que permite elegir entre ellos se conecta a un solo servicio cada vez.

**Porter Compatibility no puede instalarse junto a Shizuku.** Usa la identidad de aplicación Android de Shizuku para admitir apps antiguas. Android los considera instalaciones incompatibles de la misma app. Esto también se aplica a las bifurcaciones que usan esa identidad.

Para volver atrás, elimina Porter Compatibility y reinstala Shizuku. Reinicia la app cliente y aprueba su acceso en Shizuku. Puedes mantener Porter independiente instalado.

## Una app sigue pidiendo Shizuku
{: #an-app-still-asks-for-shizuku }

Los ajustes de una app antigua pueden seguir mostrando el nombre Shizuku aunque Porter proporcione el acceso. Es normal.

El complemento cubre los métodos habituales de detección de Shizuku. Las apps que dependen de pantallas concretas, componentes internos o API mucho más antiguas de Shizuku pueden necesitar una actualización. Si una app no conecta, incluye su nombre y versión al [comunicar el problema a Porter](https://github.com/d4rken-org/porter/issues).
