<!-- SPDX-License-Identifier: Apache-2.0 -->
# Release support

Part of the [compatibility matrix](../compatibility.md#release-support).

Scarab uses its own SemVer; version numbers do not encode a cardano-node major or era. Each
published tag must add an evidence-backed row here and link it from its release notes, following
[the release design](../design/releases.md). No released-version support claim is added by this plan.
The component matrix below describes source-level evidence, not blanket support for every release.

| Scarab tag / source | cardano-node reference | Eras / protocol versions | Platforms | Evidence and limitations |
|---|---|---|---|---|
| No release support row declared yet | — | — | — | Add only after the tagged candidate's evidence is reviewed. |

Each real row pins reference version/commit, supported node-to-node/node-to-client versions and
network/era scope, artifact/platform identities, relevant tests and known exclusions. Persisted
format and migration compatibility remain explicit. Alpha/beta/preprod/prod channel names alone
imply none of these properties and do not select a Cardano network. Preprod-channel tags use
`-rc.N`; production tags have no suffix. No deployment environment is provisioned by this contract.
Do not infer support for newer cardano-node versions from a matching major.
