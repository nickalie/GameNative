package app.gamenative.agent.commands

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.agent.AgentDispatcher
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestData
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestInstallResult
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.ManifestRepository
import com.winlator.contents.ContentProfile
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

/** What `components.install` needs from the app; replaced in tests. */
class ComponentInstallDeps(
    val queue: ComponentInstallQueue,
    /** The manifest, fetched over the network when the cache is empty or a day old. */
    val freshManifest: () -> ManifestData,
    /** Downloads and installs one manifest entry the way the config dialog does. */
    val run: suspend (InstallPlan, onProgress: (Float) -> Unit) -> ManifestInstallResult,
) {
    companion object {
        fun forApp(context: Context, queue: ComponentInstallQueue) = ComponentInstallDeps(
            queue = queue,
            freshManifest = { runBlocking { ManifestRepository.loadManifest(context) } },
            run = { plan, onProgress ->
                ManifestInstaller.installManifestEntry(context, plan.entry, plan.isDriver, plan.contentType, onProgress)
            },
        )
    }
}

/**
 * `components.list {target?}`: component versions a container config may reference, from the same
 * sources `BestConfigService.validateComponentVersions` checks (bundled entries, installed content,
 * the component manifest). The manifest is read from the offline cache only: listing never touches the
 * network.
 *
 * `components.install {target, value, variant?}`: downloads a `downloadable` version in the background and
 * answers with the job's state; asking again for the same component reports its progress. A version missing
 * from the cached manifest makes it fetch the manifest on the calling (binder) thread when the cache is stale.
 */
class ComponentCommands(
    private val installs: ComponentInstallDeps? = null,
    private val sources: () -> ComponentSources,
) {

    fun register(dispatcher: AgentDispatcher) {
        dispatcher.register(LIST) { list(it) }
        if (installs != null) dispatcher.register(INSTALL) { install(it) }
    }

    fun install(args: JsonObject): JsonElement {
        val deps = installs ?: throw IllegalStateException("components.install is not wired")
        val target = CommandArgs.optString(args, "target")
        if (target.isNullOrEmpty()) throw CommandArgs.badArgs("target is required")
        if (target !in TARGETS) {
            throw CommandArgs.badArgs(
                "unknown target: ${target.take(64)}",
                JsonObject(mapOf("targets" to JsonArray(TARGETS.map { JsonPrimitive(it) }))),
            )
        }
        val value = CommandArgs.optString(args, "value")
        if (value.isNullOrBlank()) throw CommandArgs.badArgs("value is required")
        val variant = CommandArgs.optString(args, "variant")

        val current = sources()
        // Resolved first: a shared Box64/WoWBox64 id without a variant is refused before anything else, and the
        // installed check below compares within the variant that would be installed.
        val cachedPlan = planFor(target, value, variant, current.manifest)
        val wanted = variant ?: cachedPlan?.variant

        deps.queue.find(target, value, wanted)?.let { job ->
            // An installed job answers too: the installed name may not match the manifest id the catalog compares.
            if (job.isActive || job.state == ComponentInstallQueue.State.INSTALLED) return jobJson(job)
            // A failure is answered once, so a poll sees it and why; the call after that retries.
            if (job.takeFailure()) return jobJson(job)
        }
        val installedHere = catalog(current)
            .filter { it.target == target && it.installed && sameVariant(it.variant, wanted) }
            .flatMap { listOf(it.label, it.value) }
        if (ManifestComponentHelper.versionExists(value, installedHere)) {
            return buildJsonObject {
                put("target", target)
                put("value", value)
                wanted?.let { put("variant", it) }
                put("state", "installed")
                put("progress", 1.0)
            }
        }
        val plan = cachedPlan
            ?: planFor(target, value, variant, deps.freshManifest())
            ?: throw CommandArgs.badArgs("$value is neither installed nor in the component manifest for $target; see ${LIST}")
        return jobJson(deps.queue.enqueue(plan, deps.run))
    }

    /**
     * Whether an installed entry of [entryVariant] provides [wanted]. Installed content without a variant
     * serves every variant except `arm64ec`: WoWBox64 is a separate install that shares Box64's version ids.
     */
    private fun sameVariant(entryVariant: String?, wanted: String?): Boolean = when {
        wanted == null || entryVariant == wanted -> true
        entryVariant == null -> wanted != VARIANT_ARM64EC
        else -> false
    }

    private fun jobJson(job: ComponentInstallQueue.Job) = buildJsonObject {
        put("target", job.plan.target)
        put("value", job.plan.entry.id)
        job.plan.variant?.let { put("variant", it) }
        put("state", job.state.name.lowercase(Locale.ROOT))
        put("progress", job.progress.toDouble())
        job.message?.let { put("message", it) }
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
        const val INSTALL = "components.install"

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

        /** The manifest types a target's downloadable versions come from, with how each one installs. */
        private class ManifestSource(
            val type: String,
            val contentType: ContentProfile.ContentType?,
            val variant: (ManifestEntry) -> String?,
        ) {
            val isDriver: Boolean get() = contentType == null
        }

        private fun manifestSources(target: String): List<ManifestSource> = when (target) {
            TARGET_WINE -> listOf(
                ManifestSource(ManifestContentTypes.WINE, ContentProfile.ContentType.CONTENT_TYPE_WINE, ::manifestVariant),
                ManifestSource(ManifestContentTypes.PROTON, ContentProfile.ContentType.CONTENT_TYPE_PROTON, ::manifestVariant),
            )
            TARGET_DXVK -> listOf(ManifestSource(ManifestContentTypes.DXVK, ContentProfile.ContentType.CONTENT_TYPE_DXVK, ::manifestVariant))
            TARGET_VKD3D -> listOf(ManifestSource(ManifestContentTypes.VKD3D, ContentProfile.ContentType.CONTENT_TYPE_VKD3D, ::manifestVariant))
            TARGET_DRIVER -> listOf(ManifestSource(ManifestContentTypes.DRIVER, null, ::manifestVariant))
            TARGET_BOX64 -> listOf(
                ManifestSource(ManifestContentTypes.BOX64, ContentProfile.ContentType.CONTENT_TYPE_BOX64, ::manifestVariant),
                ManifestSource(ManifestContentTypes.WOWBOX64, ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64) { VARIANT_ARM64EC },
            )
            TARGET_FEXCORE -> listOf(ManifestSource(ManifestContentTypes.FEXCORE, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE, ::manifestVariant))
            else -> emptyList()
        }

        /**
         * The manifest entry [value] names under [target] (ids compare case-insensitively), or null when the
         * manifest has none. Several entries with that id (Box64 and WoWBox64 share version ids) need
         * [variant] to tell them apart.
         */
        internal fun planFor(target: String, value: String, variant: String?, manifest: ManifestData): InstallPlan? {
            val candidates = manifestSources(target).flatMap { source ->
                manifest.items[source.type].orEmpty()
                    .filter { it.id.equals(value.trim(), ignoreCase = true) }
                    .map { InstallPlan(target, source.variant(it), it, source.isDriver, source.contentType) }
            }.filter { variant == null || it.variant == variant }
            if (candidates.size > 1) {
                throw CommandArgs.badArgs(
                    "$value exists in several variants; pass variant",
                    JsonObject(mapOf("variants" to JsonArray(candidates.map { JsonPrimitive(it.variant) }))),
                )
            }
            return candidates.singleOrNull()
        }

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
         * The manifest comes from the offline cache in `PrefManager.componentManifestJson`;
         * `ManifestRepository.loadManifest` is never called because it fetches over the network when the
         * cache is stale.
         */
        fun loadSources(context: Context): ComponentSources {
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
                manifest = ManifestRepository.parseManifest(PrefManager.componentManifestJson) ?: ManifestData.empty(),
            )
        }
    }
}
