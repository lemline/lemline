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
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ScopeType.INHERIT
import picocli.CommandLine.Spec

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
        InstanceCommand::class,
        ConfigCommand::class,
        StartCommand::class
    ]
)
@Unremovable
@Dependent
class MainCommand : Runnable {

    /**
     * The spec object is used to access the command line options and arguments.
     * It is injected by Picocli and is used to provide help and usage information.
     */
    @Spec
    lateinit var spec: CommandSpec

    @Option(
        names = ["-l", "--log"],
        description = ["Set log level (\${COMPLETION-CANDIDATES})."],
        converter = [LogOptionConverter::class],
        paramLabel = "<level>",
        scope = INHERIT
    )
    var logLevel: Level? = null

    @Option(
        names = ["-c", "--config"],
        description = ["Specify configuration file location"],
        paramLabel = "<config>",
        scope = INHERIT
    )
    var configFile: String? = null

    override fun run() {
        // This method is called if `lemline` is run without a recognized subcommand
        // (e.g., workflow, start, config, instance) or if only global options for MainCommand are provided.
        spec.commandLine().usage(System.out)
        // By default, Picocli might exit with 0 after run.
        // If a non-zero exit is desired to indicate a subcommand was missing,
        // spec.commandLine().execute() in LemlineApplication will use the exit code
        // returned by this run method or how Picocli handles "no command run".
        // For now, printing help is the primary action.

        println(
            """
                
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
            """.trimIndent()
        )

    }

    internal class LogOptionConverter : ITypeConverter<Level> {
        override fun convert(value: String): Level = Level.valueOf(value.uppercase())
    }
}
