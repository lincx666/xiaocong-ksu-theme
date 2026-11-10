package me.weishu.kernelsu.ui.util

import android.content.Context
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import java.io.File

/*
 * ============================================================================
 * SUSFS 藏形术 · 工具层
 * ============================================================================
 *
 * 整体思路（为什么这样设计）：
 * 1. SUSFS 是内核侧能力。原厂内核没有编译 SUSFS，所以这里走「guard LKM」方案：
 *    把编译好的 susfs_guard_lkm.ko 在运行时 insmod 进内核，提供 SUSFS 能力。
 * 2. .ko 和两个小工具（susfs_ver / susfs_set_uname）都内嵌在 APK assets 里，
 *    首次使用时才释放到 /data/local/tmp —— 这样管理器是自包含的，用户不用手动推文件。
 * 3. 加载必须走 `ksud insmod`，不能裸 `insmod`：su 域下裸 insmod 会被 SELinux 拒绝，
 *    ksud 的加载通道是 KernelSU 特批的。
 * 4. 所有加载/设置都发生在「管理器运行期间」，绝不写开机脚本 ——
 *    即使 LKM 出问题，最坏结果也只是管理器崩溃/手机重启一次，不会 bootloop。
 * 5. 版本查询、内核后缀伪装都通过 reboot 系统调用（supercall）与内核通信，
 *    命令与 KernelSU-Next 的 susfsd 一致：
 *        syscall(SYS_reboot, 0xDEADBEEF, 0xFAFAFAFA, <CMD>, <struct>)
 *    对应 C 工具源码见仓库 tools/susfs-lkm/。
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

/**
 * 把 APK assets 里的文件释放到 /data/local/tmp。
 *
 * 流程：assets → App cacheDir → root shell 复制到 /data/local/tmp 并 chmod。
 * 返回 false 表示复制失败（权限/空间问题），调用方应跳过后续操作。
 */
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

/** 确保版本查询工具已就绪（可执行）。 */
fun ensureSusfsVerTool(): Boolean = stageAsset(SUSFS_VER_ASSET, SUSFS_VER_TMP, executable = true)

/** 确保内核后缀设置工具已就绪（可执行）。 */
fun ensureSusfsSetUnameTool(): Boolean = stageAsset(SUSFS_SET_UNAME_ASSET, SUSFS_SET_UNAME_TMP, executable = true)

/**
 * 查询当前内核提供的 SUSFS 版本。
 *
 * 前置条件：/proc/susfs_path 存在（guard LKM 已加载）。
 * 查询方式：susfs_ver 工具通过 reboot supercall（CMD_SUSFS_SHOW_VERSION）
 * 向内核要版本字符串，例如 "v2.3.0"；不支持时返回 null。
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
 * 管理器启动时自动展开藏形术（加载 guard LKM）。
 *
 * 安全设计：只在 App 运行时执行，不写开机脚本。如果 LKM 加载导致内核异常，
 * 最坏只是 App 崩溃/手机重启一次；重启后 App 不会自动运行，因此不会 bootloop。
 *
 * 流程：
 *   1. 检测 /proc/susfs_path —— 已加载则跳过加载（幂等）
 *   2. 未加载：从 assets 释放 .ko 到 /data/local/tmp
 *   3. 通过 ksud insmod 加载（ksud 的加载通道有 KernelSU 的 SELinux 特权）
 *   4. 应用画笔签名（内核版本后缀伪装）
 *   5. 返回 SUSFS 版本供首页显示
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
 * 应用 / 恢复「画笔签名」（SUSFS SPOOF_UNAME）。
 *
 * 开启时：把内核版本伪装成 <真实前缀>-<后缀>，例如 6.12.38-android16-xiaocong，
 *         version 字段设为「小葱特供版」。任何进程调 uname() 都会看到伪装值。
 * 关闭时：用 "default" 参数让内核恢复真实的 utsname release/version。
 *
 * 注意：这只是「显示层」伪装（kretprobe hook newuname 系统调用），
 * 内核本体没有改动，功能与真实内核一致。
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
