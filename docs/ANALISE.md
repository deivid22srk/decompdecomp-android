# Análise do decompdecomp e estratégia do port para Android

Upstream: <https://github.com/n-delic/decompdecomp> — commit `UPSTREAM_COMMIT`, versão 1.0.4-alpha.1.
Licença: **AGPL-3.0-only** (ver `LICENSE` e `LICENSE-decomp.wtf`, mantidos no repositório).

## O que o projeto é (e o que ele não é)

O decompdecomp **não é um decompilador de linha de comando nem uma TUI**. Ele é um aplicativo
Python 3.12+ que:

1. sobe um servidor HTTP local (Starlette + Uvicorn em `127.0.0.1`);
2. serve uma **interface web própria** (`app/static/`, HTML/CSS/JS sem build) em uma janela
   WebKitGTK no desktop (`app/gtkwindow.py`);
3. importa uma ROM/imagem de disco que o usuário possui, divide o jogo com `splat` +
   `spimdisasm` + `rabbitizer` e monta um mapa de funções;
4. deixa agentes de IA (Claude Code, Codex, opencode, Antigravity ou qualquer API
   OpenAI-compatível) escreverem C para cada função;
5. compila com o **compilador original do jogo** (GCC 2.7.2/IDO/CodeWarrior etc.), linka na
   posição real e compara byte a byte (`app/engine/`, `rom/match.py`);
6. exporta uma árvore de decompilação reconstruível.

Consequência para o port: **não existe uma interface de terminal para “rodar dentro de um
terminal”**. O comando `decomp` apenas abre a janela/servidor. Portanto, rodar a TUI original em
um emulador de terminal (Termux) não reproduziria a interface do projeto. A interface própria é a
web, e é ela que o app Android incorpora.

## Linguagem, dependências e ferramentas

- **Linguagem:** Python 3.12+ (pacote `decompnet`, 171 arquivos, ~2,6 MB).
- **Servidor:** `starlette`, `uvicorn`, `anyio`, `httpx`, `mcp` (transporte MCP para agentes CLI).
- **Análise de ROM:** `splat64[mips]`, `spimdisasm`, `rabbitizer` (extensão C), `capstone`
  (extensão C via ctypes), `pyelftools`, `pyyaml`.
- **Ferramentas externas (baixadas em runtime):** Ghidra, dtk, objdiff, m2c,
  decomp-permuter, wibo e compiladores históricos (GCC 2.7.2 KMC, IDO, CodeWarrior,
  Visual C++). Ficam em `~/.local/share/decompnet/toolchains`.
- **Entrada:** caminho local, upload HTTP (`POST /api/import`) ou demo embutida.
- **Saída:** pasta do projeto em `~/Decomp/<slug>/` (`asm/`, `assets/`, `src/`, `progress/`).

## O que não funciona em Android/ARM (e por quê)

| Item | Situação | Mitigação |
| :-- | :-- | :-- |
| Compiladores históricos | São binários **x86_64** de Linux/Windows. O próprio upstream declara `unsupported_cpu()` para ARM. Sem eles não há veredito byte a byte. | O app roda, importa, divide, mostra o mapa e permite agentes de API. O match é explicitamente bloqueado com mensagem clara; o projeto pode ser exportado e continuado num PC. |
| Agentes CLI (Claude Code, Codex, opencode, Antigravity) | São binários/CLIs externos x86 e conversam por MCP; não existem para Android. | O **agente de API embutido** (`agents/api.py`) continua 100% funcional: ele chama as ferramentas diretamente no processo, sem MCP. |
| Servidor MCP | `mcp` exige `pydantic-core` (Rust), sem wheel Android. | Shim mínimo `mcp` (apenas `custom_route` + ASGI) no app; o endpoint `/mcp` não é exposto. Nenhuma rota da interface depende dele. |
| `subprocess` de Python (`python -m splat`) | Chaquopy não executa um interpretador Python separado. | `splat` é chamado **no mesmo processo** via `runpy` (patch em `rom/split.py`). |
| `tailscale`, `xdg-open`, `zenity`, `systemd-run` | Inexistentes no Android. | Já são opcionais/guardados por `shutil.which`/`try`; a seleção de arquivos é nativa (SAF). |
| Atualizações do app desktop | Substituiriam a instalação Python. | Desabilitadas na edição mobile. |

## Arquitetura escolhida

```
┌──────────────────────────── Android app (Kotlin) ────────────────────────────┐
│ MainActivity (WebView + barra nativa)                                        │
│   │  User-Agent: ... decompdecomp-window/<segredo>                           │
│   ▼                                                                          │
│ http://127.0.0.1:<porta>/  ◄──── decompdecomp server (Python/Chaquopy)       │
│   • interface web original (HTML/CSS/JS)                                     │
│   • rotas /api/*  (importar, projetos, funções, exportar, agentes)           │
│   • engine: splat/spimdisasm em processo, SQLite, agentes de API             │
│                                                                              │
│ ServerService (foreground, START_STICKY, WakeLock)                           │
│ RomImporter (SAF → streaming HTTP → /api/import)                             │
│ ExportManager (zip do projeto → ACTION_CREATE_DOCUMENT / share)              │
│ ConsoleActivity (log ao vivo = “terminal” da aplicação)                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Runtime Python:** Chaquopy 17.1, Python 3.13, `arm64-v8a` (x86_64 opcional para emulador).
- **Por que Chaquopy e não um rootfs/proot:** é o caminho suportado para Python em APK, produz
  binários nativos para Android e não depende de executar ELF a partir de dados do app. Um
  rootfs x86_64 sob QEMU/proot permitiria, em tese, rodar os compiladores originais, mas custaria
  centenas de MB, seria 5–20× mais lento e não é verificável neste ambiente sem dispositivo.
- **Pacotes nativos:** `capstone` e `rabbitizer` são compilados pelo NDK no CI. O `rabbitizer`
  usa os headers e o `libpython3.13.so` do target 3.13.16 do Chaquopy e é carregado de
  `nativeLibraryDir` por um shim Python; o pip do Chaquopy instala só o shim (puro Python),
  porque ele compila pacotes locais com o toolchain do host. Se um nativo falhar, o build é
  ajustado com fonte vendorizada — não se troca a arquitetura.
- **Interface:** a própria página do projeto em um `WebView`, com `viewport` já responsivo,
  mais uma barra nativa (ROM, Exportar, Console, fonte, Sobre). Não há teclas ESC/TAB/CTRL
  porque não há terminal nem TUI: a página usa toque, campos e botões nativos da web.
- **ROM:** `ACTION_OPEN_DOCUMENT` → cópia em streaming com progresso → `POST /api/import` do
  próprio projeto (mesmo pipeline de identificação/split do desktop).
- **Saída:** `POST /api/export` (quando possível) e zip da árvore do projeto salvo via SAF.
- **Segundo plano:** `ForegroundService` tipo `dataSync`, notificação persistente e WakeLock
  parcial durante o trabalho.

## Adaptações feitas no código upstream (vendorizado)

Todas marcadas com `ANDROID PATCH`:

1. `rom/split.py`, `rom/export.py`, `rom/export_build.py`: `python -m splat` em processo.
2. `app/static/index.html` + `app/static/mobile.css`: ajustes de toque/telefone e remoção do
   botão “Quit” (encerraria o processo do servidor).
3. Shim `mcp/` e `mcp_types.py` (novos, não alteram o upstream).
4. Entrada `decompmobile/` (nova): sobe o servidor, exporta zip e faz a ponte com o Kotlin.

## Limitações conhecidas

- **Não há match byte a byte no celular** (compiladores x86). Importação, split, mapa, leitura de
  assembly, edição de contexto/nomes, agentes de API e exportação funcionam.
- Agentes CLI/MCP e “decompilar em equipe” não estão disponíveis nesta edição.
- O primeiro start do Python pode levar alguns segundos; a tela mostra o progresso.
- Projetos grandes (discos de vários GB) exigem espaço livre e podem demorar; o serviço de
  primeiro plano evita que o Android mate o processo.
