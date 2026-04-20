package com.lootlogger.data;

import lombok.Getter;

@Getter
public class DroppedItem {
    private final int id;
    private final int qty;
    private final int GE;
    private final int HA;

    public DroppedItem(int id, int qty, int GE, int HA) {
        this.id = id;
        this.qty = qty;
        this.GE = GE;
        this.HA = HA;
    }

}
