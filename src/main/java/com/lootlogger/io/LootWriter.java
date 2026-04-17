package com.lootlogger.io;

import com.google.gson.Gson;
import com.lootlogger.data.LootRecord;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Slf4j
public class LootWriter {
    private final Gson compactGson = new Gson();
    private FileWriter writer;

    public void startUp() throws IOException {
        File logFile = new File("loot_log.jsonl");
        writer = new FileWriter(logFile, true);
    }

    public void writeToFile(LootRecord record) {
        try {
            synchronized (writer) {
                writer.write(compactGson.toJson(record) + "\n");
                writer.flush();
            }
        } catch (IOException e) {
            log.error("Error writing to file", e);
        }
    }

    public void shutDown() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }
}