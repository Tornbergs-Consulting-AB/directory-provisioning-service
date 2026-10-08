# IG response experiment

Use a dedicated DEV application, synthetic identity/permission, and a test Generic REST target. No real memberships are changed by this probe.

## Configure

1. Start the probe on a host reachable by IG; confirm `/health` from that network.
2. Configure Basic credentials `probe-user` / `probe-password` (synthetic only).
3. Use POST and JSON body. Existing request transformation is sufficient: this probe does not parse or validate the body.
4. Set the URL to `http://<dev-host>:8080/test/responses/success` (or your HTTPS proxy URL).
5. Map JSON response `fulfillmentId` and `comment` using the response transformation supported by your IG version. Do not assume lowercase/uppercase connector response-wrapper keys; inspect the actual script input. Never dump the original request Authorization header.
6. Submit a NEW test request for each scenario. Save fulfillment state, comment, HTTP trace, probe log and relevant catalina lines.

Change only the final URL segment for each row:

| Scenario | HTTP | Expected service meaning | IG state/comment/retry observed |
|---|---:|---|---|
| success | 200 | Changed | |
| already-member | 200 | Unchanged, already direct member | |
| not-member | 200 | Unchanged, no direct membership | |
| bad-request | 400 | Invalid input | |
| unauthorized | 401 | Authentication failure | |
| forbidden | 403 | AD rights failure | |
| not-found | 404 | Missing object | |
| unavailable | 503 | Temporary failure | |
| internal-error | 500 | Unexpected failure | |

The 401 scenarios include WWW-Authenticate. Watch for repeated requests caused by the HTTP client's authentication challenge handling.

## Retry experiment

Make a request fail with `unavailable`. Change the configured URL to `success`, then retry that item. Determine whether IG uses the updated configuration, dispatches again, maps the comment, and changes its fulfillment state. Compare single-item and changeset retry. If IG snapshots configuration, create a new request instead and record that behavior.

## Local manual call (PowerShell 7)

```powershell
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('probe-user:probe-password'))
$headers = @{ Authorization = "Basic $basic"; 'X-Correlation-ID' = 'iga-83' }
$r = Invoke-WebRequest -Uri 'http://localhost:8080/test/responses/success' -Method Post -Headers $headers -ContentType 'application/json' -Body '{"requestId":"83"}' -SkipHttpErrorCheck
$r.StatusCode
$r.Content
```

Probe fulfillment IDs are generated from the correlation ID. Without one, the probe generates a UUID. They are trace markers, not persistent operation IDs. No evidence of actual provisioning is implied.
