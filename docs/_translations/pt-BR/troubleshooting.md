---
title: Solução de problemas
lang: pt-BR
translation_key: troubleshooting
language_name: Português (Brasil)
description: Ao usar depuração, é normal precisar iniciar o Porter novamente após reiniciar o dispositivo. Abra o Porter e use seu método de inicialização. O pareamento sem fio, sozinho, não inicia o serviço.
---
# Solução de problemas
{: #troubleshooting }

## O Porter não está em execução
{: #porter-is-not-running }

Ao usar depuração, é normal precisar iniciar o Porter novamente após reiniciar o dispositivo. Abra o Porter e use seu [método de inicialização](/setup). O pareamento sem fio, sozinho, não inicia o serviço.

## A inicialização automática não funciona
{: #automatic-start-does-not-work }

Inicie o Porter manualmente uma vez após a instalação antes de depender de **Iniciar na inicialização**. Uma inicialização bem-sucedida por depuração concede a permissão de configurações do Android necessária às próximas inicializações automáticas. Se uma notificação mencionar `WRITE_SECURE_SETTINGS`, inicie o Porter pelo computador e tente novamente.

A inicialização automática ainda depende de o Android disponibilizar a depuração e permitir que o Porter rode em segundo plano. Se falhar, use um método manual.

## O pareamento sem fio não termina
{: #wireless-pairing-does-not-finish }

- Mantenha o dispositivo no Wi-Fi e confirme que a depuração por Wi-Fi está ativada.
- Permita notificações do Porter para inserir o código. Permita também dispositivos próximos ou acesso à rede local, se solicitado.
- Mantenha aberto o diálogo de código do Android enquanto digita na notificação do Porter. Se o código expirar, abra um novo diálogo.
- No diálogo do próprio Porter, copie a porta de pareamento do diálogo de código, não a porta de conexão da tela principal de depuração por Wi-Fi.
- Se uma VPN ou restrição da rede bloquear a descoberta, tente uma rede que permita comunicação entre dispositivos.

Se um código foi recusado, escolha **Tentar novamente** na notificação. Se a notificação sumiu ou ainda mostra um código antigo, volte a **Pareamento** no Porter e escolha **Reiniciar pareamento**. Feche o diálogo de código antigo do Android, abra um novo e digite o código novo. Não é preciso limpar o cache nem os dados do Porter.

No Android TV, o Porter mostra um resultado quando o pareamento é concluído, falha ou expira. Escolha **Tentar novamente** após uma falha e ative de novo o serviço de acessibilidade de pareamento, se for pedido. Se o Porter não abrir sozinho, abra-o para ver o resultado salvo. Depois de um pareamento bem-sucedido ainda resta a etapa separada **Iniciar**.

Pareamentos novos aparecem como **Porter** na lista de dispositivos pareados do Android. Uma entrada existente pode continuar com o nome **shizuku** até você esquecê-la e parear de novo. Renomear o rótulo exibido não exige substituir a chave salva, e um pareamento existente pode continuar funcionando. Esse rótulo é independente dos selos de API do Shizuku na lista de apps do Porter.

Se a depuração por Wi-Fi estiver indisponível ou instável, [inicie pelo computador](/setup#with-a-computer).

## O computador não encontra o dispositivo
{: #the-computer-cannot-find-the-device }

Execute `adb devices`. Se aparecer `unauthorized`, desbloqueie o dispositivo e aprove a solicitação de depuração. Se nada aparecer, verifique a depuração USB, teste um cabo USB de dados e outra porta, e veja se o computador precisa do driver USB do fabricante.

Se o comando de inicialização indicar um arquivo ausente, copie um novo comando em **Ver comando** no Porter instalado.

## O Porter para repetidamente
{: #porter-keeps-stopping }

Primeiro, verifique se o dispositivo reiniciou ou se o Android desativou a depuração. Inicie o Porter novamente, se necessário.

Se ele parar enquanto o dispositivo continua ligado, verifique as configurações de bateria e execução em segundo plano do fabricante para o Porter. Permita a execução em segundo plano se estiver restrita. Mudanças de rede e modificações do fabricante no Android podem afetar a depuração.

Relate paradas recorrentes com o modelo do dispositivo, a versão do Android, o método de inicialização e o que aconteceu logo antes da parada.

## Um app não consegue se conectar
{: #an-app-cannot-connect }

1. Confirme que o Porter está em execução.
2. Veja [se o app precisa do Porter Compatibility](/compatibility).
3. Se tiver seletor de serviço, escolha Porter, force a parada do app nas configurações do Android e abra-o novamente.
4. Ative a integração no app e aprove a solicitação do Porter.
5. Confira o app em **Aplicativos** no Porter e verifique se **Permitir acesso dos apps** está ativado.

Se usar o complemento, ambos os APKs devem vir da mesma fonte de publicação e ter certificados de assinatura correspondentes. Remover o complemento impede que clientes antigos usem o Porter.

## O Android não instala um APK
{: #android-wont-install-an-apk }

Se estiver instalando manualmente o APK **Porter Compatibility** separado, remova o Shizuku primeiro. O complemento não pode atualizar uma instalação do Shizuku com assinatura diferente, embora o Android reconheça a mesma identidade de app. A substituição integrada do Porter remove o Shizuku para você.

No Porter, uma versão de desenvolvimento antiga pode ter uma assinatura diferente da versão pública. O Android não instala uma sobre a outra. Remover a instalação antiga também apaga seus dados; anote sua configuração antes. Reinstale da fonte pretendida e autorize seus apps novamente.

## Um aviso de instalação na TV não pode ser selecionado pelo controle remoto
{: #a-tv-installation-warning-cannot-be-selected-with-the-remote }

O aviso do Play Protect e seus botões **Mais detalhes** ou **Instalar mesmo assim** pertencem ao instalador do Android. O Porter não pode mudar como eles recebem foco pelo controle remoto, e o aviso sozinho não diz por que o Google sinalizou um APK.

Confirme que o APK veio da [página de versões do Porter](https://github.com/d4rken-org/porter/releases). Se decidir continuar depois de ler o aviso, um mouse USB ou Bluetooth pode permitir selecionar esses botões. Se você já tem uma conexão ADB autorizada com a TV, também pode instalar o APK baixado a partir desse computador:

```sh
adb -s TV_SERIAL install -r /caminho/para/porter-compat.apk
```

Substitua `TV_SERIAL` pela entrada da TV em `adb devices` e use o caminho real do APK baixado. O Android ainda pode bloquear a instalação ou pedir confirmação. Isso não corrige os controles do Play Protect nem garante que a instalação seja permitida.

Se a instalação continuar bloqueada, relate o texto exato do aviso, o modelo da TV, a versão do Android e a versão do Porter Compatibility. Apps com suporte direto ao Porter não precisam do APK de compatibilidade.

## O acesso está permitido, mas uma operação falha
{: #access-is-allowed-but-an-operation-still-fails }

A depuração é mais limitada que root. Versões do Android e fabricantes impõem restrições adicionais, e o Porter não pode disponibilizar todas as operações. Confira também os requisitos do app cliente.

Em dispositivos Xiaomi/POCO com MIUI, as opções do desenvolvedor podem ter um interruptor separado **Depuração USB (Configurações de segurança)**. Ativar apenas a depuração USB comum pode deixar a gestão de apps restrita. Ative a opção adicional se quiser essas operações e reinicie o Porter. O nome e a disponibilidade variam conforme o sistema.

Alguns sistemas OPPO/OnePlus têm **Monitoramento de permissões** nas opções do desenvolvedor, restringindo a depuração. Desativar isso pode permitir a operação, mas altera a proteção do fabricante. Essas soluções vêm do [guia original do Shizuku](https://shizuku.rikka.app/guide/setup/) e ainda não foram verificadas com o Porter em dispositivos físicos.

## A versão exibida em execução é diferente
{: #the-version-shown-while-running-is-different }

A entrada **Versão** em **Configurações** mostra a versão do app Porter. Toque no cartão do serviço para abrir **Serviço**. A tela mostra o app instalado e o serviço em execução com seus nomes de versão, códigos de versão e IDs de build, além da versão compatível da API do Shizuku. Para compartilhar detalhes de diagnóstico, [grave um log de depuração](#report-a-problem) em **Configurações**, **Ajuda e suporte**. A versão da API indica compatibilidade, não a versão de lançamento do Porter. Um build diferente mostra **Atualização do serviço disponível**. Os recursos compatíveis continuam utilizáveis; abrir o Porter não reinicia o serviço. Toque em **Atualizar serviço** na tela Serviço no usuário principal do Android e confirme para aplicar o build instalado com os privilégios do serviço em execução, sem computador nem uma nova conexão de depuração por Wi-Fi. Os apps conectados são desconectados por um instante e as permissões são mantidas. Se a substituição falhar, use **Tentar atualizar de novo** enquanto o serviço antigo ainda estiver em execução, ou inicie-o novamente pelo seu método habitual.

Em **Configurações**, **Atualizar serviço automaticamente** verifica, após uma atualização do app Porter, se há um serviço em execução com build diferente. A opção vem desativada. O Android pode atrasar o trabalho em segundo plano; o botão de atualização manual continua disponível. Um serviço parado permanece parado. Atualizações que falharam ou foram interrompidas aparecem na tela inicial, inclusive com o watchdog ativado.

## Relatar um problema
{: #report-a-problem }

Toque no ícone de configurações do Porter e abra **Ajuda e suporte**. Você pode entrar em contato por e-mail, visitar a [comunidade no Discord](https://discord.gg/5hXXgwKNgm) ou [abrir um relato](https://github.com/d4rken-org/porter/issues).

Para incluir um registro, escolha **Gravar log de depuração**, reproduza o problema e toque em **Parar gravação**. Selecione a gravação no formulário de contato ou compartilhe em **Logs de depuração salvos**. Os registros ficam no dispositivo até você compartilhá-los. Podem conter nomes de apps, detalhes do dispositivo e ações realizadas pelo Porter.

Inclua:

- Versão do Porter e se o Porter Compatibility está instalado.
- Modelo do dispositivo e versão do Android.
- Como iniciou o Porter: depuração por Wi-Fi, computador ou root.
- O app afetado e sua versão.
- O que fez, o que esperava e o que aconteceu.

Verifique se há informações pessoais em capturas e registros antes de anexá-los. Não inclua códigos de pareamento sem fio nem chaves privadas.
