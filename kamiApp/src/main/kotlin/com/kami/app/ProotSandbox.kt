package com.kami.app

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Alpine Linux sandbox on proot. The static proot binary and the Alpine
 * minirootfs for arm64/arm are bundled in assets/ — installing needs no
 * download. Both are pushed through Shizuku into /data/local/tmp/kami-proot
 * (shell uid may execute there) and commands then run inside the guest
 * rootfs — isolated from the host, with apk. Other architectures fall back
 * to downloading (or custom URLs).
 */
object ProotSandbox {

    private const val DIR = "/data/local/tmp/kami-proot"
    private const val PROOT_TAG = "v5.3.0" // last release shipping static per-arch builds
    private const val ALPINE_BRANCH = "v3.22"
    private const val CHUNK = 90_000 // base64 chunk < MAX_ARG_STRLEN (128K)

    /** Architectures whose proot + rootfs ship inside the APK. */
    private val BUNDLED = setOf("aarch64", "arm")

    private val PROOT_ARCH = mapOf(
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "arm",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )
    private val ALPINE_ARCH = mapOf(
        "aarch64" to "aarch64",
        "arm" to "armhf",
        "x86_64" to "x86_64",
        "x86" to "x86",
    )

    private lateinit var appContext: Context
    private lateinit var cacheDir: File

    fun init(context: Context) {
        appContext = context.applicationContext
        cacheDir = context.applicationContext.cacheDir
    }

    fun status(): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        val flags = ShizukuRunner.run(
            "test -x $DIR/proot && echo proot=ok || echo proot=missing; " +
                "{ [ -f $DIR/alpine/etc/alpine-release ] || [ -f $DIR/alpine/bin/busybox ] || " +
                "[ -f $DIR/alpine/usr/bin/busybox ]; } && echo rootfs=ok || echo rootfs=missing; " +
                "du -sh $DIR 2>/dev/null | cut -f1",
        )
        return if (installed()) {
            "沙箱已安装\n$flags"
        } else {
            "沙箱未安装（内置资源一键安装，无需下载）\n$flags"
        }
    }

    /**
     * Install: read the bundled assets (no download) when available, push
     * both files through Shizuku, extract and verify. Custom URLs override
     * the assets and let the agent work around blocked sources.
     */
    fun setup(prootUrl: String? = null, rootfsUrl: String? = null): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权，请先在主界面授权"
        if (!::cacheDir.isInitialized) return "错误：沙箱尚未初始化，请先打开主界面"
        val abi = ShizukuRunner.run("getprop ro.product.cpu.abi").trim()
        val prootArch = PROOT_ARCH[abi] ?: return "不支持的设备架构：$abi"
        val alpineArch = ALPINE_ARCH[prootArch] ?: return "Alpine 无对应架构：$prootArch"

        val bundled = prootUrl.isNullOrBlank() && rootfsUrl.isNullOrBlank() && prootArch in BUNDLED
        val log = StringBuilder()
        val prootFile: File
        val fsFile: File
        if (bundled) {
            // NOTE: aapt strips the .gz suffix from bundled assets and stores
            // them decompressed, so the asset ships as a plain rootfs.tar.
            prootFile = assetToFile("proot/$prootArch/proot", File(cacheDir, "proot-static"))
            fsFile = assetToFile("rootfs/$prootArch/rootfs.tar", File(cacheDir, "minirootfs.tar"))
            log.append("已从内置资源读取（免下载）\n")
        } else {
            val pUrl = prootUrl?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "https://github.com/proot-me/proot/releases/download/$PROOT_TAG/proot-$PROOT_TAG-$prootArch-static"
            val rUrl = rootfsUrl?.trim().takeUnless { it.isNullOrEmpty() }
                ?: latestMinirootfs(alpineArch)
            prootFile = download(pUrl, File(cacheDir, "proot-static"), log)
            fsFile = maybeGunzip(download(rUrl, File(cacheDir, "minirootfs.tar.gz"), log))
        }

        ShizukuRunner.run("rm -rf $DIR/alpine && mkdir -p $DIR/alpine $DIR/tmp")
        pushFile(prootFile, "$DIR/proot", log, executable = true)
        pushFile(fsFile, "$DIR/rootfs.tar", log, executable = false)

        // Judge extraction by real marker files, never by tar's exit code
        // (toybox tar can bail on entries it dislikes). Alpine's bin/sh is
        // an ABSOLUTE symlink (/bin/busybox), so `test` on it resolves
        // against the Android host and always fails — alpine-release and
        // busybox are plain files in either rootfs layout.
        val tarOut = ShizukuRunner.run("tar -xf $DIR/rootfs.tar -C $DIR/alpine 2>/dev/null; echo tar-done")
        val check = ShizukuRunner.run(
            "cd $DIR/alpine && { [ -f etc/alpine-release ] || [ -f bin/busybox ] || " +
                "[ -f usr/bin/busybox ]; } && echo rootfs-ok || echo rootfs-broken",
        )
        if (!check.contains("rootfs-ok")) {
            return "rootfs 解压失败：${tarOut.take(200)} / $check"
        }
        // Host has no /etc/resolv.conf for proot's -R to bind; write DNS into
        // the guest so apk/wget work inside.
        ShizukuRunner.run(
            "printf 'nameserver 223.5.5.5\\nnameserver 8.8.8.8\\n' > $DIR/alpine/etc/resolv.conf && " +
                "rm -f $DIR/rootfs.tar",
        )
        val smoke = run("uname -m && head -1 /etc/os-release")
        prootFile.delete()
        fsFile.delete()
        return "${log}沙箱就绪。冒烟测试：$smoke"
    }

    fun run(cmd: String): String {
        if (!ShizukuRunner.granted()) return "错误：Shizuku 未授权"
        if (!installed()) return "错误：沙箱未安装 — 到 设置→沙箱管理 安装"
        val esc = "export PATH=/bin:/sbin:/usr/bin:/usr/sbin; " +
            cmd.trim().replace("'", "'\\''")
        // -r, not -R: -R also binds $HOME, and Android shells have HOME=/
        // whose trailing `bind /` would override the rootfs binding entirely.
        // The guest needs an explicit PATH (Android's PATH leaks in uselessly).
        return ShizukuRunner.run(
            "PROOT_TMP_DIR=$DIR/tmp PROOT_NO_SECCOMP=1 timeout 600 " +
                "$DIR/proot -r $DIR/alpine -b /dev -b /proc -b /sys -w / /bin/sh -c '$esc'",
        ).ifEmpty { "(无输出)" }
    }

    private fun installed(): Boolean = ShizukuRunner.granted() &&
        ShizukuRunner.run(
            "{ [ -f $DIR/alpine/etc/alpine-release ] || [ -f $DIR/alpine/bin/busybox ] || " +
                "[ -f $DIR/alpine/usr/bin/busybox ]; } && echo yes || echo no",
        ).contains("yes")

    /** Copy a bundled asset to a cache file (app can't hand assets to shell directly). */
    private fun assetToFile(relPath: String, dest: File): File {
        appContext.assets.open(relPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    /** The extract path expects a plain tar; gunzip a downloaded rootfs if needed. */
    private fun maybeGunzip(src: File): File {
        val head = ByteArray(2)
        src.inputStream().use { ins ->
            var read = 0
            while (read < 2) {
                val n = ins.read(head, read, 2 - read)
                if (n < 0) break
                read += n
            }
        }
        if (!(head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte())) return src
        val out = File(src.parentFile, "minirootfs.tar")
        GZIPInputStream(src.inputStream()).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        src.delete()
        return out
    }

    /**
     * App-private files are not shell-readable; transfer via chunked base64
     * piped through Shizuku shell commands (same channel as the OTA path).
     */
    private fun pushFile(local: File, remote: String, log: StringBuilder, executable: Boolean) {
        val bytes = local.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        ShizukuRunner.run("rm -f '$remote' '$remote.b64'")
        b64.chunked(CHUNK).forEach { ShizukuRunner.run("printf %s $it >> '$remote.b64'") }
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val result = ShizukuRunner.run(
            "base64 -d '$remote.b64' > '$remote' && md5sum '$remote' && rm -f '$remote.b64'",
        )
        if (!result.contains(md5)) {
            throw IOException("推送校验失败 $remote：期望 md5 $md5，实得 ${result.take(80)}")
        }
        if (executable) ShizukuRunner.run("chmod 755 '$remote'")
        log.append("已推送 ${bytes.size}B → $remote\n")
    }

    private fun download(url: String, dest: File, log: StringBuilder): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 180_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}：$url")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        log.append("已下载 ${dest.length()}B ← $url\n")
        return dest
    }

    /** Ask the Alpine CDN which minirootfs is current for this branch. */
    private fun latestMinirootfs(arch: String): String {
        val base = "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/$arch"
        val file = runCatching {
            val yaml = httpGetText("$base/latest-releases.yaml")
            Regex("file:\\s*(alpine-minirootfs-\\S+\\.tar\\.gz)").find(yaml)?.groupValues?.get(1)
        }.getOrNull()
        return file?.let { "$base/$it" } ?: "$base/alpine-minirootfs-3.22.5-$arch.tar.gz"
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}：$url")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
