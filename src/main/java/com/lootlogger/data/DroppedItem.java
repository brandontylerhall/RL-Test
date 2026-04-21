package com.lootlogger.data;

import lombok.Getter;

@Getter
public class DroppedItem {
    private final int id;
    private final String name;
    private final int qty;
    private final int GE;
    private final int HA;

    public DroppedItem(int id, String name, int qty, int GE, int HA) {
        this.id = id;
        this.name = name;
        this.qty = qty;
        this.GE = GE;
        this.HA = HA;
    }

}
