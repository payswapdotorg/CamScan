# Orchestration loop

```
DISCOVER → REFERENCE → SPECIFY → IMPLEMENT → VERIFY → RECONCILE
     → pass? ACCEPT : (GAP TASK → Worker 1 → VERIFY)
```

- **DISCOVER/REFERENCE** — Worker 2 observes CamScanner (black box), records
  capability map entries + reference evidence.
- **SPECIFY** — Lead converts observed behavior into an executable scenario file
  (status → SPECIFIED in the ledger).
- **IMPLEMENT** — Worker 1 implements against the scenario on its branch.
- **VERIFY** — the scenario runs against BOTH environments on a capable provider;
  evidence bundles land in runs/ + R2.
- **RECONCILE** — Worker 3 compares evidence; PASS → lead records acceptance;
  divergence → gap report → back to Worker 1.
- Bounded scenarios only: never implement the whole app before discovering
  behavioral divergence.
- The loop status truth is `lab/parity-ledger/ledger.json`; the tech lead is the
  only writer.
