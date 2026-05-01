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

    // DEBUG & UI VARIABLES //
    private int lastActiveAnimation = -1;
    private int lastAnimationTick = -1;
    private String lastMenuOptionClicked = "";
    private String lastMenuTargetClicked = "None";
    private Item[] previousInventory = new Item[28];

    // FISHING STATE TRACKING
    private int lastFishingTick = -1;

    // BANK TRACKING VARIABLES //
    private boolean isBankWidgetOpen = false;
    private int lastBankCloseTick = -1;
    private boolean hasSyncedBankThisSession = false;

    // COMBAT & XP TRACKING VARIABLES //
    private final java.util.Map<Integer, Integer> droppedItemsCache = new java.util.HashMap<>();
    private static final java.util.Set<String> COMBAT_SKILLS = java.util.Set.of("Attack", "Strength", "Defence", "Ranged", "Magic", "Hitpoints", "Prayer");
    private final java.util.Map<Skill, Integer> currentXpMap = new java.util.EnumMap<>(Skill.class);
    private final java.util.Map<Skill, Integer> lastXpTickMap = new java.util.EnumMap<>(Skill.class);
    private String currentCombatTarget = "None";
    private int lastCombatTick = -1;

    public void gameMsg(String msg) {
        if (!config.showChatMessages()) return;
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
    }

    private static final java.util.Map<Integer, String> SOURCE_MAP = java.util.Map.ofEntries(
            // Woodcutting
            java.util.Map.entry(879, "Woodcutting"),
            java.util.Map.entry(877, "Woodcutting"),
            java.util.Map.entry(875, "Woodcutting"),
            java.util.Map.entry(873, "Woodcutting"),
            java.util.Map.entry(871, "Woodcutting"),
            java.util.Map.entry(869, "Woodcutting"),
            java.util.Map.entry(2846, "Woodcutting"),

            // Mining
            java.util.Map.entry(625, "Mining"),
            java.util.Map.entry(626, "Mining"),
            java.util.Map.entry(627, "Mining"),
            java.util.Map.entry(628, "Mining"),
            java.util.Map.entry(629, "Mining"),
            java.util.Map.entry(624, "Mining"),
            java.util.Map.entry(7139, "Mining"),
            java.util.Map.entry(6752, "Mining"),
            java.util.Map.entry(6753, "Mining"),

            // Fishing - Improved
            java.util.Map.entry(621, "Fishing"),  // Net fishing
            java.util.Map.entry(618, "Fishing"),  // Harpoon
            java.util.Map.entry(619, "Fishing"),  // Lobster pot / Cage  ← Added this
            java.util.Map.entry(622, "Fishing"),  // Fly fishing
            java.util.Map.entry(620, "Fishing"),  // Rod fishing
            java.util.Map.entry(623, "Fishing"),  // Barbarian rod

            // Cooking
            java.util.Map.entry(896, "Cooking"),
            java.util.Map.entry(897, "Cooking"),
            java.util.Map.entry(883, "Cooking"),
            java.util.Map.entry(3705, "Cooking")
    );

    @Override
    protected void startUp() throws Exception {
        lootWriter = new LootWriter();
        lootWriter.init();
        for (int i = 0; i < 28; i++) previousInventory[i] = new Item(-1, 0);
    }

    @Subscribe
    public void onGameStateChanged(net.runelite.api.events.GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGING_IN || event.getGameState() == GameState.HOPPING) {
            hasSyncedBankThisSession = false;
        }

        if (event.getGameState() == GameState.LOGGED_IN) {
            if (sessionId == null) {
                sessionId = java.util.UUID.randomUUID().toString();
                ActionRecord startRecord = ActionRecord.builder()
                        .sessionId(sessionId).action("SESSION_START")
                        .x(0).y(0).plane(0).regionId(0)
                        .items(List.of()).build();
                executor.execute(() -> lootWriter.queueRecord(startRecord));
            }
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            if (sessionId != null) {
                ActionRecord endRecord = ActionRecord.builder()
                        .sessionId(sessionId).action("SESSION_END")
                        .x(0).y(0).plane(0).regionId(0)
                        .items(List.of()).build();

                // Use lootWriter directly (not executor) to ensure it's queued before thread dies
                lootWriter.queueRecord(endRecord);
                sessionId = null;
            }
        }
    }

    @Override
    public void shutDown() throws Exception {
        if (sessionId != null) {
            ActionRecord endRecord = ActionRecord.builder()
                    .sessionId(sessionId).action("SESSION_END")
                    .x(0).y(0).plane(0).regionId(0)
                    .items(List.of()).build();

            // FORCE write, no executor
            lootWriter.queueRecord(endRecord);
            sessionId = null;
        }

        if (lootWriter != null) {
            // Flush makes the actual HTTP call to Supabase
            lootWriter.flush();
            lootWriter.close();
        }
    }

    @Schedule(period = 5, unit = ChronoUnit.SECONDS, asynchronous = true)
    public void submitBatch() {
        if (lootWriter != null) lootWriter.flush();
    }

    @Subscribe
    public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event) {
        // 12 = Bank, 15 = Bank Pin, 192 = Deposit Box
        if (event.getGroupId() == 12 || event.getGroupId() == 15 || event.getGroupId() == 192) {
            isBankWidgetOpen = true;
        }
    }

    @Subscribe
    public void onWidgetClosed(net.runelite.api.events.WidgetClosed event) {
        if (event.getGroupId() == 12 || event.getGroupId() == 15 || event.getGroupId() == 192) {
            isBankWidgetOpen = false;
            lastBankCloseTick = client.getTickCount();
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted event) {
        if (event.getCommand().equals("status")) {
            int currentAnim = client.getLocalPlayer().getAnimation();
            WorldPoint wp = client.getLocalPlayer().getWorldLocation();

            // Context Check
            boolean inCombat = (client.getTickCount() - lastCombatTick <= 10);
            int lastAnimDiff = client.getTickCount() - lastAnimationTick;

            gameMsg("--- LOOTLOGGER DEBUG ---");
            gameMsg(String.format("Location: %d, %d, %d (Region: %d)", wp.getX(), wp.getY(), wp.getPlane(), wp.getRegionID()));
            gameMsg(String.format("Current Anim: %d | Last Saved: %d (%d ticks ago)", currentAnim, lastActiveAnimation, lastAnimDiff));
            gameMsg(String.format("Target: %s | In Combat: %b", currentCombatTarget, inCombat));
            gameMsg(String.format("Last Menu: %s", lastMenuOptionClicked));
            gameMsg(String.format("Session ID: %s", sessionId != null ? "ACTIVE" : "NULL"));
        }
    }

    @Subscribe
    public void onInteractingChanged(net.runelite.api.events.InteractingChanged event) {
        if (event.getSource() == client.getLocalPlayer()) {
            Actor target = event.getTarget();
            if (target instanceof NPC) {
                currentCombatTarget = ((NPC) target).getName();
                lastCombatTick = client.getTickCount();
            }
        }
    }

    @Subscribe
    public void onStatChanged(net.runelite.api.events.StatChanged event) {
        Skill skill = event.getSkill();
        int currentXp = event.getXp();

        Integer previousXp = currentXpMap.get(skill);

        currentXpMap.put(skill, currentXp);

        if (previousXp != null && previousXp > 0 && currentXp > previousXp) {
            int xpGained = currentXp - previousXp;

            if (xpGained > 500000) return;

            lastXpTickMap.put(skill, client.getTickCount());

            if (client.getLocalPlayer() != null && sessionId != null) {
                WorldPoint wp = client.getLocalPlayer().getWorldLocation();

                String skillName = skill.getName();
                boolean isCombat = COMBAT_SKILLS.contains(skillName);
                boolean inCombatState = (client.getTickCount() - lastCombatTick <= 10);

                String targetToLog = inCombatState ? currentCombatTarget : "None";
                String category = isCombat ? "Combat" : "Skilling";
                String source = isCombat ? targetToLog : skillName;

                List<DroppedItem> xpList = List.of(new DroppedItem(-1, skillName, xpGained, 0, 0));

                ActionRecord xpRecord = ActionRecord.builder()
                        .sessionId(sessionId)
                        .action("XP_GAIN")
                        .category(category)
                        .source(source)
                        .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                        .items(xpList)
                        .build();

                executor.execute(() -> lootWriter.queueRecord(xpRecord));
            }
        }
    }

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event) {
        if (sessionId == null) return;
        NPC npc = event.getNpc();
        String sourceName = npc.getName();
        WorldPoint wp = npc.getWorldLocation();

        List<DroppedItem> items = event.getItems().stream()
                .map(item -> new DroppedItem(item.getId(), itemManager.getItemComposition(item.getId()).getName(), item.getQuantity(), (itemManager.getItemPrice(item.getId()) * item.getQuantity()), (itemManager.getItemComposition(item.getId()).getHaPrice() * item.getQuantity())))
                .collect(java.util.stream.Collectors.toList());

        LootRecord record = LootRecord.builder()
                .sessionId(sessionId)
                .action("NPC_DROP")
                .source(sourceName)
                .category("Combat")
                .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                .items(items).build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event) {
        if (event.getActor() != client.getLocalPlayer()) return;

        int animId = client.getLocalPlayer().getAnimation();

        if (animId != -1) {
            lastActiveAnimation = animId;
            lastAnimationTick = client.getTickCount();
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        lastMenuOptionClicked = event.getMenuOption();
        lastMenuTargetClicked = net.runelite.client.util.Text.removeTags(event.getMenuTarget());
    }

    private void handleGatheringGains(int itemId, int qty, String sourceName) {
        if (sessionId == null) return;

        int currentAnim = client.getLocalPlayer() != null ? client.getLocalPlayer().getAnimation() : -1;

        boolean isFishingAnim = currentAnim == 619 || currentAnim == 618 || currentAnim == 621 ||
                lastActiveAnimation == 619 || lastActiveAnimation == 618 || lastActiveAnimation == 621;

        String itemName = itemManager.getItemComposition(itemId).getName().toLowerCase();

        // CRITICAL: Do NOT log cooked or burnt food as GATHER_GAIN from Fishing
        if (itemName.contains("burnt") ||
                (itemName.contains("lobster") && !itemName.contains("raw")) ||
                (itemName.contains("shrimp") && !itemName.contains("raw")) ||
                (itemName.contains("trout") && !itemName.contains("raw")) ||
                (itemName.contains("salmon") && !itemName.contains("raw"))) {
            return;
        }

        String cleanItemName = itemManager.getItemComposition(itemId).getName();

        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        List<DroppedItem> items = List.of(new DroppedItem(itemId, cleanItemName, qty, 0, 0));

        // Ask the SOURCE_MAP what skill matches our current animation.
        // If it doesn't know, default it to "Unknown/Pickup"
        String resolvedSource = SOURCE_MAP.getOrDefault(lastActiveAnimation, "Unknown/Pickup");

        LootRecord record = LootRecord.builder()
                .sessionId(sessionId)
                .action("GATHER_GAIN")
                .source(sourceName)
                .source(resolvedSource)
                .category("Skilling")
                .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                .items(items)
                .build();

        executor.execute(() -> lootWriter.queueRecord(record));
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (sessionId == null) return;

        WorldPoint wp = client.getLocalPlayer().getWorldLocation();

        // ==========================================
        //        1. BANK SNAPSHOT LOGIC
        // ==========================================
        if (event.getContainerId() == 95 && !hasSyncedBankThisSession) {
            ItemContainer bankContainer = event.getItemContainer();
            if (bankContainer != null) {
                Item[] bankItems = bankContainer.getItems();
                List<DroppedItem> snapshotItems = new java.util.ArrayList<>();
                for (Item item : bankItems) {
                    if (item != null && item.getId() > 0) {
                        int itemId = item.getId();
                        int qty = item.getQuantity();
                        String name = itemManager.getItemComposition(itemId).getName();
                        snapshotItems.add(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), (itemManager.getItemComposition(itemId).getHaPrice() * qty)));
                    }
                }

                executor.execute(() -> {
                    ActionRecord snapshotRecord = ActionRecord.builder()
                            .sessionId(sessionId).action("BANK_SNAPSHOT")
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(snapshotItems).build();
                    lootWriter.queueRecord(snapshotRecord);
                });
                hasSyncedBankThisSession = true;
                gameMsg("LootLogger: Initial Bank Snapshot synced.");
            }
        }

        // ==========================================
        //        2. INVENTORY CHANGE LOGIC
        // ==========================================
        if (event.getContainerId() != 93) return; // Exit if not Inventory

        ItemContainer inventoryContainer = event.getItemContainer();
        if (inventoryContainer == null) return;

        // Banking state detection
        boolean isBanking = isBankWidgetOpen;
        if (!isBanking && (client.getTickCount() - lastBankCloseTick <= 5)) isBanking = true;

        Item[] currentInventory = inventoryContainer.getItems();

        // Combat & Magic Context
        int lastMagicTick = lastXpTickMap.getOrDefault(Skill.MAGIC, -100);
        int lastRangedTick = lastXpTickMap.getOrDefault(Skill.RANGED, -100);

        boolean justCastSpell = (client.getTickCount() - lastMagicTick <= 2);
        boolean justFiredRanged = (client.getTickCount() - lastRangedTick <= 2);

        boolean inCombat = (client.getTickCount() - lastCombatTick <= 10);
        String combatTarget = inCombat ? currentCombatTarget : "None";

        // Call Processor (Dynamic array lengths handled inside via Maps)
        List<InventoryEvent> events = InventoryProcessor.invProcess(
                previousInventory,
                currentInventory,
                isBanking,
                lastMenuOptionClicked,
                lastMenuTargetClicked,
                client.getLocalPlayer().getAnimation(),
                lastActiveAnimation,
                justCastSpell,
                justFiredRanged,
                combatTarget,
                itemManager
        );

        // Handle resulting events
        for (InventoryEvent invEvent : events) {
            int itemId = invEvent.itemId;
            int qty = invEvent.qty;
            String name = itemManager.getItemComposition(itemId).getName();
            List<DroppedItem> genericItemList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

            switch (invEvent.actionType) {
                case GATHER_GAIN:
                    handleGatheringGains(itemId, qty, invEvent.targetName);
                    break;
                case BANK_DEPOSIT:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("BANK_DEPOSIT").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case BANK_WITHDRAWAL:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("BANK_WITHDRAWAL").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case CONSUME:
                    if (config.logConsumables()) {
                        executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("CONSUME").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    }
                    break;
                case TAKE:
                    int previouslyDroppedQty = droppedItemsCache.getOrDefault(itemId, 0);
                    if (previouslyDroppedQty > 0) {
                        int remainingQty = previouslyDroppedQty - qty;
                        if (remainingQty <= 0) droppedItemsCache.remove(itemId);
                        else droppedItemsCache.put(itemId, remainingQty);
                        break;
                    }
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("PICKUP").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case DROP:
                    droppedItemsCache.put(itemId, droppedItemsCache.getOrDefault(itemId, 0) + qty);
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("DROP").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case RANGED_FIRE:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("RANGED_FIRE").source(invEvent.targetName).x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case SPELL_CAST:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("SPELL_CAST").source(invEvent.targetName).x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case COMBAT_CONSUME:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("COMBAT_CONSUME").source(invEvent.targetName).x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case DESTROY:
                    executor.execute(() -> lootWriter.queueRecord(ActionRecord.builder().sessionId(sessionId).action("DESTROY").x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID()).items(genericItemList).build()));
                    break;
                case SKILLING_CONSUME:
                    List<DroppedItem> skillingConsumeList = List.of(new DroppedItem(itemId, name, qty, (itemManager.getItemPrice(itemId) * qty), itemManager.getItemComposition(itemId).getHaPrice() * qty));

                    ActionRecord skillingConsumeRecord = ActionRecord.builder()
                            .sessionId(sessionId).action("SKILLING_CONSUME")
                            .category("Skilling")
                            .source(invEvent.targetName)
                            .x(wp.getX()).y(wp.getY()).plane(wp.getPlane()).regionId(wp.getRegionID())
                            .items(skillingConsumeList).build();

                    executor.execute(() -> lootWriter.queueRecord(skillingConsumeRecord));
                    break;
            }
        }

        // Final Fix: Clone current inventory to previousInventory to keep array sizes in sync
        previousInventory = currentInventory.clone();
    }
}