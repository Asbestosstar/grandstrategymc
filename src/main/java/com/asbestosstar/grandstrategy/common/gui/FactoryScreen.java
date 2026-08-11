package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Central industry screen: owned factories, district creation, conversion and
 * production.
 */
public final class FactoryScreen extends StrategyScreen {
	public FactoryScreen(Screen parent) {
		super("Factories & Production", parent);
	}

	@Override
	protected void init() {
		beginIconLayout();
		StrategyClientContext.requestSync();
		addIconButton(UiIcon.BACK, "Back", 6, 6, b -> this.minecraft.setScreen(getParentScreen()));
		Civilisation civ = StrategyClientContext.currentPlayerCountry();
		if (civ == null)
			return;
		this.addRenderableWidget(Button
				.builder(Component.literal("Add factory district"),
						b -> this.minecraft.setScreen(new FactoryTypeSelectionScreen(this)))
				.bounds(34, 6, 145, 20).build());
		this.addRenderableWidget(Button
				.builder(Component.literal("Convert factories"),
						b -> this.minecraft.setScreen(new FactoryConversionScreen(this)))
				.bounds(183, 6, 135, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Production queue"),
				b -> this.minecraft.setScreen(new ProductionScreen(this))).bounds(322, 6, 130, 20).build());
		this.addRenderableWidget(
				Button.builder(Component.literal("Research"), b -> this.minecraft.setScreen(new ResearchScreen(this)))
						.bounds(456, 6, 86, 20).build());
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		Civilisation civ = StrategyClientContext.currentPlayerCountry();
		g.text(this.font, "Factories", 18, 40, TEXT, true);
		if (civ == null) {
			g.text(this.font, "Create a country first.", 18, 60, WARNING_TEXT, true);
			return;
		}
		g.text(this.font,
				"Factory districts are physical workshops. Production orders are assigned automatically to compatible workers.",
				18, 56, MUTED_TEXT, true);
		List<PhysicalVillagerSystem.WorkZoneMapMarker> zones = StrategyClientContext.workZones().stream()
				.filter(z -> z != null && civ.getId().equals(z.civilisationId()) && "FACTORY".equals(z.type()))
				.sorted(Comparator.comparing(z -> z.id() == null ? "" : z.id())).toList();
		int y = 78;
		if (zones.isEmpty())
			g.text(this.font, "No factories yet. Research determines which factory types can be added.", 22, y,
					WARNING_TEXT, true);
		for (int i = 0; i < zones.size() && y < this.height - 28; i++, y += 19) {
			var z = zones.get(i);
			String type = readable(z.factoryTypeId() == null ? "wooden_factory" : z.factoryTypeId());
			String worker = z.assignedWorkerUuid() == null ? "unstaffed" : "worker assigned";
			g.fill(18, y - 3, this.width - 18, y + 13, (i & 1) == 0 ? 0x55202A33 : 0x55303A42);
			g.text(this.font, "#" + (i + 1) + "  " + type + "  | " + worker + " | X " + z.minX() + ".." + z.maxX()
					+ " Z " + z.minZ() + ".." + z.maxZ(), 23, y, TEXT, true);
		}
	}

	private static String readable(String s) {
		if (s == null || s.isBlank())
			return "Factory";
		s = s.toLowerCase().replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	@Override
	protected StrategyScreen recreate() {
		return new FactoryScreen(getParentScreen());
	}
}
