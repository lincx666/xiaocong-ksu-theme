package me.weishu.kernelsu.ui.screen.susfs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ksuApp
import me.weishu.kernelsu.ui.util.DEFAULT_UNAME_SUFFIX
import me.weishu.kernelsu.ui.util.applySusfsUnameSpoof
import me.weishu.kernelsu.ui.util.getRootShell
import me.weishu.kernelsu.ui.util.getUnameSuffix
import me.weishu.kernelsu.ui.util.isUnameSpoofEnabled
import me.weishu.kernelsu.ui.util.readSusfsVersion
import me.weishu.kernelsu.ui.util.setUnameSpoofConfig
import java.io.File

data class SusfsUiState(
    val loaded: Boolean = false,
    val version: String? = null,
    val kernelVersion: String = "",
    val rules: List<String> = emptyList(),
    val message: String = "",
    val busy: Boolean = false,
    val unameEnabled: Boolean = true,
    val unameSuffix: String = DEFAULT_UNAME_SUFFIX,
    val unameCurrent: String = "",
)

private const val SUSFS_ASSET = "susfs_guard_lkm-android16-6.12.ko"
private const val SUSFS_TMP = "/data/local/tmp/susfs_guard_lkm.ko"
private const val SUSFS_PROC = "/proc/susfs_path"

class SusfsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SusfsUiState())
    val uiState: StateFlow<SusfsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                try {
                    val shell = getRootShell()
                    val kernel = ShellUtils.fastCmd(shell, "uname -r").trim()
                    val loaded = ShellUtils.fastCmd(shell, "test -e $SUSFS_PROC && echo 1 || echo 0").trim() == "1"
                    val rules = if (loaded) parseRules(ShellUtils.fastCmd(shell, "cat $SUSFS_PROC")) else emptyList()
                    val version = if (loaded) readSusfsVersion() else null
                    SusfsUiState(
                        loaded = loaded,
                        version = version,
                        kernelVersion = kernel,
                        rules = rules,
                        unameEnabled = isUnameSpoofEnabled(),
                        unameSuffix = getUnameSuffix(),
                        unameCurrent = kernel,
                    )
                } catch (e: Exception) {
                    SusfsUiState(message = e.message ?: "看走眼了")
                }
            }
            _uiState.value = state
        }
    }

    fun loadSusfs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = "正在展开藏形术…")
            val message = withContext(Dispatchers.IO) {
                try {
                    val out = File(ksuApp.cacheDir, SUSFS_ASSET)
                    if (!out.exists()) {
                        ksuApp.assets.open(SUSFS_ASSET).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    val shell = getRootShell()
                    val copyOut = exec(shell, "cp '${out.absolutePath}' $SUSFS_TMP && chmod 644 $SUSFS_TMP")
                    if (!isOk(copyOut)) {
                        "匣子没搬过去: $copyOut"
                    } else {
                        val loadOut = exec(
                            shell,
                            "if [ -x /data/adb/ksu/bin/ksud ]; then /data/adb/ksu/bin/ksud insmod $SUSFS_TMP; " +
                                "else insmod $SUSFS_TMP; fi"
                        )
                        if (isOk(loadOut)) "已展开" else "展开失败: $loadOut"
                    }
                } catch (e: Exception) {
                    "术法出了岔子: ${e.message}"
                }
            }
            _uiState.value = _uiState.value.copy(busy = false, message = message)
            refresh()
        }
    }

    fun addRule(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val shell = getRootShell()
                    val out = exec(shell, "echo 'add $path' > $SUSFS_PROC")
                    if (isOk(out)) "已藏好: $path" else "没藏住: $out"
                } catch (e: Exception) {
                    "藏的时候出了岔子: ${e.message}"
                }
            }
            _uiState.value = _uiState.value.copy(message = message)
            refresh()
        }
    }

    fun delRule(path: String) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val shell = getRootShell()
                    val out = exec(shell, "echo 'del $path' > $SUSFS_PROC")
                    if (isOk(out)) "已放出: $path" else "放不出来了: $out"
                } catch (e: Exception) {
                    "放的时候出了岔子: ${e.message}"
                }
            }
            _uiState.value = _uiState.value.copy(message = message)
            refresh()
        }
    }

    fun setUnameEnabled(enabled: Boolean) {
        setUnameSpoofConfig(enabled, getUnameSuffix())
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(unameEnabled = enabled, message = if (enabled) "画笔签名已启用" else "画笔签名已收起")
            withContext(Dispatchers.IO) { applySusfsUnameSpoof() }
            refresh()
        }
    }

    fun applyUnameSuffix(suffix: String) {
        val trimmed = suffix.trim().takeIf { it.isNotBlank() } ?: DEFAULT_UNAME_SUFFIX
        setUnameSpoofConfig(true, trimmed)
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "正在盖笔名…")
            withContext(Dispatchers.IO) { applySusfsUnameSpoof() }
            refresh()
        }
    }

    private fun exec(shell: Shell, command: String): String {
        return ShellUtils.fastCmd(shell, "($command) 2>&1; echo __EXIT:\$?")
    }

    private fun isOk(output: String): Boolean = output.contains("__EXIT:0")

    private fun parseRules(text: String): List<String> {
        return text.lineSequence()
            .mapNotNull { line ->
                val match = Regex("^path=(\\S+) ").find(line)
                match?.groupValues?.get(1)
            }
            .filterNot { path ->
                path.startsWith("/proc/susfs_") || path.startsWith("/sys/module/susfs_guard_lkm")
            }
            .toList()
    }
}
