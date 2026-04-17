package com.lootlogger.data;

import java.util.List;

public class LootRecord {
    // Name of source
    public String source;

    // World coordinates
    public int x;
    public int y;
    public int plane;
    public long timestamp;

    // Items received in this loot event
    public List<DroppedItem> items;

    public LootRecord(String source, int x, int y, int plane, List<DroppedItem> items) {
        this.source = source;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.timestamp = java.time.Instant.now().getEpochSecond();;
        this.items = items;
    }
}