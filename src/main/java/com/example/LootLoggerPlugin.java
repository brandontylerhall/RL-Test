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
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import java.util.List;

@Slf4j
@PluginDescriptor(name = "Loot to JSON")
public class LootLoggerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private ItemManager itemManager;

    @Inject
    private java.util.concurrent.ScheduledExecutorService executor;

    // Gson instance for JSON serialization
    private final Gson compactGson = new Gson(); // For the actual file
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create(); // For your debug chat/logs

    private java.io.FileWriter writer;

    // =========================
    //    START UP PROCEDURE
    // =========================
    @Override
    protected void startUp() throws Exception {
        // TODO: uncomment this when comfortable; this saves
        //  to the hidden .runelite folder
//        java.io.File logFIle = new java.io.File(RuneLite.RUNELITE_DIR, "loot_log.jsonl");
        // TODO: remove this when comfortable
        java.io.File logFIle = new java.io.File("loot_log.jsonl");

        writer = new java.io.FileWriter(logFIle, true);

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
            String lootMsg = " - " + itemName + " x" + item.getQuantity();

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    lootMsg,
                    null
            );
        });

        LootRecord record = new LootRecord(sourceName, xCoord, yCoord, planeCoord, items);
        executor.execute(() -> writeToFile(record));
    }

    // =========================
    //  WRITE LOOT DATA TO FILE
    // =========================
    private void writeToFile(LootRecord record) {
        try {
            // synchronized ensures that if two things die at once,
            // their JSON lines don't get tangled together.
            synchronized (writer) {
                writer.write(compactGson.toJson(record) + "\n");
                writer.flush();
            }
        } catch (java.io.IOException e) {
            log.error("Error writing to file", e);
        }
    }

    // =========================
    //    SHUT DOWN PROCEDURES
    // =========================
    @Override
    public void shutDown() throws Exception {
        if (writer != null) {
            writer.close();
        }
    }
}