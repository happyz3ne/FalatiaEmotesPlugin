package org.falatia.emotes.plugin;

import com.mojang.brigadier.Command;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.plugin.java.JavaPlugin;

public final class FalatiaEmotesPlugin extends JavaPlugin {

    private EmoteRelay emotes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.emotes = new EmoteRelay(this);
        this.emotes.enable();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                Commands.literal("falatiaemotes")
                        .requires(source -> source.getSender().hasPermission("falatia.emotes.admin"))
                        .then(Commands.literal("reload").executes(context -> {
                            reloadConfig();
                            this.emotes.reload();
                            context.getSource().getSender().sendMessage(
                                    Component.text("FalatiaEmotes: Перезавантажено", NamedTextColor.GREEN));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Falatia Emotes"));
    }
}
