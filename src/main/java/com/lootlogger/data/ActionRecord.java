package com.lootlogger.data;

import java.util.List;

public class ActionRecord {
    public final String sessionId;
    public final String action;
    public final int x;
    public final int y;
    public final int plane;
    public final List<DroppedItem> items;

    public ActionRecord(String sessionId, String action, int x, int y, int plane, List<DroppedItem> items) {
        this.sessionId = sessionId;
        this.action = action;
        this.x = x;
        this.y = y;
        this.plane = plane;
        this.items = items;
    }
}
