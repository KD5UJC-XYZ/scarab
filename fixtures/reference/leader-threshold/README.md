# Leader check for any active-slot coefficient

490 cases measured on 2026-11-03 through the reference's native leader-check arithmetic for
[#770](https://github.com/KD5UJC-XYZ/scarab/issues/770). Each JSON line holds an active-slot
coefficient `f`, a stake and a leader value with its range (2^256 for Praos, 2^512 for TPraos),
and what the reference made of them: `unActiveSlotLog` (`activeSlotLog`), `splitLn`'s exponent
and remainder for `1 - f`, the `taylorExpCmp` outcome with its step count, and the verdict.

The coefficients are 1/20, 1/2, 0.6, 0.63 and 0.64 (either side of `1 - 1/e`, where `splitLn`'s
exponent leaves -1), 0.75, 0.9, 0.99, (2^64-2)/(2^64-1) and 1; the stakes 1, 1/3 and 1/1000. The
leader values are 0, the range's last value, and the threshold `1 - (1 - f)^stake` moved by
relative offsets of ±10^-3, ±10^-12 and ±10^-24 and not at all, so some verdicts are decided at the
fixed-point resolution.

[Provenance](provenance.json) records the image, the compiler, the source identity of the installed
package archives against cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`, and the harness
and corpus hashes. The [harness](../../../scripts/oracle/leader-threshold/Main.hs) calls `splitLn`,
`taylorExpCmp`, `mkActiveSlotCoeff` and `activeSlotLog` of the installed packages and repeats the
body of `checkLeaderNatValue`, whose package the image does not hold. Replay from the repository
root, or regenerate with `--write`:

```sh
python3 scripts/oracle/leader-threshold/run.py --image sha256:9052fa97d907cdd7ee1ad37f4afcf5a0d653f4eaac8bab3da7223652b4b5bce0
```

`PraosLeaderThresholdSuite` compares every logarithm and verdict.
