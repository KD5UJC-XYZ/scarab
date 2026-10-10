# Bounded Plutus fee predicates

`PlutusFees` checks numerical fee, collateral and execution-unit conditions. It does not authenticate protocol parameters, validate scripts, admit transactions, or enable a Plutus runtime profile.

The [source pins](../reference/plutus-fees/source-pins.json) identify archive/member SHA256 hashes and line ranges inspected in the retained reference dependency closure. This is source inspection, not an executed reference comparison.

Execution fee is `ceil(memory * memoryPrice + steps * stepPrice)`, with one ceiling after the combined exact rational sum. Units are the declared redeemer budgets. The linear fee uses the existing Conway memo size (original body and witnesses, synthetic array wrapper and null auxiliary; no isValid byte). The final minimum is the sum of linear and execution fees.

Collateral sufficiency is `collateral * 100 >= suppliedFee * collateralPercentage`. The displayed minimum is the corresponding ceiling; supplied fee includes overpayment. This first profile has one ADA-only collateral input and no collateral return or total-collateral field. A successful transition must preserve that input; this module performs no transition.

The helper independently enforces each transaction and block execution dimension. The block helper accepts at most sixteen budget entries, matching the existing restricted transition's transaction bound. The composition gate must derive the complete declared-budget vector from all supported transactions before commit, including blocks obtained from the follower.

All intermediate arithmetic uses BigInt. Prices and individual units are bounded unsigned 64-bit values; denominators must be positive. Source authentication, model/language-view binding, the fixed profile budget, witnesses, original redeemer integrity, state resolution and final VM receipts remain separate admission requirements.

The reference time conversion truncates absolute POSIX time to milliseconds after epoch-info conversion. The initial shared context type deliberately supports only source-bound single-era geometry with integral millisecond endpoints; unsupported fractional geometry must be rejected, not silently rounded or replaced with network defaults. Actual isolated genesis values must be used.

Tests include combined versus separate ceilings, the 7,933-lovelace result for the fixed 100,000-memory/30,000,000-step budget at the retained example prices, exact minimum-fee and collateral boundaries, overpayment, independent memory/steps limits, block accumulation, and arithmetic exceeding machine-word intermediate sizes. These authored tests are not live signed-envelope parity evidence.

`PlutusParameters.decode` binds the complete original 31-field PV9.0 parameter record to an externally expected SHA256, then checks the registered model text before parsing it. The acquired V3 numeric model must match all 251 registered values. It projects the consumed linear fees, minimum-output cost, execution prices, transaction/block budgets, collateral settings and maximum value size. Fixed model lengths (V1 166, V2 175, V3 251), language IDs 0–2, canonical rational encodings and signed-Int64 execution maxima are explicit additional profile restrictions. Untouched parameter semantics are not certified; genesis timing and acquired-state attribution still require owner integration. Four synthetic projection tests cover source/model mismatch, duplicate/missing V3, version and encoding restrictions.
