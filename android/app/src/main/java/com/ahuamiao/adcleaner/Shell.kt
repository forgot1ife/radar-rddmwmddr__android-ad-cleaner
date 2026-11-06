package com.ahuamiao.adcleaner

/** 一条在跑的命令：Python 侧轮询 isDone()，超时自己 cancel()，结束后取 result()。 */
interface ShellJob {
    fun isDone(): Boolean
    fun result(timedOut: Boolean): ShellResult
    fun cancel()
}

/**
 * 以 shell 身份执行命令、读屏的门面，Python 只认这一个类（aac_android/bridge.py、u2.py）。
 * 两条路起的是同一个命令服务（ShellServer）：装了并授权 Shizuku 就经 Shizuku 起，否则经内置无线调试（#622）。
 */
object Shell {
    @JvmStatic
    fun backend(): String = when {
        ShizukuShell.ready() -> "shizuku"
        AdbShell.ready() || AdbShell.connect(4000) -> "adb"
        else -> ""
    }

    @JvmStatic
    fun ready(): Boolean = backend().isNotEmpty()

    private fun client(): ServerClient = if (ShizukuShell.ready()) ShizukuShell.client() else AdbShell.client()

    @JvmStatic
    fun submit(cmd: String, stdin: ByteArray?): ShellJob = try {
        client().submit(cmd, stdin)
    } catch (e: Exception) {
        ServerClient.Job().apply { complete(-1, ByteArray(0), "命令服务不可用：$e".toByteArray()) }
    }

    @JvmStatic
    fun daemon(name: String, cmd: String): Boolean {
        client().daemon(name, cmd)
        return true
    }

    @JvmStatic
    fun stopDaemon(name: String) {
        try {
            client().stopDaemon(name)
        } catch (_: Exception) {
        }
    }

    /** 读屏（-1 = 所有屏）：与 uiautomator 同格式的 XML；读不到返回空串。 */
    @JvmStatic
    fun dump(display: Int): String = client().dump(display)?.takeIf { it.code == 0 }?.out?.toString(Charsets.UTF_8) ?: ""

    /** 某块屏的「宽 高」（随旋转）；读不到返回空串。 */
    @JvmStatic
    fun size(display: Int): String = client().size(display)?.takeIf { it.code == 0 }?.out?.toString(Charsets.UTF_8) ?: ""
}
