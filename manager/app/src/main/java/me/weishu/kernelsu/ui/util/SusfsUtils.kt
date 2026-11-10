package me.weishu.kernelsu.ui.util

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import java.io.File

/*
 * 藏形术的"仓库管理员"。
 *
 * 干的事：把塞在 APK 里的 guard LKM 和小工具倒腾到手机临时目录，再求 ksud
 * 把模块挂进内核。别问为什么不直接 insmod —— SELinux 会翻脸，只有 ksud 有通行证。
 *
 * 还有个原则：只在你打开管理器的时候动手，绝不写开机脚本。
 * 万一模块抽风，最多 App 崩溃重启一次，不至于让你手机变砖。
 *
 * 跟内核对话靠 reboot 系统调用（supercall），命令跟 KernelSU-Next 那套一样，
 * 具体结构看 tools/susfs-lkm/ 里的 C 源码。
 */

// ---- 内嵌资产（APK assets）名称与运行时临时路径 -----------------------------
private const val SUSFS_VER_ASSET = "susfs_ver"                                     // 版本查询工具
private const val SUSFS_VER_TMP = "/data/local/tmp/susfs_ver"
private const val SUSFS_GUARD_ASSET = "susfs_guard_lkm-android16-6.12.ko"           // SUSFS 内核模块
private const val SUSFS_GUARD_TMP = "/data/local/tmp/susfs_guard_lkm.ko"
private const val SUSFS_SET_UNAME_ASSET = "susfs_set_uname"                         // 内核后缀设置工具
private const val SUSFS_SET_UNAME_TMP = "/data/local/tmp/susfs_set_uname"
private const val SUSFS_PROC = "/proc/susfs_path"                                   // 存在即代表 LKM 已加载

// ---- 设置存储（画笔签名的开关与后缀） ---------------------------------------
private const val PREFS_NAME = "susfs_settings"
private const val KEY_UNAME_ENABLED = "uname_spoof_enabled"
private const val KEY_UNAME_SUFFIX = "uname_spoof_suffix"
const val DEFAULT_UNAME_SUFFIX = "xiaocong"          // 默认签名后缀
const val SUSFS_UNAME_VERSION = "小葱特供版"          // uname 的 version 字段

private fun susfsPrefs() = ksuApp.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** 画笔签名是否启用（默认开启）。 */
fun isUnameSpoofEnabled(): Boolean = susfsPrefs().getBoolean(KEY_UNAME_ENABLED, true)

/** 当前设置的签名后缀（空值回退到默认 xiaocong）。 */
fun getUnameSuffix(): String =
    susfsPrefs().getString(KEY_UNAME_SUFFIX, DEFAULT_UNAME_SUFFIX)
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_UNAME_SUFFIX

/** 保存画笔签名配置（开关 + 后缀）。 */
fun setUnameSpoofConfig(enabled: Boolean, suffix: String) {
    susfsPrefs().edit()
        .putBoolean(KEY_UNAME_ENABLED, enabled)
        .putString(KEY_UNAME_SUFFIX, suffix)
        .apply()
}

/** 把 assets 里的东西掏出来，放到 /data/local/tmp。要执行的加 755，模块给 644。 */
private fun stageAsset(assetName: String, tmpPath: String, executable: Boolean = false): Boolean {
    return try {
        val cached = File(ksuApp.cacheDir, assetName)
        if (!cached.exists()) {
            ksuApp.assets.open(assetName).use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val shell = getRootShell()
        // 工具需要可执行位（755），内核模块只要可读（644）即可
        val perm = if (executable) "755" else "644"
        ShellUtils.fastCmd(shell, "cp '${cached.absolutePath}' $tmpPath && chmod $perm $tmpPath")
        true
    } catch (_: Throwable) {
        false
    }
}

/** 把版本查询那个小工具摆好（可执行）。 */
fun ensureSusfsVerTool(): Boolean = stageAsset(SUSFS_VER_ASSET, SUSFS_VER_TMP, executable = true)

/** 把改后缀的工具摆好（可执行）。 */
fun ensureSusfsSetUnameTool(): Boolean = stageAsset(SUSFS_SET_UNAME_ASSET, SUSFS_SET_UNAME_TMP, executable = true)

/**
 * 问内核要 SUSFS 版本号。
 * /proc/susfs_path 不在 = 模块没挂上，直接 null。
 * 工具回 "unsupported" 的也当没有——老内核没这接口。
 */
fun readSusfsVersion(): String? {
    return try {
        val shell = getRootShell()
        // 先确认 LKM 已加载（/proc/susfs_path 是 SUSFS 的能力接口之一）
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
        // 工具输出 "unsupported" 表示内核没有该 supercall（旧内核/非 SUSFS）
        output.ifBlank { null }?.takeIf { !it.contains("unsupported") }
    } catch (_: Throwable) {
        null
    }
}

/**
 * 管理器一打开就自动把藏形术展开。
 *
 * 顺序很简单：看 /proc/susfs_path 在不在（在 = 已经挂过，跳过），不在就把 .ko
 * 搬进临时目录，ksud insmod 挂上，顺手把画笔签名也盖上，最后把版本号报上去。
 *
 * 只在 App 里跑，不进开机流程——出问题最多重启一次，不会无限循环。
 */
fun ensureSusfsAutoLoaded(): String? {
    return try {
        val shell = getRootShell()
        val available = ShellUtils.fastCmd(
            shell, "test -e $SUSFS_PROC && echo 1 || echo 0"
        ).trim() == "1"
        if (!available) {
            if (stageAsset(SUSFS_GUARD_ASSET, SUSFS_GUARD_TMP)) {
                // 优先用 ksud（Symlink: /data/adb/ksu/bin/ksud → /data/adb/ksud）
                // 裸 insmod 在 su 域会被 SELinux 拒绝，仅作兜底
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
 * 盖画笔签名，改内核版本后缀。
 *
 * 开着：6.12.38-android16-5-gxxxx 会变成 6.12.38-android16-<后缀>，
 * uname 的版本字段写成「小葱特供版」。
 * 关着：发个 default，让内核把真实版本还回来。
 *
 * 就是层皮，内核本体没动——专门骗检测工具那种。
 */
fun applySusfsUnameSpoof() {
    try {
        val shell = getRootShell()
        if (!ensureSusfsSetUnameTool()) {
            return
        }
        if (!isUnameSpoofEnabled()) {
            // 关闭：default = 内核当前真实值（supercall 会读取 utsname()）
            ShellUtils.fastCmd(shell, "$SUSFS_SET_UNAME_TMP default default")
            return
        }
        val suffix = getUnameSuffix()
        val current = ShellUtils.fastCmd(shell, "uname -r").trim()
        // 已经是目标后缀 → 无需重复设置（幂等，避免每次打开都发 supercall）
        if (current.endsWith("-$suffix")) {
            return
        }
        // 从真实内核版本里提取 "X.Y.Z-androidN" 前缀，拼上自定义后缀
        // 例：6.12.38-android16-5-g844001fb8721-ab14552068-4k → 6.12.38-android16-xiaocong
        val base = Regex("^(\\d+\\.\\d+\\.\\d+-android\\d+)").find(current)?.value ?: current
        ShellUtils.fastCmd(shell, "$SUSFS_SET_UNAME_TMP '$base-$suffix' '$SUSFS_UNAME_VERSION'")
    } catch (_: Throwable) {
    }
}
