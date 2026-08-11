package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Two-party peace conference. A treaty is applied only after both sides accept
 * it.
 */
public final class PeaceConferenceScreen extends StrategyScreen {
	private final String opponentId;
	private String statusMessage = "";

	public PeaceConferenceScreen(Screen parent, String opponentId) {
		super("Peace Conference", parent);
		this.opponentId = opponentId;
	}

	@Override
	protected void init() {
		beginIconLayout();
		addIconButton(UiIcon.BACK, "Back to diplomacy", 6, 6, button -> this.minecraft.setScreen(getParentScreen()));

		Civilisation own = StrategyClientContext.currentPlayerCountry();
		WarSystem.WarState war = own == null ? null : StrategyClientContext.warBetween(own.getId(), opponentId);
		if (own == null || war == null)
			return;

		int y = 96;
		this.addRenderableWidget(
				Button.builder(Component.literal("White Peace"), button -> propose(WarSystem.PEACE_WHITE))
						.bounds(18, y, 132, 20).build());
		y += 24;

		String territory = war.recommendedTerritoryId;
		String territoryTerms = territory == null ? WarSystem.PEACE_TERRITORY
				: WarSystem.PEACE_TERRITORY + ":" + territory;
		this.addRenderableWidget(Button.builder(Component.literal("Territory (25)"), button -> propose(territoryTerms))
				.bounds(18, y, 132, 20).build());
		y += 24;

		this.addRenderableWidget(
				Button.builder(Component.literal("Puppet (65)"), button -> propose(WarSystem.PEACE_PUPPET))
						.bounds(18, y, 132, 20).build());
		y += 24;

		String combinedTerms = territory == null ? WarSystem.PEACE_TERRITORY_AND_PUPPET
				: WarSystem.PEACE_TERRITORY_AND_PUPPET + ":" + territory;
		this.addRenderableWidget(
				Button.builder(Component.literal("Territory + Puppet (85)"), button -> propose(combinedTerms))
						.bounds(18, y, 176, 20).build());

		if (war.pendingPeace != null) {
			this.addRenderableWidget(Button.builder(Component.literal("Accept Treaty"), button -> {
				if (!StrategyClientContext.requestPeaceAccept(opponentId)) {
					statusMessage = "Not connected to a Grand Strategy server.";
				} else {
					statusMessage = "Acceptance sent. Treaty applies only after both sides accept.";
					scheduleRefresh();
				}
			}).bounds(this.width - 210, 96, 96, 20).build());
			this.addRenderableWidget(Button.builder(Component.literal("Reject"), button -> {
				if (!StrategyClientContext.requestPeaceReject(opponentId)) {
					statusMessage = "Not connected to a Grand Strategy server.";
				} else {
					statusMessage = "Peace proposal rejected; the war continues.";
					scheduleRefresh();
				}
			}).bounds(this.width - 108, 96, 90, 20).build());
		}
	}

	private void propose(String terms) {
		if (!StrategyClientContext.requestPeaceProposal(opponentId, terms)) {
			statusMessage = "Not connected to a Grand Strategy server.";
		} else {
			statusMessage = "Peace terms proposed; the other side must accept.";
			scheduleRefresh();
		}
	}

	@Override
	protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Civilisation own = StrategyClientContext.currentPlayerCountry();
		Civilisation opponent = StrategyClientContext.getCivilisation(opponentId);
		graphics.text(this.font, "Peace Conference", 18, 40, TEXT, true);

		if (own == null || opponent == null) {
			graphics.text(this.font, "The belligerent country is no longer available.", 18, 64, WARNING_TEXT, true);
			return;
		}

		WarSystem.WarState war = StrategyClientContext.warBetween(own.getId(), opponentId);
		if (war == null) {
			graphics.text(this.font, "The war has ended.", 18, 64, GOOD_TEXT, true);
			return;
		}

		double score = war.scoreFor(own.getId());
		String advantage = score > 0.5 ? "winning" : score < -0.5 ? "losing" : "stalemate";
		graphics.text(
				this.font, own.getName() + " vs " + opponent.getName() + " | Your war score: " + (int) Math.round(score)
						+ " (" + advantage + ")",
				18, 58, score > 0.5 ? GOOD_TEXT : score < -0.5 ? BAD_TEXT : TEXT, true);
		graphics.text(this.font,
				"War continues until BOTH sides accept the same treaty. Territory unlocks at 25; puppet at 65; combination at 85.",
				18, 76, MUTED_TEXT, true);

		Providence recommended = null;
		if (war.recommendedTerritoryId != null) {
			for (Providence providence : StrategyClientContext.providences()) {
				if (war.recommendedTerritoryId.equals(providence.getId())) {
					recommended = providence;
					break;
				}
			}
		}
		if (recommended != null) {
			graphics.text(this.font,
					"Territory term currently selects: " + recommended.getName() + " [" + recommended.getId() + "]",
					210, 122, TEXT, true);
		}

		if (war.pendingPeace != null) {
			WarSystem.PeaceProposal proposal = war.pendingPeace;
			int y = 150;
			graphics.text(this.font, "Pending treaty: " + treatyName(proposal.type), 210, y, WARNING_TEXT, true);
			y += 18;
			if (proposal.territoryId != null) {
				graphics.text(this.font, "Territory: " + proposal.territoryId, 210, y, TEXT, true);
				y += 18;
			}
			if (proposal.winnerId != null && proposal.loserId != null) {
				graphics.text(this.font, "Favours " + proposal.winnerId + " over " + proposal.loserId, 210, y, TEXT,
						true);
				y += 18;
			}
			graphics.text(this.font, "Accepted: attacker " + yesNo(proposal.acceptedByAttacker) + " | defender "
					+ yesNo(proposal.acceptedByDefender), 210, y, MUTED_TEXT, true);
		} else {
			graphics.text(this.font,
					"No treaty is pending. Terms that exceed the current battlefield advantage will be refused by the server.",
					210, 150, MUTED_TEXT, true);
		}

		if (!statusMessage.isBlank()) {
			graphics.text(this.font, statusMessage, 18, this.height - 18, WARNING_TEXT, true);
		}
	}

	private static String treatyName(String type) {
		if (WarSystem.PEACE_TERRITORY.equals(type))
			return "Territorial settlement";
		if (WarSystem.PEACE_PUPPET.equals(type))
			return "Puppet settlement";
		if (WarSystem.PEACE_TERRITORY_AND_PUPPET.equals(type))
			return "Territory + puppet settlement";
		return "White peace";
	}

	private static String yesNo(boolean value) {
		return value ? "YES" : "NO";
	}

	@Override
	protected StrategyScreen recreate() {
		return new PeaceConferenceScreen(getParentScreen(), opponentId);
	}
}
