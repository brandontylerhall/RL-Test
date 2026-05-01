package com.lootlogger.data;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class LootRecord {
    public String sessionId;

    @Builder.Default
    private String timestamp = Instant.now().toString();

    private String action;
    private String source;
    private String category;
    private int x;
    private int y;
    private int plane;
    private int regionId;
    private List<DroppedItem> items;
}
