// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.listen

import com.lemline.common.logger.logger
import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import io.quarkus.runtime.Quarkus
import jakarta.enterprise.context.Dependent
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

    @CommandLine.Option(names = ["-p", "--port"], description = ["The metrics port to use"])
    var port: Int? = null

    val logger = logger()

    override fun run() {
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
