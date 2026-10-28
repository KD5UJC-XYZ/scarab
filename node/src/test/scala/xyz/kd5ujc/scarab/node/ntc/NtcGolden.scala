// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.node.read.ChainRead
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state as native
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  FlatView,
  FuturePParams,
  KeyRole,
  MirrorEnv,
  NewEpochState,
  ShelleyBasedEra,
  Sorted,
  VocabError,
  Word64
}
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
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
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

  /** The Conway new-epoch state of a mirror. */
  def conwayOf(m: MirrorState): NewEpochState[Conway] = m.ext.ledgerState.state match
    case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) => state.nes
    case other => sys.error(s"not a Conway state: ${other.era}")

  /** The mirror with its Conway new-epoch state changed by `f`. */
  private def editConway(m: MirrorState)(
      f: NewEpochState[Conway] => NewEpochState[Conway]
  ): MirrorState = m.ext.ledgerState.state match
    case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) =>
      val next =
        EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state.copy(nes = f(state.nes)))
      m.copy(ext = m.ext.copy(ledgerState = m.ext.ledgerState.withState(next)))
    case other => sys.error(s"not a Conway state: ${other.era}")

  /** The registered pools of a Conway mirror, their reward accounts on the node's network. */
  def pools(m: MirrorState): Map[Bytes, PoolState] =
    FlatView.pools(mirrorEnv.network, ConwayNewEpochState.pools.get(conwayOf(m)))

  /** What the battery draws its arguments from: the registered credentials, pools and DReps, and
    * the UTxO of a Conway state.
    */
  final case class Drawn(
      credentials: Set[Credential],
      pools: Set[Bytes],
      dreps: Set[Credential],
      utxo: Map[TxIn, TxOut]
  )

  def drawn(m: MirrorState): Drawn =
    val nes = conwayOf(m)
    Drawn(
      ConwayNewEpochState.accounts.get(nes).states.keysIterator.map(Convert.back).toSet,
      ConwayNewEpochState.pools.get(nes).keysIterator.map(_.bytes).toSet,
      ConwayNewEpochState.dreps.get(nes).keysIterator.map(Convert.back).toSet,
      nes.es.ls.utxo.utxo.utxo.toMap
    )

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

  private def update(field: Int, value: Int): ConwayParamUpdate =
    TestLedger.conwayUpdateOf(field -> CborOut.build(_.uint(value)))

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

  private def vocab[A](e: Either[VocabError, A]): A = e.fold(v => sys.error(s"$v"), identity)
  private def cred[R <: KeyRole](c: Credential): native.Credential[R] = vocab(
    Convert.credential[R](c)
  )
  private def compact(n: BigInt): CompactCoin = vocab(CompactCoin.fromBigInt(n))
  private def epochNo(n: BigInt): native.EpochNo = vocab(native.EpochNo.fromBigInt(n))

  /** The language index of PlutusV2 in the cost models (0 is PlutusV1, 2 PlutusV3). */
  private val PlutusV2 = 1

  /** A state that holds governance, DReps and a committee on top of `nes`: four accounts that
    * delegate to the first pool and to DReps of every kind, three DReps (in place of the state's),
    * the committee and its hot keys and resignations, two dormant epochs, the proposals and their
    * roots. `completed` gives it a completed pulser (with a ratified parameter change, so that the
    * future parameters are the ratified ones, potential until the point of no return at `slot`) and
    * explicit previous parameters; otherwise it has the pulser the genesis seeds: the ratification
    * of nothing over its enact state, with the parameters of the era translation (without the
    * PlutusV2 cost model, which the Alonzo genesis holds outside them) as its own and its previous
    * ones.
    *
    * The pools' delegators are the accounts that delegate to them. The `mark` snapshot's delegator
    * counts and the absent non-myopic state are those of the states the fixture was recorded on.
    */
  private def rich(
      nes: NewEpochState[Conway],
      completed: Boolean,
      slot: BigInt
  ): NewEpochState[Conway] =
    val epoch = nes.epoch.toBigInt
    val treasury = nes.es.chainAccount.treasury.value
    val gov = ConwayNewEpochState.gov.get(nes)
    val committee = Committee(Map(keyB -> (epoch + 3), scriptA -> (epoch - 1)), Rational(2, 3))
    val constitution = Constitution(anchorB, Some(bytes(0x31)))
    val roots = PrevIds(Some(actionId(20)), None, Some(actionId(21)), Some(actionId(22)))
    val params = gov.curPParams
    val next = params.copy(minFeeA = params.minFeeA + 1)
    val ratify = RatifyState(
      EnactState(
        Some(committee),
        constitution,
        next,
        params,
        treasury + 777,
        Map(keyA -> BigInt(5), scriptB -> BigInt(6)),
        roots
      ),
      Vector(states(0)),
      Set(actionId(30), actionId(31)),
      delayed = true
    )
    val poolIds = ConwayNewEpochState.pools.get(nes).keySet
    val snapshot = PulsingSnapshot(
      states.take(4),
      Map(
        DRep.Registered(scriptB) -> BigInt(3),
        DRep.Registered(keyA) -> BigInt(5),
        DRep.AlwaysNoConfidence -> BigInt(7),
        DRep.AlwaysAbstain -> BigInt(9),
        DRep.Registered(keyB) -> BigInt(11)
      ),
      Map(keyA -> DRepState(500, epoch + 4, Some(anchorA), Set(keyA, scriptA))),
      poolIds.iterator.map(_.bytes -> BigInt(123456)).toMap
    )
    val pool = poolIds.toVector.sortBy(_.bytes.hex).headOption
    def account(balance: BigInt, pool: Option[native.KeyHash[native.StakePool]], drep: DRep) =
      native.ConwayAccountState(
        compact(balance),
        compact(2000000),
        pool,
        Some(vocab(Convert.drep(drep)))
      )
    val accounts = ConwayNewEpochState.accounts.get(nes).states ++ Vector(
      cred[native.Staking](keyA) -> account(10, pool, DRep.Registered(keyA)),
      cred[native.Staking](scriptA) -> account(0, None, DRep.AlwaysAbstain),
      cred[native.Staking](keyB) -> account(5, pool, DRep.AlwaysNoConfidence),
      cred[native.Staking](scriptB) -> account(77, None, DRep.Registered(scriptB))
    )
    def drep(deposit: BigInt, expiry: BigInt, anchor: Option[Anchor], delegators: Set[Credential]) =
      native.DRepState(
        epochNo(expiry),
        anchor,
        compact(deposit),
        Sorted.set(delegators.toList.map(cred[native.Staking]))
      )
    val dreps = Sorted.map(
      List(
        cred[native.DRepRole](keyA) ->
          drep(500000000, epoch + 10, Some(anchorA), Set(keyA, scriptA)),
        cred[native.DRepRole](scriptB) -> drep(500000000, 0, None, Set(scriptB)),
        cred[native.DRepRole](keyB) -> drep(500000000, epoch, Some(anchorB), Set.empty)
      )
    )
    val committeeState = native.CommitteeState(
      Sorted.map(
        List(
          cred[native.ColdCommittee](keyB) -> native.CommitteeAuthorization
            .CommitteeHotCredential(cred[native.HotCommittee](keyA)),
          cred[native.ColdCommittee](scriptA) ->
            native.CommitteeAuthorization.CommitteeMemberResigned(Some(anchorA)),
          cred[native.ColdCommittee](keyA) ->
            native.CommitteeAuthorization.CommitteeMemberResigned(None)
        )
      )
    )
    // the pools' delegators: the accounts that delegate to each
    val delegators = accounts.toVector
      .flatMap((c, a) => a.pool.map(_ -> c))
      .groupMap(_._1)(_._2)
    val pools = ConwayNewEpochState.pools
      .get(nes)
      .transform((id, p) => p.copy(delegators = Sorted.set(delegators.getOrElse(id, Vector.empty))))
    // the `mark` snapshot counts each pool's delegators as the accounts do, or its stake-bearing
    // ones where they are more: the fixture was recorded on states whose `mark` counts were taken
    // from the accounts of the state (`SnapshotBasis.Accounts`)
    val mark = nes.es.snapshots.mark
    val staked = mark.activeStake.entries.valuesIterator.toVector
      .groupMapReduce(_.delegation)(_ => 1)(_ + _)
    val counted = native.SnapShot.pools.modify(
      _.transform((id, p) =>
        val count = staked.getOrElse(id, 0).max(delegators.get(id).fold(0)(_.size))
        p.copy(numDelegators = vocab(Word64.fromBigInt(VocabError.Kind.BlockCount, BigInt(count))))
      )
    )(mark)
    val translated = params.copy(costModels = params.costModels - PlutusV2)
    val pointOfNoReturn =
      mirrorEnv.geometry.firstSlot(epoch + 1) - 2 * mirrorEnv.geometry.stabilityWindow.toBigInt
    val nextGov = native.ConwayGovState(
      Proposals(states, roots),
      Some(committee),
      constitution,
      params,
      if completed then params.copy(minFeeB = params.minFeeB + 1) else translated,
      if !completed then FuturePParams.NoPParamsUpdate
      else if slot < pointOfNoReturn then FuturePParams.PotentialPParamsUpdate(Some(next))
      else FuturePParams.DefinitePParamsUpdate(next),
      if completed then native.DRComplete(snapshot, ratify)
      else
        native.DRComplete(
          PulsingSnapshot.empty,
          RatifyState(
            EnactState(
              Some(committee),
              constitution,
              translated,
              translated,
              treasury,
              Map.empty,
              roots
            ),
            Vector.empty,
            Set.empty,
            delayed = false
          )
        )
    )
    val ls = nes.es.ls
    val edited = ConwayLedgerState.gov
      .replace(nextGov)
      .andThen(ConwayLedgerState.accounts.modify(_.copy(states = accounts)))
      .andThen(ConwayLedgerState.pools.replace(pools))
      .andThen(
        ConwayLedgerState.vState.modify(
          _.copy(dreps = dreps, committeeState = committeeState, numDormantEpochs = epochNo(2))
        )
      )(ls)
    // and no non-myopic state: the recording kept none for them
    nes.copy(es =
      nes.es.copy(
        ls = edited,
        snapshots = nes.es.snapshots.copy(mark = counted),
        nonMyopic = native.NonMyopic.empty
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
    // the last state with governance, DReps and a committee in it
    def enriched(completed: Boolean): MirrorState =
      editConway(last)(rich(_, completed, lastBlock.header.slot))
    Vector(
      ("rich", enriched(completed = true)),
      ("rich-genesis", enriched(completed = false))
    ).foldLeft(real) { case (acc, (name, s)) =>
      acc :+ (name, s, point(lastBlock), Some(lastBlock.header.blockNo))
    }

  // ---- the queries

  /** Every ledger query this node answers, with arguments drawn from the state. */
  def battery(s: Drawn): Vector[(String, LedgerQuery)] =
    import LedgerQuery.*
    val credentials = s.credentials
    val pools = s.pools
    val firstPool = pools.toVector.sortBy(_.hex).take(1).toSet
    val dreps = s.dreps
    val registered: Set[DRep] = dreps.map(DRep.Registered(_))
    val entries = s.utxo.toVector.sortBy(_._1.id.hex)
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
    val utxo = state.utxo.toMap
    battery(drawn(state)).map { (label, query) =>
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
