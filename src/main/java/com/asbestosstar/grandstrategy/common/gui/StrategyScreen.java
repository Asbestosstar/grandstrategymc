package com.asbestosstar.grandstrategy.common.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Base class for loader-independent Grand Strategy management screens. */
public abstract class StrategyScreen extends Screen {
    protected static final int TEXT = 0xFFFFFFFF;
    protected static final int MUTED_TEXT = 0xFFB8C1CC;
    protected static final int GOOD_TEXT = 0xFF73D982;
    protected static final int WARNING_TEXT = 0xFFFFC857;
    protected static final int BAD_TEXT = 0xFFFF6B6B;

    protected enum UiIcon {
        BACK,
        MAP,
        ECONOMY,
        DIPLOMACY,
        SPIRITS,
        FOCUS,
        EVENTS,
        FARM_ZONE,
        FACTORY_ZONE,
        ARMY_AUTO,
        MOVE_ARMY,
        ZOOM_OUT,
        ZOOM_IN,
        PLAYER,
        FIT
    }

    private final Screen parent;
    private final List<IconControl> iconControls = new ArrayList<>();
    private int refreshCountdown = -1;

    protected StrategyScreen(String title) {
        this(title, null);
    }

    protected StrategyScreen(String title, Screen parent) {
        super(Component.literal(title));
        this.parent = parent;
    }

    protected Screen getParentScreen() {
        return parent;
    }

    /** Call at the beginning of init() before registering icon-only controls. */
    protected void beginIconLayout() {
        iconControls.clear();
    }

    /**
     * Adds a compact button whose visible face is a drawn icon rather than text.
     * The supplied text is shown only while hovering so the top-level GUI remains
     * readable even at small GUI scales.
     */
    protected Button addIconButton(UiIcon icon, String tooltip, int x, int y,
                                   Consumer<Button> onPress) {
        return addIconButton(icon, tooltip, x, y, 20, 20, 0xFFD7DEE7, 0xFFFFFFFF, 0, onPress);
    }

    protected Button addIconButton(UiIcon icon, String tooltip, int x, int y,
                                   int width, int height, Consumer<Button> onPress) {
        return addIconButton(icon, tooltip, x, y, width, height,
                0xFFD7DEE7, 0xFFFFFFFF, 0, onPress);
    }

    protected Button addIconButton(UiIcon icon, String tooltip, int x, int y,
                                   int width, int height,
                                   int colour, int hoverColour, int accentFill,
                                   Consumer<Button> onPress) {
        Button button = Button.builder(Component.literal(""), pressed -> onPress.accept(pressed))
                .bounds(x, y, width, height)
                .build();
        this.addRenderableWidget(button);
        iconControls.add(new IconControl(icon, tooltip, x, y, width, height, colour, hoverColour, accentFill));
        return button;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        renderCustom(graphics, mouseX, mouseY, partialTick);
        renderIconControls(graphics, mouseX, mouseY);
    }

    protected abstract void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick);

    protected abstract StrategyScreen recreate();

    protected void scheduleRefresh() {
        refreshCountdown = 4;
    }

    @Override
    public void tick() {
        super.tick();
        if (refreshCountdown >= 0 && --refreshCountdown <= 0) {
            refreshCountdown = -1;
            if (this.minecraft != null) {
                this.minecraft.setScreen(recreate());
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        // World simulation and queued strategy actions must continue while this GUI is open.
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    public void open() {
        net.minecraft.client.Minecraft.getInstance().setScreen(this);
    }

    private void renderIconControls(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        IconControl hovered = null;
        for (IconControl control : iconControls) {
            boolean isHovered = control.contains(mouseX, mouseY);
            if (control.accentFill() != 0) {
                int fill = isHovered ? withAlpha(control.accentFill(), 0x88) : withAlpha(control.accentFill(), 0x55);
                graphics.fill(control.x() + 1, control.y() + 1,
                        control.x() + control.width() - 1, control.y() + control.height() - 1, fill);
                graphics.outline(control.x() + 1, control.y() + 1,
                        control.width() - 2, control.height() - 2,
                        isHovered ? withAlpha(control.accentFill(), 0xFF) : withAlpha(control.accentFill(), 0xDD));
            }
            drawIcon(graphics, control.icon(), control.x(), control.y(), control.width(), control.height(),
                    isHovered ? control.hoverColour() : control.colour());
            if (isHovered) hovered = control;
        }
        if (hovered != null && hovered.tooltip() != null && !hovered.tooltip().isBlank()) {
            drawTooltip(graphics, mouseX, mouseY, hovered.tooltip());
        }
    }

    private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, String text) {
        int boxWidth = this.font.width(text) + 8;
        int boxHeight = 16;
        int boxX = Math.min(Math.max(4, mouseX + 10), Math.max(4, this.width - boxWidth - 4));
        int boxY = mouseY + 12;
        if (boxY + boxHeight > this.height - 4) boxY = Math.max(4, mouseY - boxHeight - 6);
        graphics.fill(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xF0101419);
        graphics.outline(boxX, boxY, boxWidth, boxHeight, 0xFFE2E8EF);
        graphics.text(this.font, text, boxX + 4, boxY + 4, TEXT, true);
    }

    private static void drawIcon(GuiGraphicsExtractor graphics, UiIcon icon,
                                 int x, int y, int width, int height, int colour) {
        int cx = x + width / 2;
        int cy = y + height / 2;
        switch (icon) {
            case BACK -> {
                graphics.fill(cx - 6, cy - 1, cx + 5, cy + 1, colour);
                graphics.fill(cx - 6, cy - 1, cx - 3, cy + 2, colour);
                graphics.fill(cx - 5, cy - 3, cx - 3, cy + 3, colour);
                graphics.fill(cx - 4, cy - 4, cx - 2, cy - 2, colour);
                graphics.fill(cx - 4, cy + 2, cx - 2, cy + 4, colour);
            }
            case MAP -> {
                graphics.outline(cx - 6, cy - 5, 4, 10, colour);
                graphics.outline(cx - 2, cy - 4, 4, 10, colour);
                graphics.outline(cx + 2, cy - 5, 4, 10, colour);
                graphics.fill(cx - 2, cy - 4, cx, cy + 6, colour);
                graphics.fill(cx + 2, cy - 5, cx + 4, cy + 5, colour);
            }
            case ECONOMY -> {
                graphics.outline(cx - 5, cy - 4, 10, 8, colour);
                graphics.fill(cx - 3, cy - 1, cx + 4, cy + 1, colour);
                graphics.fill(cx - 1, cy - 4, cx + 1, cy + 5, colour);
                graphics.fill(cx - 4, cy + 5, cx + 5, cy + 6, colour);
            }
            case DIPLOMACY -> {
                graphics.fill(cx - 6, cy - 3, cx + 4, cy - 1, colour);
                graphics.fill(cx + 2, cy - 5, cx + 5, cy + 1, colour);
                graphics.fill(cx - 4, cy + 2, cx + 6, cy + 4, colour);
                graphics.fill(cx - 5, cy, cx - 2, cy + 6, colour);
            }
            case SPIRITS -> {
                graphics.fill(cx - 1, cy - 6, cx + 1, cy + 7, colour);
                graphics.fill(cx - 6, cy - 1, cx + 7, cy + 1, colour);
                graphics.fill(cx - 4, cy - 4, cx - 2, cy - 2, colour);
                graphics.fill(cx + 2, cy + 2, cx + 4, cy + 4, colour);
            }
            case FOCUS -> {
                graphics.outline(cx - 6, cy - 6, 12, 12, colour);
                graphics.outline(cx - 3, cy - 3, 6, 6, colour);
                graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, colour);
            }
            case EVENTS -> {
                graphics.fill(cx - 1, cy - 6, cx + 2, cy + 3, colour);
                graphics.fill(cx - 1, cy + 5, cx + 2, cy + 7, colour);
            }
            case FARM_ZONE -> {
                // Three crop rows with a small sprout stem.
                graphics.fill(cx - 6, cy - 4, cx + 6, cy - 2, colour);
                graphics.fill(cx - 6, cy, cx + 6, cy + 2, colour);
                graphics.fill(cx - 6, cy + 4, cx + 6, cy + 6, colour);
                graphics.fill(cx - 1, cy - 6, cx + 1, cy - 3, colour);
            }
            case FACTORY_ZONE -> {
                // Compact factory silhouette: floor, walls and chimney.
                graphics.fill(cx - 6, cy + 3, cx + 6, cy + 6, colour);
                graphics.outline(cx - 5, cy - 3, 10, 8, colour);
                graphics.fill(cx - 4, cy - 6, cx - 2, cy - 2, colour);
                graphics.fill(cx + 1, cy, cx + 3, cy + 3, colour);
            }
            case ARMY_AUTO -> {
                graphics.outline(cx - 5, cy - 5, 10, 10, colour);
                graphics.fill(cx - 1, cy - 3, cx + 2, cy + 4, colour);
                graphics.fill(cx - 4, cy - 1, cx + 5, cy + 1, colour);
                graphics.fill(cx + 3, cy - 5, cx + 6, cy - 2, colour);
            }
            case MOVE_ARMY -> {
                graphics.fill(cx - 6, cy - 1, cx + 4, cy + 2, colour);
                graphics.fill(cx + 2, cy - 4, cx + 4, cy + 5, colour);
                graphics.fill(cx + 4, cy - 3, cx + 6, cy + 4, colour);
                graphics.fill(cx + 6, cy - 1, cx + 7, cy + 2, colour);
            }
            case ZOOM_OUT -> graphics.fill(cx - 5, cy - 1, cx + 6, cy + 2, colour);
            case ZOOM_IN -> {
                graphics.fill(cx - 5, cy - 1, cx + 6, cy + 2, colour);
                graphics.fill(cx - 1, cy - 5, cx + 2, cy + 6, colour);
            }
            case PLAYER -> {
                graphics.outline(cx - 6, cy - 6, 12, 12, colour);
                graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, colour);
                graphics.fill(cx - 6, cy, cx - 3, cy + 1, colour);
                graphics.fill(cx + 3, cy, cx + 6, cy + 1, colour);
                graphics.fill(cx, cy - 6, cx + 1, cy - 3, colour);
                graphics.fill(cx, cy + 3, cx + 1, cy + 6, colour);
            }
            case FIT -> {
                graphics.fill(cx - 6, cy - 6, cx - 1, cy - 4, colour);
                graphics.fill(cx - 6, cy - 6, cx - 4, cy - 1, colour);
                graphics.fill(cx + 1, cy - 6, cx + 6, cy - 4, colour);
                graphics.fill(cx + 4, cy - 6, cx + 6, cy - 1, colour);
                graphics.fill(cx - 6, cy + 4, cx - 1, cy + 6, colour);
                graphics.fill(cx - 6, cy + 1, cx - 4, cy + 6, colour);
                graphics.fill(cx + 1, cy + 4, cx + 6, cy + 6, colour);
                graphics.fill(cx + 4, cy + 1, cx + 6, cy + 6, colour);
            }
        }
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private record IconControl(UiIcon icon, String tooltip, int x, int y, int width, int height,
                               int colour, int hoverColour, int accentFill) {
        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}


