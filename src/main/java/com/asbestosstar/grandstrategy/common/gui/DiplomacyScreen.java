package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/** Basic diplomacy screen for relations and war declarations. */
public final class DiplomacyScreen extends StrategyScreen {
    private String statusMessage = "";

    public DiplomacyScreen(Screen parent) {
        super("Diplomacy", parent);
    }

    @Override
    protected void init() {
        beginIconLayout();
        addIconButton(UiIcon.BACK, "Back", 6, 6, button ->
                this.minecraft.setScreen(getParentScreen()));

        Civilisation player = StrategyClientContext.currentPlayerCountry();
        if (player == null) return;

        int y = 76;
        int maxRows = Math.max(1, (this.height - 96) / 24);
        int rows = 0;
        for (Civilisation target : otherCivilisations(player)) {
            if (rows++ >= maxRows) break;
            if (target.isActive()) {
                String targetId = target.getId();
                this.addRenderableWidget(Button.builder(Component.literal("Improve"), button -> {
                            boolean queued = StrategyClientContext.requestImproveRelations(targetId);
                            if (!queued) statusMessage = "Not connected to a Grand Strategy server.";
                            else scheduleRefresh();
                        }).bounds(this.width - 184, y - 4, 82, 20).build());

                WarSystem.WarState war = StrategyClientContext.warBetween(player.getId(), targetId);
                if (war != null) {
                    this.addRenderableWidget(Button.builder(Component.literal("Peace Conf."), button ->
                                    this.minecraft.setScreen(new PeaceConferenceScreen(this, targetId)))
                            .bounds(this.width - 98, y - 4, 92, 20).build());
                } else {
                    this.addRenderableWidget(Button.builder(Component.literal("Declare War"), button -> {
                                boolean queued = StrategyClientContext.requestDeclareWar(targetId);
                                if (!queued) statusMessage = "Not connected to a Grand Strategy server.";
                                else scheduleRefresh();
                            }).bounds(this.width - 98, y - 4, 92, 20).build());
                }
            }
            y += 24;
        }
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Civilisation player = StrategyClientContext.currentPlayerCountry();
        graphics.text(this.font, "Diplomacy", 18, 40, TEXT, true);

        if (player == null) {
            graphics.text(this.font, "Create a country before using diplomacy.",
                    18, 64, WARNING_TEXT, true);
            return;
        }

        graphics.text(this.font,
                player.getName() + " | Political Power: " + (int) player.getPoliticalPower()
                        + " | Improve relations costs 10 PP; declaring war costs 25 PP.",
                18, 56, MUTED_TEXT, true);

        int y = 76;
        int maxRows = Math.max(1, (this.height - 96) / 24);
        int rows = 0;
        for (Civilisation target : otherCivilisations(player)) {
            if (rows++ >= maxRows) break;
            int relation = player.getRelation(target.getId());
            WarSystem.WarState war = StrategyClientContext.warBetween(player.getId(), target.getId());
            String state = war != null
                    ? "AT WAR, score " + (int) Math.round(war.scoreFor(player.getId()))
                    : target.isPuppet()
                        ? "PUPPET OF " + target.getOverlordCivilisationId()
                        : target.isActive() ? "ACTIVE" : "POTENTIAL, starts " + formatYear(target.getStartYear());
            int relationColour = relation > 25 ? GOOD_TEXT : (relation < -25 ? BAD_TEXT : TEXT);
            graphics.text(this.font,
                    target.getName() + " [" + target.getId() + "]  " + state,
                    22, y, target.isActive() ? TEXT : MUTED_TEXT, true);
            graphics.text(this.font, "Relation " + relation,
                    Math.max(250, this.width / 2), y, relationColour, true);
            y += 24;
        }

        if (!statusMessage.isBlank()) {
            graphics.text(this.font, statusMessage, 18, this.height - 18, BAD_TEXT, true);
        }
    }

    private static List<Civilisation> otherCivilisations(Civilisation player) {
        return StrategyClientContext.civilisations().stream()
                .filter(Civilisation::isActive)
                .filter(civ -> !civ.getId().equals(player.getId()))
                .sorted(Comparator.comparing(Civilisation::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String formatYear(long year) {
        if (year == Long.MAX_VALUE) return "unscheduled";
        return year < 0 ? Math.abs(year) + " BCE" : year + " CE";
    }

    @Override
    protected StrategyScreen recreate() {
        return new DiplomacyScreen(getParentScreen());
    }
}



