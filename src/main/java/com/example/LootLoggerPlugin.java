package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.plugins.loottracker.LootReceived;

import javax.inject.Inject;
import java.util.Arrays;
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
    private int lastActiveAnimation = -1;

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
    //    ANIMATION CHECKER
    // =========================
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) return;

        int anim = client.getLocalPlayer().getAnimation();
        if (anim != -1) {
            lastActiveAnimation = anim;
        }
    }

    // =========================
    //    RESOURCE NODE EVENT
    // =========================
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // 93 is the ID for the Inventory container
        if (event.getContainerId() == 93) {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Inventory changed! Checking for animation...", null);

            int animationId = client.getLocalPlayer().getAnimation();

            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Animation ID: " + animationId, null);

            String items = Arrays.toString(event.getItemContainer().getItems());

            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Items: " + items, null);

        }
    }

    // =========================
    //        IDK YET LOL
    // =========================
    @Subscribe
    public void onLootReceived(LootReceived event) {
        String message = String.format("Loot: %s | Stacks: %d | Type: %s",
                event.getName(), event.getItems().size(), event.getType());

        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
        log.info(message);
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