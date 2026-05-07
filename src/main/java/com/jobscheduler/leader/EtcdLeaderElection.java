package com.jobscheduler.leader;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.Election;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.election.CampaignResponse;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leader election via etcd campaign API.
 *
 * How it works:
 *   1. Node creates a lease with a 15s TTL.
 *   2. Starts keepAlive stream — etcd server auto-renews the lease.
 *   3. Calls election.campaign() — blocks until this node wins the election.
 *   4. On leadership, sets isLeader=true.
 *   5. If the lease expires (keepAlive fails), etcd auto-revokes leadership.
 *   6. Other nodes waiting in campaign() automatically win after the TTL.
 *
 * Stronger than Redis implementation:
 *   etcd uses Raft consensus (CP). Under network partition, only the
 *   majority partition can elect a leader — preventing split-brain.
 *   Redis leader election can have split-brain under network partition (AP).
 *
 * Failover time:
 *   = lease TTL (15s) if leader crashes without releasing.
 *   = near-instant if leader releases cleanly (revokes lease).
 *
 * Activated when: scheduler.leader.backend=etcd
 */
@Component
@ConditionalOnProperty(
        name = "scheduler.leader.backend",
        havingValue = "etcd"
)
public class EtcdLeaderElection implements LeaderElection {

    private static final Logger log = LoggerFactory.getLogger(EtcdLeaderElection.class);

    private static final String ELECTION_NAME = "scheduler-leader";
    private static final long   LEASE_TTL     = 15L; // seconds

    @Value("${NODE_ID:local-1}")
    private String nodeId;

    @Value("${scheduler.etcd.endpoints:http://localhost:2379}")
    private String etcdEndpoints;

    private Client           etcdClient;
    private Lease            leaseClient;
    private Election         electionClient;
    private long             leaseId;
    private final AtomicBoolean leader    = new AtomicBoolean(false);
    private final AtomicLong    leaseIdRef = new AtomicLong(0);
    private ExecutorService  campaignExecutor;
    private volatile boolean running = false;

    @Override
    public void start() {
        running = true;

        etcdClient     = Client.builder().endpoints(etcdEndpoints).build();
        leaseClient    = etcdClient.getLeaseClient();
        electionClient = etcdClient.getElectionClient();

        campaignExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "leader-campaign");
            t.setDaemon(true);
            return t;
        });

        campaignExecutor.submit(this::runCampaign);
        log.info("EtcdLeaderElection started for node {} (TTL={}s, endpoints={})",
            nodeId, LEASE_TTL, etcdEndpoints);
    }

    @Override
    public void stop() {
        running = false;
        leader.set(false);

        // Revoking the lease immediately removes our campaign entry —
        // another node wins within milliseconds instead of waiting for TTL expiry.
        long id = leaseIdRef.get();
        if (id != 0 && leaseClient != null) {
            try {
                leaseClient.revoke(id).get();
                log.info("Node {} revoked etcd lease on shutdown — fast failover triggered", nodeId);
            } catch (Exception e) {
                log.warn("Could not revoke etcd lease on shutdown: {}", e.getMessage());
            }
        }

        if (campaignExecutor != null) campaignExecutor.shutdownNow();
        if (etcdClient       != null) etcdClient.close();
    }

    @Override
    public boolean isLeader() {
        return leader.get();
    }

    @Override
    public String getLeaderNodeId() {
        return leader.get() ? nodeId : "unknown";
    }

    // ── Campaign loop ─────────────────────────────────────────────────────────

    /**
     * Runs in a dedicated thread:
     *  1. Grant a lease.
     *  2. Start keepAlive stream.
     *  3. Call campaign() — blocks until we win.
     *  4. On win, set isLeader=true.
     *  5. If leadership is lost (keepAlive fails), restart the loop.
     */
    private void runCampaign() {
        while (running) {
            try {
                // Step 1: Grant lease
                leaseId = leaseClient.grant(LEASE_TTL).get().getID();
                leaseIdRef.set(leaseId);
                log.debug("Node {} granted etcd lease {} (TTL={}s)", nodeId, leaseId, LEASE_TTL);

                // Step 2: Start keepAlive — etcd auto-renews lease as long as stream is alive
                startKeepAlive(leaseId);

                // Step 3: Campaign — blocks until we win
                log.info("Node {} campaigning for leader election...", nodeId);
                ByteSequence electionName = ByteSequence.from(ELECTION_NAME, StandardCharsets.UTF_8);
                ByteSequence value        = ByteSequence.from(nodeId, StandardCharsets.UTF_8);

                CampaignResponse response = electionClient.campaign(electionName, leaseId, value).get();
                log.info("Node {} won leader election! LeaderKey: {}",
                    nodeId, response.getLeader().getName().toString(StandardCharsets.UTF_8));

                // Step 4: We are now the leader
                leader.set(true);

                // Step 5: Hold leadership until lease expires or stop() is called
                while (running && leader.get()) {
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in etcd campaign loop: {} — restarting in 5s", e.getMessage());
                leader.set(false);
                try { Thread.sleep(5000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void startKeepAlive(long id) {
        leaseClient.keepAlive(id, new StreamObserver<LeaseKeepAliveResponse>() {
            @Override public void onNext(LeaseKeepAliveResponse r) {
                log.debug("Node {} keepAlive TTL={}", nodeId, r.getTTL());
            }
            @Override public void onError(Throwable t) {
                log.error("Node {} keepAlive error — stepping down: {}", nodeId, t.getMessage());
                leader.set(false); // Step down — lease expired, another node will win
            }
            @Override public void onCompleted() {
                log.info("Node {} keepAlive completed — stepping down", nodeId);
                leader.set(false);
            }
        });
    }
}
