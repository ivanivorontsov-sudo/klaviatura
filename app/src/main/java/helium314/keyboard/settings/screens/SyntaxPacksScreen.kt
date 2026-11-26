// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.weight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.BackButton
import ru.klaviatura.code.CodeLayoutSwitch
import ru.klaviatura.code.SyntaxPackManager
import ru.klaviatura.glide.GlideEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyntaxPacksScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<SyntaxPackManager.Pack>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeSyntax by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Загружаю каталог…") }
    var codeLayoutsEnabled by remember { mutableStateOf(CodeLayoutSwitch.isEnabled(context)) }

    fun refreshCatalog() {
        scope.launch {
            busy = "catalog"
            try {
                val result = withContext(Dispatchers.IO) { SyntaxPackManager.catalog() }
                packs = result
                installed = withContext(Dispatchers.IO) { SyntaxPackManager.installed(context) }
                activeSyntax = withContext(Dispatchers.IO) { SyntaxPackManager.activeSyntax(context) }
                status = "Пакетов в каталоге: ${result.size}"
            } catch (e: Exception) {
                status = "Не удалось обновить каталог: ${e.message ?: "ошибка сети"}"
            } finally {
                busy = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        SyntaxPackManager.init(context)
        codeLayoutsEnabled = CodeLayoutSwitch.isEnabled(context)
        refreshCatalog()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            BackButton { onClickBack() }
            Text("Словари и синтаксисы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            "Загруженные пакеты работают офлайн. Словари расширяют свайп-ввод, а синтаксисы добавляют ключевые слова и голосовые команды для выбранных языков программирования.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Программные раскладки", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Русская и латинская раскладки для ввода кода. Включаются только здесь; кнопка </> находится в дополнительной панели клавиатуры.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp, end = 12.dp)
                )
            }
            Switch(
                checked = codeLayoutsEnabled,
                enabled = busy.isEmpty(),
                onCheckedChange = { enabled ->
                    val previous = codeLayoutsEnabled
                    try {
                        CodeLayoutSwitch.setEnabled(context, enabled)
                        codeLayoutsEnabled = CodeLayoutSwitch.isEnabled(context)
                        status = if (enabled) "Программные раскладки включены" else "Программные раскладки выключены"
                    } catch (e: Exception) {
                        codeLayoutsEnabled = previous
                        status = "Не удалось изменить раскладки: ${e.message ?: "ошибка"}"
                    }
                }
            )
        }
        Button(onClick = { refreshCatalog() }, enabled = busy.isEmpty()) {
            Text(if (busy == "catalog") "Обновляю…" else "Обновить каталог")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))

        packs.forEach { pack ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (pack.type == "lexicon") "Словарь свайпа" else "Синтаксис и голосовые команды") +
                        " · " + pack.language.uppercase(),
                    style = MaterialTheme.typography.labelMedium
                )
                if (pack.description.isNotBlank())
                    Text(pack.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                if (pack.type == "syntax" && pack.id in installed && activeSyntax != pack.id) {
                    Button(
                        onClick = {
                            SyntaxPackManager.setActiveSyntax(context, pack.id)
                            activeSyntax = pack.id
                            status = "Активирован синтаксис ${pack.name}"
                        },
                        enabled = busy.isEmpty(),
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("Использовать") }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                busy = pack.id
                                try {
                                    if (pack.id in installed && pack.type == "syntax") {
                                        withContext(Dispatchers.IO) { SyntaxPackManager.remove(context, pack) }
                                        if (activeSyntax == pack.id) {
                                            SyntaxPackManager.setActiveSyntax(context, null)
                                            activeSyntax = null
                                        }
                                        installed = installed - pack.id
                                        status = "Пакет «${pack.name}» удалён"
                                    } else {
                                        val updating = pack.id in installed
                                        withContext(Dispatchers.IO) { SyntaxPackManager.install(context, pack) }
                                        installed = installed + pack.id
                                        if (pack.type == "lexicon") {
                                            withContext(Dispatchers.IO) { GlideEngine.reloadLexicons() }
                                        } else {
                                            SyntaxPackManager.setActiveSyntax(context, pack.id)
                                            activeSyntax = pack.id
                                        }
                                        status = if (updating) "Словарь «${pack.name}» обновлён" else "Пакет «${pack.name}» установлен"
                                    }
                                } catch (e: Exception) {
                                    status = "Ошибка пакета «${pack.name}»: ${e.message ?: "неизвестная ошибка"}"
                                } finally {
                                    busy = ""
                                }
                            }
                        },
                        enabled = busy.isEmpty(),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(when {
                            busy == pack.id -> "Подождите…"
                            pack.id in installed && pack.type == "syntax" -> "Удалить · выбран"
                            pack.id in installed && pack.type == "lexicon" -> "Обновить"
                            pack.id in installed -> "Удалить"
                            else -> "Скачать"
                        })
                    }
                }
            }
        }
        if (packs.isEmpty() && busy.isEmpty())
            Text("Каталог пуст. Проверьте подключение к интернету и обновите его.")
        Text(
            "Свои бинарные словари .dict по-прежнему можно импортировать через раздел «Словари».",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
