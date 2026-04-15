package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;

import javax.inject.Inject;
import java.util.List;

@Slf4j
@PluginDescriptor(name = "Loot to JSON")
public class LootLoggerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    // Gson instance for JSON serialization
    private final Gson compactGson = new Gson(); // For the actual file
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create(); // For your debug chat/logs

    // =========================
    //    START UP PROCEDURE
    // =========================
    @Override
    protected void startUp() {
        log.info("=== PLUGIN RELOADED ===");
    }

    // =========================
    //      NPC LOOT EVENT
    // =========================
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();

        // NPC name (e.g., "Goblin", "Cow")
        String sourceName = npc.getName();

        // Exact world coordinates of the NPC
        WorldPoint location = npc.getWorldLocation();
        int xCoord = npc.getWorldLocation().getX();
        int yCoord = npc.getWorldLocation().getY();
        int planeCoord = npc.getWorldLocation().getPlane();

        // get items
        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), item.getQuantity()))
                .collect(java.util.stream.Collectors.toList());

        String killMessage = "Loot from: " + sourceName + " at " + location + "\n";
        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                killMessage,
                null
        );

        event.getItems().forEach(item -> {
            String itemName = itemManager.getItemComposition(item.getId()).getName();
            String lootMessage = " - " + itemName + " x" + item.getQuantity();

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    lootMessage,
                    null
            );
        });

        LootRecord record = new LootRecord(sourceName, xCoord, yCoord, planeCoord, items);
        log.info("JSON Output: {}", compactGson.toJson(record));
    }
}