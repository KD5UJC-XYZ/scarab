// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Unsigned.Width.W32
import xyz.kd5ujc.scarab.ledger.era.{ProtVer, Version}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayGov
import xyz.kd5ujc.scarab.ledger.state.{EraTag, ShelleyBasedEra}

/** Pre-Conway protocol-parameter update proposals: what PPUP refuses and how many genesis delegates
  * must agree (the era-indexed rules, `ShelleyUtxo` and `Upec`, call these).
  */
object UpdateChecks:
  /** Why an update proposal is refused: by PPUP, or because its protocol version is not a pair of
    * numbers (which the reference's decoder would not admit).
    */
  enum Refusal:
    case Failed(failure: PpupFailure)
    case Unreadable

    /** The refusal of transaction `id` as the ledger's failure for it. */
    def inTx(id: Bytes): LedgerFailure = this match
      case Failed(f)  => LedgerFailure.Rejected(id, PredicateFailure.utxo(UtxoFailure.Update(f)))
      case Unreadable => LedgerFailure.Undecodable(id, TxDefect.UnreadableProtocolVersion)

  /** `hasLegalProtVerUpdate` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/PParams.hs:311-316`,
    * cardano-ledger f649f975): an update without a protocol version is legal, and one with a
    * version must follow the version in force by `pvCanFollow` (`:299-307`), which
    * [[era.rules.ConwayGov.pvCanFollow]] mirrors: the next major version, as `succVersion` gives it
    * (none past `MaxVersion`), at minor 0, or the same major version at the next minor, a `Word32`
    * sum that wraps (`pvMinor`, `libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs:211`), so
    * `(major, 0)` follows `(major, 4294967295)`. The proposed version is read as the reference's
    * `ProtVer` decoder reads it ([[ParamUpdate.protocolVersion]]): a major beyond `MaxVersion` or a
    * minor beyond a `Word32` is no `ProtVer`, and is unreadable. A version in force that is no
    * `ProtVer` is followed by none; no parsed genesis gives one, as [[GenesisSchema]] reads its
    * major as a `Version` and its minor as a `Word32`, so that branch is unreachable from it.
    */
  def canFollow(params: ProtocolParams, u: ParamUpdate): Either[Refusal, Unit] =
    u.fields.get(14) match
      case None => Right(())
      case Some(raw) =>
        for
          n <- CborRead.decode(raw).leftMap(_ => Refusal.Unreadable)
          proposed <- ParamUpdate
            .protocolVersion(n, DecoderVersion.MaxVersion)
            .leftMap(_ => Refusal.Unreadable)
          next <- protVer(proposed).toRight(Refusal.Unreadable)
          _ <- Either.cond(
            protVer(params.version).exists(ConwayGov.pvCanFollow(_, next)),
            (),
            Refusal.Failed(PpupFailure.PVCannotFollow(proposed._1, proposed._2))
          )
        yield ()

  /** A major and a minor version as a `ProtVer` (`BaseTypes.hs:211-214`, cardano-ledger f649f975):
    * a `Version` (`mkVersion`) and a `Word32`.
    */
  private def protVer(version: (BigInt, BigInt)): Option[ProtVer] =
    (Version.mkVersion(version._1), Unsigned.from[W32](version._2)).mapN(ProtVer(_, _))

  /** The parameters the proposals of the epoch vote for: the one update that at least `quorum`
    * genesis delegates proposed with equal values ([[ParamUpdate.winners]]), applied to `params`
    * (`votedFuturePParams`, `Rules/Ppup.hs:205-225`, cardano-ledger f649f975).
    */
  def voted[E <: EraTag](
      params: ProtocolParams,
      current: Map[Bytes, ParamUpdate],
      quorum: Int
  )(using ShelleyBasedEra[E]): Option[ProtocolParams] =
    ParamUpdate.winners(current.values.toVector, quorum) match
      case Vector(u) =>
        ProtocolParams
          .update(params, u)
          .toOption
          .filter(p => p.maxTxSize + p.maxBlockHeaderSize < p.maxBlockBodySize)
      case _ => None
