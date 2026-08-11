package com.asbestosstar.grandstrategy.neoforge;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;

@Mod(GrandStrategyCommon.MOD_ID)
public class GrandStrategyNeoForge {
    public GrandStrategyNeoForge() {
        GrandStrategyCommon.init(FMLPaths.GAMEDIR.get().toFile());
        System.out.println("Grand Strategy NeoForge Initialized!");
    }
}




