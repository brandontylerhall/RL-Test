package com.lootlogger.util;

import com.lootlogger.data.ActionType;
import com.lootlogger.data.InventoryEvent;
import net.runelite.api.Item;

import java.util.ArrayList;
import java.util.List;

public class InventoryProcessor {
    private static final java.util.Set<String> CONSUME_OPTIONS = java.util.Set.of(
            "Eat", "Drink", "Bury", "Scatter", "Break", "Read", "Empty"
    );

    private static final java.util.Set<String> DESTROY_OPTIONS = java.util.Set.of(
            "Destroy", "Drop"
    );

    public static List<InventoryEvent> invProcess(
            Item[] previousInventory,
            Item[] currentInventory,
            boolean isBanking,
            String lastMenuOptionClicked,
            int currentAnimation
    ) {
        List<InventoryEvent> events = new ArrayList<>();

        for (int i = 0; i < 28; i++) {
            int newId = (i < currentInventory.length) ? currentInventory[i].getId() : -1;
            int newQty = (i < currentInventory.length) ? currentInventory[i].getQuantity() : 0;

            int oldId = (i < previousInventory.length) ? previousInventory[i].getId() : -1;
            int oldQty = (i < previousInventory.length) ? previousInventory[i].getQuantity() : 0;

            if (newId == oldId && newQty == oldQty) continue;

            if (newId != oldId) {
                // ==========================================
                //         Did an item LEAVE this slot?
                // ==========================================
                if (oldId != -1) {
                    if (isBanking) {
                        events.add(new InventoryEvent(ActionType.BANK_DEPOSIT, oldId, oldQty));
                    } else if (CONSUME_OPTIONS.contains(lastMenuOptionClicked)) {
                        events.add(new InventoryEvent(ActionType.CONSUME, oldId, oldQty));
                    } else if (DESTROY_OPTIONS.contains(lastMenuOptionClicked)) {
                        events.add(new InventoryEvent(ActionType.DESTROY, oldId, oldQty));
                    } else if (currentAnimation == -1) {
                        // TODO: handle equips
                        events.add(new InventoryEvent(ActionType.SWAP, oldId, oldQty));
                    } else {
                        events.add(new InventoryEvent(ActionType.CONSUME, oldId, oldQty)); // Catch-all loss
                    }
                }
                // ==========================================
                //        Did an item ENTER this slot?
                // ==========================================
                if (newId != -1) {
                    if (isBanking) {
                        events.add(new InventoryEvent(ActionType.BANK_WITHDRAWAL, newId, newQty));
                    } else if (currentAnimation == -1 && lastMenuOptionClicked.equals("Take")) {
                        events.add(new InventoryEvent(ActionType.TAKE, newId,  newQty));
                    } else {
                        // It wasn't a swap, and we aren't banking. We gained it!
                        events.add(new InventoryEvent(ActionType.GATHER_GAIN, newId, newQty));
                    }
                }
            } else {
                // case 2: Same item ID, but quantity changed (stackables like runes/arrows)
                int diff = newQty - oldQty;

                if (!isBanking) {
                    if (diff > 0) {
                        events.add(new InventoryEvent(ActionType.GATHER_GAIN, newId, newQty));
                    } else {
                        // TODO: add logic that checks if the loss is due to something like fishing (bait)
                        events.add(new InventoryEvent(ActionType.CONSUME, oldId, oldQty)); // TODO: figure out more appropriate action
                    }
                } else {
                    if (diff > 0) {
                        events.add(new InventoryEvent(ActionType.BANK_WITHDRAWAL, newId, newQty));
                    } else {
                        events.add(new InventoryEvent(ActionType.BANK_DEPOSIT, oldId, oldQty));
                    }
                }
            }
        }
        return events;
    }
}
