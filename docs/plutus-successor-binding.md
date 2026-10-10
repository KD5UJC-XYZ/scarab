# Checked Plutus successor binding

`PlutusSuccessorBinding.prepare` binds an exact source ledger object and genuine boundary
preview to a newly attached execution environment. It requires matching ledger identity,
revision, slot and epoch, then the exact next epoch and boundary slot. Genesis, network and
single-era time geometry come from the old attached environment. Full checked current
parameters determine the new source digest, fees, minimum output, execution budgets,
prices and collateral rules; unchanged model bytes do not preserve the old environment ID.

`ClusterTransition.preparePlutusSuccessorBlock` validates the incoming header and slot
against that preview and creates one unpublished candidate containing boundary fees and
the complete body. Normal block admission performs Plutus checks with the new environment.
Commit and undo cover the boundary and body together. `ConwayStake.preparePlutusSuccessor`
requires the same capability, owner and exact preview, uses the post-reward rotation, and
checks incremental stake against complete recomputation after the body.

Existing synthetic successor methods still reject Plutus. The new methods are internal
opt-in primitives, not a standalone runtime epoch permission. The enclosing coordinator
must supply current governance parameter roles, completed monetary and DRep work, correct
pre-tick reward-freeze provenance, header eligibility and every other automatic effect,
then publish the whole candidate under its source-state fence. The ledger layer does not
authenticate caller-supplied parameter roles. No native parity, live repeated-epoch run,
general governance, checkpoint codec or durable recovery is established here.

Tests cover exact source/preview fencing, wrong header/slot rejection, full new parameter
projection, old-path refusal, failed-body immutability, atomic commit/undo, and two pure
supplied absent-reward transitions retaining an original inline datum. These supplied
transitions do not prove runtime governance or monetary absence across epochs.
