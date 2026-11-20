# Política de privacidade do Titan

*Em vigor desde 20 de novembro de 2026. Também disponível em [English](PRIVACY.md), [français](PRIVACY.fr.md) e [español](PRIVACY.es.md). Em caso de divergência, prevalece a versão em inglês.*

O Titan é um diário de treino para Android. Ele é gratuito, de código aberto e feito por
voluntários. Esta política cobre o app Titan (`app.titan.gym`) como publicado no Google
Play e compilado a partir do seu repositório de código.

## Em resumo

- O Titan **não coleta nada**. Não há conta, nem estatísticas de uso, nem relatórios de
  falhas, nem anúncios.
- O app **não consegue se conectar à internet**. Ele não solicita a permissão `INTERNET` do
  Android, então o sistema não deixa que ele abra nenhuma conexão de rede.
- Tudo o que você registra fica **no seu celular**, no armazenamento privado do app.
- Seus dados só saem do celular quando **você** os exporta, para o lugar que **você** escolher.
- Você pode **excluir tudo** a qualquer momento pelo app.

## O que o app guarda, e onde

O Titan guarda no seu aparelho o que um diário de treino precisa:

| O quê | Exemplos |
| --- | --- |
| Treinos | treinos, exercícios, séries, cargas, repetições, esforço (RPE), descansos, notas |
| Sua biblioteca e seus modelos | exercícios personalizados, notas sobre exercícios, modelos de treino |
| Dados corporais | peso, percentual de gordura, medidas corporais (cintura, braços etc.) |
| Progresso | recordes, pontos de experiência, nível, sequências, missões, conquistas, itens |
| Configurações | unidades, tema, objetivo, nível, timer de descanso, dias e horário dos lembretes, barra e anilhas |

Isso fica no banco de dados e nos arquivos de configuração privados do app, que o Android não
deixa nenhum outro app ler.

Se você ativar o **Backup automático semanal** (Configurações › Backup e restauração), o app
também grava, uma vez por semana, um arquivo de backup na própria pasta de backups dele, a
mesma em que **Exportar um backup** grava. Ele mantém os três mais recentes e exclui os mais
antigos. No Android 11 e versões posteriores, que impedem o acesso de outros apps a essa
pasta, ela fica no armazenamento compartilhado do aparelho
(`Android/data/app.titan.gym/files/backups`). No Android 8 a 10, onde outros apps com acesso ao
armazenamento poderiam ler esse local, a pasta fica no armazenamento interno privado do app, e
um backup só sai dela quando você usa **Compartilhar** ou **Salvar uma cópia…** em
Configurações › Backup e restauração. Esses arquivos ficam no celular, e o Android os exclui
quando você desinstala o app.

### Dados de saúde

Peso, gordura corporal e medidas corporais são dados de saúde. O Titan os usa para uma
única coisa: mostrar a você os seus próprios registros e a evolução deles. Eles nunca são
transmitidos, vendidos nem usados para publicidade, e só são compartilhados com o Conexão
Saúde no seu celular, se você conectar, como descrito abaixo.

**Conexão Saúde (Health Connect).** O Titan pode trocar treinos, seu peso e sua gordura
corporal com o Conexão Saúde, o armazenamento de saúde do Android que fica no seu celular e que
outros apps de exercício também usam. Isso fica desativado até você conectar em Configurações ›
Dados e privacidade › Conexão Saúde e permitir na janela do próprio Conexão Saúde, e você pode
desconectar a qualquer momento, ali ou no Conexão Saúde. Depois de conectado:

- Quando você termina um treino, o Titan o grava no Conexão Saúde como uma sessão de exercício:
  o tipo, quando começou e terminou, o nome e as notas. Suas séries, cargas e repetições não são
  gravadas.
- Quando você escolhe **Importar os últimos 30 dias** em Treinos, o Titan lê as sessões de
  exercício que outros apps salvaram no Conexão Saúde nos últimos 30 dias e as adiciona ao seu
  histórico.
- Quando você registra, edita ou apaga uma medida, o Titan grava o peso e a gordura corporal
  dela no Conexão Saúde, ou os retira, com o momento em que você a tirou. Suas outras medidas
  (peito, cintura, braço, coxa) e suas notas não são gravadas.
- Quando você escolhe **Importar os últimos 30 dias** em Peso e gordura corporal, o Titan lê os
  pesos e as medidas de gordura corporal que outros apps salvaram no Conexão Saúde nos últimos
  30 dias e os adiciona às suas medidas.

Cada uma dessas trocas precisa da própria permissão, que você concede ou recusa na janela do
Conexão Saúde. O Titan só lê e grava sessões de exercício, peso e gordura corporal. Ele não lê
nem grava sua frequência cardíaca nem qualquer outro dado do Conexão Saúde. O Conexão Saúde
fica no seu celular, então nada disso sai dele. Os dados que o Titan lê do Conexão Saúde são
usados só para mostrar a você o seu próprio histórico, e nunca são transmitidos, vendidos,
compartilhados nem usados para publicidade. O que o Titan grava no Conexão Saúde pode ser lido
lá pelos outros apps que você autorizou, nos termos deles; você pode ver e apagar isso no
Conexão Saúde.

## O que o app não faz

- **Não coleta dados.** Nem o projeto Titan nem ninguém recebe os seus dados. Não temos
  nenhum servidor que pudesse recebê-los.
- **Não acessa a rede.** O app não solicita a permissão `INTERNET`. Ele solicita
  `ACCESS_NETWORK_STATE`, adicionada pela biblioteca de agendamento de tarefas do Android que
  ele usa. Essa permissão só permite saber se o celular está on-line; ela não permite enviar
  nem receber nada.
- **Nenhum SDK de terceiros que colete dados.** Nenhuma biblioteca de estatísticas,
  relatórios de falhas, publicidade ou rastreamento. A lista completa de bibliotecas está no
  app, em Configurações › Sobre › Licenças de código aberto.
- **Nenhum backup na nuvem.** O app exclui os dados dele do backup automático do Android no
  Google Drive e da transferência entre aparelhos, para que o seu histórico não seja copiado
  para fora do celular sem você saber.
- **Nenhuma conta e nenhum ID de publicidade.** O app não sabe quem você é.

## Quando seus dados saem do celular

Só quando você pede. **Exportar um backup** (Configurações › Backup e restauração) grava todos
os seus dados, inclusive os dados corporais, em um arquivo JSON. Depois você escolhe salvar
uma cópia em algum lugar ou compartilhá-la com outro app. A partir daí, essa cópia fica sob
responsabilidade do serviço para o qual você a enviou, conforme os termos dele. O arquivo não
é criptografado: guarde-o em um lugar de confiança.

**Restaurar** lê um arquivo de backup que você escolhe e substitui por ele os dados do
celular. Nada é enviado.

## Notificações

Os lembretes de treino, o alerta de fim do descanso, o aviso de treino não concluído e as
notificações de nível e recompensas são todos criados no celular. Nenhum é enviado por um
servidor. Você pode desativar cada um em Configurações › Notificações ou nas configurações do
Android.

## Permissões

| Permissão | Por que o app a solicita |
| --- | --- |
| `POST_NOTIFICATIONS` | Para mostrar as notificações acima. O Android 13 ou superior pergunta a você antes. |
| `SCHEDULE_EXACT_ALARM` (Android 12 ou anterior) / `USE_EXACT_ALARM` | Para encerrar um descanso no segundo exato e disparar um lembrete de treino no horário escolhido. |
| `RECEIVE_BOOT_COMPLETED` | Para agendar de novo os lembretes e o backup semanal depois que o celular reinicia. |
| `health.READ_EXERCISE`, `health.WRITE_EXERCISE` | Para importar treinos do Conexão Saúde e gravar nele os que você termina, como descrito em § Dados de saúde. O Conexão Saúde pergunta a você antes, e nenhuma é concedida se você não conectar. |
| `health.READ_WEIGHT`, `health.WRITE_WEIGHT`, `health.READ_BODY_FAT`, `health.WRITE_BODY_FAT` | Para importar peso e gordura corporal do Conexão Saúde e gravar nele os que você registra, como descrito em § Dados de saúde. Concedidas da mesma forma que as duas anteriores. |
| `VIBRATE` | Para vibrar no fim de um descanso. |
| `WAKE_LOCK`, `FOREGROUND_SERVICE`, `ACCESS_NETWORK_STATE` | Adicionadas pela biblioteca de agendamento de tarefas do Android (WorkManager), que executa o backup semanal. Nenhuma dá acesso à rede. |
| `app.titan.gym.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Adicionada pela biblioteca AndroidX core. Ela impede que outros apps enviem mensagens internas ao app. |

## Excluir seus dados

- **Configurações › Excluir todos os dados** apaga tudo o que o app guardou e volta cada
  configuração ao padrão. Não é possível desfazer.
- **Desinstalar o app** também exclui tudo, inclusive os backups automáticos.
- **Os arquivos exportados** que você salvou ou compartilhou ficam fora do app. Exclua-os onde
  você os colocou.

Como não temos nenhuma cópia dos seus dados, não há nada para excluirmos, nem nenhum pedido
que você precise nos enviar.

## Crianças

O Titan não é direcionado a crianças menores de 13 anos e não coleta, conscientemente,
nenhum dado de ninguém, de qualquer idade.

## Alterações nesta política

Se esta política mudar, a nova versão será publicada neste endereço com uma nova data de
vigência. Todas as versões anteriores ficam guardadas no histórico público do código do
projeto. Uma mudança que fizesse o app coletar ou enviar dados seria anunciada nas notas da
versão correspondente e exigiria o seu consentimento no app.

## Contato

O Titan é desenvolvido de forma aberta. Para qualquer dúvida sobre esta política ou sobre a
sua privacidade, escreva para [contact.titan.app@gmail.com](mailto:contact.titan.app@gmail.com).
