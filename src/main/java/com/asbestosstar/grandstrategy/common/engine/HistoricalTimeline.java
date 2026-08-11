package com.asbestosstar.grandstrategy.common.engine;

import com.asbestosstar.grandstrategy.common.network.NetworkManager;
import com.asbestosstar.grandstrategy.common.network.SyncTimelinePacket;

/**
 * World-scoped historical clock from 3900 BCE through 2026 CE.
 *
 * One Minecraft day is 24,000 server ticks. The first full Minecraft day advances
 * exactly 100 Grand Strategy years. The rate then decelerates smoothly as history
 * progresses until the 2026 endpoint is exactly one Grand Strategy day per
 * Minecraft day.
 */
public final class HistoricalTimeline {
    public static final long START_YEAR = -3_900;
    public static final long END_YEAR = 2026;
    public static final int DAYS_PER_YEAR = 365;
    public static final int MINECRAFT_TICKS_PER_DAY = 24_000;
    public static final double START_GS_DAYS_PER_MINECRAFT_DAY = 100.0 * DAYS_PER_YEAR;
    public static final double END_GS_DAYS_PER_MINECRAFT_DAY = 1.0;

    private long currentYear;
    private int currentDay;
    private double partialDay;
    private int minecraftDayTick;
    private double cachedGsDaysPerMinecraftDay;
    private double gsDaysAccumulatedThisMinecraftDay;
    private Era currentEra;

    public HistoricalTimeline() {
        reset();
    }

    public synchronized void reset() {
        currentYear = START_YEAR;
        currentDay = 0;
        partialDay = 0.0;
        minecraftDayTick = 0;
        cachedGsDaysPerMinecraftDay = START_GS_DAYS_PER_MINECRAFT_DAY;
        gsDaysAccumulatedThisMinecraftDay = 0.0;
        currentEra = Era.PREHISTORIC;
    }

    /**
     * Advances one Minecraft server tick.
     *
     * The rate is sampled at the beginning of each 24,000-tick Minecraft day and
     * held constant for that day. This makes the first Minecraft day exactly 100
     * GS years rather than merely approximately 100 years.
     *
     * @return number of whole Grand Strategy days advanced this tick
     */
    public synchronized int advanceTimeline() {
        if (isAtEnd()) {
            cachedGsDaysPerMinecraftDay = END_GS_DAYS_PER_MINECRAFT_DAY;
            return 0;
        }

        boolean finalTickOfMinecraftDay = minecraftDayTick == MINECRAFT_TICKS_PER_DAY - 1;
        double increment = finalTickOfMinecraftDay
                ? cachedGsDaysPerMinecraftDay - gsDaysAccumulatedThisMinecraftDay
                : cachedGsDaysPerMinecraftDay / MINECRAFT_TICKS_PER_DAY;
        gsDaysAccumulatedThisMinecraftDay += increment;
        partialDay += increment;

        // Remove tiny floating-point residue at exact whole-day boundaries.
        double nearestWhole = Math.rint(partialDay);
        if (Math.abs(partialDay - nearestWhole) < 1.0e-9) partialDay = nearestWhole;

        int daysToAdvance = (int) Math.floor(partialDay);
        if (daysToAdvance > 0) partialDay -= daysToAdvance;

        int advanced = 0;
        while (advanced < daysToAdvance && !isAtEnd()) {
            advanceOneDay();
            advanced++;
        }

        minecraftDayTick++;
        if (minecraftDayTick >= MINECRAFT_TICKS_PER_DAY) {
            minecraftDayTick = 0;
            gsDaysAccumulatedThisMinecraftDay = 0.0;
            cachedGsDaysPerMinecraftDay = calculateGsDaysPerMinecraftDay();
        }

        if (advanced > 0) {
            updateEra();
            synchroniseTimeline();
        }
        return advanced;
    }

    private void advanceOneDay() {
        currentDay++;
        if (currentDay < DAYS_PER_YEAR) {
            return;
        }

        currentDay = 0;
        currentYear++;

        // Historical calendars have no year zero: 1 BCE is followed by 1 CE.
        if (currentYear == 0) {
            currentYear = 1;
        }
    }

    /**
     * Cheat/testing-only calendar jump. This changes Grand Strategy historical
     * time directly without advancing Minecraft world time or replaying millions
     * of ordinary server ticks.
     *
     * @return the number of Grand Strategy days actually advanced
     */
    public synchronized long cheatAdvanceDays(long requestedDays) {
        if (requestedDays <= 0L || isAtEnd()) {
            return 0L;
        }

        long currentIndex = (long) continuousDayIndex(currentYear, currentDay);
        long endIndex = (long) continuousDayIndex(END_YEAR, DAYS_PER_YEAR - 1);
        long remaining = Math.max(0L, endIndex - currentIndex);
        long advanced = Math.min(requestedDays, remaining);
        long targetIndex = currentIndex + advanced;

        long continuousYear = Math.floorDiv(targetIndex, DAYS_PER_YEAR);
        int targetDay = (int) Math.floorMod(targetIndex, DAYS_PER_YEAR);
        currentYear = continuousYear >= 0L ? continuousYear + 1L : continuousYear;
        currentDay = targetDay;

        // A manual jump starts a fresh pacing phase at the new historical date.
        partialDay = 0.0;
        minecraftDayTick = 0;
        gsDaysAccumulatedThisMinecraftDay = 0.0;
        cachedGsDaysPerMinecraftDay = calculateGsDaysPerMinecraftDay();

        updateEra();
        synchroniseTimeline();
        return advanced;
    }

    private boolean isAtEnd() {
        return currentYear >= END_YEAR && currentDay >= DAYS_PER_YEAR - 1;
    }

    /**
     * Smooth pacing curve. sqrt(remaining progress) keeps prehistoric/ancient
     * history moving quickly, then gives progressively more play time to modern
     * history. Endpoints are exact: 36,500 GS days/MC day at the beginning and
     * 1 GS day/MC day at 2026.
     */
    private double calculateGsDaysPerMinecraftDay() {
        double progress = timelineProgress();
        if (progress <= 0.0) return START_GS_DAYS_PER_MINECRAFT_DAY;
        if (progress >= 1.0) return END_GS_DAYS_PER_MINECRAFT_DAY;

        double remaining = Math.sqrt(1.0 - progress);
        return END_GS_DAYS_PER_MINECRAFT_DAY
                + (START_GS_DAYS_PER_MINECRAFT_DAY - END_GS_DAYS_PER_MINECRAFT_DAY) * remaining;
    }

    private double timelineProgress() {
        double elapsedDays = continuousDayIndex(currentYear, currentDay)
                - continuousDayIndex(START_YEAR, 0);
        double totalDays = continuousDayIndex(END_YEAR, 0)
                - continuousDayIndex(START_YEAR, 0);
        return Math.max(0.0, Math.min(1.0, elapsedDays / totalDays));
    }

    private static double continuousDayIndex(long historicalYear, int day) {
        long continuousYear = historicalYear > 0 ? historicalYear - 1 : historicalYear;
        return continuousYear * (double) DAYS_PER_YEAR + day;
    }

    private void updateEra() {
        if (currentYear < -3000) {
            currentEra = Era.PREHISTORIC;
        } else if (currentYear < 500) {
            currentEra = Era.ANCIENT;
        } else if (currentYear < 1500) {
            currentEra = Era.MEDIEVAL;
        } else if (currentYear < 1800) {
            currentEra = Era.EARLY_MODERN;
        } else if (currentYear < 1945) {
            currentEra = Era.MODERN;
        } else {
            currentEra = Era.CONTEMPORARY;
        }
    }

    private void synchroniseTimeline() {
        NetworkManager.getInstance().sendPacket(
                new SyncTimelinePacket((int) currentYear, currentEra.name()));
    }

    /** Backwards-compatible restore used by version-1/2 world saves. */
    public synchronized void restore(long year, int day, double partialDay) {
        restore(year, day, partialDay, 0, Double.NaN);
    }

    public synchronized void restore(long year, int day, double partialDay,
                                     int minecraftDayTick, double cachedRate) {
        long restoredYear = Math.max(START_YEAR, Math.min(END_YEAR, year));
        if (restoredYear == 0) restoredYear = 1;

        this.currentYear = restoredYear;
        this.currentDay = Math.max(0, Math.min(DAYS_PER_YEAR - 1, day));
        this.partialDay = Math.max(0.0, Math.min(0.999999999, partialDay));
        this.minecraftDayTick = Math.max(0, Math.min(MINECRAFT_TICKS_PER_DAY - 1, minecraftDayTick));
        this.cachedGsDaysPerMinecraftDay = Double.isFinite(cachedRate) && cachedRate >= 1.0
                ? cachedRate : calculateGsDaysPerMinecraftDay();
        this.gsDaysAccumulatedThisMinecraftDay = this.cachedGsDaysPerMinecraftDay
                * this.minecraftDayTick / MINECRAFT_TICKS_PER_DAY;
        updateEra();
    }

    public synchronized long getCurrentYear() { return currentYear; }
    public synchronized int getCurrentDay() { return currentDay; }
    public synchronized double getPartialDay() { return partialDay; }
    public synchronized int getMinecraftDayTick() { return minecraftDayTick; }
    public synchronized double getGsDaysPerMinecraftDay() { return cachedGsDaysPerMinecraftDay; }
    public synchronized Era getCurrentEra() { return currentEra; }

    public synchronized String getFormattedYear() {
        return currentYear < 0 ? Math.abs(currentYear) + " BCE" : currentYear + " CE";
    }

    public enum Era {
        PREHISTORIC,
        ANCIENT,
        MEDIEVAL,
        EARLY_MODERN,
        MODERN,
        CONTEMPORARY
    }
}




