// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{Conway, NewEpochState, ShelleyBasedEra}

/** The pool's Conway ledger of a Conway state, for the suites that build their pool from one, as
  * the node makes it ([[ConwayPoolLedger.of]], [[PoolLedger.ticked]]).
  */
object ConwayPools:
  /** The pool's ledger of the new-epoch state `nes`, whose parameters the suite knows to be
    * Conway's.
    */
  def of(nes: NewEpochState[Conway]): ConwayPoolLedger =
    ConwayPoolLedger
      .of(nes)
      .fold(e => munit.Assertions.fail(s"parameters that are not Conway's: $e"), identity)

  /** The pool's ledger of the Conway state `nes` of a chain whose tip is at `tipSlot` (none at
    * Origin), ticked to the slot after the tip as the node ticks it ([[PoolLedger.ticked]]), on a
    * chain that stays in Conway. A tick failure is the node's ([[PoolLedger.Failure]]).
    */
  def ticked(
      nes: NewEpochState[Conway],
      tipSlot: Option[BigInt],
      env: MempoolEnv
  ): Either[PoolLedger.Failure, ConwayPoolLedger] =
    PoolLedger
      .ticked(EraNes.InEra(ShelleyBasedEra.SConway, nes), 0L, tipSlot, env, _ => None)
      .map {
        case Right(c: ConwayPoolLedger) => c
        case other => munit.Assertions.fail(s"not the pool's Conway ledger: $other")
      }

extension (l: ConwayPoolLedger)
  /** The ledger after the held transaction `e`, which the suite knows to apply, judged with every
    * check in a block of the slot after `tipSlot`, as a block that holds it leaves the ledger.
    */
  def adopted(e: Entry, tipSlot: BigInt, env: MempoolEnv): ConwayPoolLedger = e.held match
    case Held.InConway(held) =>
      l.admit(held.read, ConwayPoolEnv.of(env, tipSlot + 1), Source.Local)
        .fold(r => munit.Assertions.fail(r.message), _._1)
    case other @ (Held.Flat(_, _, _) | Held.InDijkstra(_)) =>
      munit.Assertions.fail(s"not a Conway transaction: ${other.era}")

  /** `read` held as if validated in this ledger at `env` 's slot (`unsafeMakeValidatedTx`, which
    * the reference keeps for tests), whatever the rules make of it.
    */
  def heldAsValidated(read: ConwayRead, env: ConwayPoolEnv): ConwayAdmitted =
    ConwayAdmitted(
      read,
      xyz.kd5ujc.scarab.ledger.era.rules.ConwayMempoolRun
        .unsafeMakeValidatedTx(
          env.judging,
          xyz.kd5ujc.scarab.ledger.era.rules.ConwayMempoolRun
            .mkMempoolEnv(env.slot, l.pp, l.account),
          l.ls,
          read.tx
        )
        .fold(d => munit.Assertions.fail(s"a stored output the era cannot read: $d"), identity)
    )

  /** The transactions a Conway block in `slot` takes of `entries` on this ledger, and the held ones
    * withheld for want of a verdict, as the forger takes them: the pool's snapshot of the slot
    * ([[MempoolState.conwaySnapshot]], judged with `env` 's clock) cut to the capacity of `limits`
    * ([[xyz.kd5ujc.scarab.node.forge.BlockBuilder.take]]).
    */
  def selected(
      entries: Vector[Entry],
      slot: BigInt,
      env: MempoolEnv,
      limits: xyz.kd5ujc.scarab.node.forge.BlockBuilder.Limits
  ): (Vector[xyz.kd5ujc.scarab.node.forge.ConwayHeld], Vector[(Entry, Rejection)]) =
    val (kept, withheld) = MempoolState.conwaySnapshot(entries, l, ConwayPoolEnv.of(env, slot))
    val snapshot = xyz.kd5ujc.scarab.node.forge.ConwaySnapshot(
      kept.map((e, a) => xyz.kd5ujc.scarab.node.forge.ConwayHeld(a, Capacity.measure(e))),
      withheld.map((e, r) => e.id -> r).toMap
    )
    (xyz.kd5ujc.scarab.node.forge.BlockBuilder.take(snapshot, limits.capacity), withheld)

  /** The raw transaction `raw` read as the pool reads it in this ledger. */
  def readOf(raw: xyz.kd5ujc.scarab.codec.Bytes): ConwayRead =
    MempoolState
      .parseParts(raw)
      .flatMap(p => l.read(EraTx(p.parts, p.tx)))
      .fold(r => munit.Assertions.fail(r.message), identity)

extension (e: Entry)
  /** The held Conway transaction of `e`, which the suite holds as one. */
  def conwayAdmitted: ConwayAdmitted = e.held match
    case Held.InConway(held) => held
    case other @ (Held.Flat(_, _, _) | Held.InDijkstra(_)) =>
      munit.Assertions.fail(s"not a Conway transaction: ${other.era}")

/** Held Conway transactions for the forger's suites, which take of a held one its flat form, its
  * bytes and its measure: read as the pool reads them, and held as if validated in a ledger of
  * TxKit's (`unsafeMakeValidatedTx`).
  */
object ConwayEntries:
  private lazy val ledger: ConwayPoolLedger = TxKit.ledger(1)

  /** `raw` held with the ticket `ticket`. */
  def held(raw: xyz.kd5ujc.scarab.codec.Bytes, ticket: Long = 1L): Entry =
    Entry(
      Held.InConway(ledger.heldAsValidated(ledger.readOf(raw), ConwayPoolEnv.of(TxKit.env, 1001))),
      ticket,
      0L,
      scala.concurrent.duration.Duration.Zero
    )

/** Refusals of Conway's pool in the era's types ([[Rejection.ConwayRefused]]), as the suites expect
  * them: a failure of LEDGER, or of a rule below it, as LEDGER reports it.
  */
object ConwayRefusals:
  import xyz.kd5ujc.scarab.ledger.era.rules.{
    ConwayGovPredFailure,
    ConwayLedgerPredFailure,
    ConwayUtxoPredFailure,
    ConwayUtxowPredFailure
  }

  type Failure = ConwayLedgerPredFailure[Conway]

  /** The refusal of the transaction `id` with `first` and `rest`, in that order. */
  def refused(id: xyz.kd5ujc.scarab.codec.Bytes, first: Failure, rest: Failure*): Rejection =
    Rejection.ConwayRefused(id, cats.data.NonEmptyChain(first, rest*))

  /** A failure of UTXOW, under LEDGER. */
  def utxow(f: ConwayUtxowPredFailure[Conway]): Failure =
    ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](f)

  /** A failure of UTXO, under LEDGER's UTXOW. */
  def utxo(f: ConwayUtxoPredFailure[Conway]): Failure =
    utxow(ConwayUtxowPredFailure.UtxoFailure[Conway](f))

  /** A failure of GOV, under LEDGER. */
  def gov(f: ConwayGovPredFailure[Conway]): Failure =
    ConwayLedgerPredFailure.ConwayGovFailure[Conway](f)

/** What a local client is answered for `answer`, the pool's own: the refusal's bytes
  * ([[LocalSubmission.reasonItem]] in Conway), as the node writes them, or what was admitted.
  */
def answered[A](answer: Either[Rejection, A]): Either[xyz.kd5ujc.scarab.codec.Bytes, A] =
  answer.leftMap(reply)

private def reply(r: Rejection): xyz.kd5ujc.scarab.codec.Bytes =
  LocalSubmission
    .reasonItem(r, Some(xyz.kd5ujc.scarab.protocol.Era.Conway))
    .fold(e => munit.Assertions.fail(s"no reply to $r: ${e.message}"), identity)
