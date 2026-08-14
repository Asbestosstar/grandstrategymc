package com.asbestosstar.grandstrategy.common.mixin;

import com.asbestosstar.grandstrategy.common.gui.MapScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the Grand Strategy entry button to the vanilla player inventory screens.
 *
 * <p>The hook targets AbstractContainerScreen rather than a loader lifecycle API,
 * so it works identically under every loader. The runtime type check restricts
 * the button to the normal survival inventory and the creative-mode inventory.</p>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class InventoryScreenMixin extends Screen {

    @Shadow
    protected int leftPos;

    @Shadow
    protected int topPos;

    @Shadow
    protected int imageWidth;

    protected InventoryScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void grandstrategy$addStrategyButton(CallbackInfo ci) {
        Object screen = this;

        if (!(screen instanceof InventoryScreen)
                && !(screen instanceof CreativeModeInventoryScreen)) {
            return;
        }

        // Keep the button attached to the inventory panel while remaining visible
        // at small GUI scales and unusual window sizes.
        int buttonWidth = 32;
        int buttonHeight = 20;
        int x = Math.min(this.width - buttonWidth - 4, this.leftPos + this.imageWidth + 4);
        int y = Math.max(4, this.topPos + 4);

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("GS"),
                                button -> this.minecraft.setScreen(new MapScreen((Screen) (Object) this)))
                        .bounds(x, y, buttonWidth, buttonHeight)
                        .build());
    }
}




