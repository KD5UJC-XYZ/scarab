<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron software update registration

31 cases (9 accepted, 22 refused) measured on 2026-11-03 against the unchanged released Byron ledger
(cardano-ledger-byron 1.3.0.0 at cardano-ledger `f649f975`, cardano-crypto-wrapper 1.7.0.0), for
[#748](https://github.com/KD5UJC-XYZ/scarab/issues/748): how `registerProposal` registers a software
update proposal. `registerSoftwareUpdate` (`Cardano/Chain/Update/Validation/Registration.hs:553-585`)
checks, in this order:
1. each system tag of the installer metadata, in key order (`checkSystemTag`: more than 10
   characters, then any non-ASCII one);
2. that no proposal of the application is registered;
3. the application name (`checkSoftwareVersion`: more than 12 characters, then any non-ASCII one);
4. the version's succession.

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

| group | cases |
|---|---|
| accepted | three tags; no metadata; an empty tag; a 10-character tag; a tag with U+007F; an empty name; a 12-character name; a version following an existing application's; a protocol change whose software version is unchanged, so neither its tags nor its name is checked |
| system tags | 11 ASCII characters; U+0080; an accented letter; 10 and 11 emoji (UTF-16 length 20 and 22); 6 emoji (12 UTF-16 units, not too long); 11 accented letters |
| application names | 13 ASCII characters; an accented letter; 12 and 13 emoji; 7 emoji (14 UTF-16 units); 13 accented letters |
| order | two bad tags (the first in key order is named: `aé` before a too-long `zzz…`, U+FFFD before two emoji); a bad tag before a bad name, before a duplicate; a duplicate before a bad name; a bad name before a version that cannot follow |
| others | a version that cannot follow; a duplicate; an unchanged software version without a protocol change (`NullUpdateProposal`) |

Scope: the software registration of `registerProposal`; the protocol-update rules are covered by
`fixtures/byron-version/full-registration`. `ByronSoftwareRegistrationReferenceSuite` decodes each
proposal as a block's update payload carries it, runs `ByronUpdate.registerProposal` on the case's
state, and requires the reference's verdict, the same typed error, and for an acceptance the same
registered proposals, with the proposal's installer metadata.
