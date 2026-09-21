# Gap report format (v0)

One file per identified difference: `lab/reconciliation/<scenario>.<feature>.gap.yaml`.
Worker 3 files gaps; Worker 1 implements the required change; Worker 3 reruns the
verification scenario until PASS.

```yaml
gap:
  id: <scenario>-<feature>-<n>
  scenario: S004-single-document-capture
  feature: automatic-document-detection
  reference:
    behavior: what CamScanner observably does
    evidence: runs/<run-id>/reference/… + R2 keys
  implementation:
    behavior: what CamScan observably does
    evidence: runs/<run-id>/implementation/…
  difference: precise statement of divergence
  severity: critical | high | medium | low
  required_change: precise implementation instruction (not a rewrite of W1 code)
  verification:
    scenario: S004-single-document-capture
    assertions: [document-detected, detection-overlay-shown-live]
  status: open | implemented | verified | accepted | rejected(reason)
```

Rules:
- every gap names its evidence (both sides);
- BLOCKED external dependencies are recorded as such and never counted as PASS;
- acceptance (`accepted`) is recorded only by the tech lead, from current evidence.
