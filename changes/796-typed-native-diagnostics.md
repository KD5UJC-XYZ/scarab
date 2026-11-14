### Changed (Plutus native diagnostics, #796)

- A script refusal now carries the reference's typed `EvaluationError` (the CEK error and its
  discharged cause term, the scope check's free index, the decoder's language, version and
  remainder refusals, `InvalidReturnValue`, and the remaining budget of an exhausted one) instead of
  the provider's exception text, which is kept apart for identity only. Refusals whose native text
  is not yet derived are a typed gap, never substitute text; backend unavailability is unchanged.
  Variable indices wider than Scalus holds keep their whole Word64 value in the diagnostic, and a
  phase-2 failure keeps what the machine spent. The patched Scalus artifact keeps the operands of six
  machine errors at their throw sites; verdicts and budgets are unchanged.
