// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Datum,
  NativeScript,
  Script,
  ScriptKind,
  TxBody,
  Value,
  Witnesses,
  Anchor,
  Block,
  ByteStrings,
  Certificate,
  Credential,
  DRep,
  GovAction,
  GovActionId,
  Horizon,
  PoolParams,
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
  ProposalProcedure,
  EraLedgerRun,
  EraTx,
  EraUTxO,
  IsPhase2Valid,
  Network,
  PParams
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  BbodyEnv,
  BbodyPredFailure,
  ConwayBlockRun,
  ConwayGovPredFailure,
  ConwayUtxo,
  ConwayUtxowPredFailure,
  LedgerEnv,
  LedgerPredFailure,
  LedgersEnv,
  ShelleyBbodyState,
  UtxoContext
}
import xyz.kd5ujc.scarab.ledger.rules.{NativeRules, RuleT}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EpochNo, LedgerState, NewEpochState, TxIx}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** Builders for Conway transactions whose bytes are true: a body and its witnesses are written in
  * the forms of Conway's CDDL (the body's keys, the tags of its certificates, voters and actions),
  * sets under tag 258, the keys of the body's maps ascending and a treasury withdrawal's in the
  * order given, then read by the node's flat decoder ([[Tx.fromBlock]]) and by Conway's
  * ([[ConwayTx.decode]]), and the flat reading must give back every field the test asked for. The
  * bytes are a form both decoders read, not checked against the reference's own encoder.
  *
  * [[judge]] judges such a transaction with Conway's LEDGER on the era types
  * (`ConwayLedger.conwayLedgerTransition`, through [[EraLedgerRun.ledger]]), and [[judgeBlock]] a
  * block of it with LEDGERS; [[encode]] writes a transaction a suite built as a value.
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

    /** An integer: unsigned, or negative (`-1 - n` under major type 1). */
    def int(n: BigInt): Bytes = if n >= 0 then head(0, n) else head(1, -1 - n)
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
    * `votes` casts all of their votes (a body's votes are a map by voter). The inputs are written
    * in their order, or in the order given where `inputsInOrder`, which a decoder reads as well.
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
      currentTreasury: Option[BigInt] = None,
      ttl: Option[BigInt] = None,
      mint: Map[Bytes, Map[Bytes, BigInt]] = Map.empty,
      collateral: Vector[TxIn] = Vector.empty,
      collateralReturn: Option[TxOut] = None,
      totalCollateral: Option[BigInt] = None,
      referenceInputs: Vector[TxIn] = Vector.empty,
      inputsInOrder: Boolean = false
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
        Some(
          0 -> set(
            (if inputsInOrder then inputs else inputs.sortBy(i => (i.id, i.index))).map(txIn)
          )
        ),
        Some(1 -> arr(outputs.map(_.raw)*)),
        Some(2 -> uint(fee)),
        ttl.map(t => 3 -> uint(t)),
        Option.when(certificates.nonEmpty)(4 -> set(certificates.map(certificate))),
        Option.when(withdrawals.nonEmpty)(
          5 -> map(
            withdrawals.toVector.sortBy(_._1).map((ra, c) => bytes(rewardAccount(ra)) -> uint(c))*
          )
        ),
        Option.when(mint.nonEmpty)(
          9 -> map(mint.toVector.sortBy(_._1).map { (policy, assets) =>
            bytes(policy) -> map(assets.toVector.sortBy(_._1).map((n, q) => bytes(n) -> int(q))*)
          }*)
        ),
        Option.when(collateral.nonEmpty)(
          13 -> set(collateral.sortBy(i => (i.id, i.index)).map(txIn))
        ),
        collateralReturn.map(r => 16 -> r.raw),
        totalCollateral.map(c => 17 -> uint(c)),
        Option.when(referenceInputs.nonEmpty)(
          18 -> set(referenceInputs.sortBy(i => (i.id, i.index)).map(txIn))
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

  /** `body` signed by `signers` (each once), without auxiliary data, flagged `isValid`, with the
    * redeemers `redeemers` (the witness set's key 5, as written) if given, read back by the flat
    * decoder and by Conway's; the flat reading gives every field of `body`. With no signers the
    * witness set has no key witnesses, as the CDDL holds no empty list of them, and LEDGER judges
    * the missing witnesses.
    */
  def tx(
      body: Body,
      signers: Vector[Key],
      isValid: Boolean = true,
      redeemers: Option[Bytes] = None
  ): Built =
    val raw = body.encode
    val id = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)
    val vkeys = signers.distinctBy(_.vkey).map(k => arr(bytes(k.vkey), bytes(k.sign(id))))
    val witnesses = map(
      (Option.when(vkeys.nonEmpty)(uint(0) -> set(vkeys)).toVector ++
        redeemers.map(r => uint(5) -> r).toVector)*
    )
    val parts = Block.Transaction(raw, witnesses, None, isValid)
    val Built(_, flat, typed) = read(parts)
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
    assertEquals(
      (b.ttl, b.mint, b.collateral.toSet, b.collateralReturn, b.referenceInputs.toSet),
      (
        body.ttl,
        body.mint,
        body.collateral.toSet,
        body.collateralReturn,
        body.referenceInputs.toSet
      ),
      "the flat decoder reads the body's validity, mint, collateral and reference inputs"
    )
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

  /** The stored outputs of `ls` in Conway's form, each output that occurs at several inputs read
    * once and shared, as the stored outputs share it (a suite may hold one large output at
    * thousands of inputs).
    */
  def utxoOf(ls: LedgerState[Conway]): xyz.kd5ujc.scarab.ledger.era.UTxO[Conway] =
    val byOutput = ls.utxo.utxo.utxo.toMap.toVector.groupMap(_._2)(_._1)
    val read = byOutput.toVector.map { (out, ins) =>
      ConwayUTxO
        .fromStored(Map(ins.head -> out))
        .fold(e => fail(s"outputs: $e"), identity)
        .unUTxO
        .head
        ._2 -> ins
    }
    xyz.kd5ujc.scarab.ledger.era.UTxO[Conway](read.flatMap((o, ins) => ins.map(_ -> o)).toMap)

  /** Conway's LEDGER on the era types (`ConwayLedger.conwayLedgerTransition`, through
    * [[EraLedgerRun.ledger]]) on `tx` from `nes` under `ctx`, at the root, every failure
    * accumulated: a defect, the failures in the reference's order, or the state after with the
    * events. The transaction is annotated against the stored outputs of `nes` it names.
    */
  def eraLedger(
      nes: NewEpochState[Conway],
      tx: ConwayTx,
      ctx: TxEnv,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): EraLedgerRun.Judged[
    xyz.kd5ujc.scarab.ledger.era.rules.LedgerPredFailure[Conway],
    xyz.kd5ujc.scarab.ledger.era.rules.LedgerEvent[Conway]
  ] =
    val (env, stAnnTx, context) = inputsOf(nes, tx, ctx)
    EraLedgerRun.ledger(
      NativeRules.globalsOf(ctx),
      env,
      stAnnTx,
      context,
      nes.es.ls,
      ctx.evaluator,
      ctx.verdicts,
      ctx.decoder,
      policy = policy
    )

  /** What Conway's LEDGER takes for `tx` from `nes` under `ctx`: its environment at the slot, epoch
    * and index of `ctx`, the transaction annotated against the stored outputs of `nes`, and UTXO's
    * context.
    */
  def inputsOf(
      nes: NewEpochState[Conway],
      tx: ConwayTx,
      ctx: TxEnv
  ): (LedgerEnv[Conway], AlonzoStAnnTx[Conway], UtxoContext[Conway]) =
    val pp = paramsOf(nes)
    val clock = ctx.clock.getOrElse(fail("the environment has no clock"))
    val env = LedgerEnv[Conway](
      ctx.slot,
      Some(EpochNo.fromBigInt(ctx.epoch).fold(e => fail(s"epoch: $e"), identity)),
      TxIx.txIxFromIntegral(ctx.txIndex).getOrElse(fail(s"index ${ctx.txIndex}")),
      pp,
      nes.es.chainAccount
    )
    (
      env,
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock.posix, pp, utxoOf(nes.es.ls), Map.empty, tx),
      contextOf(ctx)
    )

  /** The parameters in force in `nes`, in Conway's form. */
  private def paramsOf(nes: NewEpochState[Conway]): PParams[Conway] =
    PParams[Conway](
      ConwayPParams
        .fromStored(nes.es.ls.utxo.gov.curPParams)
        .fold(e => fail(s"parameters: $e"), identity)
    )

  /** UTXO's context under `ctx`: its network, the system start its clock gives, one era from the
    * start at the rules' geometry, its validation and its build's failure of a Shelley output.
    */
  def contextOf(ctx: TxEnv): UtxoContext[Conway] =
    val clock = ctx.clock.getOrElse(fail("the environment has no clock"))
    UtxoContext[Conway](
      Network.of(BigInt(ctx.network)).getOrElse(fail(s"network ${ctx.network}")),
      xyz.kd5ujc.scarab.ledger.SystemStart(
        java.time.Instant
          .ofEpochMilli(clock.posix(0).fold(e => fail(s"the system start: $e"), identity).toLong)
      ),
      epochInfo(NativeRules.globalsOf(ctx)),
      ctx.validation,
      ConwayUtxo.injectShelleyFailure(ctx.referenceBuild)
    )

  /** Conway's LEDGERS on the era types (Babbage's, which Conway selects, `ledgersTransition`,
    * `Babbage/Rules/Ledgers.hs:124-142`, cardano-ledger f649f975, through [[EraLedgerRun.ledgers]])
    * on a block of the one transaction `tx` from `nes` under `ctx`, its failures kept as `policy`
    * keeps them: each a LEDGER failure of the transaction, or what it admitted ([[Admitted]]).
    */
  def judgeBlock(
      nes: NewEpochState[Conway],
      built: Built,
      ctx: TxEnv,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Either[Vector[LedgerPredFailure[Conway]], Admitted] =
    val ls = nes.es.ls
    val clock = ctx.clock.getOrElse(fail("the environment has no clock"))
    EraLedgerRun.ledgers(
      NativeRules.globalsOf(ctx),
      LedgersEnv[Conway](
        ctx.slot,
        EpochNo.fromBigInt(ctx.epoch).fold(e => fail(s"epoch: $e"), identity),
        paramsOf(nes),
        nes.es.chainAccount
      ),
      Vector(built.typed),
      contextOf(ctx),
      ls,
      ctx.evaluator,
      ctx.verdicts,
      ctx.decoder,
      clock.posix,
      policy
    ) match
      case Left(defect) => fail(s"the era LEDGERS: $defect")
      case Right(Left(failures)) =>
        Left(failures.toChain.toVector.map {
          case xyz.kd5ujc.scarab.ledger.era.rules.ShelleyLedgersPredFailure.LedgerFailure(f) => f
        })
      case Right(Right((after, events))) =>
        val valid = EraTx.conway.isValid(built.typed) == IsPhase2Valid.Phase2Valid
        val (succeeded, failed, scripts) = EraLedgerRun
          .counters(
            valid,
            events.map {
              case xyz.kd5ujc.scarab.ledger.era.rules.ShelleyLedgersEvent.LedgerEvent(e) =>
                e
            }
          )
          .getOrElse(fail(s"the events $events"))
        Right(Admitted(nes.copy(es = nes.es.copy(ls = after)), succeeded, failed, scripts))

  /** The transaction `tx` the flat decoder read, read by Conway's decoder from the same original
    * parts (its body, witnesses, auxiliary data and validity flag).
    */
  def typed(tx: Tx): ConwayTx =
    ConwayTx
      .decode(
        Block.Transaction(
          tx.body.raw,
          tx.witnesses.raw.getOrElse(fail("the transaction holds no original witness bytes")),
          tx.auxiliary,
          tx.isValid
        ),
        StateLayout.Node11_1
      )
      .fold(e => fail(s"Conway's decoder: $e"), identity)

  /** Conway's LEDGER on the era types ([[eraLedger]]) on `tx` from `nes` under `ctx`, at the root,
    * every failure accumulated: its failures in the reference's order, or the ledger state after
    * it. A defect fails the test.
    */
  def eraVerdict(
      nes: NewEpochState[Conway],
      tx: ConwayTx,
      ctx: TxEnv
  ): Either[Vector[xyz.kd5ujc.scarab.ledger.era.rules.LedgerPredFailure[Conway]], LedgerState[
    Conway
  ]] =
    eraLedger(nes, tx, ctx) match
      case Left(defect)             => fail(s"the era LEDGER: $defect")
      case Right(Left(failures))    => Left(failures.toChain.toVector)
      case Right(Right((after, _))) => Right(after)

  /** Conway's BBODY on the era types as the node runs it ([[ConwayBlockRun]];
    * `conwayBbodyTransition >> alonzoBbodyTransition`, `Conway/Rules/Bbody.hs:268`, cardano-ledger
    * f649f975) on a block of the one transaction `tx` from `nes` under `ctx`, the transaction read
    * from its original parts: the block's header at the slot of `ctx` and at the major version of
    * the parameters in force, committing to its body as the Imp's does ([[ConwayImp.blockOf]]), the
    * run's network, clock and epoch information those of `ctx`. Its failures in the reference's
    * order, or the ledger state after it; a defect, or a run stopped by one, fails the test.
    */
  def eraBbody(
      nes: NewEpochState[Conway],
      tx: Tx,
      ctx: TxEnv
  ): Either[Vector[BbodyPredFailure[Conway]], LedgerState[Conway]] =
    assertEquals(tx.auxiliary, None, "a block of auxiliary data is not built here")
    val globals = NativeRules.globalsOf(ctx)
    val ls = nes.es.ls
    val stored = ls.utxo.gov.curPParams
    val pp = PParams[Conway](
      ConwayPParams.fromStored(stored).fold(e => fail(s"parameters: $e"), identity)
    )
    val clock = ctx.clock.getOrElse(fail("the environment has no clock"))
    val raw = ConwayImp.blockOf(
      stored.protocolMajor.toInt,
      Vector(
        ConwayImp.Built(
          tx.body.raw,
          tx.witnesses.raw.getOrElse(fail("the transaction holds no original witness bytes")),
          tx.isValid
        )
      ),
      _.updated(1, ConwayImp.encoded(xyz.kd5ujc.scarab.codec.Value.UInt(ctx.slot)))
    )
    ConwayBlockRun
      .program(
        globals,
        BbodyEnv[Conway](pp, nes.es.chainAccount),
        ConwayImp.decoded(raw),
        xyz.kd5ujc.scarab.ledger.SystemStart(
          java.time.Instant
            .ofEpochMilli(clock.posix(0).fold(e => fail(s"the system start: $e"), identity).toLong)
        ),
        epochInfo(globals),
        ShelleyBbodyState[Conway](ls, nes.bcur),
        ctx.evaluator,
        ctx.verdicts,
        ctx.decoder,
        clock.posix
      )
      .serial match
      case Left(defect)                                         => fail(s"the era BBODY: $defect")
      case Right(Left(ConwayBlockRun.Refusal.Failed(failures))) => Left(failures.toChain.toVector)
      case Right(Left(stopped))                                 => fail(s"the era BBODY: $stopped")
      case Right(Right(applied))                                => Right(applied.state.ls)

  /** The failures of LEDGERS among BBODY's `failures`, in order ([[eraBbody]]): LEDGER's, each of
    * one transaction; a BBODY failure of the block's own fails the test.
    */
  def ledgersFailures(
      failures: Vector[BbodyPredFailure[Conway]]
  ): Vector[xyz.kd5ujc.scarab.ledger.era.rules.LedgerPredFailure[Conway]] =
    failures.map {
      case xyz.kd5ujc.scarab.ledger.era.rules.ConwayBbodyPredFailure.LedgersFailure(
            xyz.kd5ujc.scarab.ledger.era.rules.ShelleyLedgersPredFailure.LedgerFailure(f)
          ) =>
        f
      case other => fail(s"a failure of the block's own: $other")
    }

  /** What Conway's LEDGER on the era types made of a transaction it admitted: the state after it,
    * and the counters of the scripts it ran, as its events give them ([[EraLedgerRun.counters]]):
    * those that succeeded, those that failed, and all it ran.
    */
  final case class Admitted(
      nes: NewEpochState[Conway],
      succeeded: Long,
      failed: Long,
      scripts: Long
  )

  /** Conway's LEDGER on the era types (`conwayLedgerTransitionTRC`,
    * `Conway/Rules/Ledger.hs:320-440`, cardano-ledger f649f975) on `built` from `nes` under `ctx`,
    * at the root, its failures kept as `policy` keeps them (every one, by default; under
    * [[RuleT.Policy.FirstFailure]] the first check that fails alone): its failures in the
    * reference's order, or what it admitted ([[Admitted]]). A defect fails the test.
    */
  def judge(
      nes: NewEpochState[Conway],
      built: Built,
      ctx: TxEnv,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Either[Vector[LedgerPredFailure[Conway]], Admitted] =
    eraLedger(nes, built.typed, ctx, policy) match
      case Left(defect)          => fail(s"the era LEDGER: $defect")
      case Right(Left(failures)) => Left(failures.toChain.toVector)
      case Right(Right((after, events))) =>
        val valid = EraTx.conway.isValid(built.typed) == IsPhase2Valid.Phase2Valid
        val (succeeded, failed, scripts) =
          EraLedgerRun.counters(valid, events).getOrElse(fail(s"the events $events"))
        Right(Admitted(nes.copy(es = nes.es.copy(ls = after)), succeeded, failed, scripts))

  /** [[judge]]'s failures, or the state after the transaction. */
  def ledger(
      nes: NewEpochState[Conway],
      built: Built,
      ctx: TxEnv,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Either[Vector[LedgerPredFailure[Conway]], NewEpochState[Conway]] =
    judge(nes, built, ctx, policy).map(_.nes)

  // ---- a transaction value, written ----------------------------------------------------------

  /** `native_script` of the CDDL. */
  def nativeScript(n: NativeScript): Bytes = n match
    case NativeScript.Sig(h)              => arr(uint(0), bytes(h))
    case NativeScript.AllOf(ss)           => arr(uint(1), arr(ss.map(nativeScript)*))
    case NativeScript.AnyOf(ss)           => arr(uint(2), arr(ss.map(nativeScript)*))
    case NativeScript.NOfK(k, ss)         => arr(uint(3), int(k), arr(ss.map(nativeScript)*))
    case NativeScript.InvalidBefore(t)    => arr(uint(4), uint(t))
    case NativeScript.InvalidHereafter(t) => arr(uint(5), uint(t))

  /** The native script `n` with its true hash, over the bytes [[nativeScript]] writes. */
  def native(n: NativeScript): Script = Script.nativeOf(nativeScript(n), n)

  /** A script's bytes as a witness set or a reference script holds them: a native script's
    * encoding, a Plutus script's bytes.
    */
  private def scriptBytes(sc: Script): Bytes =
    if sc.kind.isPlutus then sc.plutus.getOrElse(fail(s"a Plutus script without its bytes: $sc"))
    else sc.encoding.orElse(sc.native.map(nativeScript)).getOrElse(fail(s"no native tree: $sc"))

  /** `multiasset`: the policies and the names ascending, each quantity unsigned or, in a mint,
    * signed.
    */
  def multiasset(m: Map[Bytes, Map[Bytes, BigInt]], signed: Boolean = false): Bytes =
    val amount: BigInt => Bytes = if signed then int else uint
    map(m.toVector.sortBy(_._1).map { (policy, names) =>
      bytes(policy) -> map(names.toVector.sortBy(_._1).map((n, q) => bytes(n) -> amount(q))*)
    }*)

  /** `value`: the coin alone, or the coin and the assets. */
  def value(v: Value): Bytes =
    if v.assets.isEmpty then uint(v.coin) else arr(uint(v.coin), multiasset(v.assets))

  /** `transaction_output`: the bytes an output was read from, or, for one built as a value, the
    * array `[address, value]` where it has no datum and no reference script, the map otherwise.
    */
  def output(o: TxOut): Bytes =
    if o.raw.nonEmpty then o.raw
    else if o.datum == Datum.NoDatum && o.scriptRef.isEmpty then
      arr(bytes(o.address.raw), value(o.value))
    else
      val datum = o.datum match
        case Datum.NoDatum   => None
        case Datum.Hash(h)   => Some(2 -> arr(uint(0), bytes(h)))
        case Datum.Inline(d) => Some(2 -> arr(uint(1), tag(24, bytes(d))))
      val script = o.scriptRef.map(sc =>
        3 -> tag(
          24,
          bytes(
            arr(
              uint(sc.kind.tag),
              if sc.kind.isPlutus then bytes(scriptBytes(sc)) else scriptBytes(sc)
            )
          )
        )
      )
      map(
        (Vector(Some(0 -> bytes(o.address.raw)), Some(1 -> value(o.value)), datum, script).flatten
          .map((k, v) => uint(k) -> v))*
      )

  /** The output `o` with true bytes ([[output]]), read back as a stored output is: what a state the
    * era rules read holds.
    */
  def stored(o: TxOut): TxOut =
    given ByteStrings = ByteStrings.Chunked
    TxOut.fromBytes(output(o)).fold(e => fail(s"the output $o: ${e.message}"), identity)

  /** `transaction_body` of the flat body `b`, its keys ascending and an empty optional field left
    * out. An update has no place in Conway's body.
    */
  def body(b: TxBody): Bytes =
    if b.update.isDefined then fail("an update in a Conway body")
    def ins(xs: Vector[TxIn]): Bytes = set(xs.sortBy(i => (i.id, i.index)).map(txIn))
    val ballots = Body(
      Vector.empty,
      Vector.empty,
      0,
      votes = b.votes.map((v, bs) => v -> bs.map((id, p) => id -> p.vote))
    ).ballots
    val fields = Vector(
      Some(0 -> ins(b.inputs)),
      Some(1 -> arr(b.outputs.map(output)*)),
      Some(2 -> uint(b.fee)),
      b.ttl.map(t => 3 -> uint(t)),
      Option.when(b.certificates.nonEmpty)(4 -> set(b.certificates.map(certificate))),
      Option.when(b.withdrawals.nonEmpty)(
        5 -> map(
          b.withdrawals.toVector
            .sortBy(_._1)
            .map((ra, c) => bytes(rewardAccount(ra)) -> uint(c.value))*
        )
      ),
      b.auxDataHash.map(h => 7 -> bytes(h)),
      b.validityStart.map(t => 8 -> uint(t)),
      Option.when(b.mint.nonEmpty)(9 -> multiasset(b.mint, signed = true)),
      b.scriptDataHash.map(h => 11 -> bytes(h)),
      Option.when(b.collateral.nonEmpty)(13 -> ins(b.collateral)),
      Option.when(b.requiredSigners.nonEmpty)(14 -> set(b.requiredSigners.sorted.map(bytes))),
      b.networkId.map(n => 15 -> uint(n)),
      b.collateralReturn.map(o => 16 -> output(o)),
      b.totalCollateral.map(c => 17 -> uint(c)),
      Option.when(b.referenceInputs.nonEmpty)(18 -> ins(b.referenceInputs)),
      Option.when(b.votes.nonEmpty)(
        19 -> map(
          ballots.map((v, bs) =>
            voter(v) -> map(bs.map((id, p) => govActionId(id) -> votingProcedure(p))*)
          )*
        )
      ),
      Option.when(b.proposals.nonEmpty)(20 -> set(b.proposals.map(proposal))),
      b.currentTreasury.map(t => 21 -> uint(t)),
      Option.when(b.donation > 0)(22 -> uint(b.donation))
    ).flatten
    map(fields.map((k, v) => uint(k) -> v)*)

  /** `transaction_witness_set` of the witnesses `w`, with `vkeys` as its key witnesses: the native
    * scripts, the Plutus scripts by language, the datums and the redeemers (the bytes they were
    * sent as, where `w` keeps them), each field present only where it holds something.
    */
  def witnessSet(w: Witnesses, vkeys: Vector[(Bytes, Bytes)]): Bytes =
    def scripts(kind: ScriptKind): Vector[Bytes] =
      w.scripts.filter(_.kind == kind).map(scriptBytes)
    def plutus(kind: ScriptKind): Vector[Bytes] = scripts(kind).map(bytes)
    val redeemers = w.redeemersRaw.orElse(
      Option.when(w.redeemers.nonEmpty)(
        map(
          w.redeemers.map(r =>
            arr(uint(r.tag), uint(r.index)) -> arr(
              r.data,
              arr(uint(r.exUnits.mem), uint(r.exUnits.steps))
            )
          )*
        )
      )
    )
    val datums = w.datumsRaw.orElse(Option.when(w.datums.nonEmpty)(arr(w.datums*)))
    val fields = Vector(
      Option.when(vkeys.nonEmpty)(
        0 -> set(vkeys.distinctBy(_._1).map((vk, sig) => arr(bytes(vk), bytes(sig))))
      ),
      Option.when(scripts(ScriptKind.Native).nonEmpty)(1 -> set(scripts(ScriptKind.Native))),
      Option.when(w.bootstrap.nonEmpty)(fail("bootstrap witnesses are not written here")),
      Option.when(plutus(ScriptKind.PlutusV1).nonEmpty)(3 -> set(plutus(ScriptKind.PlutusV1))),
      datums.map(4 -> _),
      redeemers.map(5 -> _),
      Option.when(plutus(ScriptKind.PlutusV2).nonEmpty)(6 -> set(plutus(ScriptKind.PlutusV2))),
      Option.when(plutus(ScriptKind.PlutusV3).nonEmpty)(7 -> set(plutus(ScriptKind.PlutusV3)))
    ).flatten
    map(fields.map((k, v) => uint(k) -> v)*)

  /** The transaction value `t` written in Conway's CDDL and read back by both decoders ([[read]]):
    * its body ([[body]]), its witnesses ([[witnessSet]]) with a key witness of each of `signers`
    * over the body's true hash beside those `t` holds, its auxiliary data and its flag. What a test
    * built as a value with a label for its bytes is judged on bytes of its own.
    */
  def encode(t: Tx, signers: Vector[Key]): Built =
    val raw = body(t.body)
    val id = xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(raw)
    val vkeys = signers.map(k => k.vkey -> k.sign(id)) ++ t.witnesses.vkeys
    read(Block.Transaction(raw, witnessSet(t.witnesses, vkeys), t.auxiliary, t.isValid))

  /** A transaction's parts read by the flat decoder and by Conway's. */
  def read(parts: Block.Transaction): Built =
    Built(
      parts,
      Tx.fromBlock(parts, Era.Conway).fold(e => fail(s"flat decoder: ${e.message}"), identity),
      ConwayTx
        .decode(parts, StateLayout.Node11_1)
        .fold(e => fail(s"Conway's decoder: $e"), identity)
    )

  /** The original parts of a transaction the flat decoder read (its body, witnesses, auxiliary data
    * and validity flag), read again by both decoders ([[read]]).
    */
  def built(tx: Tx): Built =
    read(
      Block.Transaction(
        tx.body.raw,
        tx.witnesses.raw.getOrElse(fail("the transaction holds no original witness bytes")),
        tx.auxiliary,
        tx.isValid
      )
    )

  /** `tx` with a witness set of the key witnesses `vkeys` alone, each `(vkey, signature)`, read by
    * both decoders; none is the empty witness set.
    */
  def withVKeys(tx: Tx, vkeys: Vector[(Bytes, Bytes)]): Built =
    val witnesses =
      if vkeys.isEmpty then map()
      else map(uint(0) -> set(vkeys.map((vkey, signature) => arr(bytes(vkey), bytes(signature)))))
    read(Block.Transaction(tx.body.raw, witnesses, tx.auxiliary, tx.isValid))

  /** The era form of a proposal the flat decoder read, as a failure of GOV holds it. */
  def proposalProcedure(p: Proposal): ProposalProcedure[Conway] =
    ConwayProcedures.proposal(p).fold(e => fail(s"the proposal $p: $e"), identity)

  /** Conway's LEDGER failures as a rule below it raises them, each lifted into LEDGER's
    * (`Conway/Rules/Ledger.hs:133-187`, cardano-ledger f649f975): what the suites name a refusal
    * by.
    */
  object Fails:
    import xyz.kd5ujc.scarab.ledger.era.rules.{
      ConwayCertPredFailure,
      ConwayCertsPredFailure,
      ConwayDelegPredFailure,
      ConwayGovCertPredFailure,
      ConwayLedgerPredFailure,
      ConwayUtxoPredFailure,
      ConwayUtxosPredFailure,
      ShelleyPoolPredFailure
    }

    def gov(f: ConwayGovPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayGovFailure(f)
    def certs(f: ConwayCertsPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayCertsFailure(f)
    def cert(f: ConwayCertPredFailure[Conway]): LedgerPredFailure[Conway] =
      certs(ConwayCertsPredFailure.CertFailure(f))
    def pool(f: ShelleyPoolPredFailure[Conway]): LedgerPredFailure[Conway] =
      cert(ConwayCertPredFailure.PoolFailure(f))
    def deleg(f: ConwayDelegPredFailure[Conway]): LedgerPredFailure[Conway] =
      cert(ConwayCertPredFailure.DelegFailure(f))
    def govCert(f: ConwayGovCertPredFailure[Conway]): LedgerPredFailure[Conway] =
      cert(ConwayCertPredFailure.GovCertFailure(f))
    def utxow(f: ConwayUtxowPredFailure[Conway]): LedgerPredFailure[Conway] =
      ConwayLedgerPredFailure.ConwayUtxowFailure(f)
    def utxo(f: ConwayUtxoPredFailure[Conway]): LedgerPredFailure[Conway] =
      utxow(ConwayUtxowPredFailure.UtxoFailure(f))
    def utxos(f: ConwayUtxosPredFailure[Conway]): LedgerPredFailure[Conway] =
      utxo(ConwayUtxoPredFailure.UtxosFailure(f))

    // the same, read back: the failure of the rule below that a LEDGER failure lifts

    object Utxow:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayUtxowPredFailure[Conway]] = f match
        case ConwayLedgerPredFailure.ConwayUtxowFailure(u) => Some(u)
        case _                                             => None
    object Utxo:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayUtxoPredFailure[Conway]] = f match
        case Utxow(ConwayUtxowPredFailure.UtxoFailure(u)) => Some(u)
        case _                                            => None
    object Utxos:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayUtxosPredFailure[Conway]] = f match
        case Utxo(ConwayUtxoPredFailure.UtxosFailure(u)) => Some(u)
        case _                                           => None
    object Gov:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayGovPredFailure[Conway]] = f match
        case ConwayLedgerPredFailure.ConwayGovFailure(g) => Some(g)
        case _                                           => None
    object Certs:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayCertsPredFailure[Conway]] = f match
        case ConwayLedgerPredFailure.ConwayCertsFailure(c) => Some(c)
        case _                                             => None
    object Cert:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayCertPredFailure[Conway]] = f match
        case Certs(ConwayCertsPredFailure.CertFailure(c)) => Some(c)
        case _                                            => None
    object Pool:
      def unapply(f: LedgerPredFailure[Conway]): Option[ShelleyPoolPredFailure[Conway]] = f match
        case Cert(ConwayCertPredFailure.PoolFailure(p)) => Some(p)
        case _                                          => None
    object Deleg:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayDelegPredFailure[Conway]] = f match
        case Cert(ConwayCertPredFailure.DelegFailure(d)) => Some(d)
        case _                                           => None
    object GovCert:
      def unapply(f: LedgerPredFailure[Conway]): Option[ConwayGovCertPredFailure[Conway]] = f match
        case Cert(ConwayCertPredFailure.GovCertFailure(g)) => Some(g)
        case _                                             => None
