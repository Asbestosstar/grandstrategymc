package com.asbestosstar.grandstrategy.common.gui;

import com.asbestosstar.grandstrategy.common.data.Civilisation;
import com.asbestosstar.grandstrategy.common.data.DiplomaticWarGoal;
import com.asbestosstar.grandstrategy.common.data.Ideology;
import com.asbestosstar.grandstrategy.common.data.Leader;
import com.asbestosstar.grandstrategy.common.data.Providence;
import com.asbestosstar.grandstrategy.common.engine.DiplomacySystem;
import com.asbestosstar.grandstrategy.common.engine.WarSystem;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Detailed bilateral diplomacy page for one foreign civilisation. */
public final class DiplomacyCountryScreen extends StrategyScreen {
    private final String targetId;
    private final int tab;
    private final int territoryIndex;
    private String statusMessage = "";

    public DiplomacyCountryScreen(Screen parent, String targetId) {
        this(parent, targetId, 0, 0);
    }

    private DiplomacyCountryScreen(Screen parent, String targetId, int tab, int territoryIndex) {
        super("Country Diplomacy", parent);
        this.targetId = targetId;
        this.tab = Math.max(0, Math.min(2, tab));
        this.territoryIndex = Math.max(0, territoryIndex);
    }

    @Override
    protected void init() {
        beginIconLayout();
        addIconButton(UiIcon.BACK, "Back to country list", 6, 6, button ->
                this.minecraft.setScreen(getParentScreen()));

        Civilisation own = StrategyClientContext.currentPlayerCountry();
        Civilisation target = StrategyClientContext.getCivilisation(targetId);
        if (own == null || target == null) return;

        int actionX = Math.max(144, this.width / 2 - 14);
        int actionWidth = Math.max(120, this.width - actionX - 12);
        int third = Math.max(62, actionWidth / 3);
        this.addRenderableWidget(Button.builder(Component.literal("Conflict"), b ->
                        this.minecraft.setScreen(new DiplomacyCountryScreen(getParentScreen(), targetId, 0, territoryIndex)))
                .bounds(actionX, 64, third - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Treaties"), b ->
                        this.minecraft.setScreen(new DiplomacyCountryScreen(getParentScreen(), targetId, 1, territoryIndex)))
                .bounds(actionX + third, 64, third - 2, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Relations"), b ->
                        this.minecraft.setScreen(new DiplomacyCountryScreen(getParentScreen(), targetId, 2, territoryIndex)))
                .bounds(actionX + third * 2, 64, Math.max(54, actionWidth - third * 2), 20).build());

        if (tab == 0) initConflict(own, target, actionX, actionWidth);
        else if (tab == 1) initTreaties(own, target, actionX, actionWidth);
        else initRelations(own, target, actionX, actionWidth);
    }

    private void initConflict(Civilisation own, Civilisation target, int x, int width) {
        int y = 90;
        List<Providence> territories = targetTerritories();
        Providence selected = territories.isEmpty() ? null : territories.get(Math.floorMod(territoryIndex, territories.size()));

        String territoryLabel = selected == null ? "No target territory" : "Goal: " + selected.getName() + "  >";
        this.addRenderableWidget(Button.builder(Component.literal(territoryLabel), b -> {
                    int next = territories.isEmpty() ? 0 : (territoryIndex + 1) % territories.size();
                    this.minecraft.setScreen(new DiplomacyCountryScreen(getParentScreen(), targetId, tab, next));
                }).bounds(x, y, width, 20).build());
        y += 23;

        this.addRenderableWidget(Button.builder(Component.literal("Justify Territory (15 PP)"), b ->
                        send(DiplomacySystem.JUSTIFY_TERRITORY, selected == null ? null : selected.getId(),
                                "Territorial war-goal request sent."))
                .bounds(x, y, width, 20).build());
        y += 23;

        this.addRenderableWidget(Button.builder(Component.literal("Justify Puppet (30 PP)"), b ->
                        send(DiplomacySystem.JUSTIFY_PUPPET, null, "Puppet war-goal request sent."))
                .bounds(x, y, width, 20).build());
        y += 23;

        WarSystem.WarState war = StrategyClientContext.warBetween(own.getId(), target.getId());
        if (war != null) {
            this.addRenderableWidget(Button.builder(Component.literal("Open Peace Conference"), b ->
                            this.minecraft.setScreen(new PeaceConferenceScreen(this, targetId)))
                    .bounds(x, y, width, 20).build());
        } else {
            this.addRenderableWidget(Button.builder(Component.literal("Declare War (uses war goal)"), b ->
                            send(DiplomacySystem.DECLARE_WAR, null, "War declaration request sent."))
                    .bounds(x, y, width, 20).build());
        }
        y += 23;

        this.addRenderableWidget(Button.builder(Component.literal("Special Military Operation (20 PP)"), b ->
                        send(DiplomacySystem.SPECIAL_MILITARY_OPERATION,
                                selected == null ? null : selected.getId(), "Special-operation request sent."))
                .bounds(x, y, width, 20).build());
        y += 23;

        String guarantee = own.guarantees(target.getId()) ? "Cancel Guarantee" : "Guarantee Independence (12 PP)";
        String guaranteeAction = own.guarantees(target.getId())
                ? DiplomacySystem.CANCEL_GUARANTEE : DiplomacySystem.GUARANTEE;
        this.addRenderableWidget(Button.builder(Component.literal(guarantee), b ->
                        send(guaranteeAction, null, "Guarantee status request sent."))
                .bounds(x, y, width, 20).build());
    }

    private void initTreaties(Civilisation own, Civilisation target, int x, int width) {
        int y = 90;
        addTreatyButton(x, y, width,
                own.isAlliedWith(target.getId()) ? "Cancel Bilateral Alliance" : "Propose Bilateral Alliance (10 PP)",
                own.isAlliedWith(target.getId()) ? DiplomacySystem.CANCEL_ALLIANCE : DiplomacySystem.ALLIANCE);
        y += 23;
        addTreatyButton(x, y, width,
                own.hasDefensivePactWith(target.getId()) ? "Cancel Defensive Pact" : "Propose Defensive Pact (8 PP)",
                own.hasDefensivePactWith(target.getId()) ? DiplomacySystem.CANCEL_DEFENSIVE_PACT : DiplomacySystem.DEFENSIVE_PACT);
        y += 23;
        addTreatyButton(x, y, width,
                target.grantsMilitaryAccessTo(own.getId()) ? "Cancel Military Access" : "Request Military Access (3 PP)",
                target.grantsMilitaryAccessTo(own.getId()) ? DiplomacySystem.CANCEL_MILITARY_ACCESS : DiplomacySystem.MILITARY_ACCESS);
        y += 23;
        addTreatyButton(x, y, width,
                own.hasResearchAgreementWith(target.getId()) ? "Cancel Research Agreement" : "Propose Research Agreement (8 PP)",
                own.hasResearchAgreementWith(target.getId()) ? DiplomacySystem.CANCEL_RESEARCH_AGREEMENT : DiplomacySystem.RESEARCH_AGREEMENT);
        y += 23;

        String factionLabel;
        String factionAction;
        if (own.getFactionId() != null) {
            factionLabel = "Leave Faction: " + own.getFactionId();
            factionAction = DiplomacySystem.LEAVE_FACTION;
        } else {
            factionLabel = target.getFactionId() == null ? "Form Faction Together (15 PP)"
                    : "Request to Join " + target.getFactionId() + " (15 PP)";
            factionAction = DiplomacySystem.JOIN_FACTION;
        }
        addTreatyButton(x, y, width, factionLabel, factionAction);
        y += 23;

        String wedding = own.hasRoyalMarriageWith(target.getId()) ? "Royal Houses Already Joined"
                : "Propose Royal Wedding (12 PP)";
        addTreatyButton(x, y, width, wedding, DiplomacySystem.ROYAL_WEDDING);
    }

    private void initRelations(Civilisation own, Civilisation target, int x, int width) {
        int y = 90;
        this.addRenderableWidget(Button.builder(Component.literal("Improve Relations (10 PP)"), b ->
                        send(DiplomacySystem.IMPROVE, null, "Relations-improvement request sent."))
                .bounds(x, y, width, 20).build());
        y += 23;
        this.addRenderableWidget(Button.builder(Component.literal("Send Insult (2 PP)"), b ->
                        send(DiplomacySystem.INSULT, null, "Insult sent."))
                .bounds(x, y, width, 20).build());
        y += 31;

        String incoming = own.getPendingDiplomaticOfferFrom(target.getId());
        if (incoming != null) {
            this.addRenderableWidget(Button.builder(Component.literal("Accept: " + prettyAction(incoming)), b ->
                            send(DiplomacySystem.ACCEPT_OFFER, null, "Diplomatic offer accepted."))
                    .bounds(x, y, width, 20).build());
            y += 23;
            this.addRenderableWidget(Button.builder(Component.literal("Reject Offer"), b ->
                            send(DiplomacySystem.REJECT_OFFER, null, "Diplomatic offer rejected."))
                    .bounds(x, y, width, 20).build());
        }
    }

    private void addTreatyButton(int x, int y, int width, String label, String action) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b ->
                        send(action, null, "Diplomatic request sent."))
                .bounds(x, y, width, 20).build());
    }

    private void send(String action, String argument, String successMessage) {
        boolean sent = argument == null
                ? StrategyClientContext.requestDiplomacy(targetId, action)
                : StrategyClientContext.requestDiplomacy(targetId, action, argument);
        statusMessage = sent ? successMessage : "Not connected to a Grand Strategy server.";
        if (sent) scheduleRefresh();
    }

    @Override
    protected void renderCustom(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Civilisation own = StrategyClientContext.currentPlayerCountry();
        Civilisation target = StrategyClientContext.getCivilisation(targetId);
        if (own == null || target == null) {
            graphics.text(this.font, "Country is no longer available.", 18, 42, WARNING_TEXT, true);
            return;
        }

        drawFlag(graphics, target, 18, 42, 34, 20);
        graphics.text(this.font, governmentCountryName(target), 60, 47, TEXT, true);
        graphics.text(this.font,
                "Relation " + own.getRelation(target.getId()) + " | Your PP " + (int) own.getPoliticalPower()
                        + (target.getFactionId() == null ? "" : " | Faction: " + target.getFactionId()),
                18, 66, MUTED_TEXT, true);

        drawLeaderPlaceholder(graphics, target, 18, 86, 112, 92);
        Leader leader = StrategyClientContext.leader(target.getDefaultLeaderId());
        String leaderName = leader == null ? String.valueOf(target.getDefaultLeaderId()) : leader.getName();
        graphics.text(this.font, leaderName, 18, 184, TEXT, true);
        String traits = leader == null || leader.getTraits().isEmpty()
                ? "Personality: unknown" : "Traits: " + String.join(", ", leader.getTraits());
        graphics.text(this.font, trim(traits, 31), 18, 197, MUTED_TEXT, true);
        graphics.text(this.font, "Government: " + target.getGovernment(), 18, 210, MUTED_TEXT, true);
        if (target.isMonarchyGovernment()) {
            graphics.text(this.font, "Royal children: " + target.getRoyalChildren(), 18, 223, MUTED_TEXT, true);
        }

        DiplomaticWarGoal goal = own.getWarGoalAgainst(target.getId());
        WarSystem.WarState war = StrategyClientContext.warBetween(own.getId(), target.getId());
        int infoX = Math.max(144, this.width / 2 - 14);
        if (war != null) {
            String conflict = war.specialOperation && !war.escalated ? "LIMITED SPECIAL OPERATION"
                    : war.specialOperation ? "ESCALATED WAR" : "WAR";
            graphics.text(this.font, conflict + " | score " + (int) Math.round(war.scoreFor(own.getId())),
                    infoX, 42, BAD_TEXT, true);
            if (war.warGoalType != null) {
                graphics.text(this.font, "War goal: " + war.warGoalType
                                + (war.warGoalTerritoryId == null ? "" : " / " + war.warGoalTerritoryId),
                        infoX, 53, WARNING_TEXT, true);
            }
        } else if (goal != null) {
            graphics.text(this.font, "Justified goal: " + goal.getType()
                            + (goal.getTerritoryId() == null ? "" : " / " + goal.getTerritoryId()),
                    infoX, 42, WARNING_TEXT, true);
        }

        if (tab == 0) {
            boolean smoTech = own.hasTechnology("special_military_operations");
            String rule = "SMO: needs 2025 doctrine + target corporatist; limited territory must locally favour you.";
            graphics.text(this.font, trim(rule, Math.max(38, (this.width - infoX) / 6)),
                    infoX, this.height - 14, smoTech ? MUTED_TEXT : WARNING_TEXT, true);
        } else if (tab == 1) {
            graphics.text(this.font, "AI acceptance uses relations, shared enemies, ideology and leader traits.",
                    infoX, this.height - 14, MUTED_TEXT, true);
        }

        if (!statusMessage.isBlank()) {
            graphics.text(this.font, statusMessage, 18, this.height - 14, WARNING_TEXT, true);
        }
    }

    private List<Providence> targetTerritories() {
        return StrategyClientContext.providences().stream()
                .filter(Providence::isEstablished)
                .filter(p -> targetId.equals(p.getOwnerId()))
                .sorted(Comparator.comparing(Providence::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String governmentCountryName(Civilisation civ) {
        String id = civ.getIdeology() == null ? "" : civ.getIdeology().toLowerCase(Locale.ROOT);
        String family = "";
        for (Ideology ideology : StrategyClientContext.ideologies()) {
            if (ideology != null && civ.getIdeology() != null && civ.getIdeology().equals(ideology.getId())) {
                family = ideology.getFamilyId() == null ? "" : ideology.getFamilyId().toLowerCase(Locale.ROOT);
                break;
            }
        }
        if (family.contains("democrat") || family.contains("republic") || id.contains("republic")) return "Republic of " + civ.getName();
        if (family.contains("socialist") || id.contains("commun")) return "People's Republic of " + civ.getName();
        if (family.contains("monarch") || id.contains("monarch") || id.contains("palatial")) return "Kingdom of " + civ.getName();
        if (family.contains("theocrat") || id.contains("theocra") || id.contains("sacred")) return "Holy State of " + civ.getName();
        if (family.contains("corporat") || id.contains("corporat")) return "Corporate State of " + civ.getName();
        if (family.contains("ultranational") || id.contains("fasc")) return "State of " + civ.getName();
        return civ.getName();
    }

    private void drawLeaderPlaceholder(GuiGraphicsExtractor g, Civilisation civ, int x, int y, int w, int h) {
        int frame = 0xFF77818C;
        int back = 0xFF20262D;
        int silhouette = 0xFF8A939C;
        g.fill(x, y, x + w, y + h, back);
        g.outline(x, y, w, h, frame);
        int cx = x + w / 2;
        g.fill(cx - 20, y + 12, cx + 20, y + 47, silhouette); // faceless head
        g.fill(cx - 34, y + 51, cx + 34, y + h - 8, silhouette); // shoulders/body
        g.text(this.font, "Leader portrait", x + 8, y + h - 18, 0xFFD6DCE2, true);
    }

    private static void drawFlag(GuiGraphicsExtractor g, Civilisation civ, int x, int y, int w, int h) {
        int base = civ.getBorderColourArgb();
        int stripeA = shade(base, 30);
        int stripeB = shade(base, -35);
        int variant = Math.floorMod(civ.getId() == null ? 0 : civ.getId().hashCode(), 3);
        if (variant == 0) {
            g.fill(x, y, x + w, y + h / 3, stripeA);
            g.fill(x, y + h / 3, x + w, y + h * 2 / 3, base);
            g.fill(x, y + h * 2 / 3, x + w, y + h, stripeB);
        } else if (variant == 1) {
            g.fill(x, y, x + w / 3, y + h, stripeB);
            g.fill(x + w / 3, y, x + w * 2 / 3, y + h, base);
            g.fill(x + w * 2 / 3, y, x + w, y + h, stripeA);
        } else {
            g.fill(x, y, x + w, y + h, base);
            g.fill(x, y + h / 2 - 2, x + w, y + h / 2 + 2, stripeA);
            g.fill(x + w / 2 - 2, y, x + w / 2 + 2, y + h, stripeA);
        }
        g.outline(x, y, w, h, 0xFFE6EBF0);
    }

    private static int shade(int argb, int delta) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) a = 0xFF;
        int r = Math.max(0, Math.min(255, ((argb >>> 16) & 0xFF) + delta));
        int gr = Math.max(0, Math.min(255, ((argb >>> 8) & 0xFF) + delta));
        int b = Math.max(0, Math.min(255, (argb & 0xFF) + delta));
        return (a << 24) | (r << 16) | (gr << 8) | b;
    }

    private static String prettyAction(String action) {
        if (action == null) return "Diplomatic Offer";
        return action.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String trim(String text, int max) {
        if (text == null || text.length() <= max) return text == null ? "" : text;
        return text.substring(0, Math.max(1, max - 3)) + "...";
    }

    @Override
    protected StrategyScreen recreate() {
        return new DiplomacyCountryScreen(getParentScreen(), targetId, tab, territoryIndex);
    }
}
