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
| Apenas Shizuku | Porter e Porter Compatibility; o Porter substitui o Shizuku se ele estiver instalado |
| Ambos, com seletor de serviço | Porter; depois selecione Porter no app |

O complemento ajuda apps Shizuku existentes a encontrar o Porter. O Porter continua responsável por iniciar o serviço, mostrar solicitações de permissão e gerenciar autorizações. Mantenha o complemento instalado enquanto usar apps que precisam dele.

## Migrar do Shizuku
{: #switch-from-shizuku }

Para apps com suporte direto ao Porter, você pode manter o Shizuku instalado. Inicie o Porter, selecione-o no app e aprove a nova solicitação. Um app que já estava conectado ao Shizuku continua nele até ser reiniciado, então force sua parada nas configurações do Android e abra-o novamente.

Para um app que só oferece suporte ao Shizuku, a versão FOSS inclui o APK do Porter Compatibility correspondente:

1. Instale e inicie o Porter. Mantenha o Shizuku instalado até revisar a substituição.
2. Abra **Compatibilidade com o Shizuku** na tela inicial ou em **Configurações**.
3. Se o Shizuku estiver instalado, escolha **Substituir**. Confirme **Mudar para o Porter**. O Porter para o Shizuku, substitui o app dele e transfere automaticamente as decisões de acesso elegíveis. Se o Porter não conseguir parar o serviço, ele pede que você o pare no Shizuku e tente de novo.
4. Caso contrário, escolha **Instalar automaticamente**.
5. Volte ao app cliente. Aprove o acesso se você não importou uma decisão existente. Se o cliente ainda não conectar, force sua parada nas configurações do Android e abra-o novamente.

O Porter importa automaticamente as decisões que consegue verificar com os apps instalados e o acesso atual deles. As decisões existentes do Porter têm prioridade. As configurações do app do Shizuku e o pareamento dele não são importados. Se o banco de dados de acessos não puder ser lido, você pode continuar e autorizar os apps de novo. O Porter salva as decisões de acesso elegíveis antes de remover o Shizuku; se a instalação falhar, tente de novo ou use **Manual** e depois **Importar autorizações salvas**.

A substituição integrada está disponível no usuário principal do Android. Se o Shizuku estiver instalado para outro usuário ou perfil, resolva aquela instalação separadamente. O Porter não remove automaticamente os apps de outro usuário.

O APK do complemento continua disponível como download separado da mesma versão. Para instalar manualmente, pare e desinstale o Shizuku, instale o Porter Compatibility e inicie o Porter. Use a substituição integrada do Porter para transferir as decisões de acesso elegíveis; abrir a caixa de diálogo de substituição por si só não salva uma importação. Restrições de instalação do aparelho também podem afetar o instalador integrado; a ação **Manual** abre o instalador do Android.

Depois de instalado, a tela inicial mostra quantos apps instalados se conectam por meio dele. Toque no cartão dele para ver a versão, reinstalar a cópia incluída ou desinstalá-lo. O Porter tenta desinstalar primeiro pelo serviço dele e abre o desinstalador do Android se isso falhar. Removê-lo interrompe os apps que precisam de suporte de compatibilidade; os apps com suporte direto ao Porter continuam funcionando. A tela de compatibilidade verifica mudanças automaticamente enquanto está aberta.
## Porter e Shizuku podem funcionar juntos?
{: #can-porter-and-shizuku-run-together }

Sim. Ambos podem estar instalados e em execução ao mesmo tempo. Um app que permite escolher entre eles se conecta a um serviço por vez.

**O Porter Compatibility não pode ser instalado junto com o Shizuku.** Ele usa a identidade Android do Shizuku para dar suporte a apps antigos. O Android os considera instalações concorrentes do mesmo app, não apps separados. Isso também vale para forks que usam essa identidade.

Para voltar, remova o Porter Compatibility e reinstale o Shizuku. Reinicie o app cliente e aprove seu acesso no Shizuku. Você pode manter o Porter independente instalado.

## Um app ainda pede o Shizuku
{: #an-app-still-asks-for-shizuku }

O nome nas configurações de um app antigo pode continuar sendo Shizuku mesmo quando o Porter fornece o acesso. Isso é esperado.

O complemento cobre os métodos comuns de descoberta do Shizuku. Apps que dependem de telas específicas, componentes internos ou APIs muito antigas podem precisar de atualização. Se um app não conseguir se conectar, informe seu nome e versão em um [relato de problema do Porter](https://github.com/d4rken-org/porter/issues).
