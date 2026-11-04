package ru.klaviatura

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.klaviatura.voice.ModelDownloader
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** real network test of the exact code path the keyboard uses (skipped when offline) */
class ModelDownloaderTest {
    private fun online() = try { InetAddress.getByName("huggingface.co"); System.getenv("KL_NO_NET") == null } catch (e: Exception) { false }

    @Test fun allMirrorsRespond() {
        assumeTrue(online())
        for ((lang, urls) in ModelDownloader.MIRRORS) for (u in urls) {
            val c = ModelDownloader.open(u)
            val len = c.contentLengthLong
            c.disconnect()
            println("$lang $u -> $len")
            assertTrue("$u size $len", len > 30_000_000)
        }
    }

    @Test fun downloadAndInstallRu_fallsBackFromBrokenMirror() {
        assumeTrue(online())
        val dir = Files.createTempDirectory("kl").toFile()
        val dest = File(dir, "ru")
        var progressCalls = 0; var unzip = false; val failed = ArrayList<String>()
        val urls = listOf("https://alphacephei.com/vosk/models/does-not-exist.zip") + ModelDownloader.MIRRORS["ru"]!!
        ModelDownloader.download("ru", dest, object : ModelDownloader.Listener {
            override fun onProgress(done: Long, total: Long, mirror: Int) { progressCalls++ }
            override fun onUnzip() { unzip = true }
            override fun onMirrorFailed(url: String, error: String) { failed.add(error) }
        }, urls)
        assertTrue(failed.size == 1)
        assertTrue(progressCalls > 10 && unzip)
        assertTrue(ModelDownloader.isModelDir(dest))
        dir.deleteRecursively()
    }
}
