package cash.atto.node

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class CgroupCpuMetricProviderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `exports the process cgroup counters and converts microseconds to seconds`() {
        // Given
        val directory = fixture("/kubepods/pod/container", "/")
        writeStats(directory)

        // When
        val registry = bind()

        // Then
        assertThat(registry.get("process.cgroup.cpu.available").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("process.cgroup.cpu.periods").functionCounter().count()).isEqualTo(100.0)
        assertThat(registry.get("process.cgroup.cpu.throttled.periods").functionCounter().count()).isEqualTo(4.0)
        val time = registry.get("process.cgroup.cpu.throttled.time").functionCounter()
        assertThat(time.count()).isEqualTo(1.234567)
        assertThat(time.id.baseUnit).isEqualTo("seconds")
    }

    @Test
    fun `exports Prometheus counter names with seconds and total suffixes`() {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        // When
        CgroupCpuMetricProvider().bindTo(registry, procSelf(), 42)
        val scrape = registry.scrape()

        // Then
        mapOf(
            "process_cgroup_cpu_available" to 1.0,
            "process_cgroup_cpu_periods_total" to 100.0,
            "process_cgroup_cpu_throttled_periods_total" to 4.0,
            "process_cgroup_cpu_throttled_time_seconds_total" to 1.234567,
        ).forEach { (name, expected) ->
            val sample = scrape.lineSequence().single { it.startsWith("$name ") }
            assertThat(sample.substringAfter(' ').toDouble()).isEqualTo(expected)
        }
        assertThat(scrape).contains("# TYPE process_cgroup_cpu_throttled_time_seconds_total counter")
    }

    @Test
    fun `resolves a subtree mount and decodes escaped mount paths`() {
        // Given
        val directory = fixture("/kubepods/pod/container", "/kubepods/pod", "mounted cgroup")
        writeStats(directory)

        // When
        val registry = bind()

        // Then
        assertThat(registry.get("process.cgroup.cpu.periods").functionCounter().count()).isEqualTo(100.0)
    }

    @Test
    fun `accepts a private cgroup namespace root when it is the process cgroup`() {
        // Given
        val directory = fixture("/", "/")
        writeStats(directory)

        // When
        val registry = bind()

        // Then
        assertThat(registry.get("process.cgroup.cpu.available").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("process.cgroup.cpu.periods").functionCounter().count()).isEqualTo(100.0)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/../container", "/kubepods-other/container", "container"])
    fun `rejects namespace escapes and unrelated mount roots`(membership: String) {
        // Given
        val directory = fixture("/kubepods/container", "/kubepods")
        writeStats(directory)
        Files.writeString(procSelf().resolve("cgroup"), "0::$membership\n")

        // When
        val registry = bind()

        // Then
        assertUnavailable(registry)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cpu.max", "cgroup.procs", "cpu.stat"])
    fun `does not substitute hierarchy root statistics when a required file is absent`(missingFile: String) {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        writeStats(temporaryDirectory.resolve("cgroup"), periods = 999)
        Files.delete(directory.resolve(missingFile))

        // When
        val registry = bind()

        // Then
        assertUnavailable(registry)
    }

    @Test
    fun `rejects a cgroup that does not contain this process`() {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        Files.writeString(directory.resolve("cgroup.procs"), "7\n8\n")

        // When
        val registry = bind()

        // Then
        assertUnavailable(registry)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "usage_usec 100\n",
            "nr_periods 100\nnr_throttled 4\nthrottled_usec invalid\n",
            "nr_periods 100\nnr_throttled -1\nthrottled_usec 10\n",
        ],
    )
    fun `omits counters for missing or malformed throttling fields`(stats: String) {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        Files.writeString(directory.resolve("cpu.stat"), stats)

        // When
        val registry = bind()

        // Then
        assertUnavailable(registry)
    }

    @Test
    fun `reports unavailable after a read failure and preserves actual counter resets`() {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        val registry = bind()
        val periods = registry.get("process.cgroup.cpu.periods").functionCounter()
        val available = registry.get("process.cgroup.cpu.available").gauge()

        // When
        Files.delete(directory.resolve("cpu.stat"))

        // Then
        assertThat(periods.count()).isNaN()
        assertThat(available.value()).isZero()

        // When
        writeStats(directory, periods = 3)

        // Then
        assertThat(periods.count()).isEqualTo(3.0)
        assertThat(available.value()).isEqualTo(1.0)
    }

    @Test
    fun `v1 only and unavailable proc files do not prevent startup`() {
        // Given
        val directory = fixture("/container", "/")
        writeStats(directory)
        Files.writeString(procSelf().resolve("cgroup"), "2:cpu,cpuacct:/container\n")

        // When
        val v1Registry = bind()
        Files.writeString(procSelf().resolve("cgroup"), "0::/container\n")
        Files.delete(procSelf().resolve("mountinfo"))
        val missingRegistry = bind()

        // Then
        assertUnavailable(v1Registry)
        assertUnavailable(missingRegistry)
    }

    private fun procSelf(): Path = temporaryDirectory.resolve("proc-self")

    private fun fixture(
        membership: String,
        mountRoot: String,
        mountName: String = "cgroup",
    ): Path {
        val mount = Files.createDirectories(temporaryDirectory.resolve(mountName))
        Files.createDirectories(procSelf())
        Files.writeString(procSelf().resolve("cgroup"), "0::$membership\n")
        val escapedMount = mount.toString().replace(" ", "\\040")
        Files.writeString(procSelf().resolve("mountinfo"), "1 0 0:1 $mountRoot $escapedMount ro,nosuid - cgroup2 cgroup rw\n")
        return Files.createDirectories(mount.resolve(Path.of(mountRoot).relativize(Path.of(membership))))
    }

    private fun writeStats(
        directory: Path,
        periods: Long = 100,
    ) {
        Files.writeString(directory.resolve("cpu.max"), "200000 100000\n")
        Files.writeString(directory.resolve("cgroup.procs"), "42\n")
        Files.writeString(directory.resolve("cpu.stat"), "usage_usec 999\nnr_periods $periods\nnr_throttled 4\nthrottled_usec 1234567\n")
    }

    private fun bind(): SimpleMeterRegistry = SimpleMeterRegistry().also { CgroupCpuMetricProvider().bindTo(it, procSelf(), 42) }

    private fun assertUnavailable(registry: SimpleMeterRegistry) {
        assertThat(registry.get("process.cgroup.cpu.available").gauge().value()).isZero()
        assertThat(registry.find("process.cgroup.cpu.periods").functionCounter()).isNull()
        assertThat(registry.find("process.cgroup.cpu.throttled.periods").functionCounter()).isNull()
        assertThat(registry.find("process.cgroup.cpu.throttled.time").functionCounter()).isNull()
    }
}
