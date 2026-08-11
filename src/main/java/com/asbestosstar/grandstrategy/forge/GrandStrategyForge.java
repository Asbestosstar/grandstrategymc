package com.asbestosstar.grandstrategy.forge;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

@Mod(GrandStrategyCommon.MOD_ID)
public class GrandStrategyForge {
    public GrandStrategyForge() {
        GrandStrategyCommon.init(FMLPaths.GAMEDIR.get().toFile());
        System.out.println("Grand Strategy Forge Initialized!");
    }
}




