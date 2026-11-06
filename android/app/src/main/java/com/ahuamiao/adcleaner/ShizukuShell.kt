package com.ahuamiao.adcleaner

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * 经 Shizuku 以 shell 身份起 ShellServer（与内置无线调试同一个命令服务），之后经 ServerClient 收发。
 *
 * Shizuku 13 起 newProcess 不再公开，官方建议 UserService；这里只需要起一个进程、拿它的输入输出，
 * 反射调用它是 Shizuku 文档里认可的做法（rish 走的也是同一个接口）。
 */
object ShizukuShell {
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
    }

    /** Shizuku 在运行、并且已授权本 App。 */
    @JvmStatic
    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    private var client: ServerClient? = null

    @JvmStatic
    @Synchronized
    fun client(): ServerClient {
        client?.let { if (!it.closed) return it }
        val process = newProcess.invoke(null, arrayOf("sh", "-c", ServerClient.command(AdbShell.app.applicationInfo.sourceDir)),
            null, null) as ShizukuRemoteProcess
        val c = ServerClient(process.inputStream, process.outputStream) {
            synchronized(this) { if (client?.closed == true) client = null }
        }
        if (!c.awaitReady(15_000)) {
            process.destroy()
            throw java.io.IOException("命令服务没有启动")
        }
        client = c
        return c
    }
}
