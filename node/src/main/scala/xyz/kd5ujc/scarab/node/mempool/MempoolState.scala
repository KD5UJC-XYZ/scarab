// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.node.read.{ChainRead, ConwayView}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  LedgerFailure,
  LedgerGlobals,
  PlutusStats,
  PredicateFailure,
  Tx,
  TxEnv,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.rules.{Counted, Gov, Globals, Ledger, LedgerEnv, Sts}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway, LedgerState, NewEpochState, UtxoTables}

/** Why a transaction is not (or no longer) in the mempool. */
enum Rejection:
  /** The bytes are not a Conway transaction `[body, witnesses, is_valid, auxiliary_data]`. */
  case Malformed(detail: String)

  /** The same transaction is already held. */
  case AlreadyHeld(id: Bytes)

  /** The ledger rules (phase 1, and phase 2 for scripts) refuse it in the current state. */
  case Ledger(failure: LedgerFailure)

  /** Holding it would exceed the mempool's capacity in bytes. */
  case Full(size: Int, free: Long)

  /** The submission names an era other than the ledger's (Conway). */
  case WrongEra(era: Int)

  /** The chain has no Conway ledger state to validate against (it is in Byron or an earlier era, or
    * its state cannot be read): the pool takes nothing until it has.
    */
  case NoLedger(why: ConwayView.Unavailable)

  def message: String = this match
    case Malformed(d)    => s"malformed transaction: $d"
    case AlreadyHeld(id) => s"tx ${id.hex.take(16)} is already in the mempool"
    case Ledger(f)       => f.message
    case Full(s, free)   => s"mempool full: $s bytes needed, $free free"
    case WrongEra(e)     => s"era $e is not Conway"
    case NoLedger(why) => s"no Conway ledger state: ${cats.Show[ConwayView.Unavailable].show(why)}"

/** A held transaction. `ticket` grows with arrival order and is how peers are given each
  * transaction once. `refScriptBytes` is the size of the reference scripts the transaction names
  * (spent and reference inputs, counted per input as the ledger's `txNonDistinctRefScriptsSize`),
  * measured against the state it was added to; LocalTxMonitor reports it.
  */
final case class Entry(id: Bytes, raw: Bytes, tx: Tx, ticket: Long, refScriptBytes: Int = 0):
  def size: Int = raw.size

/** What the transaction rules need besides the ledger state. */
final case class MempoolEnv(globals: LedgerGlobals, geometry: EpochGeometry):
  /** The environment in which a transaction is validated for inclusion in the next block: the slot
    * after the tip, in the ledger's current epoch.
    */
  def txEnv(tipSlot: BigInt, ledger: MempoolLedger, index: Int): TxEnv =
    val slot = tipSlot + 1
    TxEnv(
      slot = slot,
      epoch = ledger.epoch,
      network = globals.network,
      firstSlotNextEpoch = geometry.firstSlot(ledger.epoch + 1),
      stabilityWindow = geometry.stabilityWindow,
      txIndex = index,
      updateQuorum = globals.updateQuorum,
      clock = Some(s => BigInt(geometry.slotStart(s).toEpochMilli))
    )

  /** The constants the rules of the era-indexed state read. */
  def rules: Globals = Globals(globals, geometry)

/** The ledger a transaction is validated against: the Conway ledger state at the chain tip, the
  * treasury of the chain account (the transaction's `currentTreasuryValue` is checked against it)
  * and the epoch. The mempool is Conway's: the transaction is `[body, witnesses, is_valid,
  * auxiliary_data]`, and the rules are those of the era-indexed state ([[Ledger]]).
  */
final case class MempoolLedger(ls: LedgerState[Conway], treasury: Coin, epoch: BigInt):
  /** The protocol parameters in force. */
  def params: xyz.kd5ujc.scarab.ledger.ProtocolParams = ls.utxo.gov.curPParams

  /** The handle on the UTxO set. */
  def utxo: Utxo = ls.utxo.utxo.utxo

  def withUtxo(u: Utxo): MempoolLedger =
    copy(ls = ls.copy[Conway](utxo = ls.utxo.copy(utxo = UtxoTables(u))))

  /** The transaction applied (LEDGER of Conway), or the rule that refuses it. */
  def apply(tx: Tx, env: TxEnv, rules: Globals): Either[LedgerFailure, MempoolLedger] =
    Sts(Ledger)(rules, LedgerEnv(env, treasury), Counted(ls, PlutusStats.zero), tx)
      .map(after => copy(ls = after.state))

object MempoolLedger:
  /** The ledger of the new-epoch state at the tip. */
  def of(nes: NewEpochState[Conway]): MempoolLedger =
    MempoolLedger(nes.es.ls, nes.es.chainAccount.treasury, nes.epoch.toBigInt)

  /** The ledger of the chain whose state is `s`, at its tip `at`, or why it has no Conway ledger.
    */
  def at[S: ChainRead](s: S, tip: Option[BlockRef]): Either[ConwayView.Unavailable, MempoolLedger] =
    ConwayView(s, tip).map(of)

/** The mempool as a value. The invariant, for every state reachable through this API:
  *
  * `entries` applied in order to `base` through the transaction rules succeed and give `current`.
  *
  * `base` is the ledger state at the chain tip the pool was last synchronised with. Every operation
  * is a pure function; [[Mempool]] serialises them.
  */
final case class MempoolState(
    base: Either[ConwayView.Unavailable, MempoolLedger],
    tipSlot: BigInt,
    current: Either[ConwayView.Unavailable, MempoolLedger],
    entries: Vector[Entry],
    bytes: Long,
    nextTicket: Long,
    capacityBytes: Long
):
  def free: Long = capacityBytes - bytes
  def contains(id: Bytes): Boolean = entries.exists(_.id == id)

  /** Validate and append a transaction (original bytes). */
  def add(raw: Bytes, env: MempoolEnv): Either[Rejection, (MempoolState, Entry)] =
    for
      parsed <- MempoolState.parse(raw)
      (id, tx) = parsed
      _ <- Either.cond(!contains(id), (), Rejection.AlreadyHeld(id))
      _ <- Either.cond(raw.size <= free, (), Rejection.Full(raw.size, free))
      ledger <- current.leftMap(Rejection.NoLedger(_))
      baseLedger <- base.leftMap(Rejection.NoLedger(_))
      // The MEMPOOL rule of the ledger (`Rules/Mempool.hs`) runs before LEDGER: a transaction none
      // of whose inputs is unspent is "probably a duplicate" and is refused with that alone. Votes
      // of unelected committee members are refused here, and only here, before protocol version 11;
      // from 11 the GOV rule does.
      pv = ledger.params.protocolMajor
      _ <- Either.cond(
        pv < 9 || tx.body.inputs.exists(ledger.utxo.contains),
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(id, PredicateFailure.Mempool(MempoolState.AllInputsSpent))
        )
      )
      unelected = MempoolState.unelectedCommitteeVoters(ledger, tx.body)
      _ <- Either.cond(
        pv < 9 || pv > 10 || unelected.isEmpty,
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.Mempool(
              "Unelected committee members are not allowed to cast votes: " +
                unelected.map(MempoolState.showCredential).mkString("[", ",", "]")
            )
          )
        )
      )
      next <- ledger(tx, env.txEnv(tipSlot, baseLedger, entries.size), env.rules)
        .leftMap(Rejection.Ledger(_))
    yield
      val entry = Entry(id, raw, tx, nextTicket, MempoolState.refScriptBytes(ledger, tx))
      (
        copy(
          current = Right(next),
          entries = entries :+ entry,
          bytes = bytes + raw.size,
          nextTicket = nextTicket + 1
        ),
        entry
      )

  /** Move to a new chain tip: keep, in order, the transactions that still apply to `ledger` and
    * report the others with the rule that now refuses them. Transactions that a block contains fail
    * here because their inputs are spent.
    */
  def sync(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      slot: BigInt,
      env: MempoolEnv
  ): (MempoolState, Vector[(Entry, Rejection)]) =
    ledger match
      case Left(why) =>
        // no ledger to validate against: nothing is held, and everything held is dropped
        (
          copy(
            base = Left(why),
            tipSlot = slot,
            current = Left(why),
            entries = Vector.empty,
            bytes = 0
          ),
          entries.map(_ -> Rejection.NoLedger(why))
        )
      case Right(l) =>
        val start =
          copy(
            base = Right(l),
            tipSlot = slot,
            current = Right(l),
            entries = Vector.empty,
            bytes = 0
          )
        entries.foldLeft((start, Vector.empty[(Entry, Rejection)])) {
          case ((state, dropped), entry) =>
            state.current
              .leftMap(Rejection.NoLedger(_))
              .flatMap(
                _(entry.tx, env.txEnv(slot, l, state.entries.size), env.rules)
                  .leftMap(Rejection.Ledger(_))
              ) match
              case Right(next) =>
                (
                  state.copy(
                    current = Right(next),
                    entries = state.entries :+ entry,
                    bytes = state.bytes + entry.size
                  ),
                  dropped
                )
              case Left(rejection) => (state, dropped :+ (entry -> rejection))
        }

  /** Remove transactions by id (and, because later ones may spend their outputs, revalidate the
    * rest).
    */
  def remove(ids: Set[Bytes], env: MempoolEnv): (MempoolState, Vector[(Entry, Rejection)]) =
    copy(entries = entries.filterNot(e => ids(e.id)))
      .sync(base, tipSlot, env)

  /** The longest prefix of the pool, in order, within `maxBytes`. */
  def forBlock(maxBytes: Long): Vector[Entry] =
    entries
      .scanLeft((0L, Option.empty[Entry]))((acc, e) => (acc._1 + e.size, Some(e)))
      .drop(1)
      .takeWhile(_._1 <= maxBytes)
      .flatMap(_._2)

  /** Entries with a ticket above `ticket`, oldest first. */
  def after(ticket: Long): Vector[Entry] = entries.filter(_.ticket > ticket)

object MempoolState:
  /** The text of the ledger's MEMPOOL failure for a transaction whose inputs are all spent. */
  val AllInputsSpent: String =
    "All inputs are spent. Transaction has probably already been included"

  /** `unelectedCommitteeVoters` (`Gov.hs:652`): the committee hot credentials among the voters of
    * `body` that no member of the current committee has authorised. From protocol version 11 the
    * GOV rule refuses such votes; before that the reference refuses them in its mempool only
    * (`Rules/Mempool.hs`), so blocks may carry them.
    */
  def unelectedCommitteeVoters(
      ledger: MempoolLedger,
      body: xyz.kd5ujc.scarab.ledger.TxBody
  ): Vector[Credential] =
    Gov.unelected(
      ledger.ls.utxo.gov.committee,
      ledger.ls.cert,
      body.votes.map(_._1)
    )

  /** A credential as the ledger's `Show` instance prints it. */
  def showCredential(c: Credential): String = c match
    case Credential.Key(h)    => "KeyHashObj (KeyHash {unKeyHash = \"" + h.hex + "\"})"
    case Credential.Script(h) => "ScriptHashObj (ScriptHash \"" + h.hex + "\")"

  /** The reference-script bytes of the transaction's inputs and reference inputs in `ledger`, an
    * output counted once per set it appears in.
    */
  def refScriptBytes(ledger: MempoolLedger, tx: Tx): Int =
    (tx.body.inputs.iterator ++ tx.body.referenceInputs.iterator)
      .flatMap(ledger.utxo.get)
      .map(_.referenceScriptSize)
      .sum

  def empty(
      ledger: Either[ConwayView.Unavailable, MempoolLedger],
      tipSlot: BigInt,
      capacityBytes: Long
  ): MempoolState =
    MempoolState(ledger, tipSlot, ledger, Vector.empty, 0L, 1L, capacityBytes)

  private val limits = Cbor.Limits(
    maxInputBytes = 1048576,
    maxItems = 100000,
    maxStringBytes = 1048576
  )

  /** Split `[body, witnesses, is_valid, aux]` keeping each component's original bytes. */
  def parse(raw: Bytes): Either[Rejection, (Bytes, Tx)] =
    def malformed[A](detail: String): Either[Rejection, A] = Left(Rejection.Malformed(detail))
    for
      root <- Cbor.decode(raw, limits).leftMap(e => Rejection.Malformed(e))
      parts <- root.value match
        case Value.Arr(xs) if xs.size == 4 => Right(xs)
        case _ => malformed("expected [body, witnesses, is_valid, auxiliary_data]")
      valid <- parts(2).value match
        case Value.Bool(b) => Right(b)
        case _             => malformed("is_valid is not a boolean")
      aux = parts(3).value match
        case Value.Null => None
        case _          => Some(parts(3).original)
      transaction = Block.Transaction(parts(0).original, parts(1).original, aux, valid)
      tx <- Tx.fromBlock(transaction, Era.Conway).leftMap(Rejection.Malformed(_))
    yield (Blake2b.hash256.hash(parts(0).original), tx)
