package com.asbestosstar.grandstrategy.common.ai;

/** High-level objective categories. These survive local path failures. */
public enum WorkerIntent {
    IDLE,
    TRAVEL_TO_WORK,
    RETURN_TO_DEPOT,
    SEEK_MEAL,
    BUILD_OR_OPERATE_DISTRICT,
    ARMY_ORDER,
    ESCAPE_THEN_RESUME
}


