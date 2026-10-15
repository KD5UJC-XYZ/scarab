// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.ProtocolParams
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError, Decoded, Node, Writer, at}

import DecodeError.Kind
import Wire.*

/** The governance state of the ledger state, the instant stake and the UTxO the state holds (the
  * entries of which are in Haskell's separate `tables` file, so the state writes an empty map).
  */
trait GovStateCodecs extends CertStateCodecs:
  /** `FuturePParams` (`State/Governance.hs:148-159`): `[0]`, `[1, params]` or `[2, params?]`, the
    * last with the parameters as a `Maybe` (a list of zero or one). `params` is the codec of the
    * era's `PParams`.
    */
  def futurePParams(params: Codec[ProtocolParams]): Codec[FuturePParams] =
    new Codec[FuturePParams]:
      private val maybeParams = strictMaybe(params)
      def decode(n: Node): Decoded[FuturePParams] =
        variant(n).flatMap {
          case (t, Vector()) if t == 0 => Right(FuturePParams.NoPParamsUpdate)
          case (t, Vector(p)) if t == 1 =>
            params.decode(p).map(FuturePParams.DefinitePParamsUpdate(_)).at("params")
          case (t, Vector(p)) if t == 2 =>
            maybeParams.decode(p).map(FuturePParams.PotentialPParamsUpdate(_)).at("params")
          case (t, xs) => unknownVariant(n, "FuturePParams", t, xs.size)
        }
      def encode(a: FuturePParams, out: Writer): Unit = a match
        case FuturePParams.NoPParamsUpdate =>
          out.arrayHead(1); out.uint(0L)
        case FuturePParams.DefinitePParamsUpdate(p) =>
          out.arrayHead(2); out.uint(1L); params.encode(p, out)
        case FuturePParams.PotentialPParamsUpdate(p) =>
          out.arrayHead(2); out.uint(2L); maybeParams.encode(p, out)
      override def check(a: FuturePParams): Option[String] = a match
        case FuturePParams.NoPParamsUpdate           => None
        case FuturePParams.DefinitePParamsUpdate(p)  => params.check(p)
        case FuturePParams.PotentialPParamsUpdate(p) => maybeParams.check(p)

  /** `DRComplete` (`DRepPulser.hs:455-459`, `Rec`): the snapshot and the ratification. A pulser
    * that is still running is encoded as the completed one, so this is all that is ever written.
    */
  given drComplete(using ProtVer): Codec[DRComplete] =
    Record.of[DRComplete]((pulsingSnapshot, ratifyState))

  /** `ConwayGovState` (`Conway/Governance.hs:359-369`): `[proposals, committee, constitution,
    * current parameters, previous parameters, future parameters, DRep pulser]`.
    */
  given conwayGovState(using ProtVer): Codec[ConwayGovState] =
    Record.of[ConwayGovState](
      (
        proposals,
        strictMaybe(committee),
        constitution,
        Params.conwayCodec,
        Params.conwayCodec,
        futurePParams(Params.conwayCodec),
        drComplete
      )
    )

  /** `ShelleyGovState` (`Shelley/Governance.hs:113-122`): `[proposed updates, future proposed
    * updates, current parameters, previous parameters, future parameters]`, the parameters those of
    * the era (`params`).
    */
  def shelleyGovState(params: Codec[ProtocolParams])(using ProtVer): Codec[ShelleyGovState] =
    val proposed = mapOf(keyHash[Genesis], paramUpdate)
    Record.of[ShelleyGovState](
      (proposed, proposed, params, params, futurePParams(params))
    )

  /** `ShelleyInstantStake` (`Shelley/State/Stake.hs:68-70`): `[credential stake, pointer stake]`.
    */
  given shelleyInstantStake(using ProtVer): Codec[ShelleyInstantStake] =
    Record.of[ShelleyInstantStake](
      (mapOf(credential[Staking], compactCoin), mapOf(ptr, compactCoin))
    )

  /** `ConwayInstantStake` (`Conway/State/Stake.hs:49-53`, a newtype over the map). */
  given conwayInstantStake(using ProtVer): Codec[ConwayInstantStake] =
    mapOf(credential[Staking], compactCoin).imap(ConwayInstantStake(_))(_.credentialStake)

  /** `UTxO` as the `state` file has it (`LedgerState/Types.hs:259`: `encodeMap encodeMemPack
    * encodeMemPack . unUTxO`): an empty map, the entries being in the `tables` file. The mirror
    * holds only a handle on the node's UTxO, so reading a non-empty map is refused rather than
    * dropped, and what is written is the empty map whatever the handle holds.
    */
  given utxoTables: Codec[UtxoTables] = new Codec[UtxoTables]:
    def decode(n: Node): Decoded[UtxoTables] = n.value match
      case Value.Map(pairs) if pairs.isEmpty =>
        Right(UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.empty))
      case Value.Map(_) =>
        Left(
          DecodeError.at(n, Kind.Unsupported("UTxO entries in the state file (they are in tables)"))
        )
      case _ => Left(Codec.mismatch("map", n))
    def encode(a: UtxoTables, out: Writer): Unit = out.mapHead(0)
