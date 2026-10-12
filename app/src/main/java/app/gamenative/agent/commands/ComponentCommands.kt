package app.gamenative.agent.commands

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.agent.AgentDispatcher
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestData
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestRepository
import com.winlator.core.StringUtils
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Everything [ComponentCommands] needs to list component versions. Base lists are the bundled
 * resource entries (already reduced the way the config dialog reduces them on modern builds).
 */
data class ComponentSources(
    val wineGlibc: List<String>,
    val wineBionic: List<String>,
    val dxvk: List<String>,
    val vkd3d: List<String>,
    val box64Glibc: List<String>,
    val box64Bionic: List<String>,
    val wowBox64: List<String>,
    val fexcore: List<String>,
    val drivers: List<String>,
    val installed: ManifestComponentHelper.InstalledContentLists,
    val installedDrivers: List<String>,
    val manifest: ManifestData,
)

data class ComponentEntry(
    val target: String,
    val value: String,
    val label: String,
    val installed: Boolean,
    val downloadable: Boolean,
    val variant: String?,
)

/**
 * `components.list {target?}`: component versions a container config may reference, from the same
 * sources `BestConfigService.validateComponentVersions` checks (bundled entries, installed content,
 * the component manifest). The manifest is read from the offline cache only: a binder call never
 * touches the network.
 */
class ComponentCommands(private val sources: () -> ComponentSources) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(LIST) { list(it) }
    }

    fun list(args: JsonObject): JsonElement {
        val target = CommandArgs.optString(args, "target")
        if (target != null && target !in TARGETS) {
            throw CommandArgs.badArgs(
                "unknown target: ${target.take(64)}",
                JsonObject(mapOf("targets" to JsonArray(TARGETS.map { JsonPrimitive(it) }))),
            )
        }
        val entries = catalog(sources()).filter { target == null || it.target == target }
        return buildJsonArray {
            entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("target", entry.target)
                        put("value", entry.value)
                        put("label", entry.label)
                        put("installed", entry.installed)
                        put("downloadable", entry.downloadable)
                        entry.variant?.let { put("variant", it) }
                    },
                )
            }
        }
    }

    /** One target's entries, de-duplicated on (value, variant); local entries win over manifest ones. */
    private class TargetEntries(private val target: String) {
        private val entries = LinkedHashMap<Pair<String, String?>, ComponentEntry>()

        fun local(labels: List<String>, variant: String?, value: (String) -> String = { it }) {
            labels.filter { it.isNotBlank() }.forEach { label ->
                val id = value(label)
                entries.putIfAbsent(key(id, variant), ComponentEntry(target, id, label, true, false, variant))
            }
        }

        /** Manifest entries already present locally for a compatible variant are listed once, as installed. */
        fun manifest(items: List<ManifestEntry>, variant: (ManifestEntry) -> String?) {
            items.forEach { item ->
                val itemVariant = variant(item)
                val key = key(item.id, itemVariant)
                if (key in entries) return@forEach
                val local = entries.values
                    .filter { it.installed && (it.variant == null || it.variant == itemVariant) }
                    .flatMap { listOf(it.label, it.value) }
                if (ManifestComponentHelper.versionExists(item.id, local)) return@forEach
                entries[key] = ComponentEntry(target, item.id, item.name.ifBlank { item.id }, false, true, itemVariant)
            }
        }

        fun toList(): List<ComponentEntry> = entries.values.toList()

        private fun key(value: String, variant: String?) = value.lowercase(Locale.ROOT) to variant
    }

    companion object {
        const val LIST = "components.list"

        const val TARGET_WINE = "wineVersion"
        const val TARGET_DXVK = "dxwrapperConfig.version"
        const val TARGET_VKD3D = "dxwrapperConfig.vkd3dVersion"
        const val TARGET_DRIVER = "graphicsDriverConfig.version"
        const val TARGET_BOX64 = "box64Version"
        const val TARGET_FEXCORE = "fexcoreVersion"

        val TARGETS = listOf(TARGET_WINE, TARGET_DXVK, TARGET_VKD3D, TARGET_DRIVER, TARGET_BOX64, TARGET_FEXCORE)

        const val VARIANT_GLIBC = "glibc"
        const val VARIANT_BIONIC = "bionic"

        /** WoWBox64 versions: written to `box64Version` when `wineVersion` is an arm64ec build. */
        const val VARIANT_ARM64EC = "arm64ec"

        // Resource entries carry display suffixes such as "0.3.6 (Default)"; containers store the identifier.
        private val identifier: (String) -> String = { StringUtils.parseIdentifier(it) }

        private fun manifestVariant(entry: ManifestEntry): String? = entry.variant?.lowercase(Locale.ROOT)?.ifBlank { null }

        fun catalog(sources: ComponentSources): List<ComponentEntry> {
            val items = sources.manifest.items
            val installed = sources.installed

            val wine = TargetEntries(TARGET_WINE).apply {
                local(sources.wineGlibc, VARIANT_GLIBC, identifier)
                local(sources.wineBionic, VARIANT_BIONIC, identifier)
                // The config dialog offers installed Wine/Proton only for bionic containers.
                local(installed.wine + installed.proton, VARIANT_BIONIC)
                manifest(items[ManifestContentTypes.WINE].orEmpty() + items[ManifestContentTypes.PROTON].orEmpty(), ::manifestVariant)
            }
            val dxvk = TargetEntries(TARGET_DXVK).apply {
                local(sources.dxvk, null, identifier)
                local(installed.dxvk, null)
                manifest(items[ManifestContentTypes.DXVK].orEmpty(), ::manifestVariant)
            }
            val vkd3d = TargetEntries(TARGET_VKD3D).apply {
                local(sources.vkd3d, null, identifier)
                local(installed.vkd3d, null)
                manifest(items[ManifestContentTypes.VKD3D].orEmpty(), ::manifestVariant)
            }
            val drivers = TargetEntries(TARGET_DRIVER).apply {
                // Driver ids are stored verbatim ("System", "turnip25.1.0").
                local(sources.drivers, null)
                local(sources.installedDrivers, null)
                manifest(items[ManifestContentTypes.DRIVER].orEmpty(), ::manifestVariant)
            }
            val box64 = TargetEntries(TARGET_BOX64).apply {
                local(sources.box64Glibc, VARIANT_GLIBC, identifier)
                local(sources.box64Bionic, VARIANT_BIONIC, identifier)
                local(installed.box64, null)
                manifest(items[ManifestContentTypes.BOX64].orEmpty(), ::manifestVariant)
                local(sources.wowBox64, VARIANT_ARM64EC, identifier)
                local(installed.wowBox64, VARIANT_ARM64EC)
                manifest(items[ManifestContentTypes.WOWBOX64].orEmpty()) { VARIANT_ARM64EC }
            }
            val fexcore = TargetEntries(TARGET_FEXCORE).apply {
                local(sources.fexcore, null, identifier)
                local(installed.fexcore, null)
                manifest(items[ManifestContentTypes.FEXCORE].orEmpty(), ::manifestVariant)
            }
            return listOf(wine, dxvk, vkd3d, drivers, box64, fexcore).flatMap { it.toList() }
        }

        /**
         * Reads the component sources from resources, installed content and the cached manifest.
         * [manifestJson] is the offline cache; `ManifestRepository.loadManifest` is never called because
         * it fetches over the network when the cache is stale.
         */
        fun loadSources(context: Context, manifestJson: () -> String = { PrefManager.componentManifestJson }): ComponentSources {
            val res = context.resources
            fun entries(id: Int): List<String> = res.getStringArray(id).toList()
            val installed = runBlocking { ManifestComponentHelper.loadInstalledContentLists(context) }
            return ComponentSources(
                wineGlibc = entries(R.array.glibc_wine_entries),
                wineBionic = entries(R.array.bionic_wine_entries),
                dxvk = ManifestComponentHelper.bundledDxWrapperBase(entries(R.array.dxvk_version_entries)),
                vkd3d = ManifestComponentHelper.bundledDxWrapperBase(entries(R.array.vkd3d_version_entries)),
                box64Glibc = entries(R.array.box64_version_entries),
                box64Bionic = entries(R.array.box64_bionic_version_entries),
                wowBox64 = entries(R.array.wowbox64_version_entries),
                fexcore = entries(R.array.fexcore_version_entries),
                drivers = ManifestComponentHelper.bundledGraphicsDriverBase(entries(R.array.wrapper_graphics_driver_version_entries)),
                installed = installed.installed,
                installedDrivers = installed.installedDrivers,
                manifest = ManifestRepository.parseManifest(manifestJson()) ?: ManifestData.empty(),
            )
        }
    }
}
