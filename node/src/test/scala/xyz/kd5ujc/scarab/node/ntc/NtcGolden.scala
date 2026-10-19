// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.read.{ChainRead, InEra}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.legacy
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.state.{MirrorEnv, SlotNo, WithOrigin}
import xyz.kd5ujc.scarab.node.{MirrorSteps, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import xyz.kd5ujc.scarab.protocol.Era

/** The node-to-client answers over the Conway states of the eras capture, as a fixture
  * (`fixtures/ntc-golden`): what [[QueryAnswers]] answers to every ledger query on those states,
  * recorded from the encoders the answers were written with before the Haskell state codec took
  * them over (`LedgerEncoders`, deleted since, whose bytes the devnet comparison with the reference
  * node proved). A change requires a reference-backed reason recorded with the fixture; #135's six
  * debug-answer changes affect only the corrected captured non-myopic reward pot.
  *
  * The states are the three Conway states of the capture, and two built on the last one that hold
  * what a young chain does not: DReps, a committee, one proposal of each kind, a completed pulser
  * with its snapshot, delegations of every DRep kind and credentials that sort across the byte
  * ranges. `rich-genesis` is the same state with the pulser the genesis seeds (no previous
  * parameters, no completed pulser).
  *
  * The file has one line per answer, `state TAB query TAB value`, the value the hex of the answer
  * or, above [[inlineLimit]] bytes, `sha256:` and the digest.
  */
object NtcGolden:
  val file: Path = Path.of("fixtures/ntc-golden/conway-answers.tsv")
  val inlineLimit = 2048

  private val fixtures = Path.of("fixtures/eras")

  val setup: NodeSetup =
    NodeSetup.fromDirectory(fixtures.resolve("genesis")).fold(e => sys.error(e.message), identity)

  val mirrorEnv: MirrorEnv =
    MirrorEnv(setup.globals.network, setup.geometry, setup.globals.updateQuorum)

  val queryEnv: QueryEnv = QueryEnv(
    setup.byronParams
      .flatMap(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
      EraHistory.ByronParams(b.epochSlots, BigInt(b.slotLengthMillis), BigInt(b.securityParam))
    ),
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger
  )

  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => sys.error(e.message), identity)

  val raws: Vector[Bytes] =
    Files
      .readAllLines(fixtures.resolve("blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(hex)

  val blocks: Vector[Block] =
    raws.map(
      Block.decode(_, setup.geometry.byron.epochSlots).fold(e => sys.error(e.message), identity)
    )

  /** The Conway states of the capture, each with the block that made it. */
  lazy val conwayStates: Vector[(Block, MirrorState)] =
    blocks.indices
      .foldLeft(Vector.empty[(Block, MirrorState)]) { (acc, i) =>
        val before = acc.lastOption.fold(setup.mirrorStart)(_._2)
        MirrorSteps.block(setup, before, blocks(i).header, raws(i)) match
          case Right(next) => acc :+ (blocks(i) -> next)
          case Left(f)     => sys.error(s"block $i rejected by ${f.rule.name}: ${f.reason}")
      }
      .filter(_._1.era == Era.Conway)

  /** The flat ledger state a mirror stands for (the way the fixture was built: the states the query
    * answers are recorded for are the flat state of the old rules put through the mirror, with
    * governance added to it).
    */
  def flatLedger(m: MirrorState, anchor: Option[BlockRef]): legacy.LedgerState =
    m.ledger(anchor) match
      case Right(Some(InEra.Of(era, read))) =>
        val at = anchor.fold[WithOrigin[SlotNo]](WithOrigin.Origin)(a =>
          WithOrigin.At(SlotNo.fromUnsigned(a.slot.toLong))
        )
        read.nes
          .flatMap(nes => legacy.LedgerMirrorBack.flatOf(mirrorEnv, at, nes, m.plutus)(using era))
          .fold(e => sys.error(s"flat read-back: $e"), identity)
      case other => sys.error(s"no Shelley-based ledger state: $other")

  // ---- the states

  private def bytes(from: Int, size: Int = 28): Bytes =
    Bytes(Vector.tabulate(size)(i => (from + i).toByte))

  private val keyA = Credential.Key(bytes(1))
  private val scriptA = Credential.Script(bytes(2))
  private val keyB = Credential.Key(bytes(0x80))
  private val scriptB = Credential.Script(bytes(0xf0))
  private val anchorA = Anchor("https://example.invalid/a.json", bytes(0x10, 32))
  private val anchorB = Anchor("https://example.invalid/b.json", bytes(0x90, 32))

  private def actionId(n: Int): GovActionId = GovActionId(bytes(0x40 + n, 32), n)
  private def account(c: Credential) = RewardAccount(0, c)

  private def update(field: Int, value: Int): ParamUpdate =
    ParamUpdate(Map(field -> CborOut.build(_.uint(value))))

  private def proposed(n: Int, action: GovAction): GovActionState =
    GovActionState(
      actionId(n),
      Map(keyB -> Vote.Yes, scriptA -> Vote.No),
      Map(keyA -> Vote.Abstain, scriptB -> Vote.Yes, keyB -> Vote.No),
      Map(bytes(0x20 + n) -> Vote.Yes, bytes(0xa0 + n) -> Vote.No),
      Proposal(500000000L + n, account(if n % 2 == 0 then keyA else scriptB), action, anchorA),
      3,
      9 + n
    )

  private val actions: Vector[GovAction] = Vector(
    GovAction.ParameterChange(None, update(0, 55), Some(bytes(0x30))),
    GovAction.HardFork(None, 10, 0),
    GovAction.TreasuryWithdrawals(
      Vector(account(scriptB) -> BigInt(7), account(keyA) -> BigInt(11), account(scriptB) -> 5),
      None
    ),
    GovAction.NoConfidence(None),
    GovAction.UpdateCommittee(
      None,
      Set(keyA, scriptA),
      Map(keyB -> BigInt(20), scriptB -> BigInt(21)),
      Rational(2, 3)
    ),
    GovAction.NewConstitution(None, Some(bytes(0x31)), anchorB),
    GovAction.Info
  )

  private val states: Vector[GovActionState] =
    actions.zipWithIndex.map((a, i) => proposed(i, a))

  /** A state that holds governance, DReps and a committee on top of `s`. */
  private def rich(s: legacy.LedgerState, completed: Boolean): legacy.LedgerState =
    val committee = Committee(Map(keyB -> (s.epoch + 3), scriptA -> (s.epoch - 1)), Rational(2, 3))
    val constitution = Constitution(anchorB, Some(bytes(0x31)))
    val roots = PrevIds(Some(actionId(20)), None, Some(actionId(21)), Some(actionId(22)))
    val params = s.params
    val next = params.copy(minFeeA = params.minFeeA + 1)
    val ratify = RatifyState(
      EnactState(
        Some(committee),
        constitution,
        next,
        params,
        s.treasury + 777,
        Map(keyA -> BigInt(5), scriptB -> BigInt(6)),
        roots
      ),
      Vector(states(0)),
      Set(actionId(30), actionId(31)),
      delayed = true
    )
    val snapshot = PulsingSnapshot(
      states.take(4),
      Map(
        DRep.Registered(scriptB) -> BigInt(3),
        DRep.Registered(keyA) -> BigInt(5),
        DRep.AlwaysNoConfidence -> BigInt(7),
        DRep.AlwaysAbstain -> BigInt(9),
        DRep.Registered(keyB) -> BigInt(11)
      ),
      Map(keyA -> DRepState(500, s.epoch + 4, Some(anchorA), Set(keyA, scriptA))),
      s.pools.keysIterator.map(_ -> BigInt(123456)).toMap
    )
    val pool = s.pools.keysIterator.toVector.sortBy(_.hex).headOption
    s.copy(
      accounts = s.accounts ++ Map(
        keyA -> Account(10, 2000000, pool, Some(DRep.Registered(keyA))),
        scriptA -> Account(0, 2000000, None, Some(DRep.AlwaysAbstain)),
        keyB -> Account(5, 2000000, pool, Some(DRep.AlwaysNoConfidence)),
        scriptB -> Account(77, 2000000, None, Some(DRep.Registered(scriptB)))
      ),
      dreps = Map(
        keyA -> DRepState(500000000, s.epoch + 10, Some(anchorA), Set(keyA, scriptA)),
        scriptB -> DRepState(500000000, 0, None, Set(scriptB)),
        keyB -> DRepState(500000000, s.epoch, Some(anchorB), Set.empty)
      ),
      dormantEpochs = 2,
      committeeState = Map(
        keyB -> CommitteeAuth.Hot(keyA),
        scriptA -> CommitteeAuth.Resigned(Some(anchorA)),
        keyA -> CommitteeAuth.Resigned(None)
      ),
      prevParams = Option.when(completed)(params.copy(minFeeB = params.minFeeB + 1)),
      gov = legacy.GovState(
        Proposals(states, roots),
        Some(committee),
        constitution,
        Option.when(completed)((snapshot, ratify))
      )
    )

  /** The states, named, each with the block number and slot it is answered at. */
  lazy val subjects: Vector[(String, MirrorState, Point, Option[BigInt])] =
    val point = (b: Block) =>
      Point.Block(
        UInt64.saturating(BigInt(b.header.slot.toLong)),
        bytes(b.header.blockNo.toInt, 32)
      )
    val real = conwayStates.zipWithIndex.map { case ((b, s), i) =>
      (s"conway-$i", s, point(b), Some(b.header.blockNo))
    }
    val (lastBlock, last) = conwayStates.last
    val anchor = Some(
      BlockRef(lastBlock.header.slot, lastBlock.header.hash, lastBlock.header.blockNo)
    )
    // the last state with a flat ledger state of governance, DReps and a committee in it
    def enriched(ledger: legacy.LedgerState): MirrorState =
      val flat = legacy.FlatNodeState(
        last.protocol,
        ledger,
        last.consensus.boot,
        last.consensus.byron
      )
      legacy.Mirroring
        .toMirror(setup.mirrorEnv(), anchor, flat)
        .fold(e => sys.error(s"the rich state does not mirror: $e"), _.value)
    val base = flatLedger(last, anchor)
    Vector(
      ("rich", enriched(rich(base, completed = true))),
      ("rich-genesis", enriched(rich(base, completed = false).copy(prevParams = None)))
    ).foldLeft(real) { case (acc, (name, s)) =>
      acc :+ (name, s, point(lastBlock), Some(lastBlock.header.blockNo))
    }

  // ---- the queries

  /** Every ledger query this node answers, with arguments drawn from the state. */
  def battery(s: legacy.LedgerState): Vector[(String, LedgerQuery)] =
    import LedgerQuery.*
    val credentials = s.accounts.keySet
    val pools = s.pools.keySet
    val firstPool = pools.toVector.sortBy(_.hex).take(1).toSet
    val dreps = s.dreps.keySet
    val registered: Set[DRep] = dreps.map(DRep.Registered(_))
    val entries = s.utxo.toMap.toVector.sortBy(_._1.id.hex)
    // by hash, not by the order of the map, which is the order the state was built in
    val some = credentials.toVector.sortBy(_.hash.hex).take(2).toSet
    Vector(
      "GetLedgerTip" -> GetLedgerTip,
      "GetEpochNo" -> GetEpochNo,
      "GetCurrentPParams" -> GetCurrentPParams,
      "GetGenesisConfig" -> GetGenesisConfig,
      "DebugChainDepState" -> DebugChainDepState,
      "DebugEpochState" -> DebugEpochState,
      "DebugNewEpochState" -> DebugNewEpochState,
      "GetUTxOWhole" -> GetUTxOWhole,
      "GetUTxOByAddress" -> GetUTxOByAddress(entries.take(2).map(_._2.address.raw).toSet),
      "GetUTxOByTxIn" -> GetUTxOByTxIn(entries.take(3).map(_._1).toSet),
      "GetCBOR(GetUTxOWhole)" -> GetCBOR(GetUTxOWhole),
      "GetFilteredDelegationsAndRewardAccounts all" ->
        GetFilteredDelegationsAndRewardAccounts(credentials),
      "GetFilteredDelegationsAndRewardAccounts some" ->
        GetFilteredDelegationsAndRewardAccounts(some),
      "GetFilteredDelegationsAndRewardAccounts none" ->
        GetFilteredDelegationsAndRewardAccounts(Set.empty),
      "GetStakePools" -> GetStakePools,
      "GetStakePoolParams all" -> GetStakePoolParams(pools),
      "GetStakePoolParams none" -> GetStakePoolParams(Set.empty),
      "GetPoolState all" -> GetPoolState(None),
      "GetPoolState some" -> GetPoolState(Some(pools)),
      "GetPoolState none" -> GetPoolState(Some(Set.empty)),
      "GetStakeSnapshots all" -> GetStakeSnapshots(None),
      "GetStakeSnapshots some" -> GetStakeSnapshots(Some(pools)),
      "GetStakeDelegDeposits all" -> GetStakeDelegDeposits(Set.empty),
      "GetStakeDelegDeposits some" -> GetStakeDelegDeposits(some),
      "GetConstitution" -> GetConstitution,
      "GetGovState" -> GetGovState,
      "GetDRepState all" -> GetDRepState(Set.empty),
      "GetDRepState some" -> GetDRepState(dreps.toVector.sortBy(_.hash.hex).take(2).toSet),
      "GetDRepStakeDistr all" -> GetDRepStakeDistr(Set.empty),
      "GetDRepStakeDistr some" -> GetDRepStakeDistr(
        registered.toVector.sortBy(_.toString).take(2).toSet
      ),
      "GetCommitteeMembersState all" ->
        GetCommitteeMembersState(Set.empty, Set.empty, Set.empty),
      "GetCommitteeMembersState normal" ->
        GetCommitteeMembersState(Set.empty, Set.empty, Set(0, 1)),
      "GetCommitteeMembersState unrecognised" ->
        GetCommitteeMembersState(Set.empty, Set.empty, Set(2)),
      "GetCommitteeMembersState hot" ->
        GetCommitteeMembersState(Set.empty, Set(keyA), Set.empty),
      "GetFilteredVoteDelegatees all" -> GetFilteredVoteDelegatees(Set.empty),
      "GetFilteredVoteDelegatees some" -> GetFilteredVoteDelegatees(some),
      "GetAccountState" -> GetAccountState,
      "GetSPOStakeDistr all" -> GetSPOStakeDistr(Set.empty),
      "GetSPOStakeDistr some" -> GetSPOStakeDistr(firstPool),
      "GetProposals all" -> GetProposals(Set.empty),
      "GetProposals some" -> GetProposals(Set(actionId(1), actionId(3))),
      "GetRatifyState" -> GetRatifyState,
      "GetFuturePParams" -> GetFuturePParams,
      "GetPoolDistr2 all" -> GetPoolDistr2(None),
      "GetPoolDistr2 some" -> GetPoolDistr2(Some(firstPool)),
      "GetStakeDistribution2" -> GetStakeDistribution2,
      "GetMaxMajorProtocolVersion" -> GetMaxMajorProtocolVersion,
      "GetDRepDelegations all" -> GetDRepDelegations(Set.empty),
      "GetDRepDelegations registered" -> GetDRepDelegations(registered),
      "GetDRepDelegations abstain" ->
        GetDRepDelegations(Set(DRep.AlwaysAbstain, DRep.AlwaysNoConfidence))
    ) ++ pools.toVector
      .sortBy(_.hex)
      .map(p => s"QueryStakePoolDefaultVote ${p.hex}" -> QueryStakePoolDefaultVote(p))

  /** The answers to the battery on one state, `None` for one the node does not answer. */
  def answers(
      subject: (String, MirrorState, Point, Option[BigInt])
  ): Vector[(String, String, Option[Bytes])] =
    val (name, state, point, blockNo) = subject
    val anchor = point match
      case Point.Block(slot, hash) => Some(BlockRef(slot.value, hash, blockNo.getOrElse(BigInt(0))))
      case Point.Origin            => None
    val utxo = state.utxo.toMap
    battery(flatLedger(state, anchor)).map { (label, query) =>
      (
        name,
        label,
        QueryAnswers
          .answer(
            queryEnv,
            QueryView(point, blockNo, state),
            utxo,
            NtcQuery.IfCurrent(Era.Conway.index, query)
          )
          .map(unwrap)
      )
    }

  /** An `IfCurrent` answer is `[result]`: the result. */
  private def unwrap(answer: Bytes): Bytes = Bytes(answer.value.tail)

  // ---- the file

  private def sha256(b: Bytes): String =
    MessageDigest.getInstance("SHA-256").digest(b.toArray).map("%02x".format(_)).mkString

  /** The value of the line: the hex of a small answer, the digest of a large one. */
  def value(answer: Option[Bytes]): String = answer match
    case None                             => "unanswered"
    case Some(b) if b.size <= inlineLimit => b.hex
    case Some(b)                          => s"sha256:${sha256(b)}"

  def lines: Vector[String] =
    subjects.flatMap(answers).map { (state, label, answer) =>
      s"$state\t$label\t${value(answer)}"
    }

  /** Writes the fixture from today's answers: run once, before the encoders change. */
  def main(args: Array[String]): Unit =
    Files.createDirectories(file.getParent)
    Files.write(file, (lines.mkString("\n") + "\n").getBytes("UTF-8"))
    println(s"wrote ${lines.size} answers to $file")
