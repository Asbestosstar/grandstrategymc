package com.asbestosstar.grandstrategy.common.network;

import java.io.Serializable;

/**
 * Base class for all network packets in the Grand Strategy mod.
 * Following British English standards.
 */
public abstract class Packet implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public enum Type {
        SYNC_TIMELINE,
        SYNC_CIVILISATION,
        SYNC_PROVIDENCE,
        SYNC_WAR,
        ACTION_FOCUS_SELECT
    }

    private final Type type;

    protected Packet(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}




