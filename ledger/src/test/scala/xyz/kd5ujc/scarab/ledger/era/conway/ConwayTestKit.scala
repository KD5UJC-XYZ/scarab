// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.data.{NonEmptyChain, NonEmptyVector}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Anchor,
  Block,
  ByteStrings,
  Certificate,
  Credential,
  DRep,
  GovAction,
  GovActionId,
  Horizon,
  LedgerFailure,
  PlutusStats,
  PoolParams,
  PredicateFailure,
  Proposal,
  ProtocolParams,
  RewardAccount,
  Tx,
  TxEnv,
  TxIn,
  TxOut,
  Vote,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.TestLedger.Key
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraUTxO,
  Network,
  PParams,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovPredFailure,
  ConwayUtxo,
  ConwayUtxowPredFailure,
  LedgerEnv,
  UtxoContext
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Judgment,
  Ledger as FlatLedger,
  LedgerEnv as FlatLedgerEnv,
  NativeRules,
  RuleT
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EpochNo, LedgerState, NewEpochState, TxIx}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Builders for Conway transactions whose bytes are true: a body and its witnesses are written in
  * the forms of Conway's CDDL (the body's keys, the tags of its certificates, voters and actions),
  * sets under tag 258, the keys of the body's maps ascending and a treasury withdrawal's in the
  * order given, then read by the node's flat decoder ([[Tx.fromBlock]]) and by Conway's
  * ([[ConwayTx.decode]]), and the flat reading must give back every field the test asked for. The
  * bytes are a form both decoders read, not checked against the reference's own encoder.
  *
  * [[ledger]] judges such a transaction with Conway's LEDGER on the era types
  * (`ConwayLedger.conwayLedgerTransition`, through [[EraLedgerRun.ledger]]) and with the flat
  * LEDGER on the same bytes from the same state, and requires the two to agree.
  */
object ConwayTestKit extends munit.Assertions:
  /** Definite-length CBOR writers. */
  object Cb:
    private def head(major: Int, n: BigInt): Bytes =
      val m = major << 5
      def be(width: Int): Vector[Byte] =
        (width - 1 to 0 by -1).toVector.map(i => (n >> (8 * i)).toByte)
      if n < 0 then fail(s"$n is negative: no unsigned CBOR head holds it")
      else if n < 24 then Bytes(Vector((m | n.toInt).toByte))
      else if n < 256 then Bytes((m | 24).toByte +: be(1))
      else if n < 65536 then Bytes((m | 25).toByte +: be(2))
      else if n < (BigInt(1) << 32) then Bytes((m | 26).toByte +: be(4))
      else if n < (BigInt(1) << 64) then Bytes((m | 27).toByte +: be(8))
      else fail(s"$n does not fit an unsigned CBOR head")
    private def join(items: Seq[Bytes]): Bytes = Bytes.wrap(Array.concat(items.map(_.toArray)*))
    def uint(n: BigInt): Bytes = head(0, n)
    def bytes(b: Bytes): Bytes = head(2, BigInt(b.size)) ++ b
    def text(s: String): Bytes =
      val utf8 = Bytes.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      head(3, BigInt(utf8.size)) ++ utf8
    def arr(items: Bytes*): Bytes = join(head(4, BigInt(items.size)) +: items)
    def map(entries: (Bytes, Bytes)*): Bytes =
      join(head(5, BigInt(entries.size)) +: entries.flatMap((k, v) => Seq(k, v)))
    def tag(number: Int, item: Bytes): Bytes = head(6, BigInt(number)) ++ item

    /** A set or an ordered set, tagged 258 as the CDDL allows from Conway. */
    def set(items: Seq[Bytes]): Bytes = tag(258, arr(items*))
    val `null`: Bytes = Bytes(Vector(0xf6.toByte))
    val `true`: Bytes = Bytes(Vector(0xf5.toByte))
    def optional[A](a: Option[A])(write: A => Bytes): Bytes = a.fold(`null`)(write)

  import Cb.*

  // ---- the parts --------------------------------------------------------------------------------

  def txIn(in: TxIn): Bytes = arr(bytes(in.id), uint(in.index))

  /** `[0, addr_keyhash] / [1, script_hash]`. */
  def credential(c: Credential): Bytes = c match
    case Credential.Key(h)    => arr(uint(0), bytes(h))
    case Credential.Script(h) => arr(uint(1), bytes(h))

  /** The account address: header `0b111x000y`, the credential's kind in `x` and the network in `y`.
    */
  def rewardAccount(ra: RewardAccount): Bytes =
    val kind = ra.credential match
      case Credential.Key(_)    => 0xe0
      case Credential.Script(_) => 0xf0
    Bytes((kind | ra.network).toByte +: ra.credential.hash.value)

  def drep(d: DRep): Bytes = d match
    case DRep.Registered(Credential.Key(h))    => arr(uint(0), bytes(h))
    case DRep.Registered(Credential.Script(h)) => arr(uint(1), bytes(h))
    case DRep.AlwaysAbstain                    => arr(uint(2))
    case DRep.AlwaysNoConfidence               => arr(uint(3))

  def anchor(a: Anchor): Bytes = arr(text(a.url), bytes(a.hash))

  /** `unit_interval = #6.30([uint, uint])`. */
  def unitInterval(r: Rational): Bytes = tag(30, arr(uint(r.numerator), uint(r.denominator)))

  /** `pool_params`, flattened into the registration; `relays` and `metadata` are the encodings the
    * flat parameters keep, no relays the empty value (`Bytes.empty`) for the empty list.
    */
  def poolParams(p: PoolParams): Seq[Bytes] =
    Seq(
      bytes(p.operator),
      bytes(p.vrfKeyHash),
      uint(p.pledge),
      uint(p.cost),
      unitInterval(p.margin),
      bytes(rewardAccount(p.rewardAccount)),
      set(p.owners.toVector.sorted.map(bytes)),
      if p.relays.isEmpty then arr() else p.relays,
      p.metadata.getOrElse(`null`)
    )

  /** `certificate` of Conway's CDDL. */
  def certificate(c: Certificate): Bytes = c match
    case Certificate.Register(cred, None)      => arr(uint(0), credential(cred))
    case Certificate.Register(cred, Some(d))   => arr(uint(7), credential(cred), uint(d))
    case Certificate.Unregister(cred, None)    => arr(uint(1), credential(cred))
    case Certificate.Unregister(cred, Some(d)) => arr(uint(8), credential(cred), uint(d))
    case Certificate.Delegate(cred, pool, vote, deposit) =>
      val (tag, fields) = (pool, vote, deposit) match
        case (Some(p), None, None)       => (2, Seq(bytes(p)))
        case (None, Some(d), None)       => (9, Seq(drep(d)))
        case (Some(p), Some(d), None)    => (10, Seq(bytes(p), drep(d)))
        case (Some(p), None, Some(c))    => (11, Seq(bytes(p), uint(c)))
        case (None, Some(d), Some(c))    => (12, Seq(drep(d), uint(c)))
        case (Some(p), Some(d), Some(c)) => (13, Seq(bytes(p), drep(d), uint(c)))
        case (None, None, _)             => fail(s"a delegation to nothing: $c")
      arr((uint(tag) +: credential(cred) +: fields)*)
    case Certificate.RegisterPool(p)         => arr((uint(3) +: poolParams(p))*)
    case Certificate.RetirePool(pool, epoch) => arr(uint(4), bytes(pool), uint(epoch))
    case Certificate.CommitteeAuthorize(cold, hot) =>
      arr(uint(14), credential(cold), credential(hot))
    case Certificate.CommitteeResign(cold, a) =>
      arr(uint(15), credential(cold), optional(a)(anchor))
    case Certificate.RegisterDRep(cred, deposit, a) =>
      arr(uint(16), credential(cred), uint(deposit), optional(a)(anchor))
    case Certificate.UnregisterDRep(cred, refund) => arr(uint(17), credential(cred), uint(refund))
    case Certificate.UpdateDRep(cred, a) => arr(uint(18), credential(cred), optional(a)(anchor))
    case Certificate.GenesisDelegate(_, _, _) | Certificate.MoveRewards(_, _) =>
      fail(s"no Conway certificate: $c")

  def govActionId(id: GovActionId): Bytes = arr(bytes(id.txId), uint(id.index))

  /** `gov_action` of Conway's CDDL; a parameter update is written from the original encoding of
    * each of its values.
    */
  def govAction(a: GovAction): Bytes = a match
    case GovAction.ParameterChange(previous, update, policy) =>
      arr(
        uint(0),
        optional(previous)(govActionId),
        map(update.encodings.map((k, v) => uint(k) -> v)*),
        optional(policy)(bytes)
      )
    case GovAction.HardFork(previous, major, minor) =>
      arr(uint(1), optional(previous)(govActionId), arr(uint(major), uint(minor)))
    case GovAction.TreasuryWithdrawals(withdrawals, policy) =>
      arr(
        uint(2),
        map(withdrawals.map((ra, c) => bytes(rewardAccount(ra)) -> uint(c))*),
        optional(policy)(bytes)
      )
    case GovAction.NoConfidence(previous) => arr(uint(3), optional(previous)(govActionId))
    case GovAction.UpdateCommittee(previous, removed, added, quorum) =>
      arr(
        uint(4),
        optional(previous)(govActionId),
        set(removed.toVector.sorted.map(credential)),
        map(added.toVector.sortBy(_._1).map((c, e) => credential(c) -> uint(e))*),
        unitInterval(quorum)
      )
    case GovAction.NewConstitution(previous, script, a) =>
      arr(uint(5), optional(previous)(govActionId), arr(anchor(a), optional(script)(bytes)))
    case GovAction.Info => arr(uint(6))

  def proposal(p: Proposal): Bytes =
    arr(
      uint(p.deposit),
      bytes(rewardAccount(p.returnAccount)),
      govAction(p.action),
      anchor(p.anchor)
    )

  /** `voter`: a committee member's hot credential, a DRep's credential, a pool's key hash. */
  def voter(v: Voter): Bytes = v match
    case Voter.CommitteeHot(Credential.Key(h))    => arr(uint(0), bytes(h))
    case Voter.CommitteeHot(Credential.Script(h)) => arr(uint(1), bytes(h))
    case Voter.DRepVoter(Credential.Key(h))       => arr(uint(2), bytes(h))
    case Voter.DRepVoter(Credential.Script(h))    => arr(uint(3), bytes(h))
    case Voter.Pool(h)                            => arr(uint(4), bytes(h))

  def votingProcedure(p: VotingProcedure): Bytes =
    val vote = p.vote match
      case Vote.No      => 0
      case Vote.Yes     => 1
      case Vote.Abstain => 2
    arr(uint(vote), optional(p.anchor)(anchor))

  /** An output `[address, coin]` with its true bytes, read as a stored output is. */
  def out(address: Address, coin: BigInt): TxOut =
    given ByteStrings = ByteStrings.Chunked
    TxOut
      .fromBytes(arr(bytes(address.raw), uint(coin)))
      .fold(e => fail(s"output to $address of $coin: ${e.message}"), identity)

  /** The size-based fee of the largest transaction the parameters admit: it covers any
    * transaction's size, though not a fee for execution units or reference scripts.
    */
  def maxFee(pp: ProtocolParams): BigInt = pp.minFeeA * pp.maxTxSize + pp.minFeeB

  // ---- the transaction --------------------------------------------------------------------------

  /** The fields of a Conway body that the kit writes. A voter that votes in several entries of
    * `votes` casts all of their votes (a body's votes are a map by voter).
    */
  final case class Body(
      inputs: Vector[TxIn],
      outputs: Vector[TxOut],
      fee: BigInt,
      certificates: Vector[Certificate] = Vector.empty,
      withdrawals: Map[RewardAccount, BigInt] = Map.empty,
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])] = Vector.empty,
      proposals: Vector[Proposal] = Vector.empty,
      donation: BigInt = 0,
      currentTreasury: Option[BigInt] = None
  ):
    /** The votes by voter as the body's map holds them: the voters ascending, and each voter's
      * actions ascending.
      */
    def ballots: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])] =
      votes
        .map(_._1)
        .distinct
        .sorted
        .map(v =>
          v -> votes
            .collect { case (`v`, bs) => bs }
            .flatten
            .sortBy(_._1)
            .map((id, vote) => id -> VotingProcedure(vote, None))
        )

    /** `transaction_body`, its keys in ascending order and an empty optional field left out. */
    def encode: Bytes =
      val fields = Vector(
        Some(0 -> set(inputs.sortBy(i => (i.id, i.index)).map(txIn))),
        Some(1 -> arr(outputs.map(_.raw)*)),
        Some(2 -> uint(fee)),
        Option.when(certificates.nonEmpty)(4 -> set(certificates.map(certificate))),
        Option.when(withdrawals.nonEmpty)(
          5 -> map(
            withdrawals.toVector.sortBy(_._1).map((ra, c) => bytes(rewardAccount(ra)) -> uint(c))*
          )
        ),
        Option.when(votes.nonEmpty)(
          19 -> map(
            ballots.map((v, bs) =>
              voter(v) -> map(bs.map((id, p) => govActionId(id) -> votingProcedure(p))*)
            )*
          )
        ),
        Option.when(proposals.nonEmpty)(20 -> set(proposals.map(proposal))),
        currentTreasury.map(t => 21 -> uint(t)),
        Option.when(donation > 0)(22 -> uint(donation))
      ).flatten
      map(fields.map((k, v) => uint(k) -> v)*)

  /** A transaction as both decoders read it from its parts. */
  final case class Built(parts: Block.Transaction, flat: Tx, typed: ConwayTx):
    def id: Bytes = flat.body.id

  /** `body` signed by `signers` (each once), valid and without auxiliary data, read back by the
    * flat decoder and by Conway's; the flat reading gives every field of `body`. With no signers
    * the witness set is empty, as the CDDL holds no empty list of vkey witnesses, and LEDGER judges
    * the missing witnesses.
    */
  def tx(body: Body, signers: Vector[Key]): Built =
    val raw = body.encode
    val id = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)
    val vkeys = signers.distinctBy(_.vkey).map(k => arr(bytes(k.vkey), bytes(k.sign(id))))
    val witnesses = if vkeys.isEmpty then map() else map(uint(0) -> set(vkeys))
    val parts = Block.Transaction(raw, witnesses, None, true)
    val flat =
      Tx.fromBlock(parts, Era.Conway).fold(e => fail(s"flat decoder: ${e.message}"), identity)
    val typed = ConwayTx
      .decode(parts, StateLayout.Node11_1)
      .fold(e => fail(s"Conway's decoder: $e"), identity)
    val b = flat.body
    assertEquals(
      (b.inputs.toSet, b.outputs, b.fee, b.certificates, b.withdrawals, b.proposals),
      (
        body.inputs.toSet,
        body.outputs,
        body.fee,
        body.certificates,
        body.withdrawals.view.mapValues(xyz.kd5ujc.scarab.ledger.state.Coin(_)).toMap,
        body.proposals
      ),
      "the flat decoder reads the body as it was built"
    )
    // every vote asked for, each as many times as asked, read back on its own rather than through
    // the form it was written from
    def counted[A](as: Vector[A]): Map[A, Int] = as.groupMapReduce(identity)(_ => 1)(_ + _)
    assertEquals(
      counted(b.votes.flatMap((v, bs) => bs.map((id, p) => (v, id, p.vote, p.anchor)))),
      counted(
        body.votes.flatMap((v, bs) => bs.map((id, vote) => (v, id, vote, Option.empty[Anchor])))
      ),
      "the flat decoder reads the votes as they were cast"
    )
    assertEquals((b.donation, b.currentTreasury), (body.donation, body.currentTreasury))
    Built(parts, flat, typed)

  // ---- the rules --------------------------------------------------------------------------------

  /** One era from the start at the rules' geometry, without an end, as `fixedEpochInfo` is. */
  private def epochInfo(globals: xyz.kd5ujc.scarab.ledger.rules.Globals): Horizon =
    val g = globals.geometry
    val window = g.stabilityWindow.toBigInt
    Horizon(
      NonEmptyVector.one(
        Horizon.Era(
          Horizon.Edge(0, 0, 0),
          None,
          Horizon.Params(g.epochLength, g.slotLengthMillis, window, window)
        )
      )
    )

  /** What a LEDGER made of a transaction: its failures in order in the node's flat form, or the
    * ledger state after it.
    */
  private type Outcome = Either[Vector[PredicateFailure], LedgerState[Conway]]

  /** Conway's LEDGER on the era types on `built` from `nes` under `ctx`, at the root, every failure
    * accumulated: the state after it, or its refusal with its failures in the node's flat form
    * ([[EraLedgerRun.flat]]). The flat LEDGER, on the same bytes from the same state, accumulating
    * too, gives the same failures in the same order or the same state; a defect of either, or a
    * failure without a flat form, fails the test.
    */
  def ledger(
      nes: NewEpochState[Conway],
      built: Built,
      ctx: TxEnv
  ): Either[LedgerFailure, NewEpochState[Conway]] =
    val globals = NativeRules.globalsOf(ctx)
    val ls = nes.es.ls
    val pp = PParams[Conway](
      ConwayPParams.fromStored(ls.utxo.gov.curPParams).fold(e => fail(s"parameters: $e"), identity)
    )
    val utxo =
      ConwayUTxO.fromStored(ls.utxo.utxo.utxo.toMap).fold(e => fail(s"outputs: $e"), identity)
    val clock = ctx.clock.getOrElse(fail("the environment has no clock"))
    val env = LedgerEnv[Conway](
      ctx.slot,
      Some(EpochNo.fromBigInt(ctx.epoch).fold(e => fail(s"epoch: $e"), identity)),
      TxIx.txIxFromIntegral(ctx.txIndex).getOrElse(fail(s"index ${ctx.txIndex}")),
      pp,
      nes.es.chainAccount
    )
    val context = UtxoContext[Conway](
      Network.of(BigInt(ctx.network)).getOrElse(fail(s"network ${ctx.network}")),
      xyz.kd5ujc.scarab.ledger.SystemStart(
        java.time.Instant
          .ofEpochMilli(clock.posix(0).fold(e => fail(s"the system start: $e"), identity).toLong)
      ),
      epochInfo(globals),
      ctx.validation,
      ConwayUtxo.injectShelleyFailure(ctx.referenceBuild)
    )
    val era: Outcome = EraLedgerRun.ledger(
      globals,
      env,
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, pp, utxo, Map.empty, built.typed),
      context,
      ls,
      ctx.evaluator,
      ctx.verdicts,
      ctx.decoder
    ) match
      case Left(defect)             => fail(s"the era LEDGER: $defect")
      case Right(Left(failures))    => Left(failures.toChain.toVector.map(flatForm(built, utxo, _)))
      case Right(Right((after, _))) => Right(after)
    val flat: Outcome = Judgment.run(FlatLedger, RuleT.Policy.Accumulate)(
      globals,
      FlatLedgerEnv(ctx, nes.es.chainAccount.treasury),
      Counted(ls, PlutusStats.zero),
      built.flat
    ) match
      case Left(defect)          => fail(s"the flat LEDGER: $defect")
      case Right(Left(failures)) => Left(failures.toChain.toVector)
      case Right(Right(after))   => Right(after.state)
    (era, flat) match
      case (Right(e), Right(f)) =>
        assert(cats.Eq[LedgerState[Conway]].eqv(e, f), s"the era and the flat LEDGER: $e\n$f")
      case _ => assertEquals(era.left.toOption, flat.left.toOption, "the era and the flat LEDGER")
    era.bimap(
      failures =>
        LedgerFailure.Rejected(
          built.id,
          NonEmptyChain.fromSeq(failures).getOrElse(fail("a refusal without a failure"))
        ),
      after => nes.copy(es = nes.es.copy(ls = after))
    )

  /** A failure of the era LEDGER in the node's flat form: a missing redeemer's purpose read from
    * the transaction's scripts and a proposal's action from its proposals.
    */
  private def flatForm(
      built: Built,
      utxo: xyz.kd5ujc.scarab.ledger.era.UTxO[Conway],
      failure: xyz.kd5ujc.scarab.ledger.era.rules.LedgerPredFailure[Conway]
  ): PredicateFailure =
    val t = EraTx.conway
    val b = EraTxBody.conway
    val u = EraUTxO.conway
    val body = t.body[TxLevel.Top](built.typed)(using t.bodyOf.top)
    val purposes = ConwayUtxowPredFailure.storedPurposes(
      u.getScriptsNeeded[TxLevel.Top](utxo, body)(using u.txOf.bodyOf.top),
      utxo
    )
    val read =
      ConwayGovPredFailure.readOf(b.proposalProcedures[TxLevel.Top](body)(using b.top).toVector)
    EraLedgerRun
      .flat(failure, purposes, read)
      .fold(e => fail(s"no flat form of $failure: $e"), identity)
