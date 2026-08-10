package com.asbestosstar.grandstrategy.common.data;

import java.util.ArrayList;
import java.util.List;

/** Persistent leader definition including personal religion and ideology. */
public class Leader {
    private String id;
    private String name;
    private boolean immortal;
    private List<String> traits;
    private String religionId;
    /** Adopt this religion as soon as the religion becomes historically available. */
    private String snapReligionId;
    private String ideologyId;
    private String snapIdeologyId;
    private double religiousExtremism = 25.0;
    private double ideologicalExtremism = 25.0;

    public Leader() { }

    public Leader(String id, String name, boolean immortal, List<String> traits) {
        this(id, name, immortal, traits, null, null, null, null, 25.0, 25.0);
    }

    public Leader(String id, String name, boolean immortal, List<String> traits,
                  String religionId, String snapReligionId,
                  String ideologyId, String snapIdeologyId,
                  double religiousExtremism, double ideologicalExtremism) {
        this.id = id;
        this.name = name;
        this.immortal = immortal;
        this.traits = traits == null ? new ArrayList<>() : new ArrayList<>(traits);
        this.religionId = religionId;
        this.snapReligionId = snapReligionId;
        this.ideologyId = ideologyId;
        this.snapIdeologyId = snapIdeologyId;
        this.religiousExtremism = clamp(religiousExtremism);
        this.ideologicalExtremism = clamp(ideologicalExtremism);
    }

    public void normaliseAfterLoad() {
        if (traits == null) traits = new ArrayList<>();
        religiousExtremism = clamp(religiousExtremism);
        ideologicalExtremism = clamp(ideologicalExtremism);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isImmortal() { return immortal; }
    public List<String> getTraits() { return traits == null ? List.of() : List.copyOf(traits); }
    public String getReligionId() { return religionId; }
    public String getSnapReligionId() { return snapReligionId; }
    public String getIdeologyId() { return ideologyId; }
    public String getSnapIdeologyId() { return snapIdeologyId; }
    public double getReligiousExtremism() { return clamp(religiousExtremism); }
    public double getIdeologicalExtremism() { return clamp(ideologicalExtremism); }

    public void setReligionId(String religionId) { this.religionId = religionId; }
    public void setIdeologyId(String ideologyId) { this.ideologyId = ideologyId; }
    public void setReligiousExtremism(double value) { religiousExtremism = clamp(value); }
    public void setIdeologicalExtremism(double value) { ideologicalExtremism = clamp(value); }

    private static double clamp(double value) { return Math.max(0.0, Math.min(100.0, value)); }

    @Override
    public String toString() {
        return "Leader{id='" + id + "', name='" + name + "', immortal=" + immortal + "}";
    }
}
