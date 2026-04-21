package com.lootlogger.data;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ActionRecord {
    private String sessionId;

    @Builder.Default
    private String timestamp = Instant.now().toString();

    private String action;
    private int x;
    private int y;
    private int plane;
    private int regionId;
    private List<DroppedItem> items;
}