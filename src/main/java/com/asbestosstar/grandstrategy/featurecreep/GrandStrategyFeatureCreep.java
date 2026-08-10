package com.asbestosstar.grandstrategy.featurecreep;

import com.asbestosstar.grandstrategy.common.GrandStrategyCommon;

import java.io.File;

public class GrandStrategyFeatureCreep {
    public static void main(String[] args) {
        File gameDir = new File(".");
        GrandStrategyCommon.init(gameDir);
        System.out.println("Grand Strategy FeatureCreep Initialized!");
    }
}



