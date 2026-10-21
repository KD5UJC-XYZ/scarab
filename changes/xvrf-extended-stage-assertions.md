### Fixed (devnet e2e extended stages)
- **`devnet.py e2e` now fails when an extended governance stage reports `ok: false`.** A run in which the VRF stage
  (`xvrf-dup-active`) was not refused as expected printed `ok: false` and still reported `passed: true`: the light extended
  stages and the refusal accounting (`negativeUnexpected`) were not part of the verdict. `govextra.extended_outcome` now fails the
  run on a failed stage, on an applicable stage that is not done and on an unexpected refusal; `xvrf` is reported under
  `extended.notApplicable`, with the reason, only when the chain never reached protocol version 11, and a run that did reach
  it waits up to ten minutes past its target epoch for the stage.
- **The reference's answer to a transaction that must be refused is classified, not guessed from the text.** An accepted
  transaction ends the stage as a divergence (it was retried before), and a submission with no ledger answer (empty output,
  `docker exec` failure) is submitted again and logged with its exit code and raw output instead of being counted as a refusal
  that went wrong.
