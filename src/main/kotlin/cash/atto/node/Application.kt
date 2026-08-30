package cash.atto.node

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock
import kotlin.system.exitProcess

@SpringBootApplication
class Application {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    if (args.singleOrNull() == HealthCheckCommand.NAME) {
        exitProcess(HealthCheckCommand.execute())
    }

    runApplication<Application>(*args)
}
