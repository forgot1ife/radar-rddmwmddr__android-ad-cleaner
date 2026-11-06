package com.ahuamiao.adcleaner

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 与 ShellServer（shell 身份的常驻命令服务）对话：请求按编号发、回应按编号收，协议见 ShellServer.java。
 * 两条路共用：内置无线调试给它一条 adb `exec:` 流（AdbShell），Shizuku 给它一个远程进程的输入输出（ShizukuShell）。
 */
class ServerClient(input: InputStream, private val output: OutputStream, private val onClose: () -> Unit) {
    private val pending = ConcurrentHashMap<Int, Job>()
    private val nextId = AtomicInteger(1)
    private val ready = CountDownLatch(1)
    @Volatile var closed = false
        private set

    class Job : ShellJob {
        @Volatile private var finished = false
        @Volatile private var code = -1
        @Volatile private var out = ByteArray(0)
        @Volatile private var err = ByteArray(0)

        fun complete(code: Int, out: ByteArray, err: ByteArray) {
            this.code = code; this.out = out; this.err = err
            finished = true
        }

        override fun isDone(): Boolean = finished
        override fun result(timedOut: Boolean) = ShellResult(code, out, err, timedOut)

        /** 超时：不再等它（服务那头跑完会自己回，回了也没人收）。 */
        override fun cancel() {
            finished = true
        }

        /** 同步等结果（读屏、问尺寸这类内部调用用）；超时返回 null。 */
        fun await(timeoutMs: Long): ShellResult? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!finished && System.currentTimeMillis() < deadline) Thread.sleep(5)
            return if (finished) result(false) else null
        }
    }

    init {
        thread(isDaemon = true, name = "server-reader") {
            try {
                val data = DataInputStream(input)
                while (true) {
                    val id = data.readInt()
                    val code = data.readInt()
                    val o = ByteArray(data.readInt()).also { data.readFully(it) }
                    val e = ByteArray(data.readInt()).also { data.readFully(it) }
                    if (id == 0) ready.countDown() else pending.remove(id)?.complete(code, o, e)
                }
            } catch (e: Exception) {
                android.util.Log.w("AacShell", "命令服务断开：$e")
            } finally {
                closed = true
                pending.values.toList().forEach { it.complete(-1, ByteArray(0), "命令服务断开".toByteArray()) }
                pending.clear()
                onClose()
            }
        }
    }

    /** 等服务报「ready」。 */
    fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** 发一条请求：整包一次写出（adb 流上每次 write 都要等对端回 OKAY，拆开写会慢）。 */
    fun request(type: Int, body: ByteArray): Job {
        val job = Job()
        if (closed) {
            job.complete(-1, ByteArray(0), "命令服务已断开".toByteArray())
            return job
        }
        val id = nextId.getAndIncrement()
        pending[id] = job
        val buf = ByteBuffer.allocate(9 + body.size).putInt(id).put(type.toByte()).putInt(body.size).put(body).array()
        try {
            synchronized(output) {
                output.write(buf)
                output.flush()
            }
        } catch (e: Exception) {
            pending.remove(id)
            job.complete(-1, ByteArray(0), "命令服务不可用：$e".toByteArray())
        }
        return job
    }

    fun submit(cmd: String, stdin: ByteArray?): ShellJob = request(1, field(cmd.toByteArray()) + field(stdin ?: ByteArray(0)))
    fun daemon(name: String, cmd: String) = request(2, field(name.toByteArray()) + cmd.toByteArray())
    fun stopDaemon(name: String) = request(3, name.toByteArray())
    fun dump(display: Int): ShellResult? = request(4, display.toString().toByteArray()).await(30_000)
    fun size(display: Int): ShellResult? = request(5, display.toString().toByteArray()).await(10_000)

    private fun field(data: ByteArray): ByteArray = ByteBuffer.allocate(4 + data.size).putInt(data.size).put(data).array()

    companion object {
        /** 起服务的命令：本 APK 当类路径，以调用者（shell）身份跑；stderr 丢掉，免得混进二进制协议。 */
        fun command(apk: String) = "CLASSPATH=$apk exec app_process / com.ahuamiao.adcleaner.ShellServer 2>/dev/null"
    }
}

/** 一条命令的结果（Python 侧读 code / out / err / timedOut 四个字段）。 */
class ShellResult(
    @JvmField val code: Int,
    @JvmField val out: ByteArray,
    @JvmField val err: ByteArray,
    @JvmField val timedOut: Boolean,
)
