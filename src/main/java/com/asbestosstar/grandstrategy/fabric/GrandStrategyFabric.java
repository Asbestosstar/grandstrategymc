package com.asbestosstar.grandstrategy.fabric;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class GrandStrategyFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        GrandStrategyCommon.init(FabricLoader.getInstance().getGameDir().toFile());
        System.out.println("Grand Strategy Fabric Initialized!");
    }
}




