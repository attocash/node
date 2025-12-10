package cash.atto.node

import com.sun.management.OperatingSystemMXBean
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.registerType
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints

class OsMxBeanRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType<OperatingSystemMXBean>(
            MemberCategory.INTROSPECT_PUBLIC_METHODS,
            MemberCategory.INVOKE_PUBLIC_METHODS
        )
    }
}

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(OsMxBeanRuntimeHints::class)
class NativeHintsConfiguration
