---
title: Compatibilidade de apps
lang: pt-BR
translation_key: compatibility
language_name: Português (Brasil)
description: O Porter oferece as APIs do Shizuku usadas por apps compatíveis. A necessidade do complemento opcional depende de como o app se conecta.
---
# Compatibilidade de apps
{: #app-compatibility }

O Porter oferece as APIs do Shizuku usadas por apps compatíveis. A necessidade do complemento opcional depende de como o app se conecta.

| Suporte do app | O que instalar |
| --- | --- |
| Porter diretamente | Porter |
| Apenas Shizuku | Porter e Porter Compatibility; remova o Shizuku primeiro |
| Ambos, com seletor de serviço | Porter; depois selecione Porter no app |

O complemento ajuda apps Shizuku existentes a encontrar o Porter. O Porter continua responsável por iniciar o serviço, mostrar solicitações de permissão e gerenciar autorizações. Mantenha o complemento instalado enquanto usar apps que precisam dele.

## Migrar do Shizuku
{: #switch-from-shizuku }

Para apps com suporte direto ao Porter, você pode manter o Shizuku instalado. Inicie o Porter, selecione-o no app e aprove a nova solicitação. Se o app pedir para reiniciar, force sua parada nas configurações do Android e abra-o novamente.

Para apps que só oferecem suporte ao Shizuku:

1. Pare o Shizuku e desinstale seu app gerenciador. Os apps que você usa com o Shizuku podem continuar instalados.
2. Instale o Porter e o APK do Porter Compatibility da mesma versão.
3. Inicie o Porter.
4. Force a parada do app cliente nas configurações do Android, abra-o novamente e ative sua integração com o Shizuku.
5. Aprove a solicitação de acesso exibida pelo Porter.

As autorizações anteriores do Shizuku não são transferidas. Você escolhe novamente quais apps podem usar o Porter.

## Porter e Shizuku podem funcionar juntos?
{: #can-porter-and-shizuku-run-together }

Sim. Ambos podem estar instalados e em execução ao mesmo tempo. Um app que permite escolher entre eles se conecta a um serviço por vez.

**O Porter Compatibility não pode ser instalado junto com o Shizuku.** Ele usa a identidade Android do Shizuku para dar suporte a apps antigos. O Android os considera instalações concorrentes do mesmo app, não apps separados. Isso também vale para forks que usam essa identidade.

Para voltar, remova o Porter Compatibility e reinstale o Shizuku. Reinicie o app cliente e aprove seu acesso no Shizuku. Você pode manter o Porter independente instalado.

## Um app ainda pede o Shizuku
{: #an-app-still-asks-for-shizuku }

O nome nas configurações de um app antigo pode continuar sendo Shizuku mesmo quando o Porter fornece o acesso. Isso é esperado.

O complemento cobre os métodos comuns de descoberta do Shizuku. Apps que dependem de telas específicas, componentes internos ou APIs muito antigas podem precisar de atualização. Se um app não conseguir se conectar, informe seu nome e versão em um [relato de problema do Porter](https://github.com/d4rken-org/porter/issues).
