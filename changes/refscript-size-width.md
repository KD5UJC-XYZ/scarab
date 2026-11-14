### Fixed (ledger)

- Add up the sizes of a transaction's reference scripts in 64 bits, as the reference's `Int`, in the LEDGER limit check, the reference-script fee, the per-transaction sums of BBODY and the mempool's measure. `ConwayTxRefScriptsSizeTooBig` reports the size at its full width; its node-to-client encoding is unchanged for every size below 2^31.
