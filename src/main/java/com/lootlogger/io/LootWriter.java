package com.lootlogger.io;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

@Slf4j
public class LootWriter {
    private final Gson compactGson = new Gson();
    private FileWriter writer;
    private String supabaseUrl;
    private String supabaseKey;

    public void init() throws IOException {
        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream("gradle.properties")) {
            prop.load(fis);

            supabaseUrl = prop.getProperty("SUPABASE_URL");
            supabaseKey = prop.getProperty("SUPABASE_KEY");

        } catch (IOException e) {
            System.out.println("WARNING: Could not load gradle.properties!");
            e.printStackTrace();
        }

        File logFile = new File("loot_log.jsonl");
        writer = new FileWriter(logFile, true);
    }

    public void writeToFile(Object record) {
        // turns POJO into a JSON string
        String jsonString = compactGson.toJson(record);

        // wraps it so supabase knows which column to put it in
        // This makes the JSON look like: {"log_data": { "action": "CONSUME", "x": 3000 ... }}
        String supabasePayload = String.format("{\"log_data\": %s}", jsonString);

        try {
            HttpClient client = HttpClient.newHttpClient();

            // supabase REST URLs look like this
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(supabaseUrl + "/rest/v1/loot_logs"))
                    .header("Content-Type", "application/json")
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Prefer", "return=minimal")
                    .POST(HttpRequest.BodyPublishers.ofString(supabasePayload))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 200 && response.statusCode() < 300) {
                            System.out.println("Successfully sent to Supabase!");
                        } else {
                            System.out.println("Supabase Error: " + response.statusCode() + " - " + response.body());
                        }
                    });

        } catch (Exception e) {
            System.out.println("Failed to send HTTP request!");
            e.printStackTrace();
        }
    }

    public void close() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }
}