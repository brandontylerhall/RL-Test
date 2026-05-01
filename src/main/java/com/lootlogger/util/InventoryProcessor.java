package com.lootlogger.util;

import com.lootlogger.data.ActionType;
import com.lootlogger.data.InventoryEvent;
import net.runelite.api.Item;
import net.runelite.client.game.ItemManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InventoryProcessor {
    private static final java.util.Set<String> CONSUME_OPTIONS = java.util.Set.of(
            "Eat", "Drink", "Bury", "Scatter", "Break", "Read", "Empty"
    );

    private static final java.util.Set<String> DESTROY_OPTIONS = java.util.Set.of("Destroy");
    private static final java.util.Set<Integer> FIREMAKE_OPTIONS = java.util.Set.of(733, 10572);

    public static List<InventoryEvent> invProcess(
            Item[] previousInventory,
            Item[] currentInventory,
            boolean isBanking,
            String lastMenuOptionClicked,
            String lastMenuTargetClicked,
            int currentAnimation,
            int lastAnimation,
            boolean justCastSpell,
            boolean justFiredRanged,
            String combatTarget,
            ItemManager itemManager
    ) {
        List<InventoryEvent> events = new ArrayList<>();

        Map<Integer, Integer> prevCounts = getCounts(previousInventory);
        Map<Integer, Integer> currCounts = getCounts(currentInventory);

        // ==========================================
        //       PROCESS LOSSES (Items that left)
        // ==========================================
        for (Integer itemId : prevCounts.keySet()) {
            int oldQty = prevCounts.get(itemId);
            int newQty = currCounts.getOrDefault(itemId, 0);

            if (newQty < oldQty) {
                int qtyLost = oldQty - newQty;
                String itemNameLower = itemManager.getItemComposition(itemId).getName().toLowerCase();

                boolean isRune = itemNameLower.contains("rune");
                boolean isAmmo = itemNameLower.matches("(?i).*\\b(arrow|arrows|bolt|bolts|dart|darts|javelin|javelins)\\b.*");
                boolean inCombat = !combatTarget.equals("None");

                if (isBanking) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.BANK_DEPOSIT, "Bank"));
                }
                else if (DESTROY_OPTIONS.contains(lastMenuOptionClicked)) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.DESTROY, "None"));
                }
                else if ("Drop".equals(lastMenuOptionClicked)) {
                    // Strong cooking detection for raw ingredients
                    if (itemNameLower.contains("raw ") &&
                            (itemNameLower.contains("lobster") || itemNameLower.contains("shrimp") ||
                                    itemNameLower.contains("trout") || itemNameLower.contains("salmon") ||
                                    itemNameLower.contains("tuna") || itemNameLower.contains("swordfish"))) {

                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.SKILLING_CONSUME, "Cooking"));
                    }
                    else {
                        events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                    }
                }
                else if (isRune && justCastSpell) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.SPELL_CAST, combatTarget));
                }
                else if (isAmmo && justFiredRanged) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.RANGED_FIRE, combatTarget));
                }
                else if (inCombat && (CONSUME_OPTIONS.contains(lastMenuOptionClicked) || currentAnimation != -1)) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.COMBAT_CONSUME, combatTarget));
                }
                else if (FIREMAKE_OPTIONS.contains(lastAnimation)) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.SKILLING_CONSUME, "Firemaking"));
                }
                else if (CONSUME_OPTIONS.contains(lastMenuOptionClicked)) {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.CONSUME, "None"));
                }
                else {
                    events.add(new InventoryEvent(itemId, qtyLost, ActionType.DROP, "None"));
                }
            }
        }

        // ==========================================
        //       PROCESS GAINS (Items that entered)
        // ==========================================
        for (Integer itemId : currCounts.keySet()) {
            int newQty = currCounts.get(itemId);
            int oldQty = prevCounts.getOrDefault(itemId, 0);

            if (newQty > oldQty) {
                int qtyGained = newQty - oldQty;

                if (isBanking) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.BANK_WITHDRAWAL, "Bank"));
                }
                // Strong Fishing detection
                else if (lastAnimation == 619 || lastAnimation == 618 || lastAnimation == 621 ||
                        currentAnimation == 619 || currentAnimation == 618 || currentAnimation == 621) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, "None"));
                }
                // Default skilling gain
                else if (lastMenuOptionClicked == null ||
                        (!"Take".equals(lastMenuOptionClicked) && !"Drop".equals(lastMenuOptionClicked))) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, lastMenuTargetClicked));
                }
                else if ("Take".equals(lastMenuOptionClicked)) {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.TAKE, "None"));
                }
                else {
                    events.add(new InventoryEvent(itemId, qtyGained, ActionType.GATHER_GAIN, "None"));
                }
            }
        }

        return events;
    }

    private static Map<Integer, Integer> getCounts(Item[] inv) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (inv == null) return counts;
        for (Item item : inv) {
            if (item != null && item.getId() > 0) {
                counts.put(item.getId(), counts.getOrDefault(item.getId(), 0) + item.getQuantity());
            }
        }
        return counts;
    }
}