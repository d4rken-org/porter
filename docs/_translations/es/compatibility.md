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
| Solo Shizuku | Porter y Porter Compatibility; elimina Shizuku primero |
| Ambos, con selector de servicio | Porter; después selecciona Porter en la app |

El complemento ayuda a las apps existentes de Shizuku a encontrar Porter. Porter sigue iniciando el servicio, mostrando las solicitudes de permiso y gestionando las autorizaciones. Mantén el complemento instalado mientras uses apps que lo necesiten.

## Cambiar desde Shizuku
{: #switch-from-shizuku }

Para una app con soporte directo de Porter, puedes mantener Shizuku instalado. Inicia Porter, selecciónalo en la app y aprueba la nueva solicitud. Si la app pide reiniciarse, fuerza su detención en los ajustes de Android y ábrela de nuevo.

Para una app que solo admite Shizuku:

1. Detén Shizuku y desinstala su app de gestión. Las apps que usas con Shizuku pueden seguir instaladas.
2. Instala Porter y el APK de Porter Compatibility de la misma versión.
3. Inicia Porter.
4. Fuerza la detención de la app cliente en los ajustes de Android, ábrela de nuevo y activa su integración con Shizuku.
5. Aprueba la solicitud de acceso que muestra Porter.

Las autorizaciones anteriores de Shizuku no se transfieren. Debes elegir de nuevo qué apps pueden usar Porter.

## ¿Pueden ejecutarse Porter y Shizuku a la vez?
{: #can-porter-and-shizuku-run-together }

Sí. Porter y Shizuku pueden estar instalados y ejecutarse a la vez. Una app que permite elegir entre ellos se conecta a un solo servicio cada vez.

**Porter Compatibility no puede instalarse junto a Shizuku.** Usa la identidad de aplicación Android de Shizuku para admitir apps antiguas. Android los considera instalaciones incompatibles de la misma app. Esto también se aplica a las bifurcaciones que usan esa identidad.

Para volver atrás, elimina Porter Compatibility y reinstala Shizuku. Reinicia la app cliente y aprueba su acceso en Shizuku. Puedes mantener Porter independiente instalado.

## Una app sigue pidiendo Shizuku
{: #an-app-still-asks-for-shizuku }

Los ajustes de una app antigua pueden seguir mostrando el nombre Shizuku aunque Porter proporcione el acceso. Es normal.

El complemento cubre los métodos habituales de detección de Shizuku. Las apps que dependen de pantallas concretas, componentes internos o API mucho más antiguas de Shizuku pueden necesitar una actualización. Si una app no conecta, incluye su nombre y versión al [comunicar el problema a Porter](https://github.com/d4rken-org/porter/issues).
