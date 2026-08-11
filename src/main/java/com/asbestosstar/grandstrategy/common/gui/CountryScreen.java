package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.NationalSpirit;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.data.VillagerJob;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Detail view opened by clicking a country marker on the map. */
public final class CountryScreen extends StrategyScreen {
	private final String civilisationId;

	public CountryScreen(Screen parent, String civilisationId) {
		super("Country", parent);
		this.civilisationId = civilisationId;
	}

	@Override
	protected void init() {
		beginIconLayout();
		addIconButton(UiIcon.BACK, "Back to map", 6, 6, button -> this.minecraft.setScreen(getParentScreen()));
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Civilisation civilisation = StrategyClientContext.getCivilisation(civilisationId);
		if (civilisation == null) {
			graphics.text(this.font, "Country is no longer available.", 18, 42, BAD_TEXT, true);
			return;
		}

		int y = 42;
		graphics.text(this.font, civilisation.getName() + " [" + civilisation.getId() + "]", 18, y, TEXT, true);
		y += 18;
		graphics.text(this.font,
				"Status: " + (civilisation.isActive() ? "Active" : "Potential") + " | Start: "
						+ formatYear(civilisation.getStartYear()) + " | Stability: "
						+ (int) (civilisation.getStability() * 100.0) + "%",
				18, y, civilisation.isActive() ? GOOD_TEXT : MUTED_TEXT, true);
		y += 16;
		graphics.text(this.font,
				"Government: " + civilisation.getIdeology() + " | Religion: " + civilisation.getReligion()
						+ (civilisation.isPlayerCreated() ? " | Founder: " + civilisation.getFounderIgn() : ""),
				18, y, MUTED_TEXT, true);
		y += 22;

		if (!civilisation.isActive()) {
			graphics.text(this.font, "This civilisation is visible on the strategic map but has not started yet.", 18,
					y, WARNING_TEXT, true);
			return;
		}

		graphics.text(this.font,
				"Villagers: " + civilisation.getPopulation() + " | Soldiers: "
						+ civilisation.getJobCount(VillagerJob.SOLDIER) + " | Factories: " + civilisation.getFactories()
						+ " | Roads: " + civilisation.getRoadSegments(),
				18, y, TEXT, true);
		y += 18;

		graphics.text(this.font, "Resources: physical supply-depot chest inventory (numerical duplicate hidden)", 18, y,
				TEXT, true);
		y += 20;

		graphics.text(this.font,
				"Political Power: " + (int) civilisation.getPoliticalPower() + " | Research: "
						+ (int) civilisation.getResearchPoints() + " | Conscription: "
						+ civilisation.getConscriptionLevel().getDisplayName(),
				18, y, MUTED_TEXT, true);
		y += 16;
		String focus = civilisation.getActiveFocusId() == null ? "none" : civilisation.getActiveFocusId();
		graphics.text(this.font,
				"Focus: " + focus + " | Completed focuses: " + civilisation.getCompletedFocusIds().size()
						+ (civilisation.hasPendingEvent() ? " | EVENT DECISION WAITING" : ""),
				18, y, civilisation.hasPendingEvent() ? WARNING_TEXT : MUTED_TEXT, true);
		y += 24;

		var providences = StrategyClientContext.providences().stream()
				.filter(providence -> civilisation.getId().equals(providence.getOwnerId()))
				.filter(Providence::isEstablished).sorted(java.util.Comparator.comparing(Providence::getName)).toList();
		graphics.text(this.font,
				"Providences: " + providences.size() + " | Border ARGB: #"
						+ String.format(java.util.Locale.ROOT, "%08X", civilisation.getBorderColourArgb()),
				18, y, TEXT, true);
		y += 14;
		for (Providence providence : providences) {
			String city = providence.getCity() == null ? "No city" : providence.getCity().getName();
			String capital = providence.getCity() != null && providence.getCity().isSupplyCapital()
					? " [SUPPLY CAPITAL]"
					: "";
			graphics.text(this.font,
					"- " + providence.getName() + " | " + city + capital + " | Supply "
							+ (int) (providence.getSupplyLevel() * 100.0) + "%",
					30, y, providence.getSupplyLevel() >= 0.50 ? GOOD_TEXT : WARNING_TEXT, true);
			y += 13;
		}
		y += 8;

		graphics.text(this.font, "National Spirits:", 18, y, TEXT, true);
		y += 14;
		if (civilisation.getNationalSpiritIds().isEmpty()) {
			graphics.text(this.font, "None", 30, y, MUTED_TEXT, true);
		} else {
			for (String id : civilisation.getNationalSpiritIds()) {
				NationalSpirit spirit = NationalSpirit.byId(id);
				String label = spirit == null ? id : spirit.getDisplayName();
				graphics.text(this.font, "- " + label, 30, y, GOOD_TEXT, true);
				y += 13;
			}
		}
	}

	private static String formatYear(long year) {
		if (year == Long.MAX_VALUE)
			return "unscheduled";
		return year < 0 ? Math.abs(year) + " BCE" : year + " CE";
	}

	@Override
	protected StrategyScreen recreate() {
		return new CountryScreen(getParentScreen(), civilisationId);
	}
}
