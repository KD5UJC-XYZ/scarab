// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Encoders of ledger values in the shapes the reference ledger gives them (`EncCBOR` at the Conway
  * era's lowest protocol version, 9): rationals under tag 30, sets under tag 258, and maps, sets
  * and lists of more than 23 elements as indefinite containers. Every encoder writes into a
  * [[CborOut]], so results compose without intermediate byte strings.
  *
  * Orderings are the ledger's `Ord` instances (maps and sets are written in key order): a script
  * credential sorts before a key credential, DReps by constructor.
  */
object LedgerEncoders:
  type W = CborOut

  /** The ledger's `lengthThreshold`: containers above it are indefinite. */
  val Threshold = 23

  // ---- orderings

  private def bytesKey(b: Bytes): String = b.hex

  /** `Ord (Credential r)`: `ScriptHashObj` before `KeyHashObj`, then by hash. */
  val credentialOrdering: Ordering[Credential] = Ordering.by {
    case Credential.Script(h) => (0, bytesKey(h))
    case Credential.Key(h)    => (1, bytesKey(h))
  }

  val bytesOrdering: Ordering[Bytes] = Ordering.by(bytesKey)

  val drepOrdering: Ordering[DRep] = Ordering.by {
    case DRep.Registered(Credential.Key(h))    => (0, bytesKey(h))
    case DRep.Registered(Credential.Script(h)) => (1, bytesKey(h))
    case DRep.AlwaysAbstain                    => (2, "")
    case DRep.AlwaysNoConfidence               => (3, "")
  }

  val txInOrdering: Ordering[TxIn] = Ordering.by(i => (bytesKey(i.id), i.index))

  val govActionIdOrdering: Ordering[GovActionId] = Ordering.by(i => (bytesKey(i.txId), i.index))

  val rewardAccountOrdering: Ordering[RewardAccount] = (a: RewardAccount, b: RewardAccount) =>
    val byNetwork = Integer.compare(a.network, b.network)
    if byNetwork != 0 then byNetwork else credentialOrdering.compare(a.credential, b.credential)

  // ---- containers

  /** `variableListLenEncoding`: a definite header up to 23 elements, otherwise indefinite. */
  def listHead(w: W, n: Int): W = if n <= Threshold then w.array(n) else w.indefiniteArray
  def mapHead(w: W, n: Int): W = if n <= Threshold then w.map(n) else w.indefiniteMap
  def end(w: W, n: Int): W = if n > Threshold then w.break else w

  /** A map written in key order with the ledger's variable-length framing. */
  def map[K, V](w: W, entries: Iterable[(K, V)], ordering: Ordering[K], variable: Boolean = true)(
      key: (W, K) => Unit,
      value: (W, V) => Unit
  ): W =
    val sorted = entries.toVector.sortBy(_._1)(ordering)
    if variable then mapHead(w, sorted.size) else w.map(sorted.size)
    sorted.foreach { case (k, v) => key(w, k); value(w, v) }
    if variable then end(w, sorted.size) else w

  /** A set: tag 258 and a variable-length array in element order. */
  def set[A](w: W, elements: Iterable[A], ordering: Ordering[A], variable: Boolean = true)(
      each: (W, A) => Unit
  ): W =
    val sorted = elements.toVector.sorted(ordering)
    w.tag(258)
    if variable then listHead(w, sorted.size) else w.array(sorted.size)
    sorted.foreach(each(w, _))
    if variable then end(w, sorted.size) else w

  /** `StrictMaybe` / `Maybe`: `[]` or `[x]`. */
  def maybe[A](w: W, value: Option[A])(each: (W, A) => Unit): W = value match
    case None    => w.array(0)
    case Some(a) => w.array(1); each(w, a); w

  /** `encodeNullStrictMaybe`: `null` or the value itself. */
  def nullable[A](w: W, value: Option[A])(each: (W, A) => Unit): W = value match
    case None    => w.nul
    case Some(a) => each(w, a); w

  // ---- scalars

  def rational(w: W, r: Rational): W = w.rational(r.numerator, r.denominator)

  def keyHash(w: W, h: Bytes): W = w.bytes(h)

  def credential(w: W, c: Credential): W = c match
    case Credential.Key(h)    => w.array(2).uint(0).bytes(h)
    case Credential.Script(h) => w.array(2).uint(1).bytes(h)

  def drep(w: W, d: DRep): W = d match
    case DRep.Registered(Credential.Key(h))    => w.array(2).uint(0).bytes(h)
    case DRep.Registered(Credential.Script(h)) => w.array(2).uint(1).bytes(h)
    case DRep.AlwaysAbstain                    => w.array(1).uint(2)
    case DRep.AlwaysNoConfidence               => w.array(1).uint(3)

  def txIn(w: W, i: TxIn): W = w.array(2).bytes(i.id).uint(i.index)

  def govActionId(w: W, i: GovActionId): W = w.array(2).bytes(i.txId).uint(i.index)

  def anchor(w: W, a: Anchor): W =
    w.array(2).text(a.url).bytes(a.hash)

  /** The reward account in its address form: header byte, then the credential hash. */
  def rewardAccount(w: W, a: RewardAccount): W =
    val header = (if a.credential.isInstanceOf[Credential.Script] then 0xf0 else 0xe0) | a.network
    w.bytes(Bytes(Vector(header.toByte) ++ a.credential.hash.value))

  def exUnits(w: W, e: ExUnits): W = w.array(2).uint(e.mem).uint(e.steps)

  // ---- values and outputs

  /** `MaryValue`: a bare coin, or `[coin, {policy: {asset: quantity}}]`. */
  def value(w: W, v: Value): W =
    if v.assets.isEmpty then w.uint(v.coin)
    else
      w.array(2).uint(v.coin)
      map(w, v.assets.toVector, bytesOrdering)(
        (k, p) => k.bytes(p),
        (k, names) =>
          map(k, names.toVector, bytesOrdering)((k2, n) => k2.bytes(n), (k2, q) => k2.int(q))
      )

  private val limits =
    Cbor.Limits(maxInputBytes = 1048576, maxItems = 100000, maxStringBytes = 1048576)

  /** `BabbageTxOut`: `[address, value]`, `[address, value, datum hash]`, or the keyed form with an
    * inline datum or a reference script. The datum and script items are the ones the output was
    * created with.
    */
  def txOut(w: W, o: TxOut): W =
    val fields: Map[Int, Bytes] = Cbor
      .decode(o.raw, limits)
      .toOption
      .map(_.value)
      .collect {
        case CborValue.Map(kvs) =>
          kvs.flatMap { case (k, v) =>
            k.value match
              case CborValue.UInt(i) => Some(i.toInt -> v.original)
              case _                 => None
          }.toMap
        case CborValue.Arr(xs) =>
          xs.zipWithIndex.drop(2).map { case (n, i) => i -> n.original }.toMap
      }
      .getOrElse(Map.empty)
    o.datum match
      case Datum.NoDatum if o.scriptRef.isEmpty =>
        w.array(2).bytes(o.address.raw); value(w, o.value)
      case Datum.Hash(h) if o.scriptRef.isEmpty =>
        w.array(3).bytes(o.address.raw); value(w, o.value); w.bytes(h)
      case _ =>
        val datumItem: Option[Bytes] = o.datum match
          case Datum.NoDatum   => None
          case Datum.Hash(h)   => Some(CborOut.build(_.array(2).uint(0).bytes(h)))
          case Datum.Inline(_) => fields.get(2)
        val scriptItem = o.scriptRef.flatMap(_ => fields.get(3))
        val n = 2 + (if datumItem.isDefined then 1 else 0) + (if scriptItem.isDefined then 1 else 0)
        w.map(n).uint(0).bytes(o.address.raw).uint(1)
        value(w, o.value)
        datumItem.foreach(d => w.uint(2).raw(d))
        scriptItem.foreach(s => w.uint(3).raw(s))
        w

  /** `UTxO`: a map from input to output in input order. */
  def utxo(w: W, entries: Iterable[(TxIn, TxOut)]): W =
    map(w, entries, txInOrdering)(txIn, txOut)

  // ---- protocol parameters

  /** Cost models: a map from language tag to the parameter list (indefinite above 23 values). */
  def costModels(w: W, models: Map[Int, Vector[BigInt]]): W =
    map(w, models.toVector, Ordering.Int)(
      (k, l) => k.uint(l),
      (k, values) =>
        listHead(k, values.size)
        values.foreach(k.int)
        end(k, values.size)
    )

  def poolVotingThresholds(w: W, t: PoolVotingThresholds): W =
    w.array(5)
    t.toVector.foreach(rational(w, _))
    w

  def drepVotingThresholds(w: W, t: DRepVotingThresholds): W =
    w.array(10)
    t.toVector.foreach(rational(w, _))
    w

  /** `ConwayPParams`: the 31-element array. */
  def protocolParams(w: W, p: ProtocolParams): W =
    w.array(31)
    w.uint(p.minFeeA).uint(p.minFeeB).uint(p.maxBlockBodySize).uint(p.maxTxSize)
    w.uint(p.maxBlockHeaderSize).uint(p.keyDeposit).uint(p.poolDeposit).uint(p.eMax).uint(p.nOpt)
    rational(w, p.a0); rational(w, p.rho); rational(w, p.tau)
    w.array(2).uint(p.protocolMajor).uint(p.protocolMinor)
    w.uint(p.minPoolCost).uint(p.coinsPerUTxOByte)
    costModels(w, p.costModels)
    w.array(2); rational(w, p.priceMem); rational(w, p.priceSteps)
    exUnits(w, p.maxTxExUnits); exUnits(w, p.maxBlockExUnits)
    w.uint(p.maxValueSize).uint(p.collateralPercentage).uint(p.maxCollateralInputs)
    poolVotingThresholds(w, p.poolVotingThresholds)
    drepVotingThresholds(w, p.drepVotingThresholds)
    w.uint(p.committeeMinSize).uint(p.committeeMaxTermLength).uint(p.govActionLifetime)
    w.uint(p.govActionDeposit).uint(p.drepDeposit).uint(p.drepActivity)
    rational(w, p.minFeeRefScriptCostPerByte)
    w

  // ---- pools

  /** `StakePoolParams`: nine fields, owners as a set; the relays and metadata items as registered.
    */
  def poolParams(w: W, p: PoolParams): W =
    w.array(9).bytes(p.operator).bytes(p.vrfKeyHash).uint(p.pledge).uint(p.cost)
    rational(w, p.margin)
    rewardAccount(w, p.rewardAccount)
    set(w, p.owners, bytesOrdering)(keyHash)
    // genesis pools carry no relay item
    if p.relays.size == 0 then w.array(0) else w.raw(p.relays)
    p.metadata match
      case None    => w.nul
      case Some(m) => w.raw(m)

  // ---- governance

  def vote(w: W, v: Vote): W = w.uint(v match
    case Vote.No      => 0
    case Vote.Yes     => 1
    case Vote.Abstain => 2)

  def committee(w: W, c: Committee): W =
    w.array(2)
    map(w, c.members.toVector, credentialOrdering)(credential, (k, e) => k.uint(e))
    rational(w, c.threshold)

  def constitution(w: W, c: Constitution): W =
    w.array(2)
    anchor(w, c.anchor)
    nullable(w, c.script)((k, s) => k.bytes(s))

  def paramUpdate(w: W, u: ParamUpdate): W =
    map(w, u.fields.toVector, Ordering.Int)((k, i) => k.uint(i), (k, raw) => k.raw(raw))

  def govAction(w: W, a: GovAction): W =
    def prev(k: W, p: Option[GovActionId]): Unit = nullable(k, p)(govActionId)
    a match
      case GovAction.ParameterChange(p, u, policy) =>
        w.array(4).uint(0); prev(w, p); paramUpdate(w, u); nullable(w, policy)((k, h) => k.bytes(h))
      case GovAction.HardFork(p, major, minor) =>
        w.array(3).uint(1); prev(w, p); w.array(2).uint(major).uint(minor)
      case GovAction.TreasuryWithdrawals(ws, policy) =>
        w.array(3).uint(2)
        map(w, ws.groupMapReduce(_._1)(_._2)(_ + _).toVector, rewardAccountOrdering)(
          rewardAccount,
          (k, c) => k.uint(c)
        )
        nullable(w, policy)((k, h) => k.bytes(h))
      case GovAction.NoConfidence(p) =>
        w.array(2).uint(3); prev(w, p)
      case GovAction.UpdateCommittee(p, removed, added, quorum) =>
        w.array(5).uint(4); prev(w, p)
        set(w, removed, credentialOrdering)(credential)
        map(w, added.toVector, credentialOrdering)(credential, (k, e) => k.uint(e))
        rational(w, quorum)
      case GovAction.NewConstitution(p, script, an) =>
        w.array(3).uint(5); prev(w, p); constitution(w, Constitution(an, script))
      case GovAction.Info =>
        w.array(1).uint(6)
    w

  def proposalProcedure(w: W, p: Proposal): W =
    w.array(4).uint(p.deposit)
    rewardAccount(w, p.returnAccount)
    govAction(w, p.action)
    anchor(w, p.anchor)

  private val voteCredentialOrdering = credentialOrdering

  def govActionState(w: W, g: GovActionState): W =
    w.array(7)
    govActionId(w, g.id)
    map(w, g.committeeVotes.toVector, voteCredentialOrdering)(credential, vote)
    map(w, g.drepVotes.toVector, voteCredentialOrdering)(credential, vote)
    map(w, g.poolVotes.toVector, bytesOrdering)(keyHash, vote)
    proposalProcedure(w, g.proposal)
    w.uint(g.proposedIn).uint(g.expiresAfter)

  def prevIds(w: W, p: PrevIds): W =
    w.array(4)
    List(p.paramUpdate, p.hardFork, p.committee, p.constitution)
      .foreach(maybe(w, _)(govActionId))
    w

  /** `Proposals`: the lineage roots and the proposals in order (a sequence, variable-length). */
  def proposals(w: W, p: Proposals): W =
    w.array(2)
    prevIds(w, p.roots)
    listHead(w, p.actions.size)
    p.actions.foreach(govActionState(w, _))
    end(w, p.actions.size)

  def enactState(w: W, e: EnactState): W =
    w.array(7)
    maybe(w, e.committee)(committee)
    constitution(w, e.constitution)
    protocolParams(w, e.params)
    protocolParams(w, e.prevParams)
    w.uint(e.treasury)
    map(w, e.withdrawals.toVector, credentialOrdering)(credential, (k, c) => k.uint(c))
    prevIds(w, e.prevIds)

  def ratifyState(w: W, r: RatifyState): W =
    w.array(4)
    enactState(w, r.enact)
    // enacted: a sequence of governance action states; expired: a set of ids
    listHead(w, r.enacted.size)
    r.enacted.foreach(govActionState(w, _))
    end(w, r.enacted.size)
    set(w, r.expired, govActionIdOrdering)(govActionId)
    w.bool(r.delayed)

  /** The nonce as `Nonce` encodes: `[0]` neutral, `[1, hash]`. */
  def nonce(w: W, n: Nonce): W = n match
    case Nonce.Neutral => w.array(1).uint(0)
    case Nonce.Hash(h) => w.array(2).uint(1).bytes(h)
