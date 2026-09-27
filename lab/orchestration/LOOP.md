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
- Bounded scenarios remain the acceptance units, but they no longer prevent broad
  product implementation. Workers build the product in parallel vertical slices
  from the product architecture/feature matrix; the parity scenario loop verifies
  each completed slice against the observable reference.
- The loop status truth is `lab/parity-ledger/ledger.json`; the tech lead is the
  only writer.


## Product loop

The repository now runs two nested loops:

```
PRODUCT: contract → parallel implementation → integration → build/test
PARITY:  reference → executable scenario → both sides → evidence
         → reconcile → gap → implement → rerun → accept
```

The product loop keeps all three workers productive. The parity loop prevents
the implementation from drifting away from observable CamScanner behavior.
