package com.jobscheduler.worker.handler;

import com.jobscheduler.domain.model.Job;
import com.jobscheduler.domain.model.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Executes SHELL jobs — runs a shell command via ProcessBuilder.
 *
 * Expected payload shape:
 * {
 *   "command": "echo hello world"
 * }
 *
 * Captures stdout + stderr combined.
 * Non-zero exit code → throws JobExecutionException.
 * Exceeds timeoutSeconds → throws JobExecutionException.
 *
 * SECURITY NOTE: In production, validate commands against an allowlist.
 * For this project, any shell command is accepted.
 */
@Component
public class ShellJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ShellJobHandler.class);

    @Override
    public JobType getJobType() {
        return JobType.SHELL;
    }

    @Override
    public ExecutionResult execute(Job job) throws JobExecutionException {
        Map<String, Object> payload = job.getPayload();

        String command = (String) payload.get("command");
        if (command == null || command.isBlank()) {
            throw new JobExecutionException("Payload missing required field: command");
        }

        log.debug("Executing SHELL job {} → {}", job.getId(), command);

        try {
            // Use sh -c on Unix, cmd /c on Windows
            List<String> cmd = isWindows()
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true); // merge stderr into stdout

            Process process = pb.start();

            // Read output while waiting — prevents buffer deadlock on large output
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(
                job.getTimeoutSeconds(), TimeUnit.SECONDS
            );

            if (!finished) {
                process.destroyForcibly();
                throw new JobExecutionException(
                    "Command timed out after " + job.getTimeoutSeconds() + "s: " + command
                );
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new JobExecutionException(
                    "Command exited with code " + exitCode + ": " + command
                    + (output.isBlank() ? "" : "\nOutput: " + output)
                );
            }

            log.info("SHELL job {} completed. Output: {}", job.getId(), output);
            return new ExecutionResult(output, exitCode);

        } catch (JobExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new JobExecutionException(
                "Failed to execute command: " + command + " — " + e.getMessage(), e
            );
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
