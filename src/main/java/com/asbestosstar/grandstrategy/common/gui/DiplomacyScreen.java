package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/** Country browser. All actual actions live on the per-country diplomacy page. */
public final class DiplomacyScreen extends StrategyScreen {
    private final int page;

    public DiplomacyScreen(Screen parent) {
        this(parent, 0);
    }

    private DiplomacyScreen(Screen parent, int page) {
        super("Diplomacy", parent);
        this.page = Math.max(0, page);
    }

    @Override
    protected void init() {
        beginIconLayout();
        addIconButton(UiIcon.BACK, "Back", 6, 6, button ->
                this.minecraft.setScreen(getParentScreen()));

        Civilisation player = StrategyClientContext.currentPlayerCountry();
        if (player == null) return;

        List<Civilisation> all = otherCivilisations(player);
        int maxRows = Math.max(1, (this.height - 108) / 26);
        int pageCount = Math.max(1, (all.size() + maxRows - 1) / maxRows);
        int currentPage = Math.min(page, pageCount - 1);
        int start = currentPage * maxRows;
        int end = Math.min(all.size(), start + maxRows);

        int y = 76;
        for (Civilisation target : all.subList(start, end)) {
            String targetId = target.getId();
            this.addRenderableWidget(Button.builder(Component.literal("Open Diplomacy"), button ->
                            this.minecraft.setScreen(new DiplomacyCountryScreen(this, targetId)))
                    .bounds(this.width - 118, y - 4, 108, 20).build());
            y += 26;
        }

        if (pageCount > 1) {
            if (currentPage > 0) {
                this.addRenderableWidget(Button.builder(Component.literal("< Previous"), b ->
                                this.minecraft.setScreen(new DiplomacyScreen(getParentScreen(), currentPage - 1)))
                        .bounds(18, this.height - 24, 82, 20).build());
            }
            if (currentPage + 1 < pageCount) {
                this.addRenderableWidget(Button.builder(Component.literal("Next >"), b ->
                                this.minecraft.setScreen(new DiplomacyScreen(getParentScreen(), currentPage + 1)))
                        .bounds(106, this.height - 24, 72, 20).build());
            }
        }
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Civilisation player = StrategyClientContext.currentPlayerCountry();
        graphics.text(this.font, "Diplomacy", 18, 40, TEXT, true);

        if (player == null) {
            graphics.text(this.font, "Create a country before using diplomacy.", 18, 64, WARNING_TEXT, true);
            return;
        }

        List<Civilisation> all = otherCivilisations(player);
        int maxRows = Math.max(1, (this.height - 108) / 26);
        int pageCount = Math.max(1, (all.size() + maxRows - 1) / maxRows);
        int currentPage = Math.min(page, pageCount - 1);
        int start = currentPage * maxRows;
        int end = Math.min(all.size(), start + maxRows);

        graphics.text(this.font,
                player.getName() + " | Political Power: " + (int) player.getPoliticalPower()
                        + " | Country page " + (currentPage + 1) + "/" + pageCount,
                18, 56, MUTED_TEXT, true);

        int y = 76;
        for (Civilisation target : all.subList(start, end)) {
            int relation = player.getRelation(target.getId());
            WarSystem.WarState war = StrategyClientContext.warBetween(player.getId(), target.getId());
            Leader leader = StrategyClientContext.leader(target.getDefaultLeaderId());
            String leaderName = leader == null ? target.getDefaultLeaderId() : leader.getName();
            String incoming = player.getPendingDiplomaticOfferFrom(target.getId());
            String state = war != null
                    ? (war.specialOperation && !war.escalated ? "LIMITED OPERATION" : "AT WAR")
                    : incoming != null ? "DIPLOMATIC OFFER"
                    : target.isPuppet() ? "PUPPET" : target.getFactionId() != null ? target.getFactionId() : "INDEPENDENT";
            int relationColour = relation > 25 ? GOOD_TEXT : relation < -25 ? BAD_TEXT : TEXT;

            graphics.text(this.font, target.getName() + "  |  " + state,
                    22, y, TEXT, true);
            graphics.text(this.font, "Leader: " + leaderName + "  |  Relation " + relation,
                    Math.max(190, this.width / 2 - 30), y, relationColour, true);
            y += 26;
        }
    }

    private static List<Civilisation> otherCivilisations(Civilisation player) {
        return StrategyClientContext.civilisations().stream()
                .filter(Civilisation::isActive)
                .filter(civ -> !civ.getId().equals(player.getId()))
                .sorted(Comparator.comparing(Civilisation::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    protected StrategyScreen recreate() {
        return new DiplomacyScreen(getParentScreen(), page);
    }
}
