package org.purpurmc.purpur.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import org.purpurmc.purpur.PurpurConfig;

public class SimulationDistanceCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(Commands.literal("simulationdistance")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_ADMIN, "bukkit.command.simulationdistance"))
                .executes(context -> get(context.getSource()))
                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                        .executes(context -> set(context.getSource(), IntegerArgumentType.getInteger(context, "distance")))
                )
        );
        dispatcher.register(Commands.literal("simdist")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_ADMIN, "bukkit.command.simulationdistance"))
                .redirect(node)
        );
    }

    private static int get(CommandSourceStack sender) {
        int distance = sender.getServer().getPlayerList().getSimulationDistance();
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.simulationDistanceCommandGet,
                Placeholder.unparsed("distance", Integer.toString(distance))), false);
        return distance;
    }

    private static int set(CommandSourceStack sender, int distance) {
        sender.getServer().getPlayerList().setSimulationDistance(distance);
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.simulationDistanceCommandSet,
                Placeholder.unparsed("distance", Integer.toString(distance))), false);
        return distance;
    }
}
