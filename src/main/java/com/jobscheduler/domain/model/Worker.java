package com.jobscheduler.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Worker heartbeat registry.
 *
 * PK is NODE_ID (String from the NODE_ID env var), not a UUID.
 * HeartbeatService upserts this row every 10 seconds using Spring Data's save().
 * StaleJobReclaimer queries: WHERE last_seen < now() - 30s → dead worker.
 */
@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(nullable = false)
    private String hostname;

    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    protected Worker() {}

    public Worker(String nodeId, String hostname) {
        this.nodeId    = nodeId;
        this.hostname  = hostname;
        this.lastSeen  = Instant.now();
        this.startedAt = Instant.now();
    }

    /** Called by HeartbeatService every 10 s. */
    public void touch() {
        this.lastSeen = Instant.now();
    }

    public String getNodeId()      { return nodeId; }
    public String getHostname()    { return hostname; }
    public Instant getLastSeen()   { return lastSeen; }
    public void setLastSeen(Instant t) { this.lastSeen = t; }
    public Instant getStartedAt()  { return startedAt; }
}
