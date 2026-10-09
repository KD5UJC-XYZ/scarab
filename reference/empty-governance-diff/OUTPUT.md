# Finite normalized output contract (finite executed projection)

Root exact keys: `schema: synthetic-empty-governance-result-v1`, `inputSha256`, `producer: native`, `cases`.
The input hash is `2f94d8b6d04ad338e41cff69961a39c121619305730b533b9626f4d6c2349b64`.
All integers are canonical unsigned decimal strings; object order is irrelevant, arrays are ordered.
Credentials use `script:<hex>` before `key:<hex>`, then lexical fixed-width hash. Pool IDs have no prefix.
Votes use credential strings or `always-abstain` / `always-no-confidence`.

Accepted case exact keys: `id`, `status: accepted`, `before`, `after`, `diagnostic`.
Profile-rejected case exact keys: `id`, `status: profile-rejected`, `reason` (input reject tag).
Negative cases exercise the finite supported-profile gate before STS; they are not native consensus-rejection claims.

State exact keys:
- `epoch`, `dormant`;
- `dreps`: rows `{credential,expiry,anchor,deposit,delegators}`; anchor null in this packet;
- `accounts`: rows `{credential,rewards,deposit,pool,vote}`; vote null or a vote string;
- `committee`: `{members:[{credential,expiry}],threshold:{n,d}}`;
- `committeeState`: rows `{credential,kind,hot,anchor}` with kind hot/resigned, hot credential/null, anchor null;
- `constitution`: `{url,hash,script}` (empty URL, zero 32-byte hash, null script in this finite fixture);
- `parameters`: `{currentFee,previousFee,future,currentPV,previousPV}`; PV objects `{major:"9",minor:"0"}`;
- `roots`: `{parameters:null,hardFork:null,committee:null,constitution:null}`;
- `pots`: `{treasury,reserves,fees,deposits,donations}`;
- `completed`: `{snapshot,ratify}`.

Completed snapshot exact keys: `proposals` (empty), `drepDistribution` (rows `{vote,coin}`),
`dreps` (registry rows as above), `pools` (rows `{pool,stake}`).
Ratify exact keys: `enacted` (empty), `expired` (empty), `delayed` (false), `enact`.
Enact exact keys: `committee`, `constitution`, `parameters` (same parameter object without future),
`treasury` (zero), `withdrawals` (empty), `roots`.
`completed` is obtained from native `finishDRepPulser`/`extractDRepPulsingState`, never a partial fresh distribution.

Diagnostic exact keys: `freshPulseSize`, `freshSeedTreasury`, `newMarkPools` (rows `{pool,stake}`).
This is separate source instrumentation, not live cursor serialization or cursor parity.
Fresh seed treasury is 17 whereas normalized completed enactment treasury is zero.

All positive cases: epoch 0 to 1; six accounts copied from BoundaryMain rows;
script03=40/pool01,key03=20/pool01,key04=40/pool01,key05=25/pool02,key06=75/pool02,key07=0/pool02.
All account deposits zero; all votes equal selected mode; instantaneous UTxO stake empty. Security parameter one.
Fresh new-mark pool totals are 100/100, pulser chunk is floor(max(1,6/(4*1)))=1.
Treasury17,fees1100,reserves883 minus5 if registered,deposits5 if registered,donations0; total2200.
Registered DRep key64 expiry3,anchor null,deposit5; delegators all six iff credential mode, else empty.
Committee key62 expiry2 (expired variant0),threshold1/2; authorized hotkey68 or resigned null.
Orphan variant adds coldkey65/hotkey69 before only. Expired elected member retains authorization.
Before completed snapshot is empty; after registry=current and pool totals=100/100;
after DRep distribution is selected vote200, except none gives empty.
Before current/previous fees come from case; after previous=current and future PotentialNone.
The native helper checks entire parameter-object rollover, committee and constitution equality.
Only fee/PV semantic projections are compared cross-language: this is not full parameter-CBOR decoder parity.

`BoundaryMain.hs` is copied exactly from local committed d3ab6dd; Main is imported from the existing sibling reward helper.
The helper has been compiled and executed; ../../docs/empty-governance-differential.md records finite equality. No general native equivalence is claimed.
