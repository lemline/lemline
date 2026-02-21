// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway

import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_ENABLED
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.SCHEDULED_ENABLED
import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

@QuarkusMain(name = "gateway")
class LemlineGatewayApplication : QuarkusApplication {
    override fun run(vararg args: String): Int {
        Quarkus.waitForExit()
        return 0
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            setConfigPath(args)
            if (ConfigPathHolder.configPath == null) {
                System.err.println("No valid configuration file found. Please provide one, using one of the following methods:")
                System.err.println("1. Pass the path to the file as a command-line argument (e.g., --config=<path>).")
                System.err.println("2. Set the LEMLINE_CONFIG environment variable to the file's path.")
                System.err.println("3. Place a .lemline.yaml file in the current directory.")
                System.err.println("4. Place a config.yaml file in ~/.config/lemline/.")
                System.err.println("5. Place a .lemline.yaml file in your home directory.")
                exitProcess(1)
            }

            configureRuntimeToggles()
            Quarkus.run(LemlineGatewayApplication::class.java, *args)
        }

        private fun setConfigPath(args: Array<String>) {
            parseConfigFromArgs(args)?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
            }

            System.getenv("LEMLINE_CONFIG")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                if (checkConfigLocation(Paths.get(it), true)) return
            }

            if (checkConfigLocation(Paths.get(".lemline.yaml"), false)) return

            System.getProperty("user.home")?.let {
                val xdgPath = Paths.get(it, ".config", "lemline", "config.yaml")
                if (checkConfigLocation(xdgPath, false)) return
                val homePath = Paths.get(it, ".lemline.yaml")
                if (checkConfigLocation(homePath, false)) return
            }
        }

        private fun parseConfigFromArgs(args: Array<String>): String? {
            for (i in args.indices) {
                val arg = args[i]
                if (arg.startsWith("--config=")) return arg.removePrefix("--config=").trim()
                if (arg == "--config" || arg == "-c") {
                    if (i + 1 < args.size) return args[i + 1].trim()
                }
            }
            return null
        }

        private fun configureRuntimeToggles() {
            System.setProperty(DATABASE_ENABLED, "true")
            System.setProperty(SCHEDULED_ENABLED, "false")

            System.setProperty(COMMANDS_PRODUCER_ENABLED, "true")
            System.setProperty(COMMANDS_CONSUMER_ENABLED, "false")
            System.setProperty(EVENTS_PRODUCER_ENABLED, "false")
            System.setProperty(EVENTS_CONSUMER_ENABLED, "false")
            System.setProperty(CLOUDEVENTS_PRODUCER_ENABLED, "false")
            System.setProperty(CLOUDEVENTS_CONSUMER_ENABLED, "false")
            System.setProperty(LIFECYCLE_EVENTS_PRODUCER_ENABLED, "false")
            System.setProperty(LIFECYCLE_EVENTS_CONSUMER_ENABLED, "false")

            System.setProperty("quarkus.http.port", "0")
            System.setProperty("quarkus.http.ssl-port", "0")
        }
    }
}

private fun checkConfigLocation(filePath: Path, provided: Boolean): Boolean {
    val path = filePath.normalize()
    val fileExists = Files.exists(path)
    val isRegularFile = Files.isRegularFile(path)
    if (!fileExists && provided) {
        throw IllegalArgumentException("'${path.toAbsolutePath()}' does not exist")
    }
    if (!isRegularFile && provided) {
        throw IllegalArgumentException("'${path.toAbsolutePath()}' is not a regular file")
    }
    if (fileExists && isRegularFile) {
        ConfigPathHolder.configPath = path
        return true
    }
    return false
}
