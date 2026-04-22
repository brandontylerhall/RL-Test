package com.lootlogger.data;

public class InventoryEvent {
    public String targetName;
    public ActionType actionType;
    public int itemId;
    public int qty;

    public InventoryEvent(int itemId, int qty, ActionType actionType, String targetName) {
        this.itemId = itemId;
        this.qty = qty;
        this.actionType = actionType;
        this.targetName = targetName;
    }
}
