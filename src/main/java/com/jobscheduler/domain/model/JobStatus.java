package com.jobscheduler.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * All states a job can be in.
 *
 * Valid transitions (enforced in JobService — throw IllegalStateException otherwise):
 *
 *   PENDING     → RUNNING      (worker claims the job)
 *   RUNNING     → COMPLETED    (worker reports success)
 *   RUNNING     → FAILED       (worker reports error or timeout)
 *   FAILED      → PENDING      (retry backoff — RetryPolicyService resets it)
 *   FAILED      → DEAD_LETTER  (max retries exhausted)
 *   PENDING     → CANCELLED    (DELETE /jobs/:id while job is waiting)
 *   FAILED      → CANCELLED    (DELETE /jobs/:id while job has failed but retries remain)
 *
 * Invalid transitions — always throw:
 *   COMPLETED   → anything     (terminal state, immutable)
 *   DEAD_LETTER → anything     (terminal state, requires manual intervention)
 *   CANCELLED   → anything     (terminal state)
 *   RUNNING     → CANCELLED    (cannot cancel a job already in-flight)
 */
public enum JobStatus {

    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    DEAD_LETTER;

    /**
     * Returns true if transitioning to {@code next} is a legal state move.
     * Call this before every status mutation in JobService.
     */
    public boolean canTransitionTo(JobStatus next) {
        return switch (this) {
            case PENDING     -> next == RUNNING   || next == CANCELLED;
            case RUNNING     -> next == COMPLETED || next == FAILED;
            case FAILED      -> next == PENDING   || next == DEAD_LETTER || next == CANCELLED;
            // Terminal states — no exit
            case COMPLETED, DEAD_LETTER, CANCELLED -> false;
        };
    }

    /** Convenience: throw if the transition is illegal. */
    public void assertCanTransitionTo(JobStatus next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                "Invalid job state transition: %s → %s".formatted(this, next)
            );
        }
    }

    /** Terminal states that can never be modified again. */
    public boolean isTerminal() {
        return this == COMPLETED || this == DEAD_LETTER || this == CANCELLED;
    }
}
