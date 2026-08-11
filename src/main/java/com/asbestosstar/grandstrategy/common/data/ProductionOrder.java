package com.asbestosstar.grandstrategy.common.data;

/** Mutable civilisation-owned production queue entry. */
public class ProductionOrder {
    private long serial;
    private String recipeId;
    private int requested;
    private int completed;
    private boolean paused;

    public ProductionOrder() { }

    public ProductionOrder(long serial, String recipeId, int requested) {
        this.serial = serial;
        this.recipeId = recipeId;
        this.requested = Math.max(1, requested);
    }

    public long getSerial() { return serial; }
    public String getRecipeId() { return recipeId; }
    public int getRequested() { return Math.max(1, requested); }
    public int getCompleted() { return Math.max(0, completed); }
    public int getRemaining() { return Math.max(0, getRequested() - getCompleted()); }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public void addCompleted(int amount) { completed = Math.min(getRequested(), Math.max(0, completed + Math.max(0, amount))); }
    public boolean isComplete() { return getRemaining() <= 0; }
}

