package com.deivid22srk.decompdecomp

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri

/** Credits, license and the honest limits of running decompdecomp on a phone. */
object About {

    fun show(activity: Activity) {
        val message = """
            decompdecomp ${versionName(activity)}

            Projeto original: n-delic (github.com/n-delic/decompdecomp),
            continuação do decomp.wtf de Sam Wolfs (@i12bp8).
            Licença AGPL-3.0-only. O código-fonte desta versão Android também é AGPL-3.0.

            Interface: a própria interface web do decompdecomp, exibida em um WebView.
            Runtime Python: Chaquopy 17.1 (Python 3.13), somente arm64-v8a.

            Limitações desta edição:
            • Os compiladores históricos do jogo são programas x86_64 e não rodam em ARM. Importar, dividir, ver o mapa de funções, ler/editar o assembly, usar agentes de API e exportar funcionam; o veredito byte a byte precisa de um PC x86_64.
            • Agentes CLI (Claude Code, Codex, opencode) e o endpoint MCP não estão disponíveis. O agente de API (DeepSeek, OpenRouter, OpenAI, Ollama etc.) funciona.
            • Nenhuma ROM ou jogo é incluído no APK. Use apenas cópias que você possui legalmente.

            Este app não é afiliado à Nintendo, Sony, Sega ou aos autores dos jogos.
        """.trimIndent()

        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.about_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.about_source) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/n-delic/decompdecomp"))
                    )
                } catch (_: Throwable) {
                }
            }
            .setNegativeButton(R.string.about_license) { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/agpl-3.0.html"))
                    )
                } catch (_: Throwable) {
                }
            }
            .show()
    }

    private fun versionName(activity: Activity): String {
        return try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            info.versionName ?: "1.0"
        } catch (_: Throwable) {
            "1.0"
        }
    }
}
