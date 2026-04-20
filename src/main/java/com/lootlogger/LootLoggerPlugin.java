package com.lootlogger;

import com.google.inject.Provides;
import com.lootlogger.data.ActionRecord;
import com.lootlogger.data.DroppedItem;
import com.lootlogger.data.InventoryEvent;
import com.lootlogger.data.LootRecord;
import com.lootlogger.io.LootWriter;

import com.lootlogger.util.InventoryProcessor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.config.ConfigManager;
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
    private LootLoggerConfig config;

    @Provides
    LootLoggerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LootLoggerConfig.class);
    }

    @Inject
    private ItemManager itemManager;

    @Inject
    private java.util.concurrent.ScheduledExecutorService executor;

    private String sessionId;

    private LootWriter lootWriter;

    // DEBUG VARIABLES //
    private int lastActiveAnimation = -1;
    private String lastMenuOptionClicked = "";
    private Item[] previousInventory = new Item[28];

    public void gameMsg(String msg) {
        if (!config.showChatMessages()) {
            return;
        }

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
        sessionId = java.util.UUID.randomUUID().toString();

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
            WorldPoint wp = client.getLocalPlayer().getWorldLocation();

            gameMsg("--- DEBUG STATUS ---");
            gameMsg(String.format("Current Animation: %d", currentAnim));
            gameMsg(String.format("Last Saved Animation: %d", lastActiveAnimation));
            gameMsg(String.format("Location: %d, %d, %d", wp.getX(), wp.getY(), wp.getPlane()));
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
        WorldPoint wp = npc.getWorldLocation();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), item.getQuantity()))
                .collect(java.util.stream.Collectors.toList());

        LootRecord record = new LootRecord(sessionId, sourceName, wp.getX(), wp.getY(), wp.getPlane(), items);

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

        if (config.debugMessages()) {
            gameMsg(String.format("Last animation ID: %d", lastActiveAnimation));
            gameMsg(String.format("Current animation ID: %d", client.getLocalPlayer().getAnimation()));
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
        LootRecord record = new LootRecord(sessionId, sourceName, wp.getX(), wp.getY(), wp.getPlane(), items);

        executor.execute(() -> lootWriter.writeToFile(record));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        // TODO: add container 94 logic (worn items)
        if (event.getContainerId() != 93) return;

        ItemContainer bankContainer = client.getItemContainer(95);
        boolean isBanking = (bankContainer != null);
        Item[] currentInventory = event.getItemContainer().getItems();

        List<InventoryEvent> events = InventoryProcessor.invProcess(previousInventory, currentInventory, isBanking, lastMenuOptionClicked, client.getLocalPlayer().getAnimation(), lastActiveAnimation);

        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();

            // TODO: finish adding the cases
            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    handleGatheringGains(itemId, qty);
                    gameMsg(String.format("Logged gain: %s x:%d", name, qty));
                    break;
                case BANK_WITHDRAWAL:
                    if (config.debugMessages()) {
                        gameMsg(String.format("Withdrew: %s", name));
                    }
                    break;
                case BANK_DEPOSIT:
                    if (config.debugMessages()) {
                        gameMsg(String.format("Banked: %s", name));
                    }
                    List<DroppedItem> bankList = List.of(new DroppedItem(itemId, qty));
                    ActionRecord bankRecord = new ActionRecord(sessionId, "BANK_DEPOSIT", wp.getX(), wp.getY(), wp.getPlane(), bankList);
                    executor.execute(() -> lootWriter.writeToFile(bankRecord));
                    break;
                case CONSUME:
                    if (config.debugMessages()) {
                        gameMsg(String.format("Consumed: %s", name));
                    }
                    if (config.logConsumables()) {
                        List<DroppedItem> consumeList = List.of(new DroppedItem(itemId, qty));
                        ActionRecord consumeRecord = new ActionRecord(sessionId, "CONSUME", wp.getX(), wp.getY(), wp.getPlane(), consumeList);
                        executor.execute(() -> lootWriter.writeToFile(consumeRecord));
                    }
                    break;
                case DESTROY:
                    if (config.debugMessages()) {
                        gameMsg(String.format("Destroyed: %s", name));
                    }
                    if (config.logConsumables()) {
                        List<DroppedItem> destroyList = List.of(new DroppedItem(itemId, qty));
                        ActionRecord consumeRecord = new ActionRecord(sessionId, "DESTROY", wp.getX(), wp.getY(), wp.getPlane(), destroyList);
                        executor.execute(() -> lootWriter.writeToFile(consumeRecord));
                    }
                    break;
                case TAKE:
                    if (config.debugMessages()) {
                        gameMsg(String.format("Picked up: %s", name));
                    }
                    if (config.logConsumables()) {
                        List<DroppedItem> pickupList = List.of(new DroppedItem(itemId, qty));
                        ActionRecord consumeRecord = new ActionRecord(sessionId, "TAKE", wp.getX(), wp.getY(), wp.getPlane(), pickupList);
                        executor.execute(() -> lootWriter.writeToFile(consumeRecord));
                    }
                    break;
            }
        }
        previousInventory = currentInventory.clone();
    }
}