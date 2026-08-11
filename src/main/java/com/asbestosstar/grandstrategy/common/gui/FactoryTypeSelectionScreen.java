package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FactoryType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/** Chooses a researched factory type, then hands placement to the world map. */
public final class FactoryTypeSelectionScreen extends StrategyScreen {
    public FactoryTypeSelectionScreen(Screen parent) { super("Add Factory District", parent); }
    @Override protected void init() {
        beginIconLayout(); StrategyClientContext.requestSync();
        addIconButton(UiIcon.BACK, "Back", 6, 6, b -> this.minecraft.setScreen(getParentScreen()));
        Civilisation civ = StrategyClientContext.currentPlayerCountry();
        if (civ == null) return;
        List<FactoryType> types = StrategyClientContext.factoryTypes().stream()
                .sorted(Comparator.comparing(FactoryType::getName)).toList();
        int y = 58;
        for (FactoryType type : types) {
            if (y > this.height - 28) break;
            boolean unlocked = unlocked(civ, type);
            Button button = Button.builder(Component.literal((unlocked ? "Build " : "Locked: ") + type.getName()), b -> {
                if (unlocked) this.minecraft.setScreen(new MapScreen(this, "FACTORY:" + type.getId()));
            }).bounds(22, y, 180, 20).build();
            this.addRenderableWidget(button);
            y += 28;
        }
    }
    @Override protected void renderCustom(GuiGraphicsExtractor g, int mx, int my, float pt) {
        Civilisation civ = StrategyClientContext.currentPlayerCountry();
        g.text(this.font, "Select factory type", 18, 38, TEXT, true);
        if (civ == null) return;
        int y = 62;
        for (FactoryType type : StrategyClientContext.factoryTypes().stream().sorted(Comparator.comparing(FactoryType::getName)).toList()) {
            if (y > this.height - 24) break;
            String req = type.getRequiredTechnologyIds().isEmpty() ? "starter" : "requires " + String.join(", ", type.getRequiredTechnologyIds());
            g.text(this.font, type.getDescription() + " [" + req + "]", 212, y, unlocked(civ,type) ? MUTED_TEXT : BAD_TEXT, true);
            y += 28;
        }
    }
    private static boolean unlocked(Civilisation civ, FactoryType type) {
        if (type == null || civ == null) return false;
        if (type.isStarter()) return true;
        return type.getRequiredTechnologyIds().stream().allMatch(civ::hasTechnology);
    }
    @Override protected StrategyScreen recreate() { return new FactoryTypeSelectionScreen(getParentScreen()); }
}

