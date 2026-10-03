package com.tilixibiesi.bili

import com.tilixibiesi.network.HttpUtils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.zip.DataFormatException
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * B 站弹幕的下载与解压。
 *
 * 弹幕源使用公开的历史弹幕接口 `comment.bilibili.com/<cid>.xml`：
 *  - 无需登录、无需 wbi 签名，比 `x/v2/dm/web/seg.so`（protobuf 且需鉴权）更省事；
 *  - 单文件包含该 cid 的历史弹幕，**服务端自行决定条数**（实测：冷门视频约 1200 条封顶，
 *    超长热门视频可达 9600 条并覆盖到片尾），`maxlimit` 查询参数实测被忽略。
 *
 * 服务端恒返回 `Content-Encoding: deflate`，且是**无 zlib 头的裸 deflate 流**，
 * 因此不能直接用 GZIPInputStream，必须 `Inflater(nowrap = true)`，
 * 这也是早期实现"抓下来是乱码"的根因。
 */
object BiliDanmakuLoader {

    /**
     * 弹幕请求的超时。
     *
     * 这条请求处在「用户已点开视频、正在等画面」的交互路径上，
     * 原先沿用 [HttpUtils.getBytes] 默认的 30 秒：一旦服务端慢或风控，
     * 用户要对着加载框干等半分钟，还会白占一个 IO 线程。
     */
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 10000

    /**
     * 一次可取消的弹幕请求句柄。
     *
     * 切集时旧的 `fetch` 仍在阻塞读网络——仅靠 `requestId` 丢弃结果并不能让请求停下，
     * 它要继续跑到超时为止。这里持有底层连接，[cancel] 时直接 `disconnect()`，
     * 阻塞中的 `read` 会立刻抛异常返回，线程随即释放。
     */
    class Request internal constructor() {
        @Volatile
        internal var conn: HttpURLConnection? = null

        @Volatile
        internal var cancelled = false

        /** 取消请求；可重复调用。连接建立与取消的竞态由 [bind] 兜住 */
        fun cancel() {
            cancelled = true
            val c = conn
            conn = null
            runCatching { c?.disconnect() }
        }

        internal fun bind(c: HttpURLConnection) {
            conn = c
            // cancel() 可能先于连接建立到达：此时必须立刻掐掉，否则请求照跑
            if (cancelled) runCatching { c.disconnect() }
        }
    }

    /**
     * 抓取指定 cid 的弹幕。必须在后台线程调用。
     *
     * @param request 可选句柄，用于切集时取消仍在途的请求
     * @return 解析后的弹幕列表；**失败返回 null，成功但无弹幕返回空列表**
     *         （调用方必须区分二者：把失败当"没有弹幕"会导致整个视频会话再也拉不到）
     */
    fun fetch(cid: Long, cookie: String = "", request: Request? = null): List<DanmakuItem>? {
        if (cid <= 0) return null
        val headers = mutableMapOf(
            "Referer" to "https://www.bilibili.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        if (cookie.isNotEmpty()) headers["Cookie"] = cookie
        return try {
            val raw = HttpUtils.getBytes(
                url = "https://comment.bilibili.com/$cid.xml",
                headers = headers,
                connectTimeout = CONNECT_TIMEOUT_MS,
                readTimeout = READ_TIMEOUT_MS,
                onConnected = { request?.bind(it) }
            )
            if (request?.cancelled == true) return null
            val xml = decodeBody(raw)
            // 不再截断：早期实现在这里 `subList(0, 6000)` 取的是**时间轴上最早的 6000 条**，
            // 实测 103 分钟、9600 条弹幕的视频会被砍到第 2763 秒（只留前 44.6%），
            // 后半部整段没有弹幕。服务端返回量本身有界（实测最多 9600 条），
            // 全部装载的内存代价可忽略；渲染侧也不再设同屏上限，二者必须一起放开。
            BiliDanmakuParser.parse(xml)
        } catch (_: Exception) {
            // 取消导致的 disconnect 也会走到这里：统一按失败返回，由调用方按 requestId 丢弃
            null
        }
    }

    /**
     * 还原响应体：优先 gzip，其次裸 deflate（B 站弹幕接口的实际情况），
     * 两者都不匹配时按明文 UTF-8 处理。
     */
    fun decodeBody(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // gzip 魔数 0x1F 0x8B
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { inp ->
                    return String(inp.readBytes(), StandardCharsets.UTF_8)
                }
            } catch (_: Exception) { /* 落到下面的分支再试 */ }
        }
        // 裸 deflate（无 zlib 头），对应 Inflater(nowrap = true)
        try {
            val out = ByteArrayOutputStream(bytes.size * 4)
            val inf = Inflater(true)
            try {
                inf.setInput(bytes)
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary() || inf.finished()) break
                        throw DataFormatException("no progress")
                    }
                    out.write(buf, 0, n)
                }
            } finally {
                inf.end()
            }
            val text = String(out.toByteArray(), StandardCharsets.UTF_8)
            if (text.isNotBlank()) return text
        } catch (_: Exception) { /* 继续按明文处理 */ }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
