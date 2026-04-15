package com.example;

import lombok.Getter;

@Getter
public class DroppedItem {
    private final int id;
    private final int qty;

    public DroppedItem(int id, int qty) {
        this.id = id;
        this.qty = qty;
    }

}
