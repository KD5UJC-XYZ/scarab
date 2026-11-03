<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron update-interface state

**Status: Current** — optional offline reference oracle for
[#726](https://github.com/KD5UJC-XYZ/scarab/issues/726).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/byron-update-state/Dockerfile -t scarab-byron-update-state:local .
python3 scripts/oracle/byron-update-state/cases.py --inputs /tmp/byron-update-state-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro scarab-byron-update-state:local \
  /fixtures/byron-update-state-inputs.json > /tmp/byron-update-state-reference.json
python3 scripts/oracle/byron-update-state/cases.py --inputs /tmp/byron-update-state-inputs.json \
  --reference /tmp/byron-update-state-reference.json --out /tmp/byron-update-state-cases.jsonl
cmp /tmp/byron-update-state-cases.jsonl fixtures/reference/byron-update-state/cases.jsonl
```

The reference answers Byron's `GetUpdateInterfaceState` with `encodeByronResult`, the `ToCBOR`
instance of `UPI.State`, `toByronCBOR` (`Ouroboros/Consensus/Byron/Ledger/Ledger.hs:563-565`,
ouroboros-consensus `82ecba32`; `Cardano/Chain/Update/Validation/Interface.hs:135-136, 157-169`, cardano-ledger
`f649f975`). `Main.hs` builds each case's state with the released `cardano-ledger-byron` 1.3.0.0
types (`State`, `CandidateProtocolUpdate`, `ApplicationVersion`, `ProtocolUpdateProposal`,
`SoftwareUpdateProposal`, `Endorsement`, `InstallerHash`, `ProtocolParameters`), writes
`toCBOR` of it, and decodes the bytes again with `decodeFull'` at `byronProtVer` to check they read
back as the state it built. `currentEpoch` is always 0: only `initialState` writes it
(`Interface.hs:225-238`). `cases.py` takes the protocol parameters of the repository's Byron genesis
files as `FromJSON ProtocolParameters` reads them (`Update/ProtocolParameters.hs:144-160`) and
builds states with keys out of order and values at the bounds of their types; the
[fixture](../../../fixtures/reference/byron-update-state/README.md) lists the cases and the scope.
No public-network connection, node or key is needed.

The same harness has a `register` mode for
[#748](https://github.com/KD5UJC-XYZ/scarab/issues/748). It signs each case's software update proposal
with a throwaway delegate key derived from a fixed seed in `Main.hs` and runs the released
`Registration.registerProposal` against the case's state. It writes the proposal's bytes and the
verdict, which `registration_cases.py` merges into
[`fixtures/reference/byron-software-registration`](../../../fixtures/reference/byron-software-registration/README.md):

```sh
python3 scripts/oracle/byron-update-state/registration_cases.py --inputs /tmp/byron-registration-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro scarab-byron-update-state:local \
  register /fixtures/byron-registration-inputs.json > /tmp/byron-registration-reference.json
python3 scripts/oracle/byron-update-state/registration_cases.py --inputs /tmp/byron-registration-inputs.json \
  --reference /tmp/byron-registration-reference.json --out /tmp/byron-registration-cases.jsonl
cmp /tmp/byron-registration-cases.jsonl fixtures/reference/byron-software-registration/cases.jsonl
```
