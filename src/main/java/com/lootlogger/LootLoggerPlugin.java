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
import net.runelite.client.task.Schedule;

import javax.inject.Inject;
import java.time.temporal.ChronoUnit;
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

    // BANK TRACKING VARIABLES //
    private boolean isBankWidgetOpen = false;
    private int lastBankCloseTick = -1;
    private boolean hasSyncedBankThisSession = false;

    private final java.util.Map<Integer, Integer> droppedItemsCache = new java.util.HashMap<>();

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
            java.util.Map.entry(621, "Fishing")
    );

    // =========================
    //    START UP / SHUT DOWN
    // =========================
    @Override
    protected void startUp() throws Exception {
        sessionId = java.util.UUID.randomUUID().toString();

        lootWriter = new LootWriter();
        lootWriter.init();

        for (int i = 0; i < 28; i++) {
            previousInventory[i] = new Item(-1, 0);
        }
    }

    @Subscribe
    public void onGameStateChanged(net.runelite.api.events.GameStateChanged event) {
        if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
            hasSyncedBankThisSession = false; // reset on log out or world hop
            gameMsg("" + hasSyncedBankThisSession);
        }
    }

    @Override
    public void shutDown() throws Exception {
        if (lootWriter != null) {
            lootWriter.flush();
            lootWriter.close();
        }
    }

    @Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void submitBatch() {
        if (lootWriter != null) {
            lootWriter.flush();
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

    @Subscribe
    public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event) {
        if (event.getGroupId() == 12 ||
                event.getGroupId() == 15) {
            isBankWidgetOpen = true;
        }
    }

    @Subscribe
    public void onWidgetClosed(net.runelite.api.events.WidgetClosed event) {
        if (event.getGroupId() == 12 ||
                event.getGroupId() == 15) {
            isBankWidgetOpen = false;
            lastBankCloseTick = client.getTickCount();
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
                .map(item -> new DroppedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), (itemManager.getItemPrice(item.getId()) * item.getQuantity()), (itemManager.getItemComposition(item.getId()).getHaPrice() * item.getQuantity())))
                .collect(java.util.stream.Collectors.toList());

        LootRecord record =
                LootRecord.builder()
                        .sessionId(sessionId)
                        .source(sourceName)
                        .category("Combat")
                        .x(wp.getX())
                        .y(wp.getY())
                        .plane(wp.getPlane())
                        .regionId(wp.getRegionID())
                        .items(items)
                        .build();

        executor.execute(() -> lootWriter.queueRecord(record));
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

        List<DroppedItem> items = List.of(new DroppedItem(itemId, itemManager.getItemComposition(itemId).getName(), qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

        LootRecord record =
                LootRecord.builder()
                        .sessionId(sessionId)
                        .source(sourceName)
                        .category("Skilling")
                        .x(wp.getX())
                        .y(wp.getY())
                        .plane(wp.getPlane())
                        .regionId(wp.getRegionID())
                        .items(items)
                        .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        if (event.getContainerId() == 95 && !hasSyncedBankThisSession) {
            Item[] bankItems = event.getItemContainer().getItems();

            List<DroppedItem> snapshotItems = new java.util.ArrayList<>();
            for (Item item : bankItems) {
                if (item.getId() <= 0) continue;

                int itemId = item.getId();
                int qty = item.getQuantity();
                String name = itemManager.getItemComposition(itemId).getName();

                snapshotItems.add(new DroppedItem(
                        itemId, name, qty,
                        (itemManager.getItemPrice(itemId) * qty),
                        (itemManager.getItemComposition(itemId).getHaPrice() * qty)
                ));
            }

            executor.execute(() -> {
                ActionRecord snapshotRecord = ActionRecord.builder()
                        .sessionId(sessionId)
                        .action("BANK_SNAPSHOT")
                        .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                        .items(snapshotItems).build();

                lootWriter.queueRecord(snapshotRecord);
            });

            hasSyncedBankThisSession = true;
            gameMsg("LootLogger: Initial Bank Snapshot synced.");
        }

        if (event.getContainerId() != 93) return;

        boolean isBanking = isBankWidgetOpen;

        if (!isBanking && (client.getTickCount() - lastBankCloseTick <= 2)) {
            isBanking = true;
        }

        Item[] currentInventory = event.getItemContainer().getItems();

        List<InventoryEvent> events = InventoryProcessor.invProcess(previousInventory, currentInventory, isBanking, lastMenuOptionClicked, client.getLocalPlayer().getAnimation(), lastActiveAnimation);

        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();

            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    handleGatheringGains(itemId, qty);
                    break;
                case BANK_DEPOSIT:
                    List<DroppedItem> bankList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

                    ActionRecord bankRecord = ActionRecord.builder()
                            .sessionId(sessionId).action("BANK_DEPOSIT")
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(bankList).build();

                    executor.execute(() -> lootWriter.queueRecord(bankRecord));
                    break;
                case BANK_WITHDRAWAL:
                    List<DroppedItem> withdrawList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

                    ActionRecord withdrawRecord = ActionRecord.builder()
                            .sessionId(sessionId).action("BANK_WITHDRAWAL")
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(withdrawList).build();

                    executor.execute(() -> lootWriter.queueRecord(withdrawRecord));
                    break;
                case CONSUME:
                    if (config.logConsumables()) {
                        List<DroppedItem> consumeList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

                        ActionRecord consumeRecord = ActionRecord.builder()
                                .sessionId(sessionId).action("CONSUME")
                                .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                                .items(consumeList).build();

                        executor.execute(() -> lootWriter.queueRecord(consumeRecord));
                    }
                    break;
                case TAKE:
                    // check cache to see if we dropped it ourselves
                    int previouslyDroppedQty = droppedItemsCache.getOrDefault(itemId, 0);

                    if (previouslyDroppedQty > 0) {
                        // picked up own dropped item. do not log it.
                        int remainingQty = previouslyDroppedQty - qty;
                        if (remainingQty <= 0) {
                            droppedItemsCache.remove(itemId);
                        } else {
                            droppedItemsCache.put(itemId, remainingQty);
                        }

                        gameMsg("Ignored pickup of our own dropped item: " + name);
                        break;
                    }

                    // it wasn't in the cache
                    List<DroppedItem> pickupList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

                    ActionRecord pickupRecord = ActionRecord.builder()
                            .sessionId(sessionId)
                            .action("PICKUP")
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(pickupList).build();

                    executor.execute(() -> lootWriter.queueRecord(pickupRecord));
                    break;
                case DROP:
                    droppedItemsCache.put(itemId, droppedItemsCache.getOrDefault(itemId, 0) + qty);

                    List<DroppedItem> dropList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));
                    ActionRecord dropRecord = ActionRecord.builder()
                            .sessionId(sessionId).action("DROP")
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(dropList).build();
                    executor.execute(() -> lootWriter.queueRecord(dropRecord));
                    break;
            }
        }

        previousInventory = currentInventory.clone();
    }
}