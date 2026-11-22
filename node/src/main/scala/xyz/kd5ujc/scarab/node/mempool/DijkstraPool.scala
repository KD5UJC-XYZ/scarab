// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.Id
import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.{Horizon, PosixClock, TxEnv, Verdicts}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx as FlatDijkstraTx
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  EraTx,
  EraTxError,
  IsPhase2Valid,
  PParamsError,
  ProposalsReadError,
  ProposalsWriteError,
  ProtVer,
  StAnnTx,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraRuleDefect, DijkstraUtxoContext, UtxoStateDefect}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, Network}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.Era

/** A Dijkstra transaction as a client or a peer submitted it, read once from its mempool form
  * (`decodeDijkstraTopTx`, cardano-ledger 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/
  * Tx.hs:152-169`, [[DijkstraTx.submitted]]): the transaction in the era's types, which the rules
  * judge, and the flat transaction it was read from, which the forger builds blocks of until its
  * Dijkstra path takes the era's (the bridge of #85). The two are set together ([[withIsValid]]),
  * so they hold the same flag.
  */
final case class DijkstraRead private (flat: FlatDijkstraTx, tx: Tx[TxLevel.Top, Dijkstra]):
  /** The id, the hash of the body. */
  def id: Bytes = tx.id

  /** The phase-two flag (`isPhase2ValidTxL`). */
  def isValid: IsPhase2Valid = EraTx.dijkstra.isValid(tx)

  /** Both forms with the flag `valid` (`tx & isPhase2ValidTxL .~ valid`). */
  def withIsValid(valid: IsPhase2Valid): DijkstraRead =
    DijkstraRead(
      flat.copy(isValid = valid == IsPhase2Valid.Phase2Valid),
      DijkstraTx.withIsValid(tx, valid)
    )

  /** `toCBORForMempoolSubmission` (`Tx.hs:500-515`, cardano-ledger 9df9f98c): `[body, wits,
    * auxiliary data / null]`, each part as the bytes it memoizes, the flag omitted (`OmitC`). The
    * form the pool relays and lists the transaction in (`wrapCBORinCBOR toCBOR`,
    * ouroboros-consensus 2416493b `Shelley/Ledger/Mempool.hs:299-302`, whose `ToCBOR` is the era's
    * `EncCBOR`, `Tx.hs:144-145`).
    */
  lazy val raw: Bytes =
    Bytes.fromArray(Array(0x83.toByte)) ++ flat.body.raw ++ flat.witnesses.raw ++
      flat.auxiliary.fold(Bytes.fromArray(Array(0xf6.toByte)))(_.raw)

  /** `sizeTxF` (`Tx.hs:310-319`, cardano-ledger 9df9f98c): the bytes of the parts and the list's
    * head, which is the length of [[raw]].
    */
  def size: Long = EraTx.dijkstra.sizeTxF[TxLevel.Top](tx)(using BothLevels.Top)

object DijkstraRead:
  /** The transaction `raw` in its mempool form, read once ([[DijkstraTx.submitted]]). */
  def submitted(raw: Bytes): Either[EraTxError, DijkstraRead] =
    DijkstraTx.submitted(raw).map(DijkstraRead(_, _))

/** `ValidatedTx` (`Shelley/API/Mempool.hs:81-88`, cardano-ledger 9df9f98c): the annotation the
  * transaction was admitted with (`vtStAnnTx`), the protocol version in force then
  * (`vtProtocolVersion`) and the slot (`vtSlotNo`), which `reapplyValidatedTx` reapplies
  * (`:420-440`).
  */
final case class DijkstraValidated(
    stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
    protocolVersion: ProtVer,
    slot: BigInt
)

/** A Dijkstra transaction the pool holds: the transaction with the flag of its verdict, and what it
  * was validated with (`Validated (GenTx (ShelleyBlock proto DijkstraEra))`, `ShelleyValidatedTx`,
  * ouroboros-consensus 2416493b `Shelley/Ledger/Mempool.hs:153-156`).
  */
final case class DijkstraAdmitted(read: DijkstraRead, validated: DijkstraValidated)

/** Why the pool has no verdict on a Dijkstra transaction, none of them a refusal of it: the node's
  * own limits and defects, which no peer is charged for.
  */
enum DijkstraUnjudged extends DomainError:
  /** A defect the rules raised ([[DijkstraRuleDefect]]): a script with no verdict, a stored output,
    * a quantity or a key the rules cannot read, as the block path meets them
    * (`MirrorDijkstra.ruleDefect`).
    */
  case Rules(defect: DijkstraRuleDefect)

  /** A stored output of the transaction's inputs that the era's types cannot read. */
  case UtxoState(defect: UtxoStateDefect)

  /** The node's two readings of the transaction disagree, or a bound of its decoder did not hold
    * (the projection's failures, as `DijkstraBlockError.of` places them).
    */
  case Projection(cause: EraTxError)

  /** The state's parameters in the era's types. */
  case Parameters(cause: PParamsError)

  /** The state's proposals in the era's types. */
  case Proposals(cause: ProposalsReadError)

  /** Proposals the state's record cannot hold once the transaction is applied: an update of the
    * parameters Dijkstra adds (keys 34 to 56), which the record holds once its format does (#826).
    * The testnet's ledger admits such a transaction, so it is no refusal; the pool does not hold
    * it, as the block path withholds a block with one (`BlockNoVerdict.UnstorableProposals`).
    */
  case Unstorable(cause: ProposalsWriteError)

/** What a Dijkstra transaction is judged with besides the ledger: the slot (`mkMempoolEnv`), the
  * constants of the rules, the UTxO rules' context and the clock of its annotation, the Plutus
  * backend and the signature verifiers.
  */
final case class DijkstraPoolEnv(
    slot: BigInt,
    rules: Globals,
    network: Network,
    clock: PosixClock,
    evaluator: ScriptEvaluator[Id],
    decoder: ScriptDecoder[Id],
    verdicts: Verdicts
):
  /** The context of Dijkstra's UTXO with `validation`: the network, the system start and the era
    * history of the clock, as `MirrorDijkstra.envOf` gives the block path its own. A clock with no
    * horizon ([[PosixClock.Total]], pools of tests without a chain) is one era with no end of the
    * geometry's slots, in which every slot has a time, as it has in that clock.
    */
  def context(validation: TxEnv.Validation): DijkstraUtxoContext =
    val (start, horizon) = DijkstraPoolEnv.horizonOf(clock, rules)
    DijkstraUtxoContext(network, start, horizon, validation)

object DijkstraPoolEnv:
  /** The environment of a transaction of the pool, judged at `slot`. */
  def of(env: MempoolEnv, slot: BigInt): DijkstraPoolEnv =
    DijkstraPoolEnv(
      slot,
      env.rules,
      Network.of(BigInt(env.globals.network)).getOrElse(Network.Testnet),
      env.clock.fold(PosixClock.total(env.geometry))(_(Era.Dijkstra)),
      env.evaluator,
      env.decoder,
      env.verdicts
    )

  private def horizonOf(clock: PosixClock, rules: Globals): (BigInt, Horizon) = clock match
    case PosixClock.Forecast(start, horizon)                         => (start, horizon)
    case PosixClock.Extended(_, PosixClock.Forecast(start, horizon)) => (start, horizon)
    case PosixClock.Total(_) =>
      val g = rules.geometry
      (
        BigInt(g.systemStart.toEpochMilli),
        Horizon(
          NonEmptyVector.one(
            Horizon.Era(
              Horizon.Edge(0, 0, 0),
              None,
              Horizon.Params(g.epochLength, g.slotLengthMillis, 0, 0)
            )
          )
        )
      )
