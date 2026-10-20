# QEMU no Android: compiladores x86_64 no próprio celular

Este documento registra a investigação e o plano para remover a limitação de *match* byte a
byte no Android. A conclusão é: **é viável**, mas exige dois emuladores QEMU (i386 e x86_64),
um sysroot glibc x86_64 e um wrapper para o GCC 2.7.2 do KMC.

## Teste já realizado (host Linux x86_64)

Os binários reais dos toolchains foram baixados e inspecionados:

| Toolchain | Formato | Precisa de |
|---|---|---|
| IDO 7.1 / 5.3 (static recomp) | ELF x86_64, PIE, dinâmico (glibc) | `qemu-x86_64` + sysroot glibc |
| GCC 2.7.2 KMC / `cc1` / `cpp` | ELF i386, estático | `qemu-i386` |
| binutils 2.6 (`as`, `ar`, ...) | ELF x86_64, estático | `qemu-x86_64` |
| PS2 EE-GCC 2.96/2.9/3.2 | ELF i386, dinâmico | `qemu-i386` + runtime i386 |
| PS1 old-gcc | ELF i386/x86_64 + wrapper `/bin/sh` | os dois QEMUs |
| CodeWarrior/MSVC (PE Windows) | PE 32-bit via `wibo` | `wibo` + QEMU (incerto) |

Resultados dos testes no host:

- **IDO 7.1**: `qemu-x86_64 -L sysroot ./cc -c ...` compilou um arquivo C para um objeto
  `ELF 32-bit MSB relocatable, MIPS`. O objeto foi **idêntico** ao gerado pelo `cc` nativo
  (`cmp` sem diferenças). O sysroot mínimo usado tinha só `ld-linux-x86-64.so.2`,
  `libc.so.6` e `libm.so.6`.
- **KMC GCC 2.7.2**: o driver `gcc` é i386 estático e não consegue executar o `as` x86_64.
  A solução é separar em duas etapas: `qemu-i386 gcc -S` e depois
  `qemu-x86_64 as -G0 -mips3 -O2`. O objeto resultante foi **byte a byte idêntico** ao
  `gcc -c` nativo (813 bytes, `cmp` sem diferenças).
- **Desempenho**: 20 compilações pequenas levaram 0,22 s nativas e 1,35 s sob QEMU no IDO
  (≈6×); no KMC, 0,13 s nativas e 1,60 s sob QEMU (≈13×). Em ARM64 o número pode variar,
  mas uma função pequena deve ficar na casa de dezenas a poucas centenas de milissegundos.

## Por que QEMU resolve o bloqueio do Android

O Android 10+ bloqueia `execve` de arquivos graváveis no diretório do app. Se os
compiladores fossem copiados para `files/`, o Android recusaria executá-los.

Com QEMU:

1. O binário do QEMU fica em `nativeLibraryDir` (como o `libcapstone.so`), que é somente
   leitura e executável.
2. Os compiladores e o sysroot ficam em dados do app. O QEMU os lê como **arquivos** e
   emula o `execve` dos processos filhos (IDO chama `cfe`, `uopt`, `as1`, etc.) dentro do
   próprio QEMU. O kernel Android não precisa executar nenhum binário x86.
3. Um wrapper Python no `decompnet` prefixa os comandos com o QEMU correto e passa
   `-L <sysroot>`.

## Empacotamento previsto

- Termux fornece pacotes `qemu-user-x86-64` e `qemu-user-i386` para `aarch64`, versão
  `1:11.1.2`, com interpretador `/system/bin/linker64`.
- Eles são dinâmicos e dependem de `glib`, `libgnutls`, `libpixman`, `libdw`,
  `libandroid-shmem` e transitivas. O closure de pacotes tem ~19 MB comprimidos; é possível
  podar o que o QEMU realmente carrega.
- Os `DT_NEEDED`/`RUNPATH` do Termux apontam para `/data/data/com.termux/...` e usam
  sonames como `libglib-2.0.so.0`. No APK, os arquivos precisam terminar em `.so` e o
  `RUNPATH` precisa ser `$ORIGIN`; o CI vai renomear e rodar `patchelf --replace-needed`
  e `--set-rpath` em todos os ELF.
- O QEMU e as libs vão no APK (não podem ser baixados para `nativeLibraryDir` em runtime).
  Os compiladores e o sysroot podem ser um pacote opcional baixado com SHA-256.
- Estimativa: APK base atual ~20 MB; variante “full” com QEMU ~50–80 MB; pacote N64
  (IDO + KMC + binutils + sysroot) ~15–25 MB.

## Escopo recomendado

1. **Fase 1 — N64**: IDO 7.1/5.3 e KMC GCC 2.7.2 + binutils 2.6, com match byte a byte.
2. **Fase 2 — PS1/PS2**: old-gcc (i386) e EE-GCC (i386), ajustando o wrapper `/bin/sh`.
3. **Fase 3 — GameCube/DS/MSVC**: `wibo` + PE 32-bit. É a parte mais incerta: o `wibo` é
   x86_64 e executa código 32-bit; sob `qemu-x86_64` isso pode ou não funcionar. Só depois
   de testar.

## Licenças

- QEMU é GPLv2-or-later; o app é AGPLv3. A distribuição do binário exige oferecer o
  código-fonte correspondente (link para o fonte do QEMU/Termux e aviso no app).
- IDO, CodeWarrior e MSVC são proprietários; o instalador já pede aceite da licença.
- GCC 2.x/binutils são GPL; manter créditos e fontes.

## Próximos passos técnicos

1. Montar no CI o `jniLibs/arm64-v8a` com `qemu-x86_64`, `qemu-i386` e libs Termux
   renomeadas/patcheadas; verificar `readelf` e `file`.
2. Criar o pacote `n64-qemu-tools` (IDO, KMC, binutils, sysroot, manifesto e SHA-256).
3. Ensinar `toolchains.toml`/`toolchains.py` a plataforma `android-aarch64` e o runtime
   QEMU; adicionar o wrapper KMC (`gcc -S` + `as`).
4. Testar no celular: instalar, ativar o pacote, compilar uma função e comparar os bytes.
5. Só então atualizar o release e remover a mensagem de “precisa de um PC”.
