package cash.atto.node

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Component
class CgroupCpuMetricProvider : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        bindTo(registry, Path.of("/proc/self"), ProcessHandle.current().pid())
    }

    internal fun bindTo(
        registry: MeterRegistry,
        procSelf: Path,
        pid: Long,
    ) {
        val source = CpuStat(resolve(procSelf, pid), pid)
        val supported = source.read() != null
        Gauge
            .builder("process.cgroup.cpu.available", source) {
                if (supported && it.read() != null) 1.0 else 0.0
            }.strongReference(true)
            .register(registry)
        if (!supported) return

        listOf(
            "process.cgroup.cpu.periods" to "nr_periods",
            "process.cgroup.cpu.throttled.periods" to "nr_throttled",
            "process.cgroup.cpu.throttled.time" to "throttled_usec",
        ).forEach { (name, field) ->
            FunctionCounter
                .builder(name, source) {
                    val value = it.read()?.get(field)?.toDouble()
                    if (field == "throttled_usec") value?.div(1_000_000) ?: Double.NaN else value ?: Double.NaN
                }.apply {
                    if (field == "throttled_usec") baseUnit("seconds")
                }.register(registry)
        }
    }

    private fun resolve(
        procSelf: Path,
        pid: Long,
    ): Path? =
        unavailableOnFailure {
            val membership =
                Files
                    .readAllLines(procSelf.resolve("cgroup"))
                    .singleOrNull { it.startsWith("0::") }
                    ?.removePrefix("0::")
                    ?.let(::absolutePath) ?: return@unavailableOnFailure null
            Files
                .readAllLines(procSelf.resolve("mountinfo"))
                .mapNotNull { line ->
                    val halves = line.split(" - ", limit = 2)
                    if (halves.size != 2 || halves[1].substringBefore(' ') != "cgroup2") return@mapNotNull null
                    val fields = halves[0].split(' ')
                    if (fields.size < 6) return@mapNotNull null
                    val root = absolutePath(unescape(fields[3])) ?: return@mapNotNull null
                    val mount = absolutePath(unescape(fields[4])) ?: return@mapNotNull null
                    if (!membership.startsWith(root)) return@mapNotNull null
                    val directory = mount.resolve(root.relativize(membership))
                    directory.takeIf { CpuStat(it, pid).read() != null }
                }.distinct()
                .singleOrNull()
        }

    private fun absolutePath(value: String): Path? {
        val path = Path.of(value)
        return path.takeIf { it.isAbsolute && it.none { part -> part.toString() == ".." } }
    }

    private fun unescape(value: String): String =
        Regex("\\\\([0-7]{3})").replace(value) {
            it.groupValues[1]
                .toInt(8)
                .toChar()
                .toString()
        }

    private class CpuStat(
        private val directory: Path?,
        private val pid: Long,
    ) {
        fun read(): Map<String, Long>? =
            unavailableOnFailure {
                val directory = directory ?: return@unavailableOnFailure null
                // cpu.max is absent at the real hierarchy root; cgroup.procs verifies exact membership.
                val quota = Files.readString(directory.resolve("cpu.max")).trim().split(Regex("\\s+"))
                if (quota.size != 2 || quota[1].toLongOrNull()?.let { it > 0 } != true) return@unavailableOnFailure null
                if (quota[0] != "max" && quota[0].toLongOrNull()?.let { it > 0 } != true) return@unavailableOnFailure null
                val member = Files.readAllLines(directory.resolve("cgroup.procs")).any { it.toLongOrNull() == pid }
                if (!member) return@unavailableOnFailure null
                val fields =
                    Files.readAllLines(directory.resolve("cpu.stat")).associate { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        parts[0] to parts.getOrNull(1)?.toLongOrNull()
                    }
                val required = listOf("nr_periods", "nr_throttled", "throttled_usec")
                if (required.any { fields[it]?.let { value -> value >= 0 } != true }) return@unavailableOnFailure null
                required.associateWith { fields.getValue(it)!! }
            }
    }

    private companion object {
        fun <T> unavailableOnFailure(block: () -> T): T? =
            try {
                block()
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SecurityException) {
                null
            }
    }
}
