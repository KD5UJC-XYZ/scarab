<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron software update registration

42 cases (14 accepted, 28 refused) measured on 2026-11-03 against the unchanged released Byron ledger
(cardano-ledger-byron 1.3.0.0 at cardano-ledger `f649f975`, cardano-crypto-wrapper 1.7.0.0), for
[#748](https://github.com/KD5UJC-XYZ/scarab/issues/748): how `registerProposal` registers a software
update proposal. `registerSoftwareUpdate` (`Cardano/Chain/Update/Validation/Registration.hs:553-585`)
checks, in this order:
1. each system tag of the installer metadata, in key order (`checkSystemTag`: more than 10
   characters, then any non-ASCII one);
2. that no proposal of the application is registered;
3. the application name (`checkSoftwareVersion`: more than 12 characters, then any non-ASCII one);
4. the version's succession.

`registerProposalComponents` registers the protocol update first, so a refused protocol update
(`Registration.hs:376-390`) is reported before any software check.

Characters are `Data.Text` characters, code points. The oracle source and
[recipe](../../../scripts/oracle/byron-update-state/README.md) are committed. `provenance.json`
records the pins, the hash of each reference source the rule runs through, of the harness and of
`cases.jsonl`.

`cases.jsonl` holds one case per line:
- the proposal: its system `tags`, its `app` name and `number`, the protocol `version` it proposes;
- the state: `adoptedParameters`, `appVersions`, and the `registered` software proposals, given ids
  of 32 bytes of 1, 2, ... in order;
- the proposal's bytes (`proposal`), signed by a throwaway delegate key derived from a fixed seed in
  the harness (no key is kept here);
- the verdict: `accepted`, and for a refusal the reference's constructor (`rule`), the refused text
  (`text`) and its `failure`;
- for an acceptance, the registered software proposals after it (`registeredAfter`).

A case with a `vote` is a block's update payload instead, run through `registerUpdate`
(`Validation/Interface.hs:244-258`): the proposal, a vote of its proposer for it (its bytes are in
`vote`), then the block's endorsement. Its acceptance also lists the application versions after it
(`appVersionsAfter`) and the number of `confirmed` proposals. `InvalidProtocolVersion` gives the
proposed `version`. `ProposalTooLarge` gives `max` and `actual`: the reference builds its `TooLarge`
positionally as (maximum, size) (`Registration.hs:511-513`).

| group | cases |
|---|---|
| accepted | three tags; no metadata; an empty tag; a 10-character tag; a tag with U+007F; an empty name; a 12-character name; a version following an existing application's; a protocol change whose software version is unchanged, so neither its tags nor its name is checked |
| system tags | 11 ASCII characters; U+0080; an accented letter; 10 and 11 emoji (UTF-16 length 20 and 22); 6 emoji (12 UTF-16 units, not too long); 11 accented letters |
| application names | 13 ASCII characters; an accented letter; 12 and 13 emoji; 7 emoji (14 UTF-16 units); 13 accented letters |
| order | two bad tags (the first in key order is named: `aé` before a too-long `zzz…`, U+FFFD before two emoji); a bad tag before a bad name, before a duplicate; a duplicate before a bad name; a bad name before a version that cannot follow |
| others | a version that cannot follow; a duplicate; an unchanged software version without a protocol change (`NullUpdateProposal`) |
| ASCII | names with U+007F, with U+0000, tab, newline and U+001F (accepted), with U+0080 (refused); a tag of U+0000 and U+001F (accepted) |
| protocol first | a protocol version that cannot follow, before a bad tag and before a bad name; a proposal larger than the maximum, before a bad name; a protocol update that is accepted, then a bad tag; both accepted |
| payload | a refused proposal with a vote for it (the proposal's refusal is the payload's); a proposal confirmed by its vote, whose application version keeps the metadata |

Scope: the software registration of `registerProposal`; the protocol-update rules are covered by
`fixtures/byron-version/full-registration`. `ByronSoftwareRegistrationReferenceSuite` decodes each
proposal (and vote) as a block's update payload carries it, runs `ByronUpdate.registerProposal` on
the case's state (`ByronUpdate.register` for a payload), and requires the reference's verdict and
the same typed error. For an acceptance it requires the same registered proposals. It also requires
the proposal under its own id with its installer metadata when the software version changes, and no
new entry when it does not. For a payload it requires the same application versions and
confirmations.
