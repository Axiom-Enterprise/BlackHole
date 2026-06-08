package org.bxteam.divinemc.diagnostics.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.bxteam.divinemc.diagnostics.ThreadWatchdog;

/**
 * {@code /axiomthreads} — prints the live thread breakdown (groups by pool family, with growth
 * since boot and the container's pid/thread budget). The leak-hunting companion to the watchdog.
 *
 * <p>{@code /axiomthreads interrupt <pattern>} dry-runs a cooperative interrupt, listing the threads
 * that match and would be touched. {@code /axiomthreads interrupt <pattern> confirm} actually sends
 * the interrupts (only when {@code thread-watchdog.allow-interrupt} is true in the config). The main
 * thread, fork-owned pools, Netty, the scheduler, and JVM/system threads are never interruptible.
 */
public final class AxiomThreadsCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("axiomthreads")
            .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "axiom.command.diagnostics"))
            .executes(context -> report(context.getSource()))
            .then(Commands.literal("interrupt")
                .then(Commands.argument("pattern", StringArgumentType.string())
                    .executes(context -> interrupt(context.getSource(), StringArgumentType.getString(context, "pattern"), false))
                    .then(Commands.literal("confirm")
                        .executes(context -> interrupt(context.getSource(), StringArgumentType.getString(context, "pattern"), true))))));
    }

    private static int report(CommandSourceStack source) {
        // snapshot() emits only digits/letters/spaces and +/%() — no MiniMessage tags — so wrapping
        // it in a single <gray> is safe without escaping.
        reply(source, "<gray>" + ThreadWatchdog.snapshot());
        return 1;
    }

    private static int interrupt(CommandSourceStack source, String pattern, boolean confirm) {
        final ThreadWatchdog.InterruptResult r = ThreadWatchdog.interrupt(pattern, !confirm);

        if (r.matched().isEmpty()) {
            reply(source, "<yellow>No interruptible threads match <white>" + escape(pattern) + "<yellow>.");
            if (!r.skippedCritical().isEmpty()) {
                reply(source, "<gray>(" + r.skippedCritical().size() + " critical/system threads matched but are protected.)");
            }
            return 0;
        }

        if (!r.allowed()) {
            reply(source, "<red>Interrupts are disabled. Set <white>diagnostics.thread-watchdog.allow-interrupt: true<red> to enable.");
            reply(source, "<gray>Would match " + r.matched().size() + " thread(s): <white>" + preview(r.matched()));
            return 0;
        }

        if (!confirm) {
            reply(source, "<yellow>Dry run — would interrupt <white>" + r.matched().size() + "<yellow> thread(s):");
            reply(source, "<gray>" + preview(r.matched()));
            reply(source, "<gray>Run <white>/axiomthreads interrupt " + escape(pattern) + " confirm<gray> to send the interrupts. "
                + "interrupt() is cooperative — threads that ignore it will survive.");
            return 0;
        }

        reply(source, "<green>Interrupted <white>" + r.matched().size() + "<green> thread(s): <gray>" + preview(r.matched()));
        return 1;
    }

    private static String preview(java.util.List<String> names) {
        final int cap = 12;
        if (names.size() <= cap) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, cap)) + " ... (+" + (names.size() - cap) + " more)";
    }

    private static String escape(String s) {
        return s.replace("<", "\\<");
    }

    private static void reply(CommandSourceStack source, String mini) {
        final Component msg = MiniMessage.miniMessage().deserialize(mini);
        MinecraftServer.getServer().execute(() -> source.sendSuccess(msg, false));
    }
}
