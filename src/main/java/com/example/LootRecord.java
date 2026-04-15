package com.example;

import net.runelite.client.game.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class LootRecord {
    // Name of source
    public String source;

    // World coordinates
    public int x;
    public int y;
    public int plane;

    // Items received in this loot event
    public List<DroppedItem> items;

    public LootRecord(String source, int x, int y, int plane, List<DroppedItem> items) {
        this.source = source;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.items = items;
    }
}