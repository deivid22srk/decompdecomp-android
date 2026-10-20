# decompdecomp para Android

Port do [decompdecomp](https://github.com/n-delic/decompdecomp) (de n-delic) para Android,
com a **interface própria do projeto** adaptada para celular e seleção de ROM pelo seletor de
arquivos do Android.

O APK é compilado **exclusivamente pelo GitHub Actions** e fica disponível como artifact de cada
execução (e como release quando uma tag `v*` é enviada).

> **Limite importante, sem rodeios:** o decompdecomp verifica o resultado compilando o jogo com o
> **compilador original**, e esses compiladores são programas **x86_64**. Um celular ARM não os
> executa. Nesta edição funcionam: importar a ROM, dividir o jogo, o mapa de funções, leitura e
> edição do assembly/contexto, renomear símbolos, agentes de **API** (DeepSeek, OpenRouter,
> OpenAI, Ollama etc.) e a exportação do projeto. O veredito **byte a byte** e os agentes de CLI
> (Claude Code, Codex, opencode) precisam de um PC x86_64.

## Baixar o APK

1. Abra a aba **Actions** do repositório:
   <https://github.com/deivid22srk/decompdecomp-android/actions/workflows/build.yml>
2. Entre na execução mais recente de **build** (verde).
3. Em **Artifacts**, baixe `decompdecomp-android-arm64`.
4. Descompacte o artifact: dentro está `decompdecomp-android-arm64.apk`.

Se houver uma release publicada, o APK também aparece em
<https://github.com/deivid22srk/decompdecomp-android/releases>.

## Instalar

- Android **8.0 (API 26)** ou mais novo, **arm64-v8a** (praticamente todo celular atual).
- Permita “instalar de fontes desconhecidas” para o navegador/gerenciador de arquivos que abrir o
  APK.
- O APK é assinado com a chave de **debug** do CI (a não ser que o repositório configure os
  secrets de keystore). Isso é suficiente para instalar e usar; se a chave mudar numa versão
  futura, pode ser necessário desinstalar a anterior antes de atualizar.
- O primeiro start extrai o runtime Python e pode levar de 10 a 40 segundos. A tela mostra o
  progresso; depois disso o app abre direto na interface.

## Usar

1. Abra o app e espere a interface do decompdecomp carregar (ela é a mesma do desktop).
2. Toque em **ROM** na barra inferior.
3. Leia o aviso, toque em **Escolher ROM** e selecione o arquivo pelo seletor do Android (SAF).
   O arquivo é copiado em streaming, com progresso, para o armazenamento privado do app.
4. Espere a identificação e o split. O projeto aparece na interface, com o mapa de funções.
5. Para continuar depois ou levar para um PC, toque em **Exportar**:
   - **Código + progresso (.zip)**: árvore de assembly/fonte + `progress/`.
   - **Somente progresso (.zip)**: o pacote enxuto para continuar em outra máquina.
   O jogo em si nunca entra no ZIP.
6. **Console** mostra o log ao vivo do servidor (splat, importação, agentes) e permite
   compartilhá-lo.
7. **A− / A+** ajustam o tamanho do texto da interface.
8. A decompilação continua com a tela apagada: o app usa um **serviço de primeiro plano** com
   notificação e WakeLock. O botão **Parar** da notificação encerra o servidor.

Para trocar de ROM, basta repetir o passo 2; não é preciso reinstalar nem reiniciar o app. A pasta
privada pode ser limpa em Configurações do Android → Apps → decompdecomp → Armazenamento.

## O que foi portado

- **Runtime:** Chaquopy 17.1 com **Python 3.13** dentro do processo Android; o pacote
  `decompnet` do upstream é vendorizado no repositório.
- **Interface:** a página web original (Starlette + Uvicorn em `127.0.0.1`) em um `WebView`,
  com o segredo de janela do upstream no `User-Agent`. Uma barra nativa acrescenta ROM,
  Exportar, Console, fonte e Sobre. O CSS mobile ajusta alvos de toque e telas estreitas.
- **ROM:** `ACTION_OPEN_DOCUMENT` (sem permissão ampla de armazenamento) → upload em streaming
  para o próprio endpoint `/api/import` do decompdecomp, com progresso e checagem de espaço.
- **Split:** `splat` roda **no mesmo processo** (o Android não tem um segundo interpretador
  Python para `python -m splat`); `spimdisasm` e `rabbitizer` fazem a desmontagem.
- **Nativos:** `libcapstone.so` é compilado com o NDK para arm64 no CI e carregado via
  `LIBCAPSTONE_PATH`; `rabbitizer` é compilado pelo NDK no CI com os headers e o
  `libpython3.13.so` do target do Chaquopy e carregado de `nativeLibraryDir` por um shim Python.
- **MCP:** um shim mínimo mantém as rotas `custom_route` e o app ASGI, sem `pydantic-core`
  (Rust, sem wheel Android). O agente de API do upstream não usa MCP e continua funcionando.
- **Exportação:** o exportador do projeto gera a árvore e o app a compacta; o ZIP é salvo com
  `ACTION_CREATE_DOCUMENT` ou compartilhado.
- **Segundo plano:** `ForegroundService` (`dataSync`) + WakeLock parcial + `FLAG_KEEP_SCREEN_ON`.

O detalhamento e o porquê de cada decisão estão em [`docs/ANALISE.md`](docs/ANALISE.md).

## Compilar (GitHub Actions)

O workflow [`build.yml`](.github/workflows/build.yml) roda em `ubuntu-latest` e faz:

1. checkout, JDK 17 e Python 3.13;
2. instalação do SDK 35 e NDK 27;
3. compilação do capstone 5.0.9 para `arm64-v8a`;
4. `./gradlew assembleRelease` (Chaquopy baixa/compila as dependências Python);
5. assinatura com a chave de debug (ou com um keystore dos secrets, se configurado);
6. upload do APK e `SHA256SUMS.txt`;
7. release automática em tags `v*`.

Para acionar manualmente:

```bash
gh workflow run build.yml --repo deivid22srk/decompdecomp-android
gh run watch --repo deivid22srk/decompdecomp-android
```

Para uma assinatura estável, configure os secrets `DECOMP_KEYSTORE_BASE64`,
`DECOMP_KEYSTORE_PASSWORD`, `DECOMP_KEY_ALIAS` e `DECOMP_KEY_PASSWORD` (o workflow atual usa a
chave de debug por padrão). **Nunca** comite keystore ou senhas.

Compilar localmente não é necessário e não é o caminho suportado por este repositório.

## Estrutura

```
app/src/main/java/...        app Kotlin (WebView, serviço, importador SAF, exportação, console)
app/src/main/python/         decompnet vendorizado + shim mcp + entrada decompmobile
app/src/main/jniLibs/        libcapstone.so (gerado pelo CI, não versionado)
docs/ANALISE.md              análise do upstream e arquitetura do port
.github/workflows/build.yml  build do APK
gradle/, gradlew*            Gradle Wrapper 8.11.1
LICENSE, LICENSE-decomp.wtf  licenças do upstream (AGPL-3.0)
```

## Créditos e licença

- **decompdecomp** — n-delic e contribuidores: <https://github.com/n-delic/decompdecomp>
- **decomp.wtf**, que o decompdecomp continua — Sam Wolfs (@i12bp8) e contribuidores.
- O logo e a interface são do projeto original.
- Este port Android foi feito por deivid22srk, mantendo a licença **AGPL-3.0-only**.

O texto completo está em [`LICENSE`](LICENSE) e [`LICENSE-decomp.wtf`](LICENSE-decomp.wtf).
Se você distribuir uma versão modificada, ela também precisa ser AGPL-3.0 e incluir o
código-fonte.

## Aviso legal

O app não inclui ROMs, jogos, assets ou chaves. Use apenas cópias de jogos que você possui
legalmente. decompdecomp e este port não são afiliados à Nintendo, Sony, Sega, Microsoft ou aos
autores dos jogos.
