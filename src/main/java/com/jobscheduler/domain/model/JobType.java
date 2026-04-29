package com.jobscheduler.domain.model;

/**
 * Supported job execution types.
 *
 * Each type has a corresponding JobHandler implementation registered as a @Component.
 * WorkerPool builds a Map<JobType, JobHandler> at startup by injecting all handlers.
 *
 * Adding a new type = add the enum value here + add a new @Component JobHandler impl.
 * No other wiring needed.
 *
 * Payload shape per type:
 *
 *   WEBHOOK → { "url": "https://...", "httpMethod": "POST", "body": { ... } }
 *   SHELL   → { "command": "echo hello world" }
 */
public enum JobType {

    /** HTTP POST to a URL. WebhookJobHandler executes this. */
    WEBHOOK,

    /** Shell command via ProcessBuilder. ShellJobHandler executes this. */
    SHELL
}
