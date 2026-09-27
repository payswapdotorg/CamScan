# AGENTS — worker contracts & dispatch protocol

## Roles

| Agent | Owns | Never does |
|---|---|---|
| **Tech lead** (orchestrator, chat.z.ai) | parity ledger, scenario specs, work orders, acceptance gates, merges, CI | implement product/lab code |
| **Worker 1** | `app/` (CamScan implementation), implementation AVD env, build/install tooling | declare a feature complete from its own tests alone |
| **Worker 2** | reference environment (official CamScanner, black-box), capability discovery, `lab/fixtures/`, reference evidence | copy proprietary source/assets/credentials; mutate Worker 1's env |
| **Worker 3** | `lab/reconciliation/`, gap reports, verdicts, evidence comparison tooling | casually rewrite Worker 1's code |

## Dispatch protocol (binding)

Workers are dispatched **through the chat.z.ai replay console** — never from a plain
chat tab:

1. Session created in the **agents tab**, model **GLM-5.3** (never the Flash variant),
   skill **Full-Stack** — all three selections hard-verified after any cancel/retry.
2. Concurrency cap: **3 workers**.
3. One session = one bounded task packet, fully self-contained (the worker cannot see
   the lead's context). Packets live in `lab/orchestration/packets/` (gitignored —
   rebuilt from `WORK-ORDERS.md` on demand).
4. Capacity/peak popups: **never wait** — cancel, re-verify the three selections,
   resend. Popups with Cancel buttons are always cancel-then-retry.
5. Sends are verified **server-side** (chats API message tree), never trusted from
   DOM-only proofs.
6. Long dispatches run detached via launcher scripts; monitors poll registry + chats
   API.

## Task packets

Every packet contains: role, environment setup steps, the exact task, verification
commands the worker must run, and the mandatory final-report format:

```
=== CAMSCAN-<WO-ID> COMPLETION REPORT ===
task: …
environment: provider/AVD ids + versions
what was implemented/observed: …
verification: commands + outputs (verbatim)
evidence: artifact list + sha256 + R2 keys (no raw bytes in chat)
assumptions: …
open questions / handoffs: …
base sha: <40-hex>
```

File delivery: small inline fenced blocks are acceptable for review, but the gold
standard is **git delivery** — worker pushes a branch
`work/CAMSCAN-<WO-ID>` and reports branch + SHA + gate table; the lead re-runs all
gates at the integration station and never trusts reported numbers.

## Git workflow

- `main` — integration branch; only the lead merges (squash-merge after gates).
- `work/CAMSCAN-<WO-ID>` — one branch per work order, owned by its worker.
- Concurrent workers use disjoint paths by design (W1: `app/**`, `tools/adb-bridge/**`;
  W2: `lab/fixtures/**`, reference evidence; W3: `lab/reconciliation/**`,
  comparison tooling). Shared-surface conflicts resolve at the integration station.
- Work orders and status live in `lab/orchestration/WORK-ORDERS.md`; the parity
  ledger (`lab/parity-ledger/ledger.json`) is the single status truth.

## Status vocabulary (use precisely)

`implemented` ≠ `verified` ≠ `reconciled` ≠ `accepted`. A feature is `accepted` only
after a scenario PASS recorded in the ledger with current evidence. Never report
"implemented" when only code exists; never turn an untested feature into a pass.


## Product-program operating model (effective 2026-09-27)

The repository now has a permanent three-worker product implementation program. Read
lab/orchestration/FULL-APP-PROGRAM.md and the product architecture documents before
dispatching product work.

| Agent | Product surface | Primary acceptance |
|---|---|---|
| Tech lead | architecture, reference oracle, scenarios, ledger, work orders, integration/merge | current evidence + CI + parity |
| Worker 1 | app capture + processing + scan engine | scanner fixture tests + live scan flow |
| Worker 2 | app document/library/export/navigation | persistence + output tests + live workspace flow |
| Worker 3 | app OCR/tools/search + reconciliation tooling | OCR/tool tests + reconciliation + live verification |

Worker path ownership is deliberate. Do not create competing implementations of the same
domain model in different worker trees. Shared contracts are lead-owned and live in
docs/PRODUCT-ARCHITECTURE-LOCK.md and docs/SCAN-ENGINE-CONTRACT.md.

The previous rule that the team must avoid implementing broader product behavior until
the entire reference app is discovered is superseded. The correct rule is: implement
vertical product slices in parallel, but never mark a slice accepted until its observable
reference behavior has been exercised or explicitly documented as NOT OBSERVED.

Android Studio is not an agent dependency. Android CLI, SDK, Emulator, ADB and Gradle
are the canonical execution stack.
