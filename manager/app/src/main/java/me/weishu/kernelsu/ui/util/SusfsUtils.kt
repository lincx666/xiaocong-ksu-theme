package me.weishu.kernelsu.ui.util

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import java.io.File

private const val SUSFS_VER_ASSET = "susfs_ver"
private const val SUSFS_VER_TMP = "/data/local/tmp/susfs_ver"
private const val SUSFS_GUARD_ASSET = "susfs_guard_lkm-android16-6.12.ko"
private const val SUSFS_GUARD_TMP = "/data/local/tmp/susfs_guard_lkm.ko"
private const val SUSFS_SET_UNAME_ASSET = "susfs_set_uname"
private const val SUSFS_SET_UNAME_TMP = "/data/local/tmp/susfs_set_uname"
private const val SUSFS_PROC = "/proc/susfs_path"

private const val PREFS_NAME = "susfs_settings"
private const val KEY_UNAME_ENABLED = "uname_spoof_enabled"
private const val KEY_UNAME_SUFFIX = "uname_spoof_suffix"
const val DEFAULT_UNAME_SUFFIX = "xiaocong"
const val SUSFS_UNAME_VERSION = "小葱特供版"

private fun susfsPrefs() = ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

fun isUnameSpoofEnabled(): Boolean = susfsPrefs().getBoolean(KEY_UNAME_ENABLED, true)

fun getUnameSuffix(): String =
    susfsPrefs().getString(KEY_UNAME_SUFFIX, DEFAULT_UNAME_SUFFIX)
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_UNAME_SUFFIX

fun setUnameSpoofConfig(enabled: Boolean, suffix: String) {
    susfsPrefs().edit()
        .putBoolean(KEY_UNAME_ENABLED, enabled)
        .putString(KEY_UNAME_SUFFIX, suffix)
        .apply()
}

private fun stageAsset(assetName: String, tmpPath: String, executable: Boolean = false): Boolean {
    return try {
        val cached = File(ksuApp.cacheDir, assetName)
        if (!cached.exists()) {
            ksuApp.assets.open(assetName).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val shell = getRootShell()
        val perm = if (executable) "755" else "644"
        ShellUtils.fastCmd(shell, "cp '${cached.absolutePath}' $tmpPath && chmod $perm $tmpPath")
        true
    } catch (_: Throwable) {
        false
    }
}

fun ensureSusfsVerTool(): Boolean = stageAsset(SUSFS_VER_ASSET, SUSFS_VER_TMP, executable = true)

fun ensureSusfsSetUnameTool(): Boolean = stageAsset(SUSFS_SET_UNAME_ASSET, SUSFS_SET_UNAME_TMP, executable = true)

/**
 * Returns the SUSFS version reported by the kernel (guard LKM), or null when unavailable.
 */
fun readSusfsVersion(): String? {
    return try {
        val shell = getRootShell()
        val available = ShellUtils.fastCmd(
            shell, "test -e $SUSFS_PROC && echo 1 || echo 0"
        ).trim() == "1"
        if (!available) {
            return null
        }
        if (!ensureSusfsVerTool()) {
            return null
        }
        val output = ShellUtils.fastCmd(shell, "$SUSFS_VER_TMP 2>/dev/null").trim()
        output.ifBlank { null }?.takeIf { !it.contains("unsupported") }
    } catch (_: Throwable) {
        null
    }
}

/**
 * Auto-expand the SUSFS guard LKM when the manager launches (runtime only, never at boot).
 * If the module is already loaded this is a cheap no-op; returns the resulting version.
 */
fun ensureSusfsAutoLoaded(): String? {
    return try {
        val shell = getRootShell()
        val available = ShellUtils.fastCmd(
            shell, "test -e $SUSFS_PROC && echo 1 || echo 0"
        ).trim() == "1"
        if (!available) {
            if (stageAsset(SUSFS_GUARD_ASSET, SUSFS_GUARD_TMP)) {
                ShellUtils.fastCmd(
                    shell,
                    "if [ -x /data/adb/ksu/bin/ksud ]; then /data/adb/ksu/bin/ksud insmod $SUSFS_GUARD_TMP; " +
                        "else insmod $SUSFS_GUARD_TMP; fi"
                )
            }
        }
        applySusfsUnameSpoof()
        readSusfsVersion()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Applies (or restores) the custom kernel release via SUSFS SPOOF_UNAME.
 * Enabled: uname -r becomes e.g. 6.12.38-android16-<suffix>.
 * Disabled: resets release to the real kernel value ("default").
 */
fun applySusfsUnameSpoof() {
    try {
        val shell = getRootShell()
        if (!ensureSusfsSetUnameTool()) {
            return
        }
        if (!isUnameSpoofEnabled()) {
            ShellUtils.fastCmd(shell, "$SUSFS_SET_UNAME_TMP default default")
            return
        }
        val suffix = getUnameSuffix()
        val current = ShellUtils.fastCmd(shell, "uname -r").trim()
        if (current.endsWith("-$suffix")) {
            return
        }
        val base = Regex("^(\\d+\\.\\d+\\.\\d+-android\\d+)").find(current)?.value ?: current
        ShellUtils.fastCmd(shell, "$SUSFS_SET_UNAME_TMP '$base-$suffix' '$SUSFS_UNAME_VERSION'")
    } catch (_: Throwable) {
    }
}
