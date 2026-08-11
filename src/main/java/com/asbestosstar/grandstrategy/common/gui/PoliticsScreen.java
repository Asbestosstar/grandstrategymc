package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Politics overview and entry point to focuses, events and national spirits.
 */
public class PoliticsScreen extends StrategyScreen {

	public PoliticsScreen() {
		this(null);
	}

	public PoliticsScreen(Screen parent) {
		super("Politics & Focus Trees", parent);
	}

	@Override
	protected void init() {
		beginIconLayout();
		int x = 6;
		addIconButton(UiIcon.MAP, "Map", x, 6, button -> this.minecraft.setScreen(new MapScreen(getParentScreen())));
		x += 24;
		addIconButton(UiIcon.DIPLOMACY, "Diplomacy", x, 6,
				button -> this.minecraft.setScreen(new DiplomacyScreen(this)));
		x += 24;
		addIconButton(UiIcon.FOCUS, "Focus tree", x, 6, button -> this.minecraft.setScreen(new FocusTreeScreen(this)));
		x += 24;

		Civilisation own = StrategyClientContext.currentPlayerCountry();
		addIconButton(UiIcon.EVENTS,
				own != null && own.hasPendingEvent() ? "Events - decision waiting" : "Events & decisions", x, 6,
				button -> this.minecraft.setScreen(new EventsScreen(this)));
		x += 24;
		addIconButton(UiIcon.SPIRITS, "National spirits", x, 6,
				button -> this.minecraft.setScreen(new NationalSpiritsScreen(this)));
		x += 28;

		if (own != null) {
			this.addRenderableWidget(Button.builder(Component.literal("Research"),
					button -> this.minecraft.setScreen(new ResearchScreen(this))).bounds(x, 6, 82, 20).build());
			x += 86;
			this.addRenderableWidget(Button.builder(Component.literal("Religion & Ideology"),
					button -> this.minecraft.setScreen(new SocietyScreen(this))).bounds(x, 6, 132, 20).build());
			x += 136;
			this.addRenderableWidget(Button.builder(Component.literal("Change Government"), button -> {
				StrategyClientContext.requestCycleGovernment();
				StrategyClientContext.requestSync();
			}).bounds(x, 6, 130, 20).build());
		}
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.text(this.font, "Politics, Focuses & Events", 18, 40, TEXT, true);

		Civilisation own = StrategyClientContext.currentPlayerCountry();
		int y = 62;
		if (own != null) {
			graphics.fill(20, y, 34, y + 10, own.getBorderColourArgb());
			graphics.outline(19, y - 1, 16, 12, 0xFF111111);
			String focus = own.getActiveFocusId() == null ? "none" : own.getActiveFocusId();
			String event = own.hasPendingEvent() ? "DECISION WAITING" : "none";
			graphics.text(this.font,
					"Your government: " + own.getGovernment() + " | Focus: " + focus + " | Event: " + event, 42, y + 1,
					own.hasPendingEvent() ? WARNING_TEXT : TEXT, true);
			y += 20;
		}

		for (Civilisation civ : StrategyClientContext.civilisations()) {
			String text = "Civ: " + civ.getName() + " | Government: " + civ.getIdeology() + " | Focuses completed: "
					+ civ.getCompletedFocusIds().size() + " | Status: " + (civ.isActive() ? "Active" : "Potential");
			graphics.text(this.font, text, 20, y, civ.isActive() ? TEXT : MUTED_TEXT, true);
			y += 14;
		}
	}

	@Override
	protected StrategyScreen recreate() {
		return new PoliticsScreen(getParentScreen());
	}
}
