# Pinned Conway CERTS conformance sample

This is one actual generated CERTS judgment from the exact property path and source pins in `manifest.json`. Both the upstream implementation and Agda accept the empty certificate vector, and all raw context/environment/state/signal/result files are preserved and hashed.

The adapter status is explicitly `unsupported`, not a pass. The translation supplies epoch, parameters, voting procedures, withdrawals and the committee cold-credential set, while Scarab\'s current CERTS wrapper takes a full `Tx` and uses its proposal procedures in the DRep dormancy/expiry base case. The exported judgment does not carry that proposal field. No default or inferred transaction has been introduced.
