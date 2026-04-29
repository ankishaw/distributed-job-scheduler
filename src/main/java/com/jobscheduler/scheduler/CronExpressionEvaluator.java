package com.jobscheduler.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Thin wrapper around Spring's CronExpression parser.
 *
 * Spring's CronExpression uses 6-field format:
 *   second minute hour day-of-month month day-of-week
 *
 * Examples:
 *   "0 * * * * *"      → every minute at :00 seconds
 *   "0 0 * * * *"      → every hour
 *   "0 0 9 * * MON-FRI"→ 9am on weekdays
 *   "0 *'/5 * * * *"    → every 5 minutes
 *
 * NOTE: This is different from standard Unix cron (5-field).
 * The extra first field is seconds.
 */
@Component
public class CronExpressionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(CronExpressionEvaluator.class);

    /**
     * Compute the next execution time after the given base time.
     * Returns empty if the expression is invalid or has no future execution.
     */
    public Optional<Instant> nextRunAfter(String expression, ZonedDateTime from) {
        try {
            CronExpression cron = CronExpression.parse(expression);
            ZonedDateTime next = cron.next(from);
            if (next == null) {
                log.warn("Cron expression '{}' has no future execution after {}", expression, from);
                return Optional.empty();
            }
            return Optional.of(next.toInstant());
        } catch (IllegalArgumentException e) {
            log.error("Invalid cron expression '{}': {}", expression, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Compute the next execution time from now.
     */
    public Optional<Instant> nextRunFromNow(String expression) {
        return nextRunAfter(expression, ZonedDateTime.now());
    }

    /**
     * Validate a cron expression without computing next run.
     */
    public boolean isValid(String expression) {
        try {
            CronExpression.parse(expression);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
