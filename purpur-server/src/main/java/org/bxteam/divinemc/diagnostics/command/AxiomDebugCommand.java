package org.bxteam.divinemc.diagnostics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.bxteam.divinemc.config.DivineConfig;
import org.bxteam.divinemc.diagnostics.DiagnosticsSession;

/**
 * {@code /axiomdebug [seconds]} — runs a timed profiling session (CPU flamegraph,
 * TPS/MSPT/memory/CPU timelines, lag spikes, GC/JIT deltas, heap leak delta) and
 * returns a temporary viewer link. Default duration comes from the config.
 */
public final class AxiomDebugCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("axiomdebug")
            .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "axiom.command.diagnostics"))
            .executes(context -> execute(context.getSource(), 0))
            .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                .executes(context -> execute(context.getSource(), IntegerArgumentType.getInteger(context, "seconds")))));
    }

    private static int execute(CommandSourceStack source, int seconds) {
        if (!DivineConfig.diagnosticsEnabled) {
            reply(source, "<red>Axiom diagnostics are disabled in the config.");
            return 0;
        }
        if (DiagnosticsSession.isDebugRunning()) {
            reply(source, "<red>A debug session is already running. Wait for it to finish.");
            return 0;
        }
        final int duration = seconds > 0 ? seconds : DivineConfig.diagnosticsProfilerDurationSeconds;
        reply(source, "<gray>Profiling for <yellow>" + duration + "s<gray>... a link will appear when done.");
        DiagnosticsSession.debug(seconds, result -> CommandResponder.send(source, result));
        return 1;
    }

    private static void reply(CommandSourceStack source, String mini) {
        final Component msg = MiniMessage.miniMessage().deserialize(mini);
        MinecraftServer.getServer().execute(() -> source.sendSuccess(msg, false));
    }
}
