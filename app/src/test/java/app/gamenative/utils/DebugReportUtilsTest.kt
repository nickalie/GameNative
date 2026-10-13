package app.gamenative.utils

import android.app.Application
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DebugReportUtilsTest {

    private var prefScope: PrefManagerTestScope? = null
    private var tempDir: File? = null

    @After
    fun tearDown() {
        prefScope?.close()
        tempDir?.deleteRecursively()
    }

    @Test
    fun envSecretsNeverAppearInExportedReport() {
        val secret = "s3cr3tValue42"
        prefScope = installFakePrefManager(FakeDataStore())
        val dir = Files.createTempDirectory("debug-report").toFile().also { tempDir = it }
        val containerDir = File(dir, "container").apply { mkdirs() }
        val container = Container("STEAM_123456").apply {
            setRootDir(containerDir)
            setEnvVars("MY_API_TOKEN=$secret DXVK_ASYNC_BYPASS=1 AUTH_SECRET=$secret MONKEY=banana")
            setExecArgs("-AUTH_PASSWORD=$secret --access-token=$secret -dx11")
            saveData()
        }
        assertTrue("secret was written", container.containerJson.contains(secret))

        val configs = DebugReportUtils.redactedConfigs(container.containerJson)
        val reportDir = File(dir, "report").apply { mkdirs() }
        DebugReportUtils.headerFile(reportDir).writeText(JSONObject().put("configs", configs).toString())
        val zipped = ByteArrayOutputStream().also { DebugReportUtils.writeZip(reportDir, it) }

        val exported = unzip(zipped.toByteArray())
        assertEquals(setOf("header.json"), exported.keys)
        exported.values.forEach { assertFalse(it.contains(secret)) }

        val env = EnvVars(configs.getString("envVars"))
        assertEquals(SecretEnv.MASK, env.get("MY_API_TOKEN"))
        assertEquals(SecretEnv.MASK, env.get("AUTH_SECRET"))
        assertEquals("1", env.get("DXVK_ASYNC_BYPASS"))
        assertEquals("banana", env.get("MONKEY"))
        assertTrue(configs.getString("execArgs").endsWith(" -dx11"))
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        return entries
    }
}
