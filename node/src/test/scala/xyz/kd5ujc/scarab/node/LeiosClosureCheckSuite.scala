// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.leios.EbClosureVerdict
import xyz.kd5ujc.scarab.consensus.state.{MirrorState, MirrorStep}
import xyz.kd5ujc.scarab.ledger.{Block, Header, NodeError, StorageError}
import xyz.kd5ujc.scarab.ledger.era.{IsPhase2Valid, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraCorpus, DijkstraTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxosPredFailure,
  DijkstraClosure,
  DijkstraLedgerPredFailure,
  DijkstraMempoolPredFailure,
  DijkstraUtxoPredFailure,
  DijkstraUtxowPredFailure,
  LeiosClosureDefect
}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, SlotNo}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, DijkstraRead, MempoolEnv, Rejection}
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, Era, LeiosTxHash, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.MemoryUtxoStore

/** The closure check a voter runs before it votes (`validateEbClosure`, ouroboros-consensus
  * 2416493b `LeiosVoting.hs:446-565`), on the devnet's recorded Dijkstra transaction
  * (`fixtures/dijkstra-devnet`, the transaction of its block 37) as an endorser block's closure on
  * the state before that block, its tip the announcing block: it applies, ticked to the tip's own
  * slot; a closure that spends its inputs twice, an altered transaction and a transaction whose
  * validity flag its scripts contradict (`Intervene`) are refused with the ledger's refusal, as the
  * reference's `EbClosureInvalid`; a missing or undecodable closure is no verdict
  * (`EbClosureUnreadable`).
  */
class LeiosClosureCheckSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  private given Raise[IO, StorageError] with
    def functor = cats.Functor[IO]
    def raise[E <: StorageError, A](error: E): IO[A] = IO(fail(error.message))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  private val devnet = Path.of("fixtures", "dijkstra-devnet")

  private lazy val setup: NodeSetup =
    NodeSetup
      .fromDirectory(ReferenceBuild.LeiosB004fe0c)(devnet.resolve("genesis"))
      .fold(e => fail(e.message), identity)

  private def geometry = setup.praos.geometry

  private lazy val chain: Vector[(Header, Bytes)] =
    Files
      .readAllLines(devnet.resolve("blocks.tsv"))
      .asScala
      .toVector
      .drop(1)
      .filter(_.nonEmpty)
      .map(_.split('\t'))
      .take(37)
      .map { r =>
        val raw = hex(r(4))
        Block.decode(raw, geometry.byron.epochSlots).fold(e => fail(e.message), _.header) -> raw
      }

  /** The state before block 37: block 36 its tip, the announcing block here. */
  private lazy val announcing: IO[MirrorState] =
    val rules = new MirrorRules[IO](
      setup.praos,
      setup.globals,
      setup.byronRules,
      setup.handOver,
      setup.byronHistory,
      PlutusBackend.default
    )
    chain.foldLeftM(setup.mirrorStart) { case (state, (header, raw)) =>
      Handle
        .allow[NodeError](rules(state, header, raw))
        .rescue(e => IO(fail(s"block ${header.blockNo}: ${e.message}")))
    }

  private def tip: BlockRef =
    val h = chain.last._1
    BlockRef(h.slot, h.hash, h.blockNo)

  private def nesOf(state: MirrorState): NewEpochState[Dijkstra] =
    MirrorStep
      .nesOf(state)
      .flatMap(MirrorDijkstra.inDijkstra)
      .getOrElse(fail("not in Dijkstra"))

  /** The endorser block's point: the announcing block's slot (`announcementLeiosPoint`). */
  private def point(hash: Int): EbPoint =
    EbPoint(
      Word64.fromUnsigned(tip.slot.toLong),
      EbHash.fromBytes(Bytes(Vector.fill(32)(hash.toByte)))
    )

  private def envOf(state: MirrorState): DijkstraPoolEnv =
    DijkstraPoolEnv
      .of(
        MempoolEnv(
          setup.globals,
          geometry,
          PlutusBackend.default.evaluator,
          PlutusBackend.default.decoder
        ),
        tip.slot
      )
      .copy(clock = setup.txClock.at(state, Era.Dijkstra, tip.slot))

  /** The wallet's submitted transaction (`[body, wits, aux / null]`). */
  private lazy val submitted: Bytes =
    val json = parser.parse(Files.readString(devnet.resolve("transaction.json")))
    hex(json.flatMap(_.hcursor.get[String]("cborHex")).fold(e => fail(e.getMessage), identity))

  /** The transaction as an endorser block holds it, `[body, wits, aux / null, valid]`
    * (`decodeDijkstraTopTxInBlock`, the flag last), with the validity flag `valid`.
    */
  private def inBlock(valid: Boolean): Bytes =
    DijkstraCorpus.node(submitted).value match
      case Value.Arr(items) if items.sizeIs == 3 =>
        val parts = items.map(_.original)
        Bytes.fromArray(Array(0x84.toByte)) ++ parts(0) ++ parts(1) ++ parts(2) ++
          Bytes.fromArray(Array((if valid then 0xf5 else 0xf4).toByte))
      case other => fail(s"not a transaction: $other")

  private lazy val tx: Tx[TxLevel.Top, Dijkstra] =
    DijkstraRead.submitted(submitted).fold(e => fail(e.toString), _.tx)

  private def entry(t: Tx[TxLevel.Top, Dijkstra]): (LeiosTxHash, Tx[TxLevel.Top, Dijkstra]) =
    LeiosTxHash.fromBytes(t.id) -> t

  private def checked(
      closure: Vector[Tx[TxLevel.Top, Dijkstra]]
  ): IO[EbClosureVerdict[Rejection, Nothing]] =
    announcing.map { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      LeiosClosureCheck.applyClosure(closure.map(entry), state, envOf(s))
    }

  private def refused(verdict: EbClosureVerdict[Rejection, ?]) = verdict match
    case EbClosureVerdict.Invalid(Rejection.DijkstraRefused(id, failures)) =>
      (id, failures.toChain.toVector)
    case other => fail(s"not a refusal of the rules: $other")

  test(
    "a closure of a transaction that applies is valid on the announcing state ticked to its slot"
  ) {
    checked(Vector(tx)).map(v => assertEquals(v, EbClosureVerdict.Valid))
  }

  test("a closure that spends its inputs twice is refused at its second transaction") {
    checked(Vector(tx, tx)).map(v =>
      assertEquals(
        refused(v),
        (tx.id, Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))
      )
    )
  }

  test("an altered transaction is refused by the ledger, the closure with it") {
    // the fee one lovelace more: the body's hash and its balance change, the witness does not
    val fee = hex("021a0002990d")
    val at = submitted.value.indexOfSlice(fee.value)
    assert(at > 0, "the fee field")
    val altered = DijkstraRead
      .submitted(submitted.take(at) ++ hex("021a0002990e") ++ submitted.drop(at + fee.size))
      .fold(e => fail(e.toString), _.tx)
    checked(Vector(altered)).map { v =>
      val (id, failures) = refused(v)
      assertEquals(id, altered.id)
      assert(
        failures.exists {
          case DijkstraMempoolPredFailure.LedgerFailure(
                DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                  DijkstraUtxowPredFailure.InvalidWitnessesUTXOW(_)
                )
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    }
  }

  test("Intervene: a flag the scripts contradict is refused, not applied as collateral-only") {
    // the transaction runs no script, so a declared invalid flag mismatches its evaluation
    val flipped = DijkstraTx.withIsValid(tx, IsPhase2Valid.Phase2Invalid)
    checked(Vector(flipped)).map { v =>
      val (_, failures) = refused(v)
      assert(
        failures.exists {
          case DijkstraMempoolPredFailure.LedgerFailure(
                DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                  DijkstraUtxowPredFailure.UtxoFailure(
                    DijkstraUtxoPredFailure.UtxosFailure(
                      ConwayUtxosPredFailure.ValidationTagMismatch(_, _)
                    )
                  )
                )
              ) =>
            true
          case _ => false
        },
        s"$failures"
      )
    }
  }

  test("the closure is read from the store, decoded as an endorser block holds it, and applied") {
    val p = point(7)
    val stored = Vector(LeiosTxHash.fromBytes(tx.id) -> inBlock(valid = true))
    assertEquals(DijkstraClosure.deserializeTxForLeios(stored.head._2).map(_.id), Right(tx.id))
    announcing.flatMap { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      def lookup(answer: Option[Vector[(LeiosTxHash, Bytes)]]) =
        LeiosClosureCheck.validateEbClosure[IO](
          h => IO(assertEquals(h, p.hash)).as(answer),
          None,
          p,
          state,
          envOf(s)
        )
      val garbled = Vector(LeiosTxHash.fromBytes(tx.id) -> hex("8200"))
      (lookup(Some(stored)), lookup(None), lookup(Some(garbled))).tupled.map {
        (valid, missing, undecodable) =>
          assertEquals(valid, EbClosureVerdict.Valid)
          assertEquals(missing, EbClosureVerdict.Unreadable(LeiosClosureDefect.Missing(p.hash)))
          undecodable match
            case EbClosureVerdict.Unreadable(LeiosClosureDefect.TxUndecodable(eb, txh, _)) =>
              assertEquals((eb, txh), (p.hash, garbled.head._1))
            case other => fail(s"not undecodable: $other")
      }
    }
  }

  test("the outputs a closure spends are read from the store when the state does not hold them") {
    val p = point(8)
    val stored = Vector(LeiosTxHash.fromBytes(tx.id) -> inBlock(valid = true))
    announcing.flatMap { s =>
      val at = SlotNo.fromBigInt(tip.slot).fold(e => fail(e.toString), identity)
      val state =
        LeiosClosureCheck.ticked(envOf(s).rules, nesOf(s), at).fold(e => fail(e.toString), identity)
      val inputs = MirrorDijkstra.inputs(Vector(tx))
      val held = state.utxo
      val spent = inputs.flatMap(i => held.get(i).map(i -> _)).toMap
      assert(spent.nonEmpty, "the transaction's inputs are in the state")
      // the state without the spent outputs, which only the store holds; an absent key is no entry
      val stripped = held.copy(diff = held.diff -- inputs, resident = held.resident -- inputs)
      for
        store <- MemoryUtxoStore[IO]
        _ <- store.write(spent.view.mapValues(Some(_)).toMap, 0L)
        withStore <- LeiosClosureCheck.validateEbClosure[IO](
          _ => IO.pure(Some(stored)),
          Some(store),
          p,
          state.withUtxo(stripped),
          envOf(s)
        )
        without <- LeiosClosureCheck.validateEbClosure[IO](
          _ => IO.pure(Some(stored)),
          None,
          p,
          state.withUtxo(stripped),
          envOf(s)
        )
      yield
        assertEquals(withStore, EbClosureVerdict.Valid)
        // without the store the state holds none of them: MEMPOOL's own refusal
        assertEquals(
          refused(without),
          (tx.id, Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))
        )
    }
  }

  test("an empty closure is valid, as `goValidate` 's `[]` is") {
    checked(Vector.empty).map(v => assertEquals(v, EbClosureVerdict.Valid))
  }
