package com.asbestosstar.grandstrategy.common.data;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles localization for multiple languages. Following British English
 * standards.
 */
public class LocalizationManager {
	private static final Map<String, Map<String, String>> TRANSLATIONS = new HashMap<>();
	private static String currentLanguage = "en_gb";

	static {
		// Initialize English (GB)
		Map<String, String> enGb = new HashMap<>();
		enGb.put("gui.grandstrategy.title", "Grand Strategy Management");
		enGb.put("gui.grandstrategy.civilisation", "Civilisation");
		enGb.put("gui.grandstrategy.leader", "Leader");
		enGb.put("gui.grandstrategy.providence", "Providence");
		TRANSLATIONS.put("en_gb", enGb);

		// Initialize English (US)
		Map<String, String> enUs = new HashMap<>();
		enUs.put("gui.grandstrategy.title", "Grand Strategy Management");
		enUs.put("gui.grandstrategy.civilisation", "Civilization"); // Though AGENTS.MD says use GB standards in code
		enUs.put("gui.grandstrategy.leader", "Leader");
		enUs.put("gui.grandstrategy.providence", "Province"); // US variant
		TRANSLATIONS.put("en_us", enUs);

		// Initialize Spanish (MX) - Using British English equivalent standards where
		// requested
		Map<String, String> esMx = new HashMap<>();
		esMx.put("gui.grandstrategy.title", "Gestión de Gran Estrategia");
		esMx.put("gui.grandstrategy.civilisation", "Civilización");
		esMx.put("gui.grandstrategy.leader", "Líder");
		esMx.put("gui.grandstrategy.providence", "Providencia");
		TRANSLATIONS.put("es_mx", esMx);
	}

	public static String translate(String key) {
		return TRANSLATIONS.getOrDefault(currentLanguage, TRANSLATIONS.get("en_gb")).getOrDefault(key, key);
	}

	public static void setLanguage(String lang) {
		currentLanguage = lang.toLowerCase();
	}
}
