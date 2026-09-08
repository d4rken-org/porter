---
title: Instalar e iniciar o Porter
lang: pt-BR
translation_key: setup
language_name: Português (Brasil)
description: Baixe o Porter no GitHub Releases e siga estas etapas.
---
# Instalar e iniciar o Porter
{: #install-and-start-porter }

Baixe o Porter no [GitHub Releases](https://github.com/d4rken-org/porter/releases) e siga estas etapas.

## Instalação
{: #install }

1. Baixe o APK do Porter na seção **Assets** da versão. Os arquivos ZIP e TAR de código-fonte não são apps Android.
2. Abra o APK no dispositivo. Se o Android solicitar, permita que seu navegador ou gerenciador de arquivos instale apps dessa fonte.
3. Abra o Porter.

Para apps com suporte direto ao Porter, basta instalar o Porter. Se um app só oferece suporte ao Shizuku, instale também o **Porter Compatibility** da mesma versão. Desinstale o Shizuku primeiro: o complemento não pode ser instalado junto com ele. Mantenha o complemento instalado enquanto usar esses apps com o Porter. Consulte o [guia de compatibilidade](/compatibility).

## Escolha como iniciar
{: #choose-how-to-start }

| Seu dispositivo | Método de inicialização |
| --- | --- |
| Android 11 ou mais recente com depuração por Wi-Fi | [Depuração por Wi-Fi](#wireless-debugging) |
| Android 7.0 ou mais recente e um computador | [Depuração USB](#with-a-computer) |
| Já tem root | [Root](#root) |

## Depuração por Wi-Fi
{: #wireless-debugging }

Você precisa de uma conexão Wi-Fi e Android 11 ou mais recente. Alguns fabricantes restringem a depuração por Wi-Fi; se ela não estiver disponível, use um computador.

1. Ative as **Opções do desenvolvedor** nas configurações do Android. Geralmente, abra **Sobre o telefone** e toque sete vezes em **Número da versão**. O local varia conforme o dispositivo.
2. Nas opções do desenvolvedor, ative **Depuração USB** e **Depuração por Wi-Fi**. Aceite a solicitação de autorização da rede, se aparecer.
3. No Porter, toque em **Pareamento** na seção de inicialização por depuração por Wi-Fi. Permita notificações e, se solicitado, acesso a dispositivos próximos ou à rede local.
4. Abra as configurações de **Depuração por Wi-Fi** do Android e toque em **Parear dispositivo com código de pareamento**. Mantenha esse diálogo aberto.
5. Expanda a notificação de pareamento do Porter e digite o código mostrado pelo Android. Aguarde a confirmação do pareamento.
6. Volte ao Porter e toque em **Iniciar** na seção de depuração por Wi-Fi.
7. Confirme que o Porter indica que está em execução.

O Porter deixa a depuração do Android ativada quando para. Você pode desativá-la nas opções do desenvolvedor quando não precisar mais desse acesso.

Normalmente, o pareamento só precisa ser feito uma vez. Iniciar o serviço é uma etapa separada, necessária novamente após reiniciar o dispositivo. Se o Android esquecer o pareamento, repita essas etapas.

Se você escolheu o diálogo dentro do app em **Configurações**, **Inicialização**, **Método de pareamento**, aguarde o diálogo do Porter encontrar o serviço e digite o código ali. Se ele pedir uma porta, use a porta de pareamento do diálogo de código do Android, não a porta de conexão da tela principal de depuração por Wi-Fi.

## Com um computador
{: #with-a-computer }

1. Instale o [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) do Google no computador.
2. Ative as opções do desenvolvedor e a **Depuração USB** no dispositivo Android.
3. Conecte o dispositivo com um cabo USB de dados. Desbloqueie-o e aprove a conexão de depuração. Autorize apenas computadores confiáveis.
4. Abra um terminal na pasta Platform-Tools e execute `adb devices`. No Windows PowerShell, use `./adb.exe devices`; no macOS ou Linux, use `./adb devices` se o ADB não estiver no PATH.
5. No Porter, encontre a seção de inicialização com computador e toque em **Ver comando**. Execute exatamente esse comando no computador, ajustando o prefixo do executável `adb` conforme indicado acima, se necessário.
6. Confirme que o Porter está em execução. Depois, você pode desconectar o cabo.

Se houver vários dispositivos conectados, insira `-s DEVICE_SERIAL` logo após `adb` no comando exibido. Use o número de série mostrado por `adb devices` para o dispositivo que executa o Porter.

Obtenha um novo comando no Porter após atualizá-lo ou reinstalá-lo. O caminho pode mudar. Não use um comando copiado do Shizuku ou de outra instalação.

## Root
{: #root }

Este método é para dispositivos que já têm acesso root funcionando. Instalar o Porter não faz root no dispositivo.

1. Abra o Porter e toque em **Iniciar** na seção de inicialização com root.
2. Aprove a solicitação do Porter no gerenciador de root.
3. Confirme que o Porter está em execução e mostra root como modo de inicialização.

Pare o serviço atual do Porter antes de alternar entre root e depuração.

## Autorizar um app
{: #allow-an-app }

Abra o app desejado, ative sua integração com Porter ou Shizuku e aprove a solicitação de acesso do Porter. Autorize apenas apps confiáveis: eles podem realizar tarefas com o acesso de depuração ou root do Porter.

Para remover o acesso, toque em **Aplicativos** no Porter e desative a autorização do app. Porter e Shizuku mantêm autorizações separadas.

Para pausar o acesso de todos os apps, desative **Permitir acesso dos apps** no topo dessa tela. As autorizações individuais são preservadas. Ative novamente para retomar o acesso. Comandos shell já iniciados podem continuar durante a pausa.

Se o app tiver um seletor de serviço, escolha Porter. Siga as instruções do próprio app. Se a mudança exigir reiniciar o app, use **Forçar parada** nas configurações do Android e abra-o novamente.

## Parar o Porter
{: #stop-porter }

Toque no cartão que indica que o Porter está em execução e escolha **Parar o Porter**. Os apps conectados perdem o acesso até você iniciar o Porter novamente.

## Atualizar o Porter
{: #update-porter }

Instale o APK mais recente sobre o app existente e inicie o Porter novamente. Se usar o Porter Compatibility, atualize-o com a mesma versão. O Android exige assinaturas correspondentes para atualizações; consulte [problemas de instalação](/troubleshooting#android-wont-install-an-apk) se houver recusa.
