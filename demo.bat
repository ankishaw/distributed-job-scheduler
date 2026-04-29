@echo off
REM demo.bat — Full job scheduler demo sequence for Windows
REM Requires: curl, running app on localhost:8080
REM Usage: demo.bat

echo.
echo ========================================
echo  Distributed Job Scheduler — Live Demo
echo ========================================
echo.

set BASE=http://localhost:8080/api/v1/jobs

REM ── Step 1: Health check ─────────────────────────────────────────────────────
echo [1/6] Health check...
curl -s http://localhost:8080/health
echo.
echo.

REM ── Step 2: Submit a SHELL job ───────────────────────────
echo [2/6] Submitting SHELL job (echo hello world)...
curl -s -X POST %BASE% -H "Content-Type: application/json" -d "{\"name\":\"demo-shell\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"echo hello from job scheduler\"},\"priority\":\"HIGH\",\"maxRetries\":1,\"timeoutSeconds\":10}"
echo.

REM ── Step 3: Submit a WEBHOOK job ─────────────────────────────────────────────
echo [3/6] Submitting WEBHOOK job (httpbin.org)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-webhook\",\"jobType\":\"WEBHOOK\",\"payload\":{\"url\":\"https://httpbin.org/post\",\"httpMethod\":\"POST\",\"body\":{\"demo\":\"job scheduler\"}},\"priority\":\"CRITICAL\",\"maxRetries\":2,\"timeoutSeconds\":15}"
echo.
echo.

REM ── Step 4: Submit a failing job ─────────────────────────────────────────────
echo [4/6] Submitting FAILING job (will retry and reach DEAD_LETTER)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-failing\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"exit 1\"},\"priority\":\"MEDIUM\",\"maxRetries\":2,\"timeoutSeconds\":5}"
echo.
echo.

REM ── Step 5: Submit a cron job ────────────────────────────────────────────────
echo [5/6] Submitting CRON job (every minute)...
curl -s -X POST %BASE% ^
  -H "Content-Type: application/json" ^
  -d "{\"name\":\"demo-cron\",\"jobType\":\"SHELL\",\"payload\":{\"command\":\"echo cron tick\"},\"cronExpression\":\"0 * * * * *\",\"priority\":\"LOW\",\"maxRetries\":1,\"timeoutSeconds\":10}"
echo.
echo.

REM ── Step 6: Wait and show metrics ──────────────────────
echo [6/6] Waiting 5 seconds then checking metrics...
timeout /t 5 /nobreak >nul
echo.
echo --- Key metrics ---
curl -s http://localhost:8080/actuator/prometheus | findstr "jobs_"

echo.
echo.
echo ========================================
echo  Demo complete! Check Postman for job
echo  statuses. Metrics at /actuator/prometheus
echo ========================================
