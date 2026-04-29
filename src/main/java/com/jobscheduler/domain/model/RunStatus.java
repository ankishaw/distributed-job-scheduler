package com.jobscheduler.domain.model;

/** Terminal status for a single execution attempt recorded in job_runs. */
public enum RunStatus {
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CANCELLED
}
