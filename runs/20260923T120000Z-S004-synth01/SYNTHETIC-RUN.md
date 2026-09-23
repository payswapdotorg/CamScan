# SYNTHETIC RUN — parity-cli verification fixture (CAMSCAN-005)

This run dir is **fixture data for tools/parity-cli**, not a device
observation: the two bundles were produced by
`tools/evidence-cli bundle` from tiny synthetic artifacts (no emulator,
no app, no network) and assembled into the paired layout per
`lab/evidence/EVIDENCE.md`. Regenerate with:

    python3 tools/parity-cli/tests/make_demo_pair.py

The pair deliberately carries one medium divergence (implementation
missing the step-03 ui dump) and two low output sha divergences, and
exercises the shipped reference-app step mask (`masks/
single-document-capture.json`: the reference trace has a 6th
CamScanner-only premium-upsell step) → verdict PARTIAL.
