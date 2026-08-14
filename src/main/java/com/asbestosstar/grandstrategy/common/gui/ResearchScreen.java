package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.Technology;
import com.asbestosstar.grandstrategy.common.engine.ResearchSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Data-driven research screen showing historical dates, progress, catch-up dates and unlocks. */
public final class ResearchScreen extends StrategyScreen {
    private int refreshTicks;

    public ResearchScreen(Screen parent) {
        super("Research", parent);
    }

    @Override
    protected void init() {
        beginIconLayout();
        StrategyClientContext.requestSync();
        addIconButton(UiIcon.BACK, "Back", 6, 6, b -> this.minecraft.setScreen(getParentScreen()));
        Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
        if (civilisation == null) return;

        int y = 58;
        for (Technology technology : visible(civilisation)) {
            if (y > this.height - 28) break;
            boolean ready = technology.getPrerequisites().stream().allMatch(civilisation::hasTechnology);
            String label = civilisation.hasTechnology(technology.getId())
                    ? "Researched"
                    : technology.getId().equals(civilisation.getActiveTechnologyId()) ? "Researching" : "Research";
            Button button = Button.builder(Component.literal(label), b -> {
                if (ready && !civilisation.hasTechnology(technology.getId())) {
                    StrategyClientContext.requestStartTechnology(technology.getId());
                    scheduleRefresh();
                }
            }).bounds(this.width - 112, y, 102, 18).build();
            this.addRenderableWidget(button);
            y += 24;
        }
    }

    @Override
    public void tick() {
        super.tick();
        // NetworkManager already broadcasts periodically, but an explicit request
        // while this screen is open makes the percentage visibly advance even on
        // slow/laggy integrated-server snapshots.
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            StrategyClientContext.requestSync();
        }
    }

    private static List<Technology> visible(Civilisation civilisation) {
        return StrategyClientContext.technologies().stream()
                .sorted(Comparator.comparingLong(Technology::getBaseYear))
                .toList();
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
        graphics.text(this.font, "Research", 18, 38, TEXT, true);
        if (civilisation == null) return;

        graphics.text(this.font,
                "Research always progresses with time; researchers accelerate it. Future technologies are slower and old technologies catch up faster.",
                18, 48, MUTED_TEXT, true);
        long currentYear = StrategyClientContext.currentYear();
        int y = 62;
        for (Technology technology : visible(civilisation)) {
            if (y > this.height - 24) break;
            String state = "";
            if (civilisation.hasTechnology(technology.getId())) {
                state = "DONE";
            } else if (technology.getId().equals(civilisation.getActiveTechnologyId())) {
                double fraction = ResearchSystem.researchProgressFraction(civilisation, technology, currentYear);
                double remaining = ResearchSystem.estimatedRemainingSeconds(civilisation, technology, currentYear);
                state = String.format(Locale.ROOT, "ACTIVE %.2f%% | ~%s remaining",
                        fraction * 100.0, formatDuration(remaining));
            }
            graphics.text(this.font,
                    technology.getName() + " | base " + year(technology.getBaseYear())
                            + " | catch-up " + year(technology.getBackwaterYear())
                            + (state.isBlank() ? "" : " | " + state),
                    22, y, civilisation.hasTechnology(technology.getId()) ? GOOD_TEXT : TEXT, true);
            y += 24;
        }
    }

    private static String formatDuration(double seconds) {
        if (!Double.isFinite(seconds)) return "?";
        long rounded = Math.max(0L, Math.round(seconds));
        if (rounded < 60) return rounded + "s";
        long minutes = rounded / 60;
        long remainder = rounded % 60;
        if (minutes < 60) return minutes + "m " + remainder + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    private static String year(long year) {
        if (year == Long.MAX_VALUE) return "never";
        return year < 0 ? (-year) + " BCE" : year + " CE";
    }

    @Override
    protected StrategyScreen recreate() {
        return new ResearchScreen(getParentScreen());
    }
}

