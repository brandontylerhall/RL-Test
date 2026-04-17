package com.lootlogger;

import com.lootlogger.data.DroppedItem;
import com.lootlogger.data.LootRecord;
import com.lootlogger.io.LootWriter;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
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

    @Inject
    private java.util.concurrent.ScheduledExecutorService executor;

    // ---> NEW: Our dedicated file writer class <---
    private LootWriter lootWriter;

    // DEBUG VARIABLES //
    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private Item[] previousInventory = new Item[28];
    ///////////////////////////////

    public void gameMsg(String msg) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
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
    //    START UP / SHUT DOWN
    // =========================
    @Override
    protected void startUp() throws Exception {
        lootWriter = new LootWriter();
        lootWriter.init(); // Tell the writer to open the file

        for (int i = 0; i < 28; i++) {
            previousInventory[i] = new Item(-1, 0);
        }
    }

    @Override
    public void shutDown() throws Exception {
        if (lootWriter != null) {
            lootWriter.close();
        }
    }

    // =========================
    //      DEBUG COMMAND
    // =========================
    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if (event.getCommand().equals("status")) {
            int currentAnim = client.getLocalPlayer().getAnimation();
            WorldPoint loc = client.getLocalPlayer().getWorldLocation();

            gameMsg("--- DEBUG STATUS ---");
            gameMsg("Current Animation: " + currentAnim);
            gameMsg("Last Saved Animation: " + lastActiveAnimation);
            gameMsg("Location: " + loc.getX() + ", " + loc.getY() + ", " + loc.getPlane());
            gameMsg(String.format("Last menu option: %s", lastMenuOptionClicked));
        }
    }

    // =========================
    //      NPC LOOT EVENT
    // =========================
    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        NPC npc = event.getNpc();
        String sourceName = npc.getName();
        WorldPoint location = npc.getWorldLocation();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), item.getQuantity()))
                .collect(java.util.stream.Collectors.toList());

        LootRecord record = new LootRecord(sourceName, location.getX(), location.getY(), location.getPlane(), items);

        // Pass the record to the writer via the executor
        executor.execute(() -> lootWriter.writeToFile(record));
    }

    // =========================
    //    ANIMATION & MENU EVENTS
    // =========================
    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) return;
        int animId = client.getLocalPlayer().getAnimation();
        if (animId != -1) {
            lastActiveAnimation = animId;
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = event.getMenuOption();
    }

    // =========================
    //    RESOURCE NODE LOGIC
    // =========================
    private void handleGatheringGains(int itemId, int qty) {
        if (client.getLocalPlayer().getAnimation() == -1 && lastActiveAnimation == -1) return;

        String sourceName = SOURCE_MAP.getOrDefault(lastActiveAnimation, "Unknown/Pickup");
        String resourceName = itemManager.getItemComposition(itemId).getName();
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        gameMsg(String.format("Resource gained from %s: %s. Qty: %d", sourceName, resourceName, qty));

        List<DroppedItem> items = List.of(new DroppedItem(itemId, qty));
        LootRecord record = new LootRecord(sourceName, wp.getX(), wp.getY(), wp.getPlane(), items);

        executor.execute(() -> lootWriter.writeToFile(record));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() != 93) return;

        ItemContainer bankContainer = client.getItemContainer(95);
        boolean isBanking = (bankContainer != null);
        Item[] currentInventory = event.getItemContainer().getItems();

        for (int i = 0; i < 28; i++) {
            int newId = (i < currentInventory.length) ? currentInventory[i].getId() : -1;
            int newQty = (i < currentInventory.length) ? currentInventory[i].getQuantity() : 0;

            int oldId = (i < previousInventory.length) ? previousInventory[i].getId() : -1;
            int oldQty = (i < previousInventory.length) ? previousInventory[i].getQuantity() : 0;

            String oldName = itemManager.getItemComposition(oldId).getName();
            String newName = itemManager.getItemComposition(newId).getName();

            if (newId == oldId && newQty == oldQty) continue;

            if (newId != oldId) {
                // player use
                if (newId == -1) {
                    if (lastMenuOptionClicked != null && lastMenuOptionClicked.equals("Drop")) {
                        gameMsg(String.format("You dropped: %s", oldName));
                    } else if (lastMenuOptionClicked != null && lastMenuOptionClicked.equals("Bury")) {
                        gameMsg(String.format("You buried: %s", oldName));
                        // TODO: probably better to have a map of various consume options
                    } else if (lastMenuOptionClicked != null && lastMenuOptionClicked.equals("Drink")) {
                        gameMsg(String.format("You drank: %s", oldName));
                    }
                }
                // other stuff
                else if (oldId != -1 && !isBanking) {
                    if (client.getLocalPlayer().getAnimation() == -1) {
                        gameMsg(String.format("swapped %s and %s", oldName, newName));
                        previousInventory = currentInventory.clone();
                        continue;
                    }
                    gameMsg(String.format("Loss (Slot %d): %s x%d", i + 1, oldName, oldQty));
                } else if (oldId != -1) {
                    gameMsg(String.format("Deposit (Slot %d): %s x%d", i + 1, oldName, oldQty));
                }

                // TODO: figure out how to check for moving an item to an empty slot
                if (newId != -1 && !isBanking) {
                    handleGatheringGains(newId, newQty);
                } else if (newId != -1) {
                    gameMsg(String.format("Withdrawal (Slot %d): %s x%d", i + 1, newName, newQty));
                }
            } else {
                int diff = newQty - oldQty;
                if (!isBanking) {
                    if (diff > 0) {
                        gameMsg(String.format("Gain (Slot %d): %s x%d", i + 1, newName, diff));
                    } else {
                        // TODO: add logic that checks if the loss is due to something like fishing (bait)
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
}