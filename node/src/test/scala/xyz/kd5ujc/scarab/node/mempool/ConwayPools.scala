// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.{ChainAccountState, Coin}
import xyz.kd5ujc.scarab.protocol.StateLayout

extension (l: MempoolLedger)
  /** The pool's Conway ledger, which Conway's era rules judge, of the flat rules' ledger `l`, for
    * the suites that build their states as the flat rules' ledger: the same ledger state, epoch and
    * treasury (the only account the rules read), in the 11.1 layout.
    */
  def toPool: ConwayPoolLedger =
    ConwayPoolLedger
      .of(l.ls, ChainAccountState(l.treasury, Coin.zero), l.epoch, StateLayout.Node11_1)
      .fold(e => munit.Assertions.fail(s"parameters that are not Conway's: $e"), identity)

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
  private lazy val ledger: ConwayPoolLedger = TxKit.ledger(1).toPool

  /** `raw` held with the ticket `ticket`. */
  def held(raw: xyz.kd5ujc.scarab.codec.Bytes, ticket: Long = 1L): Entry =
    Entry(
      Held.InConway(ledger.heldAsValidated(ledger.readOf(raw), ConwayPoolEnv.of(TxKit.env, 1001))),
      ticket,
      0L,
      scala.concurrent.duration.Duration.Zero
    )

/** What a local client is answered for `answer`: the refusal's bytes
  * ([[LocalSubmission.reasonItem]] in Conway), or what was admitted. The suites written against the
  * flat rules' refusals compare the answers of Conway's rules with those of the same refusals in
  * the flat form, byte for byte: a refusal in the flat form, which no Conway pool gives, is written
  * by the flat rules' Conway encoder, as the node wrote it before Conway's pool refused in the
  * era's types (#85).
  */
def answered[A](answer: Either[Rejection, A]): Either[xyz.kd5ujc.scarab.codec.Bytes, A] =
  answer.leftMap { r =>
    val flat = r match
      case Rejection.Ledger(xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(_, failures)) =>
        Some(failures.toChain.toVector)
      case Rejection.Limits(_, failures) => Some(failures.toList.toVector)
      case _                             => None
    flat
      .fold(
        LocalSubmission
          .reasonItem(r, Some(xyz.kd5ujc.scarab.protocol.Era.Conway))
          .leftMap(_.message)
      )(failures =>
        xyz.kd5ujc.scarab.node.ntc.PredicateFailureCbor.applyTxError(failures).leftMap(_.message)
      )
      .fold(e => munit.Assertions.fail(s"no reply to $r: $e"), identity)
  }
