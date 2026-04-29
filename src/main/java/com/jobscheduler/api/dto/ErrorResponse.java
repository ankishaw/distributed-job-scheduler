package com.jobscheduler.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error envelope returned for ALL error responses.
 * The client always gets the same shape regardless of exception type.
 *
 * Example 404:
 * {
 *   "timestamp": "2024-01-01T12:00:00Z",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Job not found: 123e4567-...",
 *   "path": "/api/v1/jobs/123e4567-...",
 *   "fieldErrors": null
 * }
 *
 * Example 400 with field errors:
 * {
 *   "timestamp": "...",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed",
 *   "path": "/api/v1/jobs",
 *   "fieldErrors": [
 *     { "field": "name", "message": "name is required" }
 *   ]
 * }
 */
public class ErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private List<FieldError> fieldErrors;

    public ErrorResponse() {}

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = Instant.now();
        this.status    = status;
        this.error     = error;
        this.message   = message;
        this.path      = path;
    }

    // ── Field error record (for 400 validation failures) ─────────────────────

    public static class FieldError {
        private String field;
        private String message;

        public FieldError(String field, String message) {
            this.field   = field;
            this.message = message;
        }

        public String getField()   { return field; }
        public String getMessage() { return message; }
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Instant getTimestamp()          { return timestamp; }
    public int getStatus()                 { return status; }
    public String getError()               { return error; }
    public String getMessage()             { return message; }
    public String getPath()                { return path; }
    public List<FieldError> getFieldErrors(){ return fieldErrors; }
    public void setFieldErrors(List<FieldError> fieldErrors) { this.fieldErrors = fieldErrors; }
}
