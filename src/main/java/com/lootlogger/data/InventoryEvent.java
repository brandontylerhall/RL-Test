package com.lootlogger.data;

public class InventoryEvent {
    public ActionType actionType;
    public int itemId;
    public int qty;


    public InventoryEvent(ActionType actionType, int itemId, int qty) {
        this.actionType = actionType;
        this.itemId = itemId;
        this.qty = qty;
    }
}
