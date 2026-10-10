# Source-bound empty DRep completion prerequisite

`ConwayEmptyDRepCompletion` is a pure supplied-state component. It consumes a genuine
`ConwayEmptyGovernance.Applied`, its exact 32-byte ID and captured successor epoch.
It does not admit native state, advance a runtime epoch, prove intervening governance
freshness, or enable repeated epochs. No runtime guard or coordinator changes accompany it.

The supported capture has an empty DRep registry, no account voting delegation (including
the two special votes), no proposals or proposal deposits, no withdrawals, index zero,
and empty partial DRep distribution. Accounts, rewards, pool delegation, instantaneous
stake, committee authorizations and all other captured inputs remain unchanged in the
retained source. Nonempty historical completed state is governed by the existing boundary
constructor; this component completes the new capture and does not reuse old completion.

Completion preserves exactly the supplied mark pool distribution's keys and stake values,
including explicit zero-stake pools. This profile additionally requires that domain to
equal the registered stake-pool domain. That restriction is stronger than native completion:
an omitted registered pool is rejected, never inserted with invented zero stake. Existing
boundary sum/sentinel checks remain unchanged; in particular this does not add support for
a nonempty all-zero mark distribution. Empty registered/mark domains retain the existing
denominator-one sentinel. No SNAP recomputation or SNAP provenance proof is claimed.

The completed snapshot has empty proposals, DRep registry and DRep distribution. RATIFY
has empty enacted/expired actions and no delay. Its enactment copies the exact fresh
committee, constitution, current/previous parameter payload objects, roots and empty
withdrawals, changing only enactment treasury to zero. Chain treasury and the captured
fresh enactment treasury remain unchanged and separate.

The private completed value retains its original application and a domain-separated
deterministic ID derived from the complete application identity and epoch. `forSource`
requires that exact application object, ID and epoch, rejecting stale or reconstructed
capture reuse. This is local capture binding, not durable checkpoint or runtime authority.
The returned old-DRep projection is ordinary supplied data; enclosing transition checks
remain mandatory.

## Pinned rule evidence

The existing [source pins](../ledger/src/test/resources/empty-governance/source-pins.json)
identify `cardano-ledger-conway` 1.23.0.0, archive SHA-256
`486831d3d94060fff90e5e85c74030c744008526aa83f4a18215da124720cf1a`.
`DRepPulser.hs` SHA-256 is
`c0e5c984d28c69ff024e9f3d950284c7fd122ff22703279b744141d9720fcf97`.
Its `computeDRepDistr` lines 200–241 only changes pool distribution for proposal deposits;
without voting delegations it leaves the empty DRep distribution unchanged.
`finishDRepPulser` lines 385–417 maps the final supplied pool domain to stake values and
copies the registered DRep snapshot. `Governance.hs` fresh construction retains that
mark distribution separately from stake-pool registration. `Ratify.hs`'s empty signal
clears enactment treasury. These local source hashes were checked against the pinned files.

The six focused tests cover preserved zero pools and treasury distinction, exact parameter
and governance binding, malformed/stale/foreign identities and epochs, omitted pool domains,
empty domains, all unsupported voting variants, nonempty registry, constructor rejection
of pending proposals/deposits, and compile-time rejection of forged completion/raw pulser
input. Existing governance and recorded finite differential tests remain regression checks;
this change performs no new native differential or live execution.
