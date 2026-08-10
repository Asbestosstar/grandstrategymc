package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.FactoryRecipe;
import com.asbestosstar.grandstrategy.common.data.ProductionOrder;
import com.asbestosstar.grandstrategy.common.data.ResourceType;
import com.asbestosstar.grandstrategy.common.world.PhysicalVillagerSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Player-facing output queue. Workers choose compatible factories automatically. */
public final class ProductionScreen extends StrategyScreen {
    public ProductionScreen(Screen parent){super("Production Queue",parent);}
    @Override protected void init(){
        beginIconLayout(); StrategyClientContext.requestSync(); addIconButton(UiIcon.BACK,"Back",6,6,b->this.minecraft.setScreen(getParentScreen()));
        Civilisation civ=StrategyClientContext.currentPlayerCountry(); if(civ==null)return;
        Set<String> ownedTypes=ownedFactoryTypes(civ);
        int y=58;
        for(FactoryRecipe r: availableRecipes(civ,ownedTypes)){
            if(y>this.height-80)break;
            this.addRenderableWidget(Button.builder(Component.literal("+1"),b->{StrategyClientContext.requestQueueProduction(r.getId(),1);scheduleRefresh();}).bounds(this.width-92,y,38,18).build());
            this.addRenderableWidget(Button.builder(Component.literal("+16"),b->{StrategyClientContext.requestQueueProduction(r.getId(),16);scheduleRefresh();}).bounds(this.width-50,y,42,18).build());
            y+=22;
        }
    }
    private static Set<String> ownedFactoryTypes(Civilisation c){ Set<String>s=new HashSet<>(); for(var z:StrategyClientContext.workZones()) if(z!=null&&c.getId().equals(z.civilisationId())&&"FACTORY".equals(z.type())) s.add(z.factoryTypeId()==null?"wooden_factory":z.factoryTypeId()); return s; }
    private static List<FactoryRecipe> availableRecipes(Civilisation c,Set<String>types){return StrategyClientContext.factoryRecipes().stream().filter(r->r.getRequiredTechnologyIds().stream().allMatch(c::hasTechnology)).filter(r->r.getFactoryTypeIds().stream().anyMatch(types::contains)).sorted((a,b)->a.getName().compareTo(b.getName())).toList();}
    @Override protected void renderCustom(GuiGraphicsExtractor g,int mx,int my,float pt){
        Civilisation civ=StrategyClientContext.currentPlayerCountry(); g.text(this.font,"Production",18,38,TEXT,true); if(civ==null)return;
        Set<String> types=ownedFactoryTypes(civ); int y=61;
        for(FactoryRecipe r:availableRecipes(civ,types)){if(y>this.height-80)break; String ingredients=r.getIngredients().entrySet().stream().map(e->e.getValue()+"x "+shortId(e.getKey())).reduce((a,b)->a+", "+b).orElse("none"); boolean ready=materialsReady(civ,r); g.text(this.font,r.getName()+" x"+r.getOutputCount()+" | "+ingredients+" | "+r.getCapability()+" | "+(ready?"materials ready":"waiting for materials"),22,y,ready?GOOD_TEXT:MUTED_TEXT,true); y+=22;}
        int qy=Math.max(y+8,this.height-72); g.text(this.font,"Queue:",18,qy,TEXT,true); qy+=13; for(ProductionOrder o:civ.getProductionQueue()){if(qy>this.height-10)break; FactoryRecipe r=StrategyClientContext.factoryRecipes().stream().filter(x->x.getId().equals(o.getRecipeId())).findFirst().orElse(null); g.text(this.font,"#"+o.getSerial()+" "+(r==null?o.getRecipeId():r.getName())+"  "+o.getCompleted()+"/"+o.getRequested(),24,qy,MUTED_TEXT,true); qy+=12;}
    }

    private static boolean materialsReady(Civilisation civ, FactoryRecipe recipe) {
        if (civ == null || recipe == null) return false;
        for (var entry : recipe.getIngredients().entrySet()) {
            ResourceType type = resourceForItem(entry.getKey());
            if (type != null && civ.getResource(type) + 1.0e-9 < entry.getValue()) return false;
        }
        return true;
    }

    private static ResourceType resourceForItem(String itemId) {
        if (itemId == null) return null;
        String id = itemId.toLowerCase(java.util.Locale.ROOT);
        if (id.contains("wheat") || id.contains("bread") || id.contains("potato") || id.contains("carrot") || id.contains("beetroot")) return ResourceType.FOOD;
        if (id.contains("log") || id.contains("planks") || id.contains("wood") || id.contains("stick")) return ResourceType.WOOD;
        if (id.contains("cobblestone") || id.contains("stone")) return ResourceType.STONE;
        if (id.contains("coal")) return ResourceType.COAL;
        if (id.contains("iron")) return ResourceType.IRON;
        if (id.contains("gold")) return ResourceType.GOLD;
        if (id.contains("copper")) return ResourceType.COPPER;
        if (id.contains("redstone")) return ResourceType.REDSTONE;
        if (id.contains("lapis")) return ResourceType.LAPIS;
        if (id.contains("emerald")) return ResourceType.EMERALD;
        if (id.contains("diamond")) return ResourceType.DIAMOND;
        return null;
    }
    private static String shortId(String s){int i=s==null?-1:s.indexOf(':');return i>=0?s.substring(i+1):String.valueOf(s);}
    @Override protected StrategyScreen recreate(){return new ProductionScreen(getParentScreen());}
}
