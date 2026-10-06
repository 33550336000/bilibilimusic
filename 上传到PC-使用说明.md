# 从 APK 上传文件到 PC —— 使用说明

> 本文面向在本项目中写代码的人：如何让 APK 把手机上的文件传到电脑本地，
> 以及如何从电脑把文件下发给 APK。
>
> **读完这一节就能动手**：先看「快速上手」，再看「Android 端代码」。

---

## 1. 这套东西是什么

一句话：**手机通过一条固定的公网 HTTPS 地址，把文件直接写进电脑的磁盘目录。**

```
   APK（手机）                                  你的 PC
       │                                          │
       │  HTTPS PUT /upload/xxx.mp3               │
       ▼                                          │
  upload.tibao.dpdns.org  ──Cloudflare 隧道──▶  cloudflared 进程
                                                      │
                                                      ▼
                                              127.0.0.1:8090
                                              （本地文件服务）
                                                      │
                                                      ▼
                                            ~/uploads/  ← 文件在这里
```

几个要点：

- **不是云盘中转**。文件不在 Cloudflare 上停留，是穿过隧道**直接落到电脑磁盘**。
- **地址永久固定**（命名隧道），电脑重启、服务重启都不会变。
- **不需要数据库、不需要账号系统**，只有一把「上传令牌」当钥匙。

---

## 2. 电脑端：一条命令启动

在电脑的终端里执行：

```sh
clo
```

**`Ctrl+C` 停止。** 关终端、按 Ctrl+C，服务和隧道一起结束，公网立刻不可达。

启动成功会看到：

```
▸ 启动文件服务（127.0.0.1:8090）…
✓ 文件服务已就绪

  固定地址 : https://upload.tibao.dpdns.org
  令牌     : e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e
  接收目录 : /home/tibao/uploads
  下发目录 : /home/tibao/share
  服务日志 : /home/tibao/.local/state/upload-service/service.log
  停止     : Ctrl+C

▸ 启动命名隧道（filehub）…
```

之后**每次手机传完文件，终端会实时打一行**：

```
[filehub] 收到 我的视频.mp4 (15728640 字节) -> /home/tibao/uploads/我的视频.mp4
```

> ⚠️ **`clo` 没运行时，公网地址是不通的**（返回隧道错误）。这是刻意设计——
> 等于一个人工开关，你不想让人传的时候关掉就行。

---

## 3. 核心参数（写代码要用的）

| 项目 | 值 |
|---|---|
| 基础地址 | `https://upload.tibao.dpdns.org` |
| 上传令牌 | `e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e` |
| 上传目录 | `~/uploads/`（电脑上） |
| 下发目录 | `~/share/`（电脑上） |

**令牌怎么带**（三选一，服务端都认）：

```
X-Upload-Token: e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e   ← 首选
Authorization: Bearer e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e
?token=e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e            ← 给播放器用
```

> 🔐 **令牌就是钥匙**。拿到它的人可以往你电脑写文件。别提交进 git、别贴到公开地方。
> 想换令牌：改 `~/.config/upload-service/token` 的内容，重启 `clo` 即可。

---

## 4. 接口一览

### 4.1 上传（手机 → 电脑）

```http
PUT /upload/<文件名>
X-Upload-Token: <令牌>
Content-Type: application/octet-stream

<文件原始字节>
```

成功响应：

```json
{
  "ok": true,
  "name": "我的视频.mp4",
  "path": "/home/tibao/uploads/我的视频.mp4",
  "bytes": 15728640
}
```

还有两个等价写法（一般用不上）：

- `POST /upload?name=<文件名>` —— 不方便发 PUT 的客户端用
- `?token=<令牌>` —— 代替请求头

**行为约定（都已实测）：**

| 情况 | 行为 |
|---|---|
| 文件名重名 | **不覆盖**，自动变成 `名字-2.mp4`、`名字-3.mp4` |
| 文件名含中文 | **必须 URL 编码**（见下方「最大的坑」） |
| 文件名含 `../` | 被消毒成纯文件名，**不可能写出 `uploads/` 外** |
| 无令牌 / 令牌错 | `401` |
| 超大 | 见「100 MB 限制」 |

### 4.2 下发（电脑 → 手机）

先把文件放到电脑的 `~/share/` 目录，然后：

```http
GET /f/<文件名>?token=<令牌>
```

- **支持 HTTP Range**（`206 Partial Content`）——播放器拖进度条、断点续传都靠它
- 加 `&dl=1` 会带 `Content-Disposition: attachment`（触发浏览器下载）
- 响应 `Content-Type` 按扩展名自动识别（mp3/mp4/jpg/apk… 都覆盖了）

### 4.3 回读已上传的文件

`/f/` **只读 `~/share/`**，读不到你刚上传的东西。要回读上传目录（`~/uploads/`），
用另一个前缀：

```http
GET /u/<文件名>?token=<令牌>
```

同样支持 Range 和 `dl=1`。

> 两个前缀是刻意分开的：`/f/` → `~/share/`（你主动放出去给别人拿的），
> `/u/` → `~/uploads/`（别人传给你的）。混用会导致「已接收」列表里的
> 链接点开是 404 —— 因为 `/f/` 在 `~/share/` 里找不到那个文件。

**列目录：**

```http
GET /list
X-Upload-Token: <令牌>
```

```json
{
  "share":   { "dir": "/home/tibao/share",   "files": [ { "name": "a.mp3", "bytes": 123, "mtime": "..." } ] },
  "uploads": { "dir": "/home/tibao/uploads", "files": [ ... ] }
}
```

**健康检查（无需令牌）：** `GET /health`

---

## 5. Android 端代码

### ⚠️ 最大的坑：中文文件名必须 URL 编码

这是最容易踩的一个。**直接把中文拼进 URL 会失败**（我实测过）：

```kotlin
// ❌ 错误：中文没编码，请求会失败
val url = URL("$BASE/upload/$fileName")

// ✅ 正确：先编码，再拼
val encoded = URLEncoder.encode(fileName, "UTF-8")
              .replace("+", "%20")      // URLEncoder 把空格编成 +，但路径里必须是 %20
val url = URL("$BASE/upload/$encoded")
```

> `URLEncoder` 是给 **query 参数**设计的，它把空格变成 `+`。用在 **URL 路径**里
> 必须把 `+` 换回 `%20`，否则文件名里的空格会出错。这一行很多人会漏。

### 5.1 上传：小文件（简单版）

适合图片、短音频这类几 MB 以内的：

```kotlin
package com.tilixibiesi.network

import com.tilixibiesi.util.AppExecutors
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 把字节数组上传到电脑。复用项目里已有的 AppExecutors.io 线程池。
 */
object PcUploader {

    private const val BASE = "https://upload.tibao.dpdns.org"
    private const val TOKEN = "e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e"

    fun uploadBytesAsync(
        fileName: String,
        bytes: ByteArray,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        AppExecutors.io.execute {
            var conn: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                conn = (URL("$BASE/upload/$encoded").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("X-Upload-Token", TOKEN)
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setFixedLengthStreamingMode(bytes.size)   // 关键：流式，不缓冲整包
                }
                conn.outputStream.use { it.write(bytes) }

                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.let { BufferedReader(InputStreamReader(it, "UTF-8")).readText() } ?: ""
                onDone(code in 200..299, if (code in 200..299) "上传成功" else "HTTP $code: $body")
            } catch (e: Exception) {
                onDone(false, "上传失败: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
    }
}
```

### 5.2 上传：大文件（流式，重要）

**大文件不要读成 `ByteArray`**——视频几百 MB 会直接 OOM。要边读边写：

```kotlin
/**
 * 从 Uri 流式上传，全程不在内存里堆积整个文件。
 *
 * @param contentResolver 用来打开 content:// 流（相册、下载目录的文件都走这个）
 */
fun uploadUriAsync(
    contentResolver: android.content.ContentResolver,
    fileName: String,
    uri: android.net.Uri,
    onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    onDone: (ok: Boolean, message: String) -> Unit
) {
    AppExecutors.io.execute {
        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            val total = contentResolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L

            conn = (URL("$BASE/upload/$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 15_000
                // 大文件给足读超时：1 小时。太小会在慢网络下误判失败
                readTimeout = 3_600_000
                setRequestProperty("X-Upload-Token", TOKEN)
                setRequestProperty("Content-Type", "application/octet-stream")
                // 已知长度 → 固定长度流式；未知 → 分块传输（服务端两种都支持）
                if (total > 0) setFixedLengthStreamingMode(total)
                else setChunkedStreamingMode(64 * 1024)
            }

            contentResolver.openInputStream(uri)?.use { input ->
                conn.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress?.invoke(sent, total)
                    }
                    out.flush()
                }
            }

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.let { BufferedReader(InputStreamReader(it, "UTF-8")).readText() } ?: ""
            onDone(code in 200..299, if (code in 200..299) "上传成功" else "HTTP $code: $body")
        } catch (e: Exception) {
            onDone(false, "上传失败: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
```

### 5.3 下载（电脑 → 手机）

```kotlin
fun downloadAsync(
    fileName: String,
    destFile: java.io.File,
    onDone: (ok: Boolean, message: String) -> Unit
) {
    AppExecutors.io.execute {
        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            conn = (URL("$BASE/f/$encoded?token=$TOKEN").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 3_600_000
            }
            if (conn.responseCode !in 200..299) {
                onDone(false, "HTTP ${conn.responseCode}"); return@execute
            }
            conn.inputStream.use { input ->
                destFile.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
            onDone(true, "已保存到 ${destFile.absolutePath}")
        } catch (e: Exception) {
            onDone(false, "下载失败: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
```

### 5.4 用播放器直接播电脑上的文件

因为下发接口**支持 Range**，可以直接把 URL 交给 `MediaPlayer`：

```kotlin
val url = "$BASE/f/${URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}?token=$TOKEN"
mediaPlayer.setDataSource(url)   // 能拖动进度条、能 seek
mediaPlayer.prepareAsync()
```

### 5.5 权限与线程

- **`INTERNET` 权限已经有了**（`AndroidManifest.xml` 里已声明），不用加。
- **所有网络请求必须离开主线程**——上面都套了 `AppExecutors.io`，和项目现有风格一致。
- **`targetSdk 30` 不需要担心**明文流量问题：我们用的是 **HTTPS**，不受 `usesCleartextTraffic` 影响。

---

## 6. 100 MB 限制（重要）

Cloudflare 免费版**单次请求上限 100 MB**。这是 Cloudflare 边缘的限制，
**在我们代码之前就拦掉了**，服务端根本收不到。

实测结果：

| 文件大小 | 结果 |
|---|---|
| 95 MB | ✅ 成功（约 36 秒，2.7 MB/s） |
| 101 MB | ❌ `413 Payload Too Large`（Cloudflare 返回的 HTML，不是我们的 JSON） |

**应对方式：**

- 传音频/文档，通常没问题
- 传大视频（>100 MB），需要**分片上传**（把文件切块，多次 PUT），服务端目前**不支持**
  分片——要真需要，得先扩展 `server.mjs`。

> 判断是否撞到这个限制：看响应体。**Cloudflare 的 413 是 HTML**，
> 而我们自己的错误一律是 **JSON**。这是个很好用的区分信号。

---

## 7. 排错

| 现象 | 原因 / 处理 |
|---|---|
| `401 unauthorized` | 令牌不对。核对第 3 节的令牌，注意别带多余空格 |
| `404 not-found` | 路径写错了。上传必须是 `PUT /upload/<名>` |
| Cloudflare **HTML** 的 413 | 文件超 100 MB，见第 6 节 |
| 连接超时 / 打不开 | 电脑上 `clo` 没在跑，去电脑终端执行 `clo` |
| 中文名文件上传失败 | 忘了 URL 编码，见 5.1 前的「最大的坑」 |
| 上传成功但电脑上找不到 | 看错目录了：接收目录是 `~/uploads/` |
| 网页「已接收」点开是 404 | 用错前缀了：`/u/` 读 `~/uploads/`，`/f/` 只读 `~/share/`，见 4.3 |
| 跑 `clo` 后**终端一片空白**、浏览器却能用 | 上一次的 `clo` 没退干净，8090 被占。现在会明确报「端口已被占用」；照着 `ss -ltnp \| grep 8090` 找到旧进程杀掉即可。**浏览器能用正是因为那个旧实例还在服务** |
| 大文件传一半断（QUIC 报错） | 隧道协议问题。`~/.cloudflared/filehub.yml` 里已固定 `protocol: http2`，别删 |

---

## 8. 文件位置速查（电脑上）

| 用途 | 路径 |
|---|---|
| 启动命令 | `~/.local/bin/clo` |
| 服务端源码 | `~/upload-service/server.mjs` |
| 令牌 | `~/.config/upload-service/token` |
| 隧道配置 | `~/.cloudflared/filehub.yml` |
| 隧道凭据 | `~/.cloudflared/008c55f8-faca-41ac-bdfd-2de05463df3e.json` |
| 接收目录 | `~/uploads/` |
| 下发目录 | `~/share/` |
| 运行日志 | `~/.local/state/upload-service/service.log` |

> ⚠️ **不要把隧道配置放回 `~/.cloudflared/config.yml`。** 那是 cloudflared 的
> **默认**配置路径，任何不带 `--config` 启动的 cloudflared 都会自动读到它。
> 这台机器上还跑着 `dsh-pocket`（DSH 的手机访问隧道），它启动时恰好不带 `--config`，
> 于是会连带读到我们的 `ingress` 规则 —— 因为我们只映射了
> `upload.tibao.dpdns.org`，它的域名就会全部落进 `http_status:404` 兜底，
> **把 DSH 的手机访问打成 404**（血泪教训，已实测复现并修复）。
> 所以配置刻意命名为 `filehub.yml`，并在 `clo` 里显式用 `--config` 指定。

---

## 9. 快速验证（不用写代码）

在**电脑**上开个终端，用 curl 模拟 APK 的行为：

```sh
# 上传
curl -X PUT \
  -H "X-Upload-Token: e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e" \
  --data-binary "@/path/to/某个文件.mp3" \
  "https://upload.tibao.dpdns.org/upload/%E6%9F%90%E4%B8%AA%E6%96%87%E4%BB%B6.mp3"

# 确认落盘
ls -lh ~/uploads/

# 下发（先往 ~/share/ 放个文件）
curl "https://upload.tibao.dpdns.org/f/readme.txt?token=e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e"

# 回读刚上传的文件（注意前缀是 /u/ 不是 /f/）
curl "https://upload.tibao.dpdns.org/u/%E6%9F%90%E4%B8%AA%E6%96%87%E4%BB%B6.mp3?token=e2252f73c1e4051e2efae067adbdf8ee76dfb5b7da478f2e"
```

> 也可以在手机浏览器直接打开 `https://upload.tibao.dpdns.org/?token=<令牌>`，
> 有个拖拽上传/下载的网页界面，适合手动传。
>
> 网页里两个区块的链接分别对应两个前缀：**📥 下发** 走 `/f/`（读 `~/share/`），
> **📤 已接收** 走 `/u/`（读 `~/uploads/`）。每项右侧还有个 `↓` 直接下载。
