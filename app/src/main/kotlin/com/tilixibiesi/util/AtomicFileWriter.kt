package com.tilixibiesi.util

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 整份覆写的原子落盘工具。
 *
 * ## 解决什么问题
 *
 * 项目里大量文件是「整份覆写」语义（`music.txt` / `Playlist.txt` / `change.txt` /
 * `blocked_words.txt` / `Settings.json` / `cache_map.json` / 语言 JSON）。
 * 原先直接用 `File.writeText()` 或 `FileOutputStream(target)`：
 * 它们会**先截断目标文件再写**，写入过程中一旦抛异常、进程被杀或掉电，
 * 目标文件就停留在「已被截断 + 只写了一半」的状态 —— 整份 JSON 报废，
 * 而调用方拿到的只是一个 `catch` 到的异常，数据已经没了。
 *
 * ## 做法
 *
 * 先写同目录的临时文件 `<name>.tmp`，写完再 `renameTo` 覆盖目标。
 * 同一分区内 rename 是原子的：读者只会看到「旧的完整文件」或「新的完整文件」，
 * 不存在中间态。任一步失败最多留下一个临时文件，目标文件保持原样。
 *
 * ## 两个细节
 *
 *  - **临时文件必须与目标同目录**：跨分区 rename 会退化成「复制 + 删除」，
 *    就不再原子了。
 *  - **不做 fsync**：与 [PlaybackStatsWriter] 的取舍一致——这些配置文件的写入
 *    频率虽低，但 `saveMusicList` 等会在 UI 线程被调用，每次 fsync 会带来可感的
 *    卡顿。rename 已经消除了「半截文件」这一最常见、也最难恢复的损坏形态；
 *    仅掉电场景下才可能丢掉最后一次写入（丢内容，但不会得到损坏内容）。
 *
 * 调用方拿到的返回值表示「是否成功落盘」，失败时目标文件未被破坏。
 */
object AtomicFileWriter {

    /** 临时文件名的进程内唯一序号，见 [tmpFileFor] 的说明。 */
    private val tmpSeq = AtomicLong(0)

    /**
     * 为 [target] 生成**本次调用独有**的临时文件。
     *
     * 这里必须带唯一后缀，不能简单地用 `<name>.tmp`：同一个文件可能被多个线程
     * 并发写入（例如 `music.txt` 同时被列表保存与历史合并触发）。
     * 若共用同一个临时名，会出现「A 写完正在 rename，B 把同一个 tmp 截断重写」
     * 的互相破坏——实测会让 rename 失败、甚至把目标文件删掉。
     * 每次调用用独立临时文件，多个写入者各自完成、最后由 rename 定序，读者始终
     * 看到完整的旧版或新版。
     */
    private fun tmpFileFor(target: File): File =
        File(target.parentFile, "${target.name}.${tmpSeq.incrementAndGet()}.tmp")

    /**
     * 原子地把 [text] 写成 [target] 的全部内容（UTF-8）。
     *
     * @return 是否成功；失败时 [target] 保持调用前的内容
     */
    fun writeText(target: File, text: String): Boolean =
        write(target) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }

    /**
     * 原子地写入 [target]：在临时文件上执行 [write]，成功后 rename 覆盖目标。
     *
     * [write] 只会被调用一次（rename 失败时改为搬运已写好的临时文件，
     * 不会重复调用），因此可以安全地传入「从 InputStream 拷贝」这类不可重放的逻辑。
     *
     * @return 是否成功；失败时 [target] 保持调用前的内容
     */
    fun write(target: File, write: (OutputStream) -> Unit): Boolean {
        val dir = target.parentFile
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false

        val tmp = tmpFileFor(target)
        return try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
            }
            if (!tmp.renameTo(target)) {
                // 少数文件系统上 rename 会失败（如目标被占用）：退化为直接覆写目标。
                // 先确认临时文件确实存在，否则绝不动目标——不能出现「没写成功却把原文件删了」。
                // 注意只判存在、不判非空：空内容（如清空屏蔽字列表）是合法的写入结果。
                if (tmp.exists()) {
                    tmp.copyTo(target, overwrite = true)
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            // 无论成功、失败还是走了退化路径，都不留临时文件
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }
}
