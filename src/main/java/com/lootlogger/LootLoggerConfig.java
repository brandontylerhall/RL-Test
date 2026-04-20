package com.lootlogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

// The string inside ConfigGroup is the internal ID for your settings
@ConfigGroup("lootlogger")
public interface LootLoggerConfig extends Config {

	// --- ITEM 1: A simple toggle (Boolean) ---
	@ConfigItem(
			keyName = "showChatMessages",
			name = "Show Chat Messages",
			description = "Toggle whether the plugin spams your chat box with logs.",
			position = 1
	)
	default boolean showChatMessages() {
		return true; // This is the default state when they first install it
	}

	// --- ITEM 2: Another toggle for filtering ---
	@ConfigItem(
			keyName = "logConsumables",
			name = "Log Consumed Items",
			description = "Include eaten food and drank potions in the JSON file.",
			position = 2
	)
	default boolean logConsumables() {
		return false;
	}

	@ConfigItem(
			keyName = "debugMessages",
			name = "Debug Messages",
			description = "Shows various information.",
			position = 3
	)
	default boolean debugMessages() {
		return true;
	}
}