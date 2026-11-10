package me.weishu.kernelsu.ui.screen.susfs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 藏形术页面：看状态、手动展开、管 sus_path 规则，还有画笔签名的开关。 */
@Composable
fun SusfsScreen() {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<SusfsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "藏形术",
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("藏形术：${if (uiState.loaded) "已展开" else "尚未展开"}")
                    Spacer(Modifier.height(4.dp))
                    if (uiState.loaded) {
                        Text("版本：${uiState.version ?: "未知"}")
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("内核：${uiState.kernelVersion}")
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = if (uiState.busy) "正在展开…" else if (uiState.loaded) "重新展开" else "展开藏形术",
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { viewModel.loadSusfs() },
                    )
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("画笔签名（内核版本后缀）")
                    SwitchPreference(
                        title = "启用画笔签名",
                        summary = "打开后 uname -r 会显示成自定义的尾缀，像别人的内核作者签名一样",
                        checked = uiState.unameEnabled,
                        onCheckedChange = { viewModel.setUnameEnabled(it) },
                    )
                    var suffixInput by remember(uiState.unameSuffix) { mutableStateOf(uiState.unameSuffix) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = suffixInput,
                            onValueChange = { suffixInput = it },
                            label = "签名后缀（如 xiaocong）",
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        TextButton(
                            text = "盖上去",
                            onClick = { viewModel.applyUnameSuffix(suffixInput) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("画布上现在写着：${uiState.unameCurrent}")
                }
            }

            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    SmallTitle("隐形的角落（sus_path）")
                    var input by remember { mutableStateOf("") }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            label = "要藏起来的东西",
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        )
                        TextButton(
                            text = "藏起来",
                            onClick = {
                                if (input.isNotBlank()) {
                                    viewModel.addRule(input.trim())
                                    input = ""
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (uiState.rules.isEmpty()) {
                        Text("这里还空着，什么都没藏")
                    } else {
                        uiState.rules.forEach { rule ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(rule, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.delRule(rule) }) {
                                    Icon(imageVector = MiuixIcons.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.message.isNotBlank()) {
                Card {
                    Text(
                        text = uiState.message,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
