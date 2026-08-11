package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FocusTree;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Interactive national focus tree for the player's country. */
public final class FocusTreeScreen extends StrategyScreen {
	private String statusMessage = "";
	private int syncTicks;
	private String lastStateSignature = "";

	public FocusTreeScreen(Screen parent) {
		super("National Focus Tree", parent);
	}

	@Override
	protected void init() {
		beginIconLayout();
		addIconButton(UiIcon.BACK, "Back", 6, 6, button -> this.minecraft.setScreen(getParentScreen()));
		StrategyClientContext.requestSync();

		Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
		FocusTree tree = StrategyClientContext.focusTreeFor(civilisation);
		lastStateSignature = stateSignature(civilisation);
		if (civilisation == null || tree == null)
			return;

		int y = 74;
		for (FocusTree.FocusNode node : tree.getNodes()) {
			String label;
			if (civilisation.hasCompletedFocus(node.getId())) {
				label = "[DONE] " + node.getTitle();
			} else if (node.getId().equals(civilisation.getActiveFocusId())) {
				int pct = (int) Math.min(100.0,
						civilisation.getActiveFocusProgress() * 100.0 / node.getDurationSteps());
				label = "[" + pct + "%] " + node.getTitle();
			} else if (canStartLocally(civilisation, node)) {
				label = "Start: " + node.getTitle();
			} else {
				label = "[LOCKED] " + node.getTitle();
			}

			if (canStartLocally(civilisation, node)) {
				this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
					boolean queued = StrategyClientContext.requestStartFocus(node.getId());
					statusMessage = queued ? "Focus request sent to server."
							: "Not connected to a Grand Strategy server.";
					if (queued)
						scheduleRefresh();
				}).bounds(18, y - 4, 220, 20).build());
			}
			y += 36;
		}
	}

	private static boolean canStartLocally(Civilisation civilisation, FocusTree.FocusNode node) {
		if (civilisation == null || node == null || civilisation.getActiveFocusId() != null
				|| civilisation.hasCompletedFocus(node.getId()))
			return false;
		if (civilisation.getPoliticalPower() + 1.0e-9 < node.getPoliticalPowerCost())
			return false;
		for (String prerequisite : node.getPrerequisites()) {
			if (!civilisation.hasCompletedFocus(prerequisite))
				return false;
		}
		for (String excluded : node.getMutuallyExclusive()) {
			if (civilisation.hasCompletedFocus(excluded))
				return false;
		}
		return true;
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.text(this.font, "National Focus Tree", 18, 40, TEXT, true);

		Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
		if (civilisation == null) {
			graphics.text(this.font, "Create a country before selecting national focuses.", 18, 58, WARNING_TEXT, true);
			return;
		}

		FocusTree tree = StrategyClientContext.focusTreeFor(civilisation);
		if (tree == null || tree.getNodes().isEmpty()) {
			graphics.text(this.font, "No focus tree is available from the server.", 18, 58, WARNING_TEXT, true);
			return;
		}

		graphics.text(this.font, civilisation.getName() + " | Political Power " + (int) civilisation.getPoliticalPower()
				+ " | Completed " + civilisation.getCompletedFocusIds().size(), 18, 56, MUTED_TEXT, true);

		int y = 74;
		for (FocusTree.FocusNode node : tree.getNodes()) {
			boolean completed = civilisation.hasCompletedFocus(node.getId());
			boolean active = node.getId().equals(civilisation.getActiveFocusId());
			int colour = completed ? GOOD_TEXT : active ? WARNING_TEXT : MUTED_TEXT;

			if (!canStartLocally(civilisation, node)) {
				String prefix = completed ? "[DONE] " : active ? "[ACTIVE] " : "[LOCKED] ";
				graphics.text(this.font, prefix + node.getTitle(), 20, y + 1, colour, true);
			}

			String requirements = requirementText(node);
			graphics.text(this.font,
					node.getDescription() + "  | " + node.getDurationSteps() + "s | "
							+ (int) node.getPoliticalPowerCost() + " PP" + requirements,
					248, y + 1, active ? TEXT : MUTED_TEXT, true);
			y += 36;
		}

		if (!statusMessage.isBlank()) {
			graphics.text(this.font, statusMessage, 18, this.height - 18, WARNING_TEXT, true);
		}
	}

	private static String requirementText(FocusTree.FocusNode node) {
		List<String> prerequisites = node.getPrerequisites();
		List<String> exclusive = node.getMutuallyExclusive();
		StringBuilder text = new StringBuilder();
		if (!prerequisites.isEmpty())
			text.append(" | requires ").append(String.join(", ", prerequisites));
		if (!exclusive.isEmpty())
			text.append(" | excludes ").append(String.join(", ", exclusive));
		return text.toString();
	}

	@Override
	public void tick() {
		super.tick();
		if (++syncTicks % 20 == 0)
			StrategyClientContext.requestSync();
		Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
		String signature = stateSignature(civilisation);
		if (!signature.equals(lastStateSignature) && this.minecraft != null) {
			this.minecraft.setScreen(new FocusTreeScreen(getParentScreen()));
		}
	}

	private static String stateSignature(Civilisation civilisation) {
		if (civilisation == null)
			return "none";
		return String.valueOf(civilisation.getActiveFocusId()) + "|" + civilisation.getCompletedFocusIds().size() + "|"
				+ (int) civilisation.getPoliticalPower();
	}

	@Override
	protected StrategyScreen recreate() {
		return new FocusTreeScreen(getParentScreen());
	}
}
