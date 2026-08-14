package com.asbestosstar.grandstrategy.common.network;

/**
 * Packet to synchronise the historical timeline across the network.
 */
public class SyncTimelinePacket extends Packet {
    private final int year;
    private final String era;

    public SyncTimelinePacket(int year, String era) {
        super(Type.SYNC_TIMELINE);
        this.year = year;
        this.era = era;
    }

    public int getYear() { return year; }
    public String getEra() { return era; }
}




