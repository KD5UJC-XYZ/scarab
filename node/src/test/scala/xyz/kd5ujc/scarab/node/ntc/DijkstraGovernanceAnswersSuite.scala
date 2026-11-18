// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.consensus.{BlockRef, ConsensusExtras}
import xyz.kd5ujc.scarab.consensus.state.{
  EraSummary,
  MirrorClock,
  MirrorProvenance,
  Placement,
  ProtocolState
}
import xyz.kd5ujc.scarab.ledger.{PlutusStats, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra, MirrorEraRead, Read}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, NtcVersion, StateLayout}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs, Params}

/** The answers of a Dijkstra state's parameters, governance state and ratify state (#826), asked of
  * the two Dijkstra states the Leios build wrote (`fixtures/leios/state`): the devnet's first
  * Dijkstra epoch and the public testnet's at epoch 100.
  *
  * The expected bytes are the reference's own: each answer is `toCBOR` (`toEraCBOR @DijkstraEra`,
  * version 12) of a part of the state (`encodeShelleyResult`, `Shelley/Ledger/Query.hs:935, 953,
  * 961-962`, ouroboros-consensus 2416493b), and the build wrote the state with the same encoders at
  * the same version (`toCBOR shelleyLedgerState`, `Shelley/Ledger/Ledger.hs:885-903`), so the part
  * is where the state file holds it: the governance state, its current parameters, its pulser's
  * ratify state and, as `Maybe`, its future parameters (`encodeMaybe`,
  * `Encoding/Encoder.hs:318-321`, cardano-ledger 9df9f98c). Each is inside `[result]`, the
  * hard-fork combinator's answer of the current era (`encodeEitherMismatch`,
  * `HardFork/Combinator/Serialisation/Common.hs:546-550`).
  *
  * Under the Leios build each is answered at every node-to-client version alike (that build has no
  * legacy parameters encoding); under the 11.1 build none is, as its Dijkstra state is not read or
  * written yet.
  */
class DijkstraGovernanceAnswersSuite extends FunSuite:
  private val setup: NodeSetup = NtcGolden.setup

  private final case class Native(nes: NewEpochState[Dijkstra])
  private given ChainRead[Native] with
    extension (native: Native)
      def provenance: MirrorProvenance = MirrorProvenance.complete
      def era: Era = Era.Dijkstra
      def ledgerEpoch: BigInt = native.nes.epoch.toBigInt
      def tipSlot: WithOrigin[SlotNo] = WithOrigin.Origin
      // These queries read the ledger state only; the genesis state's stands in for the rest.
      def protocol: ProtocolState = setup.mirrorStart.protocol
      def afterVoting: Long = 0L
      def consensus: ConsensusExtras = setup.mirrorStart.consensus
      def plutus: PlutusStats = PlutusStats.zero
      def utxo: Utxo = native.nes.es.ls.utxo.utxo.utxo
      def summary(placement: Placement): NonEmptyVector[EraSummary] =
        MirrorClock.interpreter(setup.mirrorStart, placement)
      def ledger(at: Option[BlockRef]): Read[Option[InEra]] =
        Right(
          Some(
            InEra.Of(
              ShelleyBasedEra.SDijkstra,
              new MirrorEraRead(native.nes)(using ShelleyBasedEra.SDijkstra)
            )
          )
        )

  private val leios = NtcGolden.queryEnv.copy(layout = StateLayout.Leios)
  private val eleven = NtcGolden.queryEnv.copy(layout = StateLayout.Node11_1)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  /** A captured state: the Dijkstra `NewEpochState` read from it, and the reference's bytes of each
    * answer, taken from where the state holds them.
    */
  private final case class Captured(
      name: String,
      nes: NewEpochState[Dijkstra],
      gov: Bytes,
      current: Bytes,
      ratify: Bytes,
      future: Bytes
  )

  private def captured(name: String): Captured =
    val raw = Bytes.wrap(Files.readAllBytes(Path.of(s"fixtures/leios/state/$name/state")))
    val root = Cbor.decode(raw, HaskellState.limits).fold(e => fail(e.toString), identity)
    // `[1, [ledger, header, Peras]]`, the ledger's telescope at its eighth era, Dijkstra:
    // `[start, [2, [tip, NewEpochState, transition, cumulative transaction bytes]]]`
    val nes = at(root, 1, 0, 7, 1, 1, 1)
    val state = LeiosStateCodecs
      .newEpochState[Dijkstra]
      .decode(nes)
      .fold(e => fail(s"$name: ${e.render}"), identity)
    // the epoch state's ledger state's `UTxOState`, its governance state: `[proposals, committee,
    // constitution, current, previous, future, [snapshot, ratify state]]`
    val gov = at(nes, 3, 1, 1, 3)
    // `FuturePParams`: `[0]`, `[1, params]` or `[2, [params?]]`, the answer `Maybe` of it
    val future = items(at(gov, 5)) match
      case Vector(_)                                    => CborOut.build(_.array(0))
      case Vector(tag, p) if tag.value == Value.UInt(1) => CborOut.build(_.array(1).raw(p.original))
      case Vector(tag, m) if tag.value == Value.UInt(2) => m.original
      case other                                        => fail(s"$name: future parameters $other")
    Captured(name, state, gov.original, at(gov, 3).original, at(gov, 6, 1).original, future)

  private val states = Vector("dijkstra-203", "testnet-2168639").map(captured)

  private def asked(env: QueryEnv, c: Captured, q: LedgerQuery, v: NtcVersion) =
    QueryAnswers.answerOrWhy(
      env,
      QueryView(Point.Origin, None, Native(c.nes)),
      Map.empty,
      NtcQuery.IfCurrent(QueryEra.Dijkstra, q),
      v
    )

  private def result(plain: Bytes): Bytes = CborOut.build(_.array(1).raw(plain))

  /** What `GetCBOR` answers about a result: `#6.24(bytes)` around it. */
  private def wrapped(plain: Bytes): Bytes = CborOut.build(_.array(1).tag(24).bytes(plain))

  private val versions = NtcVersion.values.toVector

  test("the Leios build answers each with the reference's bytes, at every node-to-client version") {
    states.foreach { c =>
      val expected = Vector(
        LedgerQuery.GetCurrentPParams -> c.current,
        LedgerQuery.GetGovState -> c.gov,
        LedgerQuery.GetRatifyState -> c.ratify,
        LedgerQuery.GetFuturePParams -> c.future
      )
      for
        v <- versions
        (q, bytes) <- expected
      do
        assertEquals(asked(leios, c, q, v), Right(result(bytes)), s"${c.name} $q at ${v.number}")
        assertEquals(
          asked(leios, c, LedgerQuery.GetCBOR(q), v),
          Right(wrapped(bytes)),
          s"${c.name} GetCBOR $q at ${v.number}"
        )
    }
  }

  test("the answered parameters are Dijkstra's 54, the state's current ones") {
    val codec = Params.codecIn(StateLayout.Leios, ShelleyBasedEra.SDijkstra)
    states.foreach { c =>
      val answer = asked(leios, c, LedgerQuery.GetCurrentPParams, NtcVersion.V16)
        .fold(e => fail(s"${c.name}: $e"), identity)
      val params = at(Cbor.decode(answer).fold(e => fail(e.toString), identity), 0)
      assertEquals(items(params).size, 54, c.name)
      assertEquals(
        codec.decode(params).map(_ == EraLedger.curPParams(c.nes.es.ls.utxo.gov)),
        Right(true),
        c.name
      )
    }
  }

  test("the 11.1 build answers none of them for a Dijkstra state") {
    states.foreach { c =>
      for
        v <- versions
        q <- Vector(
          LedgerQuery.GetCurrentPParams,
          LedgerQuery.GetGovState,
          LedgerQuery.GetRatifyState,
          LedgerQuery.GetFuturePParams
        )
      do
        assertEquals(asked(eleven, c, q, v), Left(Unanswered.Unsupported), s"${c.name} $q")
        assertEquals(
          asked(eleven, c, LedgerQuery.GetCBOR(q), v),
          Left(Unanswered.Unsupported),
          s"${c.name} GetCBOR $q"
        )
    }
  }

  test("the other queries of Conway's governance are still not answered in Dijkstra") {
    states.foreach { c =>
      Vector(
        LedgerQuery.GetConstitution,
        LedgerQuery.GetProposals(Set.empty),
        LedgerQuery.GetDRepState(Set.empty),
        LedgerQuery.GetDRepStakeDistr(Set.empty)
      ).foreach { q =>
        assertEquals(
          asked(leios, c, q, NtcVersion.V23),
          Left(Unanswered.Unsupported),
          s"${c.name} $q"
        )
      }
    }
  }
