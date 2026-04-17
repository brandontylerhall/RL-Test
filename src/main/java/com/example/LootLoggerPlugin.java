package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.CommandExecuted;
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
    private final Gson compactGson = new Gson();

    private java.io.FileWriter writer;
    private int lastActiveAnimation = -1;
    private Item[] previousInventory = new Item[28];

    public void gameMsg(String msg) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE,
                "",
                msg,
                null);
    }

    private static final java.util.Map<Integer, String> SOURCE_MAP = java.util.Map.ofEntries(
            java.util.Map.entry(879, "Woodcutting"), // Bronze axe
            java.util.Map.entry(877, "Woodcutting"), // Iron axe
            java.util.Map.entry(875, "Woodcutting"), // Steel axe
            java.util.Map.entry(625, "Mining"),      // Bronze pick
            java.util.Map.entry(626, "Mining"),      // Iron pick
            java.util.Map.entry(627, "Mining"),      // Steel pick
            java.util.Map.entry(621, "Small Net Fishing")
    );

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

        for (int i = 0; i < 28; i++) {
            previousInventory[i] = new Item(-1, 0);
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        // Usage: Type ::status in game chat
        if (event.getCommand().equals("status")) {
            int currentAnim = client.getLocalPlayer().getAnimation();
            WorldPoint loc = client.getLocalPlayer().getWorldLocation();

            gameMsg("--- DEBUG STATUS ---");
            gameMsg("Current Animation: " + currentAnim);
            gameMsg("Last Saved Animation: " + lastActiveAnimation);
            gameMsg("Location: " + loc.getX() + ", " + loc.getY() + ", " + loc.getPlane());
            gameMsg("Inventory Memory Size: " + (previousInventory != null ? previousInventory.length : "NULL"));
        }
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
        List<DroppedItem> items = event.getItems()
                .stream()
                .map(item -> new DroppedItem(
                        item.getId(), item.getQuantity())
                )
                .collect(java.util.stream.Collectors.toList());

        gameMsg(String.format("Loot from: %s at %d", sourceName, location));

        event.getItems().forEach(item -> {
            String itemName = itemManager.getItemComposition(item.getId()).getName();
            int itemQty = item.getQuantity();

            gameMsg(String.format("- %s x %d", itemName, itemQty));
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

        int animId = client.getLocalPlayer().getAnimation();

        if (animId != -1) {
            lastActiveAnimation = animId;
        }

        gameMsg(String.format("Current Animation ID: %d", animId));
    }


    private void handleGatheringGains(int itemId, int qty) {
        // 1. Filter out idle moves
        if (client.getLocalPlayer().getAnimation() == -1 && lastActiveAnimation == -1) return;

        // 2. Lookup source (default to Unknown if not in map)
        String sourceName = SOURCE_MAP.getOrDefault(lastActiveAnimation, "Unknown/Pickup");

        // 3. Log and write
        String resourceName = itemManager.getItemComposition(itemId).getName();

        WorldPoint wp = client.getLocalPlayer().getWorldLocation();
        int x = wp.getX();
        int y = wp.getY();
        int plane = wp.getPlane();

        gameMsg(String.format("Resource gained from %s: %s. Qty: %d", sourceName, resourceName, qty));

        List<DroppedItem> items = List.of(new DroppedItem(itemId, qty));
        LootRecord record = new LootRecord(sourceName, x, y, plane, items);

        writeToFile(record);
    }

    /////////////////////////////////////////////////////////////////////////
    ////////////////// RESOURCE NODE EVENT / BANKING LOGIC //////////////////
    /////////////////////////////////////////////////////////////////////////
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        // 93 is the ID for the Inventory container
        if (event.getContainerId() != 93) return;

        //////////////////////// BANK-CHECK LOGIC /////////////////////////////
        ItemContainer bankContainer = client.getItemContainer(InventoryID.BANK);
        boolean isBanking = (bankContainer != null);
        //////////////////////////////////////////////////////////////////////

        Item[] currentInventory = event.getItemContainer().getItems();

        // Loop through all 28 inv slots
        for (int i = 0; i < 28; i++) {
            int newId = (i < currentInventory.length) ? currentInventory[i].getId() : -1;
            int newQty = (i < currentInventory.length) ? currentInventory[i].getQuantity() : 0;

            int oldId = (i < previousInventory.length) ? previousInventory[i].getId() : -1;
            int oldQty = (i < previousInventory.length) ? previousInventory[i].getQuantity() : 0;

            String oldName = itemManager.getItemComposition(oldId).getName();
            String newName = itemManager.getItemComposition(newId).getName();

            // SKIP if nothing changed in this slot
            if (newId == oldId && newQty == oldQty) continue;
            // CASE 1: Item ID changed (slot was replaced)
            if (newId != oldId) {
                // If there was something there before and the bank is not open, it's a loss...
                if (oldId != -1 && !isBanking) {
                    gameMsg(String.format("Loss (Slot %d): %s x%d", i + 1, oldName, oldQty));
                    // ...otherwise, it's a deposit
                } else if (oldId != -1) {
                    gameMsg(String.format("Deposit (Slot %d): %s x%d", i + 1, oldName, oldQty));
                }
                // If there is something here now and the bank is not open, it's a gain...
                if (newId != -1 && !isBanking) {
                    handleGatheringGains(newId, newQty);
                    // ...otherwise, it's a withdrawal
                } else if (newId != -1) {
                    gameMsg(String.format("Withdrawal (Slot %d): %s x%d", i + 1, newName, newQty));
                }
            }
            // CASE 2: Same item, but quantity changed (stackables)
            else {
                int diff = newQty - oldQty;
                if (!isBanking) {
                    if (diff > 0) {
                        gameMsg(String.format("Gain (Slot %d): %s x%d", i + 1, newName, diff));
                    } else {
                        gameMsg(String.format("Loss (Slot %d): %s x%d", i + 1, newName, Math.abs(diff)));
                    }
                } else {
                    if (diff > 0) {
                        gameMsg(String.format("Withdrawal (Slot %d): %s x%d. New total: %d", i + 1, newName, diff, newQty));
                    } else {
                        gameMsg(String.format("Deposit (Slot %d): %s x%d. New total: %d", i + 1, newName, Math.abs(diff), newQty));
                    }
                }
            }
        }
        previousInventory = currentInventory.clone();
    }

    // =========================
    //        IDK YET LOL
    // =========================
    @Subscribe
    public void onLootReceived(LootReceived event) {
        gameMsg(String.format("Loot: %s | Stacks: %d | Type: %s",
                event.getName(), event.getItems().size(), event.getType()));
    }

    // =========================
    //    WRITE DATA TO FILE
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