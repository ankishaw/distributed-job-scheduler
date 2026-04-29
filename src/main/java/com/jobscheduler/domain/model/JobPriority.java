package com.jobscheduler.domain.model;

/**
 * Job priority levels — stored as int in the DB (priority column).
 * Higher value = dispatched first.
 *
 * Scheduler query: ORDER BY priority DESC, created_at ASC
 *
 * Starvation note: CRITICAL jobs arriving continuously will block LOW jobs.
 * Anti-starvation: a @Scheduled query bumps priority by 1 for any PENDING job
 * waiting more than N minutes (see StaleJobReclaimer or a dedicated promoter).
 */
public enum JobPriority {

    LOW(1),
    MEDIUM(2),      // default
    HIGH(3),
    CRITICAL(4);

    private final int value;

    JobPriority(int value) { this.value = value; }

    public int getValue() { return value; }

    /** Reverse-lookup from int value stored in DB. */
    public static JobPriority fromValue(int value) {
        for (JobPriority p : values()) {
            if (p.value == value) return p;
        }
        throw new IllegalArgumentException("Unknown priority value: " + value);
    }
}
