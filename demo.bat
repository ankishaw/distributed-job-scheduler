@echo off
REM demo.bat — Full distributed job scheduler demo
REM Shows: job execution, leader election, Redis queue, metrics
REM Usage: demo.bat (app must be running on localhost:8080)

echo.
echo ============================================================
echo   Distributed Job Scheduler — Live Demo
echo   Redis Leader Election + BRPOPLPUSH Queue + SKIP LOCKED
echo ============================================================
echo.

set BASE=http://localhost:8080/api/v1/jobs
set HEALTH=http://localhost:8080/health

REM ── Step 1: Health + Leader status ───────────────────────────────────────────
echo [1/7] Health check + Leader status...
curl -s %HEALTH%
echo.
echo.

REM ── Step 2: Submit a SHELL job (Redis hot path) ───────────────────────────────
echo [2/7] Submitting SHELL job via Redis hot path...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-shell\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"echo hello from distributed scheduler\"},\"priority\":\"HIGH\",\"maxRetries\":1,\"timeoutSeconds\":10}"
echo.
echo.

REM ── Step 3: Submit a WEBHOOK job ─────────────────────────────────────────────
echo [3/7] Submitting WEBHOOK job (httpbin.org)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-webhook\",\"jobType\":\"WEBHOOK\",\"payload\":{\"url\":\"https://httpbin.org/post\",\"httpMethod\":\"POST\",\"body\":{\"demo\":\"distributed scheduler\"}},\"priority\":\"CRITICAL\",\"maxRetries\":2,\"timeoutSeconds\":15}"
echo.
echo.

REM ── Step 4: Submit a failing job (retry demo) ─────────────────────────────────
echo [4/7] Submitting FAILING job (will retry → DEAD_LETTER)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-failing\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"exit 1\"},\"priority\":\"MEDIUM\",\"maxRetries\":2,\"timeoutSeconds\":5}"
echo.
echo.

REM ── Step 5: Submit a cron job ─────────────────────────────────────────────────
echo [5/7] Submitting CRON job (every minute, auto-rescheduled by leader)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-cron\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"echo cron tick\"},\"cronExpression\":\"0 * * * * *\",\"priority\":\"LOW\",\"maxRetries\":1,\"timeoutSeconds\":10}"
echo.
echo.

REM ── Step 6: Simulate leader failover ─────────────────────────────────────────
echo [6/7] Simulating leader failover (deleting Redis leader key)...
echo Before failover:
curl -s %HEALTH%
echo.
echo Deleting scheduler:leader key from Redis...
docker exec js-redis redis-cli -h host.docker.internal -p 6379 DEL scheduler:leader
echo Waiting 6 seconds for re-election...
timeout /t 6 /nobreak >nul
echo After failover (node re-elected):
curl -s %HEALTH%
echo.
echo.

REM ── Step 7: Show live metrics ─────────────────────────────────────────────────
echo [7/7] Waiting 5 seconds then showing live metrics...
timeout /t 5 /nobreak >nul
echo.
echo --- Job metrics ---
powershell -Command "Invoke-RestMethod http://localhost:8080/actuator/prometheus | Select-String 'jobs_'"
echo.
echo ============================================================
echo   Demo complete!
echo   - Jobs running:  GET http://localhost:8080/api/v1/jobs
echo   - Leader status: GET http://localhost:8080/health
echo   - Metrics:       GET http://localhost:8080/actuator/prometheus
echo ============================================================
