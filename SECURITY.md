# Security

## Never

- commit credentials to Git
- print credentials into logs
- store credentials in evidence bundles (including screenshots)
- embed credentials in scenario files
- place credentials in screenshots
- send credentials through agent prompts unnecessarily
- give every worker all credentials

## Always

- inject via environment/secrets at run time
- least-privilege, ideally short-lived credentials
- rotate any credential that has touched an unsafe channel

## Credential distribution (current)

| Credential | Held by | Notes |
|---|---|---|
| `E2B_API_KEY` | Tech lead only | control/agent environment provisioning |
| GitHub token | Tech lead; **scoped push access granted transiently to Worker 1 packets only** | never committed, never echoed in reports |
| `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` / `R2_ENDPOINT` | Tech lead; evidence write granted to Worker 3 as required | bucket: `camscan-parity-evidence` |
| `ZAI_API_KEY` | Worker 1 via sandbox secret mechanism | provided by the chat.z.ai environment |
| Reference-env credentials | Worker 2 only, only if a concrete operation requires them | never the E2B master credential |

Secrets live in the lead's private environment file (never in any repo). If a
credential appears in a prompt, log, or evidence artifact: rotate it and record the
incident in the worklog.

## Evidence hygiene

Evidence bundles contain device fingerprints, UI text, logs and generated documents —
treat them as potentially sensitive: no credential values, no account passwords, no
personal documents beyond synthetic fixtures.
