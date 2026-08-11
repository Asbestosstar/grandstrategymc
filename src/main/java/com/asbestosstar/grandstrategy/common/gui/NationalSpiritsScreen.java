package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.NationalSpirit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Editor for the national spirits of the player's own country. */
public final class NationalSpiritsScreen extends StrategyScreen {
    private String statusMessage = "";

    public NationalSpiritsScreen(Screen parent) {
        super("National Spirits", parent);
    }

    @Override
    protected void init() {
        beginIconLayout();
        addIconButton(UiIcon.BACK, "Back to map", 6, 6, button ->
                this.minecraft.setScreen(getParentScreen()));

        Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
        if (civilisation == null) return;

        int y = 72;
        for (NationalSpirit spirit : NationalSpirit.values()) {
            boolean selected = civilisation.hasNationalSpirit(spirit);
            String label = (selected ? "[X] " : "[ ] ") + spirit.getDisplayName();
            this.addRenderableWidget(Button.builder(Component.literal(label), button -> {
                        boolean queued = StrategyClientContext.requestToggleSpirit(spirit.getId());
                        if (!queued) statusMessage = "Not connected to a Grand Strategy server.";
                        else scheduleRefresh();
                    }).bounds(24, y - 4, 190, 20).build());
            y += 31;
        }
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Civilisation civilisation = StrategyClientContext.currentPlayerCountry();
        graphics.text(this.font, "National Spirits", 18, 40, TEXT, true);

        if (civilisation == null) {
            graphics.text(this.font,
                    "No player country exists yet. National Spirits are empty until you create your country from the map.",
                    18, 64, WARNING_TEXT, true);
            return;
        }

        graphics.text(this.font,
                civilisation.getName() + " | selected spirits: " + civilisation.getNationalSpiritIds().size(),
                18, 54, MUTED_TEXT, true);

        int y = 72;
        for (NationalSpirit spirit : NationalSpirit.values()) {
            graphics.text(this.font, spirit.getDescription(), 226, y + 2,
                    civilisation.hasNationalSpirit(spirit) ? GOOD_TEXT : MUTED_TEXT, true);
            y += 31;
        }

        if (!statusMessage.isBlank()) {
            graphics.text(this.font, statusMessage, 18, this.height - 18, BAD_TEXT, true);
        }
    }

    @Override
    protected StrategyScreen recreate() {
        return new NationalSpiritsScreen(getParentScreen());
    }
}




