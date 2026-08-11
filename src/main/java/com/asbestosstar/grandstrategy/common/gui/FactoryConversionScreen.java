package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/** Converts existing physical factory districts between researched types. */
public final class FactoryConversionScreen extends StrategyScreen {
	private String status = "";

	public FactoryConversionScreen(Screen parent) {
		super("Convert Factories", parent);
	}

	@Override
	protected void init() {
		beginIconLayout();
		StrategyClientContext.requestSync();
		addIconButton(UiIcon.BACK, "Back", 6, 6, b -> this.minecraft.setScreen(getParentScreen()));
		Civilisation civ = StrategyClientContext.currentPlayerCountry();
		if (civ == null)
			return;
		List<FactoryType> unlocked = StrategyClientContext.factoryTypes().stream()
				.filter(t -> t.isStarter() || t.getRequiredTechnologyIds().stream().allMatch(civ::hasTechnology))
				.sorted(Comparator.comparing(FactoryType::getName)).toList();
		List<PhysicalVillagerSystem.WorkZoneMapMarker> zones = StrategyClientContext.workZones().stream()
				.filter(z -> z != null && civ.getId().equals(z.civilisationId()) && "FACTORY".equals(z.type()))
				.toList();
		int y = 58;
		for (PhysicalVillagerSystem.WorkZoneMapMarker zone : zones) {
			if (y > this.height - 28)
				break;
			FactoryType next = nextType(unlocked, zone.factoryTypeId());
			String current = zone.factoryTypeId() == null ? "wooden_factory" : zone.factoryTypeId();
			this.addRenderableWidget(Button
					.builder(Component.literal(readable(current) + " -> " + (next == null ? "none" : next.getName())),
							b -> {
								if (next != null
										&& StrategyClientContext.requestFactoryConversion(zone.id(), next.getId())) {
									status = "Conversion requested.";
									scheduleRefresh();
								}
							})
					.bounds(22, y, 260, 20).build());
			y += 26;
		}
	}

	private static FactoryType nextType(List<FactoryType> list, String current) {
		if (list.isEmpty())
			return null;
		for (int i = 0; i < list.size(); i++)
			if (list.get(i).getId().equals(current))
				return list.get((i + 1) % list.size());
		return list.get(0);
	}

	private static String readable(String s) {
		if (s == null)
			return "Wooden factory";
		s = s.replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor g, int mx, int my, float pt) {
		g.text(this.font, "Factory conversion", 18, 38, TEXT, true);
		g.text(this.font,
				"Each button cycles that district to the next researched type. Conversion keeps the same district and worker.",
				18, 48, MUTED_TEXT, true);
		if (!status.isBlank())
			g.text(this.font, status, 18, this.height - 18, GOOD_TEXT, true);
	}

	@Override
	protected StrategyScreen recreate() {
		return new FactoryConversionScreen(getParentScreen());
	}
}
