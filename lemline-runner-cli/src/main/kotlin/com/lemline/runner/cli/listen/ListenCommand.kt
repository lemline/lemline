// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.listen

import com.lemline.common.logger.logger
import com.lemline.runner.common.activities.TestModeConfiguration
import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import io.quarkus.runtime.Quarkus
import jakarta.enterprise.context.Dependent
import jakarta.inject.Inject
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Command(
    name = "listen",
    description = ["listen for Lemline messages"],
)
@Unremovable
@Dependent
class ListenCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @CommandLine.Option(names = ["-m", "--metrics-port"], description = ["The metrics endpoint port to use"])
    var metricsPort: Int? = null

    @CommandLine.Option(
        names = ["--mock-config"],
        description = ["Path to mock configuration file (YAML/JSON). Enables test mode with mock activity responses."]
    )
    var mockConfigPath: String? = null

    @Inject
    lateinit var testModeConfiguration: TestModeConfiguration

    val logger = logger()

    override fun run() {
        // Configure test mode if mock config is provided
        if (mockConfigPath != null) {
            testModeConfiguration.enable(mockConfigPath)
            logger.info { "Test mode enabled with mock config: $mockConfigPath" }
        }

        val mascotLines = """

                              %%%                         @%%
                             %*****%%%                    %***%%
                             %********=%%                %******%%
                             %****#%*****@%              %********%
                             %*****%#%+++++@%            %*********%
                             %%****#%%*%+++++@%@%%%%%%%%%%@%@*******%
                              %*****%##%%=+++++%++++++++++++++*%%****%
                               %*****#%##%-+++++=+++++++++++++++++@%*@@
                               @%*****%#%%@++++++++++++++++++++++++++%%
                                *%****#*@++++++++++++++++++++++++++++++%
                                  @%*##@+++%@+******==+=:%@%%@%%%@@%%@=*+=%
                                    @%%@@=%***%%%%%%%%%%%%%%%%%%%%%%%%%%%%+%
                                    %#%%##%**@%%%%%%%:@%%%%%%%%%%%%%%%%%%%*%
                                  %%@*%#%#@**%%%@%%%::.:%%@%%%%%@%%%@...%@*%
                  ::::              %%@%%%%**%%%%%%%::::*@%%%@%%%%@%%: :%%*%
                           ::::      %****@@**%%%%%%@:::%%%%%%%@%%%%%::%%@*%
                                     @%****+%***%%%%%%%%%%%%@%%*******%%%*%
               ::::                   @%**+%.:@%@+***%%%%@%**@@@......%%%@ %
             :::: :::       :::-        %@#*-.....: %%@@%%%@......%%%%%...@@
            :::     ::               %%%*#%%*#....................@%%@...:%
            :::     ::            %%*+**+*++**#**.......................%%
             :::  :::           %@+*+*++++*+*+*******:...............@%@
               ::::           %@*++*++++*++++*+*+****.::::::::@%%%@@
              :-            @%++**++++*++*+++*++=****......:::::%***%%
              @==          %%**++++**=++++++*+***%*%...........@%******%@
               ++         %@***++*+++++@++++**+++*@@..........%%*********%
                @*%*     %%*++++*++**++*+%+*++**+*+%@........%%%%%*******%%
                 %%****##%**+**++++*++*++**%%@++*+*=%......:%
                    %%%%%***++***+++*+******.::@%%%@......%%
                       %#*++*+*++**+*++%*:::::::::.....:%%
                     %%***#**+**++***%::::::::: ..:::%%#
                    %******%%@*%%%%%%@%:::::::: %%%%
                   %*****%@       %%%%%##%%%
                  %%#@%%        *%%%###%%
                                %@@%%@
                   """.trimIndent().lines()

        mascotLines.forEach { line -> logger.info { line } }

        logger.info { "Start consuming messages..." }
        Quarkus.waitForExit()
    }
}
