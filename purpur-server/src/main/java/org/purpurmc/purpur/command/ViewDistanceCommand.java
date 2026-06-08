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

public class ViewDistanceCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(Commands.literal("viewdistance")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_ADMIN, "bukkit.command.viewdistance"))
                .executes(context -> get(context.getSource()))
                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                        .executes(context -> set(context.getSource(), IntegerArgumentType.getInteger(context, "distance")))
                )
        );
        dispatcher.register(Commands.literal("vd")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_ADMIN, "bukkit.command.viewdistance"))
                .redirect(node)
        );
    }

    private static int get(CommandSourceStack sender) {
        int distance = sender.getServer().getPlayerList().getViewDistance();
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.viewDistanceCommandGet,
                Placeholder.unparsed("distance", Integer.toString(distance))), false);
        return distance;
    }

    private static int set(CommandSourceStack sender, int distance) {
        sender.getServer().getPlayerList().setViewDistance(distance);
        sender.sendSuccess(MiniMessage.miniMessage().deserialize(PurpurConfig.viewDistanceCommandSet,
                Placeholder.unparsed("distance", Integer.toString(distance))), false);
        return distance;
    }
}
