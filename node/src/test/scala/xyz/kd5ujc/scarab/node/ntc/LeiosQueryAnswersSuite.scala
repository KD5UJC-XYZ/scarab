// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.data.NonEmptyVector
import io.circe.{ACursor, Json, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
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
import xyz.kd5ujc.scarab.ledger.{PlutusStats, PoolParams, RewardAccount, Utxo}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.{
  EpochInterval,
  EraTag,
  PoolBlsKey,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  WithOrigin,
  addInterval
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.{ChainRead, EraRead, InEra, MirrorEraRead, Read}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{Era, NtcVersion, StateLayout}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** The answers of the Leios build's two queries, `GetStakePoolState` (40) and `GetLeiosCommittee`
  * (41), asked under that build at node-to-client version 23 of the three states it wrote
  * (`fixtures/leios/state`: Conway's last devnet state, Dijkstra's first, and the public testnet's
  * at epoch 100), against the bytes the build's own ledger code writes for each
  * (`fixtures/reference/leios-ntc-queries`, oracle `scripts/oracle/leios-ntc-queries`):
  * `queryStakePoolState` and `queryLeiosCommittee` written with `toEraCBOR @era` (cardano-ledger
  * 9df9f98c `Api/State/Query.hs:551-660, 989-1026`; ouroboros-consensus 2416493b
  * `Shelley/Ledger/Query.hs:467-470, 969-970`). Each is inside `[result]`, the hard-fork
  * combinator's answer of the current era (`encodeEitherMismatch`,
  * `HardFork/Combinator/Serialisation/Common.hs:546-550`), which the oracle does not run.
  *
  * No captured state holds a retirement or future parameters, so the oracle also derives a state
  * from each: its smallest pool P retiring at the next epoch, with its current parameters as its
  * future ones (`stakePoolStateToStakePoolParams Testnet`, the networks of the captures). Here the
  * same state is built. On the testnet's state P holds a voting key, which its future parameters
  * would carry (`StakePool.hs:701-740`) and which a pool's parameters do not hold here yet
  * (`DijkstraNotYet.PoolParamsBlsKey`, #826 comment 6967015840), so that case is compared on the
  * two devnet states only.
  */
class LeiosQueryAnswersSuite extends FunSuite:
  private val setup: NodeSetup = NtcGolden.setup

  private final case class Native(era: Era, epoch: BigInt, in: InEra, derived: Option[Native])
  private given ChainRead[Native] with
    extension (native: Native)
      def provenance: MirrorProvenance = MirrorProvenance.complete
      def era: Era = native.era
      def ledgerEpoch: BigInt = native.epoch
      def tipSlot: WithOrigin[SlotNo] = WithOrigin.Origin
      // These queries read the ledger state only; the genesis state's stands in for the rest.
      def protocol: ProtocolState = setup.mirrorStart.protocol
      def afterVoting: Long = 0L
      def consensus: ConsensusExtras = setup.mirrorStart.consensus
      def plutus: PlutusStats = PlutusStats.zero
      def utxo: Utxo = Utxo.empty
      def summary(placement: Placement): NonEmptyVector[EraSummary] =
        MirrorClock.interpreter(setup.mirrorStart, placement)
      def ledger(at: Option[BlockRef]): Read[Option[InEra]] = Right(Some(native.in))

  private val leios = NtcGolden.queryEnv.copy(layout = StateLayout.Leios)

  private def items(n: Node): Vector[Node] = n.value match
    case Value.Arr(xs) => xs
    case other         => fail(s"not an array: $other")

  private def at(n: Node, path: Int*): Node = path.foldLeft(n)((m, i) => items(m)(i))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def sha256(b: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(b.toArray).map(x => f"$x%02x").mkString

  /** A captured state, read in the Leios layout: the ledger's telescope at the era's index,
    * `[start, [2, [tip, NewEpochState, transition, cumulative transaction bytes]]]`.
    */
  private def native[E <: EraTag](raw: Bytes, era: ShelleyBasedEra[E], tag: Era): Native =
    val root = Cbor.decode(raw, HaskellState.limits).fold(e => fail(e.toString), identity)
    val nes: NewEpochState[E] = LeiosStateCodecs
      .newEpochState[E](using era)
      .decode(at(root, 1, 0, tag.ordinal, 1, 1, 1))
      .fold(e => fail(e.render), identity)
    def of(n: NewEpochState[E], derived: Option[Native]) =
      Native(tag, n.epoch.toBigInt, InEra.Of(era, new MirrorEraRead(n)(using era)), derived)
    of(nes, retiringWithFuture(nes, era).map(of(_, None)))

  /** The oracle's derived state: the smallest pool retiring at the next epoch, its parameters as
    * read back from its state (`stakePoolStateToStakePoolParams`, `State/StakePool.hs:325-343`,
    * cardano-ledger 9df9f98c) on the testnet (network 0) as its future ones; none if that pool
    * holds a voting key.
    */
  private def retiringWithFuture[E <: EraTag](
      nes: NewEpochState[E],
      era: ShelleyBasedEra[E]
  ): Option[NewEpochState[E]] =
    given ShelleyBasedEra[E] = era
    val ls = nes.es.ls
    val p = EraLedger.pState(ls)
    p.stakePools.headOption.collect {
      case (id, s) if s.blsKey == PoolBlsKey.Leios(None) =>
        val params = PoolParams(
          id.bytes,
          s.vrf.bytes,
          s.pledge.value,
          s.cost.value,
          s.margin,
          RewardAccount(0, EraRead.credential(s.accountId)),
          s.owners.iterator.map(_.bytes).toSet,
          s.relays,
          s.metadata
        )
        val held = p.copy(
          retiring = p.retiring + (id -> nes.epoch.addInterval(EpochInterval.one)),
          future = p.future + (id -> params)
        )
        nes.copy(es = nes.es.copy(ls = EraLedger.withPState(ls, held)))
    }

  private final case class Case(
      label: String,
      query: LedgerQuery,
      result: Bytes,
      derived: Boolean
  )
  private final case class Captured(name: String, era: QueryEra, state: Native, cases: Vector[Case])

  private def str(c: ACursor, field: String): String =
    c.get[String](field).fold(e => fail(s"$field: ${e.message}"), identity)

  private def query(c: ACursor): LedgerQuery =
    c.get[Int]("tag").toOption match
      case Some(40) =>
        val pools = c.get[Option[Vector[String]]]("poolIds").fold(e => fail(e.message), identity)
        val delegators = c.get[Boolean]("includeDelegators").fold(e => fail(e.message), identity)
        LedgerQuery.GetStakePoolState(
          pools.map(_.map(hex).toSet),
          if delegators then PoolDelegators.Included else PoolDelegators.Omitted
        )
      case Some(41) => LedgerQuery.GetLeiosCommittee
      case other    => fail(s"unknown case tag $other")

  private val captured: Vector[Captured] =
    Files
      .readAllLines(Path.of("fixtures/reference/leios-ntc-queries/cases.jsonl"))
      .toArray(Array.empty[String])
      .toVector
      .map { line =>
        val json = parser.parse(line).fold(e => fail(e.message), identity)
        val c = json.hcursor
        val name = str(c, "name")
        val raw = Bytes.wrap(Files.readAllBytes(Path.of(s"fixtures/leios/state/$name/state")))
        assertEquals(sha256(raw), str(c, "stateSha256"), s"$name: the input is the oracle's")
        val (era, state) = str(c, "era") match
          case "conway" => (QueryEra.Conway, native(raw, ShelleyBasedEra.SConway, Era.Conway))
          case "dijkstra" =>
            (QueryEra.Dijkstra, native(raw, ShelleyBasedEra.SDijkstra, Era.Dijkstra))
          case other => fail(s"$name: era $other")
        val cases = c
          .downField("reference")
          .downField("cases")
          .values
          .getOrElse(fail(s"$name: no cases"))
          .toVector
          .zipWithIndex
          .map { (j: Json, i: Int) =>
            val k = j.hcursor
            Case(
              s"$name #$i ${str(k, "query")}",
              query(k),
              hex(str(k, "result")),
              k.get[String]("derived").isRight
            )
          }
        Captured(name, era, state, cases)
      }

  private def asked(c: Captured, k: Case, q: LedgerQuery, v: NtcVersion) =
    val state =
      if k.derived then c.state.derived.getOrElse(fail(s"${k.label}: no derived state"))
      else c.state
    QueryAnswers.answerOrWhy(
      leios,
      QueryView(Point.Origin, None, state),
      Map.empty,
      NtcQuery.IfCurrent(c.era, q),
      v
    )

  private def result(plain: Bytes): Bytes = CborOut.build(_.array(1).raw(plain))

  test("the fixture holds the three states the Leios build wrote, both queries of each") {
    assertEquals(captured.map(_.name), Vector("conway-195", "dijkstra-203", "testnet-2168639"))
    captured.foreach { c =>
      assertEquals(c.cases.size, 8, c.name)
      assertEquals(c.cases.count(_.derived), 2, c.name)
      assert(c.cases.exists(_.query == LedgerQuery.GetLeiosCommittee), c.name)
    }
    // the derived state is built where the oracle's smallest pool holds no voting key
    assertEquals(captured.map(_.state.derived.isDefined), Vector(true, true, false))
  }

  /** The cases compared: the derived ones where the derived state is built. */
  private def compared(c: Captured): Vector[Case] =
    c.cases.filter(k => !k.derived || c.state.derived.isDefined)

  test("the Leios build at version 23 answers each with the bytes of the build's ledger code") {
    val checked =
      for
        c <- captured
        k <- compared(c)
      yield
        assertEquals(
          asked(c, k, k.query, NtcVersion.V23).map(_.hex),
          Right(result(k.result).hex),
          k.label
        )
        k
    assertEquals(checked.size, 22)
  }

  test("inside GetCBOR each answer is the same bytes under tag 24") {
    for
      c <- captured
      k <- compared(c)
    do
      assertEquals(
        asked(c, k, LedgerQuery.GetCBOR(k.query), NtcVersion.V23),
        Right(CborOut.build(_.array(1).tag(24).bytes(k.result))),
        k.label
      )
  }

  test("a query of another era is answered with the era mismatch") {
    captured.foreach { c =>
      val other = if c.era == QueryEra.Conway then QueryEra.Dijkstra else QueryEra.Conway
      val answer = QueryAnswers.answerOrWhy(
        leios,
        QueryView(Point.Origin, None, c.state),
        Map.empty,
        NtcQuery.IfCurrent(other, LedgerQuery.GetLeiosCommittee),
        NtcVersion.V23
      )
      assert(
        answer.exists(b => items(Cbor.decode(b).fold(e => fail(e.toString), identity)).size == 2),
        c.name
      )
    }
  }

  test("GetMaxMajorProtocolVersion: the Leios build's 12 at version 23, 11.1's 11 at 16 to 22") {
    val experimental =
      leios.copy(forks = leios.forks.copy(maxMajorProtVer = 12))
    val c = captured.head
    def asked(v: NtcVersion) =
      QueryAnswers.answerOrWhy(
        experimental,
        QueryView(Point.Origin, None, c.state),
        Map.empty,
        NtcQuery.IfCurrent(c.era, LedgerQuery.GetMaxMajorProtocolVersion),
        v
      )
    assertEquals(asked(NtcVersion.V23).map(_.hex), Right(result(Bytes(Vector(0x0c.toByte))).hex))
    NtcVersion.All.filter(_ != NtcVersion.V23).foreach { v =>
      assertEquals(asked(v).map(_.hex), Right(result(Bytes(Vector(0x0b.toByte))).hex), v.toString)
    }
  }
