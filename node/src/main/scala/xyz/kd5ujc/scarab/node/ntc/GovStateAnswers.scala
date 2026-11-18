// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.read.EraRead
import xyz.kd5ujc.scarab.protocol.{NtcVersion, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellStateCodecs,
  LedgerFileCodecs,
  LeiosStateCodecs,
  Params,
  Versions
}

/** The answers of the two queries that read the governance state of a Shelley-based era,
  * `GetCurrentPParams` and `GetGovState`, written as the reference writes them in each era
  * (`answerPureBlockQuery` and `encodeShelleyResult`, `Shelley/Ledger/Query.hs:415-416, 456-457,
  * 1011, 1032`, ouroboros-consensus 82ecba32), with the era's codecs of the ledger state.
  *
  * Up to Conway the answers use the 11.1 layout's codecs. A Dijkstra state is answered in the
  * layout of the build the node mirrors (`layout`): the Leios build's with its codecs
  * ([[Params.codecIn]], [[LeiosStateCodecs]]), as that build writes both answers with `toCBOR`
  * whatever the node-to-client version (`Query.hs:935, 953`, ouroboros-consensus 2416493b); the
  * 11.1 line's Dijkstra state is not read or written yet, so it answers neither.
  */
object GovStateAnswers:
  /** `GetCurrentPParams`: `getPParams`, the current parameters of the era's governance state
    * (`Shelley/Ledger/Ledger.hs:795-796`, ouroboros-consensus 82ecba32), written as
    * `currentPParamsEnDecoding` chooses by the node-to-client version (`Query.hs:1098-1111`): from
    * version 21 (`ShelleyNodeToClientVersion13`, `Cardano/Node.hs:388-401, 451`) the era's
    * `PParams` (`toCBOR`, `Core/PParams.hs:198-216` at cardano-ledger f649f975); below it the
    * legacy encoding, which writes the protocol version as its two fields where the era's writes
    * one pair (`Query/LegacyPParams.hs:29-30`): at the 15th place in Shelley, Allegra, Mary and
    * Alonzo (`LegacyPParams.hs:64-65, 126-127, 186-187, 246-247`), at the 13th in Babbage
    * (319-320). Conway's legacy encoding is its own (`LegacyPParams.hs:371-372`).
    *
    * Dijkstra's in the Leios build: `getPParams` (`Shelley/Ledger/Ledger.hs:814-815`) written by
    * `toCBOR` at every version (`Query.hs:935`, ouroboros-consensus 2416493b), which has no legacy
    * encoding (its `LegacyPParams` is removed, `CHANGELOG.md:149-153`): `toEraCBOR @DijkstraEra`
    * (`Core/PParams.hs:204-209, 221-222`, `Core/Era.hs:232-233`, cardano-ledger 9df9f98c), the 54
    * parameters at version 12.
    */
  def currentPParams[E <: EraTag](
      l: EraRead[E],
      layout: StateLayout,
      version: NtcVersion
  ): Either[Unanswered, Bytes] =
    def written(in: StateLayout): Either[Unanswered, Bytes] =
      Params.codecIn(in, l.era).write(l.params).left.map(_ => Unanswered.Unwritable)
    def legacy(protocolVersionAt: Int): Either[Unanswered, Bytes] =
      if version.number >= NtcVersion.V21.number then written(StateLayout.Node11_1)
      else written(StateLayout.Node11_1).flatMap(splitPair(_, protocolVersionAt))
    l.era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo =>
        legacy(14)
      case ShelleyBasedEra.SBabbage => legacy(12)
      case ShelleyBasedEra.SConway  => written(StateLayout.Node11_1)
      case ShelleyBasedEra.SDijkstra =>
        layout match
          case StateLayout.Node11_1 => Left(Unanswered.Unsupported)
          case StateLayout.Leios    => written(StateLayout.Leios)

  /** `GetGovState`: `queryGovState`, the era's governance state (`Api/State/Query.hs:177-178` at
    * cardano-ledger f649f975), `ShelleyGovState` before Conway and `ConwayGovState` in it, written
    * by `toCBOR` at the era's lowest version whatever the node-to-client version
    * (`Shelley/Governance.hs:108-163`, cardano-ledger f649f975): its parameters in the era's own
    * encoding at every version.
    *
    * Dijkstra's in the Leios build: `queryGovState` (`Api/State/Query.hs:198-199`, cardano-ledger
    * 9df9f98c), `ConwayGovState DijkstraEra` (`Dijkstra/Governance.hs:29`), written by `toCBOR` at
    * every version (`Query.hs:953`, ouroboros-consensus 2416493b): `toEraCBOR @DijkstraEra`
    * (`Conway/Governance.hs:363-376`), at version 12, Dijkstra's parameters in it.
    */
  def govState[E <: EraTag](l: EraRead[E], layout: StateLayout): Either[Unanswered, Bytes] =
    def written(codecs: LedgerFileCodecs): Either[Unanswered, Bytes] =
      val codec = codecs.govState[E](using l.era, Versions.eraLow(l.era))
      l.gov.left
        .map(Unanswered.Refused(_))
        .flatMap(codec.write(_).left.map(_ => Unanswered.Unwritable))
    l.era match
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
        written(HaskellStateCodecs)
      case ShelleyBasedEra.SDijkstra =>
        layout match
          case StateLayout.Node11_1 => Left(Unanswered.Unsupported)
          case StateLayout.Leios    => written(LeiosStateCodecs)

  /** The record `current` with its item at `index`, the protocol version pair, written as the
    * pair's two items: one item more, the others as they are (`Rec mkLegacy… !> To (pvMajor …) !>
    * To (pvMinor …)`). The record is the era's codec's, so it always has the pair there.
    */
  private def splitPair(current: Bytes, index: Int): Either[Unanswered, Bytes] =
    (for
      record <- Cbor.decode(current).toOption.flatMap(_.items).map(_.toVector)
      pair <- record.lift(index).flatMap(_.items).map(_.toVector).filter(_.sizeIs == 2)
    yield CborOut.build { w =>
      w.array(record.size + 1)
      record.patch(index, pair, 1).foreach(item => w.raw(item.original))
    }).toRight(Unanswered.Unwritable)
