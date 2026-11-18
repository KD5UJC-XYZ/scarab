// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.{NtcVersion, StateLayout}

/** The ledger queries a session reads, by the reference whose decoder of queries it follows
  * (`decodeShelleyQuery`). The build the node mirrors and the negotiated node-to-client version
  * select it ([[QueryTable.of]]).
  */
enum QueryTable:
  /** ouroboros-consensus 82ecba32 (`Shelley/Ledger/Query.hs:944-998`): tags 0 to 39, the
    * one-element `[34]` among them.
    */
  case Node11_1

  /** ouroboros-consensus 2416493b (`Shelley/Ledger/Query.hs:838-922`): tags 4, 5 and 21 and the
    * one-element `[34]` removed (`CHANGELOG.md:141-148`), `GetStakePoolState` (40) and
    * `GetLeiosCommittee` (41) added
    * (`changelog.d/20261015_142428_jasataco_query_get_stake_pool_state.md`,
    * `changelog.d/20261016_120000_sebastian.nagel_leios_queries.md`).
    */
  case Leios

object QueryTable:
  /** The table of a session of the build whose layout is `layout`, at `version`. The 11.1 build
    * reads its own at every version. The Leios build negotiates version 23 only
    * (`supportedNodeToClientVersions`, `Cardano/Node.hs:352-357`, ouroboros-consensus 2416493b);
    * the node offers 16 to 22 beside it under that build too, and a session at one of them reads as
    * the 11.1 build reads it at that version, an approved Deviation (docs/decisions.md, #826,
    * 2026-11-18) like 11.2.1's (#1002).
    */
  def of(layout: StateLayout, version: NtcVersion): QueryTable = layout match
    case StateLayout.Node11_1 => Node11_1
    case StateLayout.Leios =>
      version match
        case NtcVersion.V23 => Leios
        case NtcVersion.V16 | NtcVersion.V17 | NtcVersion.V18 | NtcVersion.V19 | NtcVersion.V20 |
            NtcVersion.V21 | NtcVersion.V22 =>
          Node11_1
