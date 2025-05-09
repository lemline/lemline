// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.cli.instance.InstanceCommand
import com.lemline.runner.cli.workflow.WorkflowCommand
import io.quarkus.arc.Unremovable
import io.quarkus.picocli.runtime.annotations.TopCommand
import jakarta.enterprise.context.Dependent
import org.jboss.logging.Logger.Level
import picocli.CommandLine.Command
import picocli.CommandLine.ITypeConverter
import picocli.CommandLine.Option

/**
 * Entry command for Picocli command-line parsing.
 * This class is annotated with @TopCommand to identify it as the main command.
 * It doesn't implement QuarkusApplication - it's purely for command parsing.
 */
@TopCommand
@Command(
    name = "lemline",
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
    subcommands = [
        WorkflowCommand::class,
        RuntimeCommand::class,
        InstanceCommand::class,
        ConfigCommand::class
    ]
)
@Unremovable
@Dependent
class MainCommand : Runnable {

    @Option(
        names = ["-l", "--log"],
        description = ["Set log level (\${COMPLETION-CANDIDATES})."],
        converter = [LogOptionConverter::class],
        paramLabel = "<level>"
    )
    lateinit var logLevel: Level

    @Option(
        names = ["-c", "--config"],
        description = ["Specify configuration file location"],
        paramLabel = "<config>"
    )
    var configFile: String? = null

    /**
     * Flag to indicate if the application should stop after executing a command.
     * If the main command is run without subcommands (and not for help/version),
     * it will set this to true to enter daemon mode.
     * Otherwise, for subcommands or help/version display, the application will exit.
     */
    var daemon: Boolean = false

    override fun run() {
        // This method is called if `lemline` is run without a subcommand,
        // and it's not a --help or --version request for the MainCommand itself
        // (Picocli handles those before calling run()).
        // This is the scenario where we intend to start the server.
        this.daemon = true

        //
        //                       %%%                         @%%
        //                      %*****%%%                    %***%%
        //                      %********=%%                %******%%
        //                      %****#%*****@%              %********%
        //                      %*****%#%+++++@%            %*********%
        //                      %%****#%%*%+++++@%@%%%%%%%%%%@%@*******%
        //                       %*****%##%%=+++++%++++++++++++++*%%****%
        //                        %*****#%##%-+++++=+++++++++++++++++@%*@@
        //                        @%*****%#%%@++++++++++++++++++++++++++%%
        //                         *%****#*@++++++++++++++++++++++++++++++%
        //                           @%*##@+++%@+******==+=:%@%%@%%%@@%%@=*+=%
        //                             @%%@@=%***%%%%%%%%%%%%%%%%%%%%%%%%%%%%+%
        //                             %#%%##%**@%%%%%%%:@%%%%%%%%%%%%%%%%%%%*%
        //                           %%@*%#%#@**%%%@%%%::.:%%@%%%%%@%%%@...%@*%
        //           ::::              %%@%%%%**%%%%%%%::::*@%%%@%%%%@%%: :%%*%
        //                    ::::      %****@@**%%%%%%@:::%%%%%%%@%%%%%::%%@*%
        //                              @%****+%***%%%%%%%%%%%%@%%*******%%%*%
        //        ::::                   @%**+%.:@%@+***%%%%@%**@@@......%%%@ %
        //      :::: :::       :::-        %@#*-.....: %%@@%%%@......%%%%%...@@
        //     :::     ::               %%%*#%%*#....................@%%@...:%
        //     :::     ::            %%*+**+*++**#**.......................%%
        //      :::  :::           %@+*+*++++*+*+*******:...............@%@
        //        ::::           %@*++*++++*++++*+*+****.::::::::@%%%@@
        //       :-            @%++**++++*++*+++*++=****......:::::%***%%
        //       @==          %%**++++**=++++++*+***%*%...........@%******%@
        //        ++         %@***++*+++++@++++**+++*@@..........%%*********%
        //         @*%*     %%*++++*++**++*+%+*++**+*+%@........%%%%%*******%%
        //          %%****##%**+**++++*++*++**%%@++*+*=%......:%
        //             %%%%%***++***+++*+******.::@%%%@......%%
        //                %#*++*+*++**+*++%*:::::::::.....:%%
        //              %%***#**+**++***%::::::::: ..:::%%#
        //             %******%%@*%%%%%%@%:::::::: %%%%
        //            %*****%@       %%%%%##%%%
        //           %%#@%%        *%%%###%%
        //                         %@@%%@
    }

    internal class LogOptionConverter : ITypeConverter<Level> {
        override fun convert(value: String): Level = Level.valueOf(value.uppercase())
    }
}
