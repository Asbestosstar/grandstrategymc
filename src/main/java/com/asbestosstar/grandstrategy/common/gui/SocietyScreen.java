package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.Leader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

import java.util.Comparator;
import java.util.Map;

/** Read-only cohesion overview for religion and ideology. */
public final class SocietyScreen extends StrategyScreen {
    public SocietyScreen(Screen parent){super("Religion & Ideology",parent);}
    @Override protected void init(){beginIconLayout();StrategyClientContext.requestSync();addIconButton(UiIcon.BACK,"Back",6,6,b->this.minecraft.setScreen(getParentScreen()));}
    @Override protected void renderCustom(GuiGraphicsExtractor g,int mx,int my,float pt){Civilisation c=StrategyClientContext.currentPlayerCountry();g.text(this.font,"Religion & Ideology",18,38,TEXT,true);if(c==null)return;Leader leader=StrategyClientContext.leader(c.getDefaultLeaderId());int y=58;g.text(this.font,"Leader: "+(leader==null?c.getDefaultLeaderId():leader.getName())+" | religion: "+(leader==null?"unknown":String.valueOf(leader.getReligionId()))+" | ideology: "+(leader==null?"unknown":String.valueOf(leader.getIdeologyId())),20,y,TEXT,true);y+=16;g.text(this.font,"State religion: "+c.getStateReligionId()+" | religious extremism: "+Math.round(c.getReligiousExtremism())+"% | stability: "+Math.round(c.getStability()*100)+"%",20,y,TEXT,true);y+=16;g.text(this.font,"Population religions:",20,y,MUTED_TEXT,true);y+=13;for(var e:sorted(c.getPopulationReligions())){g.text(this.font,e.getKey()+" "+Math.round(e.getValue()*100)+"%",30,y,TEXT,true);y+=12;}y+=8;g.text(this.font,"State ideology: "+c.getIdeology()+" | ideological extremism: "+Math.round(c.getIdeologicalExtremism())+"%",20,y,TEXT,true);y+=16;for(var e:sorted(c.getIdeologySupport())){g.text(this.font,e.getKey()+" "+Math.round(e.getValue()*100)+"%",30,y,TEXT,true);y+=12;}y+=8;g.text(this.font,"High extremism strengthens stability when leader/state/population align, but magnifies fragmentation and civil-war risk when they do not.",20,y,WARNING_TEXT,true);}
    private static java.util.List<Map.Entry<String,Double>> sorted(Map<String,Double> map){return map.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue(Comparator.reverseOrder())).toList();}
    @Override protected StrategyScreen recreate(){return new SocietyScreen(getParentScreen());}
}

