// SPDX-License-Identifier: Apache-2.0
package lab.node.mempool

import cats.syntax.all.*
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Value}
import lab.network.Era
import lab.node.{Block, EpochGeometry}
import lab.node.ledger.{
  ConwayRules,
  Credential,
  LedgerFailure,
  LedgerGlobals,
  LedgerState,
  PredicateFailure,
  Tx,
  TxRules
}

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

  def message: String = this match
    case Malformed(d)    => s"malformed transaction: $d"
    case AlreadyHeld(id) => s"tx ${id.hex.take(16)} is already in the mempool"
    case Ledger(f)       => f.message
    case Full(s, free)   => s"mempool full: $s bytes needed, $free free"
    case WrongEra(e)     => s"era $e is not Conway"

/** A held transaction. `ticket` grows with arrival order and is how peers are given each
  * transaction once.
  */
final case class Entry(id: Bytes, raw: Bytes, tx: Tx, ticket: Long):
  def size: Int = raw.size

/** What the transaction rules need besides the ledger state. */
final case class MempoolEnv(globals: LedgerGlobals, geometry: EpochGeometry):
  /** The environment in which a transaction is validated for inclusion in the next block: the slot
    * after the tip, in the ledger's current epoch.
    */
  def txEnv(tipSlot: BigInt, ledger: LedgerState, index: Int): TxRules.Env =
    val slot = tipSlot + 1
    TxRules.Env(
      slot = slot,
      epoch = ledger.epoch,
      network = globals.network,
      firstSlotNextEpoch = geometry.firstSlot(ledger.epoch + 1),
      stabilityWindow = geometry.stabilityWindow,
      txIndex = index,
      updateQuorum = globals.updateQuorum,
      clock = Some(s => BigInt(geometry.slotStart(s).toEpochMilli))
    )

/** The mempool as a value. The invariant, for every state reachable through this API:
  *
  * `entries` applied in order to `base` through the transaction rules succeed and give `current`.
  *
  * `base` is the ledger state at the chain tip the pool was last synchronised with. Every operation
  * is a pure function; [[Mempool]] serialises them.
  */
final case class MempoolState(
    base: LedgerState,
    tipSlot: BigInt,
    current: LedgerState,
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
      // The MEMPOOL rule of the ledger (`Rules/Mempool.hs`) runs before LEDGER: a transaction none
      // of whose inputs is unspent is "probably a duplicate" and is refused with that alone. Votes
      // of unelected committee members are refused here, and only here, before protocol version 11;
      // from 11 the GOV rule does.
      conway = current.params.protocolMajor >= 9
      _ <- Either.cond(
        !conway || tx.body.inputs.exists(current.utxo.contains),
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(id, PredicateFailure.Mempool(MempoolState.AllInputsSpent))
        )
      )
      _ <- Either.cond(
        current.params.protocolMajor < 9 || current.params.protocolMajor > 10 ||
          ConwayRules.unelectedCommitteeVoters(current, tx.body).isEmpty,
        (),
        Rejection.Ledger(
          LedgerFailure.Rejected(
            id,
            PredicateFailure.Mempool(
              "Unelected committee members are not allowed to cast votes: " +
                ConwayRules
                  .unelectedCommitteeVoters(current, tx.body)
                  .map(MempoolState.showCredential)
                  .mkString("[", ",", "]")
            )
          )
        )
      )
      next <- TxRules(current, tx, env.txEnv(tipSlot, base, entries.size))
        .leftMap(Rejection.Ledger(_))
    yield
      val entry = Entry(id, raw, tx, nextTicket)
      (
        copy(
          current = next,
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
      ledger: LedgerState,
      slot: BigInt,
      env: MempoolEnv
  ): (MempoolState, Vector[(Entry, Rejection)]) =
    val start =
      copy(base = ledger, tipSlot = slot, current = ledger, entries = Vector.empty, bytes = 0)
    entries.foldLeft((start, Vector.empty[(Entry, Rejection)])) { case ((state, dropped), entry) =>
      TxRules(state.current, entry.tx, env.txEnv(slot, ledger, state.entries.size)) match
        case Right(next) =>
          (
            state.copy(
              current = next,
              entries = state.entries :+ entry,
              bytes = state.bytes + entry.size
            ),
            dropped
          )
        case Left(failure) => (state, dropped :+ (entry -> Rejection.Ledger(failure)))
    }

  /** Remove transactions by id (and, because later ones may spend their outputs, revalidate the
    * rest).
    */
  def remove(ids: Set[Bytes], env: MempoolEnv): (MempoolState, Vector[(Entry, Rejection)]) =
    copy(entries = entries.filterNot(e => ids(e.id))).sync(base, tipSlot, env)

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

  /** A credential as the ledger's `Show` instance prints it. */
  def showCredential(c: Credential): String = c match
    case Credential.Key(h)    => "KeyHashObj (KeyHash {unKeyHash = \"" + h.hex + "\"})"
    case Credential.Script(h) => "ScriptHashObj (ScriptHash \"" + h.hex + "\")"

  def empty(ledger: LedgerState, tipSlot: BigInt, capacityBytes: Long): MempoolState =
    MempoolState(ledger, tipSlot, ledger, Vector.empty, 0L, 1L, capacityBytes)

  private val limits = Cbor.Limits(
    maxInputBytes = 1048576,
    maxDepth = 64,
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
