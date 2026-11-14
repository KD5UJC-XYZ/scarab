// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyVector
import cats.syntax.all.*
import java.time.Instant
import xyz.kd5ujc.scarab.codec.{Bytes, Node, Rational}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CommitteeAuthorization,
  CommitteeState,
  ColdCommittee,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayGovState,
  ConwayLedgerState,
  DRepRole,
  DRepState,
  Empty,
  EpochNo,
  FuturePParams,
  HotCommittee,
  KeyHash,
  LedgerState,
  NonZero,
  PoolBlsKey,
  PositiveUnitInterval,
  Sorted,
  StakePool,
  StakePoolState,
  Staking,
  UtxoTables,
  VRFVerKeyHash,
  VState,
  VocabError,
  Word64
}
import xyz.kd5ujc.scarab.ledger.state.Credential as StateCredential
import xyz.kd5ujc.scarab.node.mempool.MempoolLedger
import xyz.kd5ujc.scarab.plutus.PlutusBackend
import xyz.kd5ujc.scarab.protocol.Era

/** What a fixture's transaction is judged with: the ledger at the fixture's point, the context of
  * the transaction, and the network's constants.
  */
final case class LedgerRulesContext(ledger: MempoolLedger, env: TxEnv, globals: Globals)

/** The ledger state a fixture declares, built as the reference harness of the corpus builds its
  * `NewEpochState` (Amaru `crates/amaru/tests/conformance/validation-rules`,
  * `Data/Fixture/InitialState.hs` `buildNewEpochState`, and `Command/ValidatePhaseOne/Run.hs`
  * `buildGlobals`, at 40ba9950): every value the fixture leaves out is the reference's `def`.
  */
object LedgerRulesState:
  /** `defaultActiveSlotCoeff` of the harness, from which it infers the security parameter. */
  private val ActiveSlotsCoeff = Rational(1, 20)

  /** `maxLovelaceSupply` of the harness's `Globals`. */
  private val MaxSupply = BigInt("45000000000000000")

  private val PsPerSecond = BigInt("1000000000000")

  /** `maxBound :: EpochNo`, the expiry of a synthetic proposal when no proposal is seeded. */
  private val MaxEpoch = BigInt("18446744073709551615")

  /** `def :: TxId`, `def :: KeyHash`: hashes of zeros. */
  private val ZeroTxId = Bytes(Vector.fill(32)(0.toByte))
  private val ZeroKeyHash = Bytes(Vector.fill(28)(0.toByte))

  /** `def :: AccountAddress`: a key-hash account of zeros on the test network. */
  private val DefaultAccount = RewardAccount(0, Credential.Key(ZeroKeyHash))

  /** The encoding of a parameter update setting only `maxBlockBodySize` to 1 (`ppuMaxBBSizeL .~
    * SJust 1`), the security-group stand-in of `toGovAction`.
    */
  private val SecurityGroupUpdate = Bytes(Vector(0xa1.toByte, 0x02.toByte, 0x01.toByte))

  private given ByteStrings = ByteStrings.of(DecoderVersion.of(Era.Conway))

  def build(
      f: LedgerRulesFixture,
      backend: PlutusBackend
  ): Either[FixtureError, LedgerRulesContext] =
    val era = f.eraHistory.era
    for
      _ <- Either.cond(
        f.slot >= era.start.slot,
        (),
        FixtureError.Unsupported(s"slot ${f.slot} before the era start ${era.start.slot}")
      )
      epoch = era.start.epoch + (f.slot - era.start.slot) / era.epochSize
      globals <- globals(f)
      ls <- ledger(f.params, f.state, epoch)
    yield
      val firstSlotNextEpoch = era.start.slot + (epoch + 1 - era.start.epoch) * era.epochSize
      val env = TxEnv(
        slot = f.slot,
        epoch = epoch,
        network = f.network,
        firstSlotNextEpoch = firstSlotNextEpoch,
        stabilityWindow = f.eraHistory.stabilityWindow,
        txIndex = f.txIndex,
        clock = Some(clock(era, epoch)),
        evaluator = backend.evaluator,
        decoder = backend.decoder
      )
      LedgerRulesContext(MempoolLedger(ls, Coin(f.state.treasury), epoch), env, globals)

  /** `buildEpochInfo`: the era from its start to its end, or, open-ended in the fixture, to the
    * start of the epoch after the point's (`mkUpperBound`), so a slot past that has no time.
    */
  private def clock(era: FixtureEra, epoch: BigInt): PosixClock =
    val params = Horizon.Params(era.epochSize, era.slotLengthMillis, BigInt(0), BigInt(0))
    def edge(b: FixtureBound) = Horizon.Edge(b.time * PsPerSecond, b.slot, b.epoch)
    val start = edge(era.start)
    val end = era.end.fold(params.boundAt(start, epoch + 1))(edge)
    PosixClock.Forecast(
      BigInt(0),
      Horizon(NonEmptyVector.one(Horizon.Era(start, Some(end), params)))
    )

  /** `buildGlobals`: the security parameter is the one whose stability window (`3k/f`) the fixture
    * states, with `f` = 1/20.
    */
  private def globals(f: LedgerRulesFixture): Either[FixtureError, Globals] =
    val window = f.eraHistory.stabilityWindow
    val era = f.eraHistory.era
    for
      k <- Either.cond(
        window > 0 && window % 60 == 0 && (window / 60).isValidLong,
        window / 60,
        FixtureError.Unsupported(s"no security parameter has the stability window $window")
      )
      securityParam <- NonZero
        .from(Word64.fromUnsigned(k.toLong))
        .leftMap(FixtureError.Vocab("k", _))
      coeff <- PositiveUnitInterval
        .fromRational(ActiveSlotsCoeff)
        .leftMap(_ => FixtureError.Unsupported(s"active slot coefficient $ActiveSlotsCoeff"))
      _ <- Either.cond(
        era.epochSize.isValidLong && era.slotLengthMillis.isValidLong,
        (),
        FixtureError.Unsupported(
          s"an epoch of ${era.epochSize} slots of ${era.slotLengthMillis} ms"
        )
      )
    yield Globals(
      LedgerGlobals(
        maxSupply = MaxSupply,
        epochLength = era.epochSize,
        activeSlotsCoeff = ActiveSlotsCoeff,
        network = f.network,
        stabilityWindow = window
      ),
      EpochGeometry(
        Instant.EPOCH,
        era.slotLengthMillis.toLong,
        era.epochSize.toLong,
        securityParam,
        coeff
      )
    )

  private def cbor(field: String, bytes: Bytes): Either[FixtureError, Node] =
    CborRead.decode(bytes).leftMap(FixtureError.Cbor(field, _))

  private def decoded[A](field: String, bytes: Bytes)(
      read: Node => Either[LedgerDecodeError, A]
  ): Either[FixtureError, A] =
    cbor(field, bytes).flatMap(read(_).leftMap(FixtureError.Cbor(field, _)))

  private def credential(field: String, bytes: Bytes): Either[FixtureError, Credential] =
    decoded(field, bytes)(Credential.decode(_, DecodeField.CertificateCredential))

  private def vocab[A](field: String)(r: Either[VocabError, A]): Either[FixtureError, A] =
    r.leftMap(FixtureError.Vocab(field, _))

  private def compact(field: String, n: BigInt): Either[FixtureError, CompactCoin] =
    vocab(field)(CompactCoin.fromBigInt(n))

  private def epochNo(field: String, n: BigInt): Either[FixtureError, EpochNo] =
    vocab(field)(EpochNo.fromBigInt(n))

  /** A UTxO entry, read as a Conway transaction body reads its inputs and outputs (`parseCborHex`
    * at `eraProtVerLow @ConwayEra`).
    */
  private def entry(input: Bytes, output: Bytes): Either[FixtureError, (TxIn, TxOut)] =
    (
      decoded("utxo.input", input)(TxIn.decode),
      decoded("utxo.output", output)(
        TxOut.decode(
          _,
          Address.Mode.of(Era.Conway),
          TxOut.Forms.of(Era.Conway),
          ScriptKind.newest(Era.Conway)
        )
      )
    ).tupled

  private def ledger(
      pp: ProtocolParams,
      s: FixtureState,
      epoch: BigInt
  ): Either[FixtureError, LedgerState[Conway]] =
    for
      utxo <- s.utxo.traverse(entry)
      accounts <- s.accounts.traverse(account)
      dreps <- s.dreps.traverse(drep(_, accounts))
      pools <- s.pools.traverse(pool(_, pp))
      members <- s.committee.traverse(member)
      dormant <- epochNo("consecutive_dormant_epochs", s.dormantEpochs)
      states <- s.proposals.traverse(proposal(_, pp, epoch))
      proposals <- proposalStore(s.roots, states, orphans(members))
    yield
      val elected = members.collect { case Member(cold, _, _, Some(until)) => cold -> until }.toMap
      val committeeState = CommitteeState(
        Sorted.map(members.collect { case Member(_, key, Some(auth), _) => key -> auth })
      )
      val deposited = accounts.map(_._3).sum + s.dreps.map(_.deposit).sum +
        BigInt(s.pools.size) * pp.poolDeposit + states.map(_.proposal.deposit).sum
      val base = Empty.ledgerState[Conway](pp)
      val gov = ConwayGovState(
        proposals,
        Option.when(elected.nonEmpty)(Committee(elected, Rational.zero)),
        Constitution(ConwayGenesis.noAnchor, s.guardrail),
        pp,
        pp,
        FuturePParams.NoPParamsUpdate,
        ConwayLedgerState.gov.get(base).drepPulsing
      )
      val withCert = ConwayLedgerState.cert.modify(c =>
        c.copy(
          vState = VState(Sorted.map(dreps), committeeState, dormant),
          pState = c.pState.copy(stakePools = Sorted.map(pools)),
          dState =
            c.dState.copy(accounts = ConwayAccounts(Sorted.map(accounts.map(a => a._1 -> a._2))))
        )
      )(base)
      withCert.copy[Conway](utxo =
        withCert.utxo.copy[Conway](
          utxo = UtxoTables(Utxo.fromMap(utxo.toMap)),
          deposited = Coin(deposited),
          gov = gov
        )
      )

  /** An account and its deposit as a number, for the total of the deposits. */
  private def account(
      a: FixtureAccount
  ): Either[FixtureError, (StateCredential[Staking], ConwayAccountState, BigInt)] =
    for
      cred <- credential("accounts.credential", a.credential)
      key <- vocab("accounts.credential")(Convert.credential[Staking](cred))
      deposit <- compact("accounts.deposit", a.deposit)
      balance <- compact("accounts.rewards", a.rewards)
      pool <- a.pool.traverse(p => vocab("accounts.pool")(KeyHash.fromBytes[StakePool](p)))
      drep <- a.drep.traverse(d =>
        decoded("accounts.drep", d)(DRep.decode).flatMap(r =>
          vocab("accounts.drep")(Convert.drep(r))
        )
      )
    yield (key, ConwayAccountState(balance, deposit, pool, drep), a.deposit)

  /** A DRep with no anchor and the accounts that delegate to it as its delegators. */
  private def drep(
      d: FixtureDRep,
      accounts: Vector[(StateCredential[Staking], ConwayAccountState, BigInt)]
  ): Either[FixtureError, (StateCredential[DRepRole], DRepState)] =
    for
      cred <- credential("dreps.credential", d.credential)
      key <- vocab("dreps.credential")(Convert.credential[DRepRole](cred))
      deposit <- compact("dreps.deposit", d.deposit)
      expiry <- epochNo("dreps.valid_until", d.validUntil)
    yield
      val delegators = accounts.collect {
        case (staking, a, _) if a.drep.exists(delegatesTo(key)) => staking
      }
      key -> DRepState(expiry, None, deposit, Sorted.set(delegators))

  private def delegatesTo(key: StateCredential[DRepRole])(d: state.DRep): Boolean =
    (d, key) match
      case (state.DRep.DRepKeyHash(h), StateCredential.KeyHashObj(k))       => h.bytes == k.bytes
      case (state.DRep.DRepScriptHash(h), StateCredential.ScriptHashObj(k)) => h.bytes == k.bytes
      case _                                                                => false

  /** `defaultStakePoolState`: the reference's `def` with the deposit of the parameters. */
  private def pool(
      id: Bytes,
      pp: ProtocolParams
  ): Either[FixtureError, (KeyHash[StakePool], StakePoolState)] =
    for
      key <- vocab("pools")(KeyHash.fromBytes[StakePool](id))
      vrf <- vocab("pools")(VRFVerKeyHash.fromBytes(ZeroTxId))
      account <- vocab("pools")(Convert.credential[Staking](DefaultAccount.credential))
      deposit <- compact("stake_pool_deposit", pp.poolDeposit)
    yield key -> StakePoolState(
      vrf,
      PoolBlsKey.Node11_1,
      Coin.zero,
      Coin.zero,
      Rational.zero,
      account,
      Sorted.emptySet,
      Bytes.empty,
      None,
      deposit,
      Sorted.emptySet
    )

  /** A committee member: its cold credential, as the committee and as the committee state key it,
    * its authorisation and the last epoch of its term.
    */
  private final case class Member(
      cold: Credential,
      key: StateCredential[ColdCommittee],
      auth: Option[CommitteeAuthorization],
      until: Option[BigInt]
  )

  private def member(m: FixtureMember): Either[FixtureError, Member] =
    for
      cold <- credential("committee.cold_credential", m.cold)
      key <- vocab("committee.cold_credential")(Convert.credential[ColdCommittee](cold))
      auth <- m.status.traverse {
        case FixtureAuthorization.Resigned =>
          Right(CommitteeAuthorization.CommitteeMemberResigned(None))
        case FixtureAuthorization.Hot(bytes) =>
          credential("committee.status", bytes)
            .flatMap(c => vocab("committee.status")(Convert.credential[HotCommittee](c)))
            .map(CommitteeAuthorization.CommitteeHotCredential(_))
      }
    yield Member(cold, key, auth, m.validUntil)

  /** The cold credentials the reference harness elects through a synthetic proposal: authorised but
    * holding no term, or holding neither (`orphanAuthorizations`).
    */
  private def orphans(members: Vector[Member]): Vector[Credential] =
    val elected = members.collect { case Member(cold, _, _, Some(_)) => cold }.toSet
    members.collect {
      case Member(cold, _, Some(_), _) if !elected(cold) => cold
      case Member(cold, _, None, None)                   => cold
    }.distinct

  /** `toGovActionState`: a seeded proposal with the stand-in action of its lineage, the deposit of
    * the parameters, a default return account and anchor, proposed in the point's epoch.
    */
  private def proposal(
      p: FixtureProposal,
      pp: ProtocolParams,
      epoch: BigInt
  ): Either[FixtureError, GovActionState] =
    action(p.lineage).map(a =>
      GovActionState(
        p.id,
        Map.empty,
        Map.empty,
        Map.empty,
        Proposal(pp.govActionDeposit, DefaultAccount, a, ConwayGenesis.noAnchor),
        epoch,
        p.validUntil
      )
    )

  /** `toGovAction` of a slim proposal: the weakest action of its lineage, with no parent. */
  private def action(lineage: FixtureLineage): Either[FixtureError, GovAction] = lineage match
    case FixtureLineage.ParameterChange(false) =>
      Right(GovAction.ParameterChange(None, ConwayParamUpdate.empty, None))
    case FixtureLineage.ParameterChange(true) =>
      decoded("security-group update", SecurityGroupUpdate)(ConwayParamUpdate.decode)
        .map(GovAction.ParameterChange(None, _, None))
    case FixtureLineage.HardFork(major, minor) => Right(GovAction.HardFork(None, major, minor))
    case FixtureLineage.Committee              => Right(GovAction.NoConfidence(None))
    case FixtureLineage.Constitution           => Right(GovAction.NewConstitution(None, None))
    case FixtureLineage.Information            => Right(GovAction.Info)
    case FixtureLineage.TreasuryWithdrawals =>
      Right(GovAction.TreasuryWithdrawals(Vector(DefaultAccount -> BigInt(1)), None))

  /** `buildProposals`: the seeded proposals over the roots, then, when some cold credentials are
    * not elected, an `UpdateCommittee` electing them (`buildOrphanCommitteeProposal`) whose id is
    * the first free index of the zero transaction id, with the template of the first proposal.
    */
  private def proposalStore(
      roots: PrevIds,
      states: Vector[GovActionState],
      orphans: Vector[Credential]
  ): Either[FixtureError, Proposals] =
    val used = states.map(_.id).toSet
    val synthetic = Option
      .when(orphans.nonEmpty) {
        val id = (0 to used.size).map(i => GovActionId(ZeroTxId, BigInt(i))).find(!used(_))
        val template = states.headOption
          .map(_.proposal)
          .getOrElse(
            Proposal(BigInt(0), DefaultAccount, GovAction.Info, ConwayGenesis.noAnchor)
          )
        val expires = states.headOption.fold(MaxEpoch)(_.expiresAfter)
        id.map(i =>
          GovActionState(
            i,
            Map.empty,
            Map.empty,
            Map.empty,
            template.copy(action =
              GovAction.UpdateCommittee(
                roots.committee,
                Set.empty,
                orphans.map(_ -> expires).toMap,
                Rational(1)
              )
            ),
            states.headOption.fold(BigInt(0))(_.proposedIn),
            expires
          )
        )
      }
      .flatten
    Proposals.rebuild(roots, states ++ synthetic).leftMap(FixtureError.Unattached(_))
