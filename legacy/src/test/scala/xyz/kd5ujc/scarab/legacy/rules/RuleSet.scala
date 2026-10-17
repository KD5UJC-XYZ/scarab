// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy as flat
import xyz.kd5ujc.scarab.legacy.*
import xyz.kd5ujc.scarab.legacy.LedgerMirrorBack.*

import cats.data.Kleisli
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.rules.*
import xyz.kd5ujc.scarab.ledger.state.{LedgerState as _, *}

/** The era-indexed rules as the ledger suites run them (J2.6 to J2.9): the LEDGER rules on a
  * transaction, the boundary and the tick, on a state written as the flat [[LedgerState]] the
  * suites build, which the mirror turns into the state the rules run on and [[LedgerMirrorBack]]
  * reads the result back from, so that the suites' assertions are written over the flat state.
  * (Until J2.9 the suites ran against the old rules too, beside these.)
  *
  * The rules run LEDGER of the era the state's protocol version names, with the checks and the
  * state the reference has (`ShelleyLedger` before Conway, `Ledger` from it). A refusal is the
  * typed [[LedgerFailure]], so a suite compares the rule and its payload as values.
  */
object RuleSet:
  /** The LEDGER rules on one transaction: the state after it, or its refusal. */
  def tx(s: LedgerState, t: Tx, ctx: TxEnv): Either[LedgerFailure, LedgerState] =
    newTx(s, t, ctx)

  /** Whether POOL accepts the registration of a pool in the state (a certificate of the transaction
    * `tx`, at index `ix`), or the rule that refuses it.
    */
  def registerPool(
      s: LedgerState,
      cert: Certificate.RegisterPool,
      @annotation.unused ix: Int,
      ctx: TxEnv,
      tx: Tx
  ): Either[LedgerFailure, Unit] = newRegisterPool(s, cert, ctx, tx)

  /** The state one epoch later, as the boundary into `s.epoch + 1` makes it. The state's own reward
    * update, if it has one, is applied; none is made.
    */
  def boundary(s: LedgerState, g: LedgerGlobals): LedgerState = newBoundary(s, g, rewards = false)

  /** The state one epoch later, the epoch's rewards included: the rules make the reward update of
    * the epoch at the first tick past the reward window and the boundary applies it. The state must
    * not have one already.
    */
  def epoch(s: LedgerState, g: LedgerGlobals): LedgerState = newBoundary(s, g, rewards = true)

  /** What the reward update of the epoch gives each credential, made at the first block past the
    * reward window of the state's epoch (RUPD).
    */
  def rewards(s: LedgerState, g: LedgerGlobals): Map[xyz.kd5ujc.scarab.ledger.Credential, BigInt] =
    newRewards(s, g).rewardUpdate
      .fold(throw new AssertionError("the rules made no reward update"))(_.rewards)

  /** The state after the tick to `slot`, the first slot of whose epoch is `epochStart`. */
  def tick(
      s: LedgerState,
      slot: BigInt,
      @annotation.unused epochStart: BigInt,
      g: LedgerGlobals
  ): LedgerState =
    newTick(s, slot, g)

  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]
  private val mirror: LedgerMirror[M] = LedgerMirror[M]
  private val at: WithOrigin[SlotNo] = WithOrigin.At(SlotNo.fromUnsigned(50L))

  private def newRegisterPool(
      s: LedgerState,
      cert: Certificate.RegisterPool,
      ctx: TxEnv,
      tx: Tx
  ): Either[LedgerFailure, Unit] =
    val env = FlatStateGen.env.copy(network = ctx.network, quorum = ctx.updateQuorum)
    val globals = globalsOf(ctx, env)
    eraOf(s.params.protocolMajor) match
      case e: Of[x] =>
        given ShelleyBasedEra[x] = e.era
        val image =
          mirror.to[x](at, s).run(env).fold(m => broken(s"to the mirror: ${m.show}"), _.value)
        Sts(Pool)(
          globals,
          CertEnv(tx.body.id, s.params, ctx.epoch, None, Proposals.empty),
          xyz.kd5ujc.scarab.ledger.state.LedgerState.pState[x].get(image.nes.es.ls),
          PoolCert.RegPool(cert.params)
        ).void

  /** An era of the state's protocol version, with its witness. */
  private final class Of[E <: EraTag](val era: ShelleyBasedEra[E])

  private def eraOf(pv: BigInt): Of[?] =
    if pv <= 2 then Of(ShelleyBasedEra.SShelley)
    else if pv == 3 then Of(ShelleyBasedEra.SAllegra)
    else if pv == 4 then Of(ShelleyBasedEra.SMary)
    else if pv <= 6 then Of(ShelleyBasedEra.SAlonzo)
    else if pv <= 8 then Of(ShelleyBasedEra.SBabbage)
    else Of(ShelleyBasedEra.SConway)

  /** The era the hard-fork configuration puts the chain in once the parameters adopted have this
    * major version.
    */
  private val eraAt: Hfc.EraAt = (_, major) =>
    if major <= 2 then Era.Shelley
    else if major == 3 then Era.Allegra
    else if major == 4 then Era.Mary
    else if major <= 6 then Era.Alonzo
    else if major <= 8 then Era.Babbage
    else Era.Conway

  /** The era history the old rules' constants stand for: the epoch length and the active slot
    * coefficient as they are, and the security parameter that makes the reward window (4k/f) what
    * `startRewards` says (2 where it says nothing; a window that no integer k gives is a mistake of
    * the test, which says what it wants of the new side's clock).
    */
  private def geometryOf(g: LedgerGlobals): EpochGeometry =
    val f = BigDecimal(g.activeSlotsCoeff.numerator) / BigDecimal(g.activeSlotsCoeff.denominator)
    val k =
      if g.startRewards.signum == 0 then 2
      else (BigDecimal(g.startRewards) * f / 4).setScale(0, BigDecimal.RoundingMode.CEILING).toInt
    val geometry = EpochGeometry(java.time.Instant.EPOCH, 1000L, g.epochLength.toLong, k, f)
    if g.startRewards.signum != 0 && geometry.randomnessStabilisationWindow != g.startRewards then
      broken(
        s"no security parameter makes the reward window ${g.startRewards} of $f (${geometry.randomnessStabilisationWindow})"
      )
    geometry

  /** The pieces of the mirror that a state the rules made may differ in from the mirror of its flat
    * state, and nothing else: the pulser. The node's flat state holds the DRep pulser as its
    * snapshot and ratification result only (`GovState.pulsed`), and the mirror derives a pulser
    * from them (`PulserPlaceholder`); the pulser the rules leave is the one they made, which holds
    * the same two and more. Every other derived piece (the VRF key hashes, the delegators, the
    * pointers, the instant stake, the future parameters, the pool distribution, the snapshots and
    * the node's extras) must be what the flat state derives.
    */
  private val waived: Set[Derived] = Set(Derived.PulserPlaceholder)

  private val placeholder: KeyHash[StakePool] =
    KeyHash
      .fromBytes[StakePool](Bytes.fromArray(new Array[Byte](28)))
      .fold(e => broken(s"placeholder: $e"), identity)

  /** The old state's reward update attributed: its mirror holds the amounts in the node's extras
    * and no pools (`rs` empty), so each credential gets its amount as one reward of a placeholder
    * pool, as the harness' seed does (`Relations.seed`).
    */
  private def attributed[E <: EraTag](image: LedgerImage[E]): NewEpochState[E] =
    val nes = image.nes
    nes.ru match
      case Some(PulsingRewUpdate.Complete(update))
          if update.rs.isEmpty && image.extras.rewardAmounts.nonEmpty =>
        val rewards = image.extras.rewardAmounts.toList.map { (c, n) =>
          val credential =
            Convert.credential[Staking](c).fold(e => broken(s"credential: $e"), identity)
          val coin = Coin.fromBigInt(n).fold(e => broken(s"coin: $e"), identity)
          credential -> RewardSet(List(Reward(RewardType.MemberReward, placeholder, coin)))
        }
        nes.copy(ru = Some(PulsingRewUpdate.Complete(update.copy(rs = Sorted.map(rewards)))))
      case _ => nes

  /** The state after the tick to the slot `target` gives for the state's epoch (the new rules'
    * TICK, with the epoch boundaries on the way and the translation into a later era), or, with
    * `rewards`, after the tick that begins the epoch's reward update in it, then to `target`.
    */
  private def advance(
      s: LedgerState,
      g: LedgerGlobals,
      rewards: Boolean,
      from: (Globals, BigInt) => BigInt,
      target: (Globals, BigInt) => BigInt
  ): LedgerState =
    val geometry = geometryOf(g)
    val env = FlatStateGen.env.copy(
      network = g.network,
      quorum = g.updateQuorum,
      geometry = geometry,
      snapshots = SnapshotBasis.AsTaken
    )
    val globals = Globals(g, geometry)
    if rewards && globals.randomnessStabilisationWindow + 1 >= g.epochLength then
      broken(
        s"the reward window ${globals.randomnessStabilisationWindow} leaves no slot of an epoch of ${g.epochLength}"
      )
    def slot(n: BigInt): SlotNo = SlotNo.fromBigInt(n).fold(v => broken(s"slot: $v"), identity)
    eraOf(s.params.protocolMajor) match
      case e: Of[x] =>
        given ShelleyBasedEra[x] = e.era
        val epoch = s.epoch
        // the state is that of the tip `from` (the end of its epoch for a boundary: the future
        // parameters are definite past the point of no return); the result is that of `target`
        val before: WithOrigin[SlotNo] = WithOrigin.At(slot(from(globals, epoch)))
        val after: WithOrigin[SlotNo] = WithOrigin.At(slot(target(globals, epoch)))
        val image =
          mirror.to[x](before, s).run(env).fold(m => broken(s"to the mirror: ${m.show}"), _.value)
        def tick(from: EraNes, to: BigInt): EraNes =
          Hfc
            .tick(globals, eraAt)(from, slot(to))
            .fold(f => broken(s"the new tick refuses: $f"), identity)
        val start = EraNes.InEra(e.era, attributed(image))
        // the first block past the reward window starts the update
        val started =
          if rewards then
            tick(start, globals.firstSlot(epoch) + globals.randomnessStabilisationWindow + 1)
          else start
        tick(started, target(globals, epoch)) match
          case r: EraNes.InEra[y] =>
            given ShelleyBasedEra[y] = r.era
            // the extras are the old state's bookkeeping, which a tick makes stale: its reward draft
            // and registered set (it applies the update or makes its own), the amounts of the
            // update, the pool distribution it kept apart from the snapshots; a state that has been
            // ticked has the pulser (the boundary makes it) whether or not the old state held one,
            // and, past a boundary, previous parameters of its own
            val crossed = r.nes.epoch.toBigInt > epoch
            val extras = image.extras.copy(
              rewardDraft = None,
              rewardRegistered = None,
              rewardAmounts = Map.empty,
              poolDistribution = None,
              pulserAbsent = false,
              prevParamsExplicit = image.extras.prevParamsExplicit || crossed
            )
            def read(e: flat.NodeExtras): Rebuilt =
              mirror
                .rebuilt[y](after, LedgerImage[y](r.nes, e))
                .run(env)
                .fold(m => broken(s"back from the mirror: ${m.show}"), identity)
            // the pool details of the snapshots (owners, relays, metadata) are the old state's own:
            // the flat state of the result takes them from the state it was ticked from, by pool,
            // and the extras the result is read with are the ones that flat state gives
            val first = read(extras)
            val derived = mirror
              .to[y](after, first.state)
              .run(env)
              .fold(m => broken(s"to the mirror: ${m.show}"), _.value.extras)
            // and the amounts of the reward update are the sums of its rewards
            val back = read(
              extras.copy(snapshots = derived.snapshots, rewardAmounts = derived.rewardAmounts)
            )
            val unexpected = back.differing.filterNot(waived)
            if unexpected.nonEmpty then
              broken(s"the result differs from its flat state's mirror in: $unexpected")
            back.state

  private def newBoundary(s: LedgerState, g: LedgerGlobals, rewards: Boolean): LedgerState =
    advance(
      s,
      g,
      rewards,
      (gl, epoch) => gl.firstSlot(epoch + 1) - 1,
      (gl, epoch) => gl.firstSlot(epoch + 1)
    )

  /** The state after the first block past the reward window (the reward update is made in it). */
  private def newRewards(s: LedgerState, g: LedgerGlobals): LedgerState =
    advance(
      s,
      g,
      rewards = true,
      (gl, epoch) => gl.firstSlot(epoch),
      (gl, epoch) => gl.firstSlot(epoch) + gl.randomnessStabilisationWindow + 1
    )

  private def newTick(s: LedgerState, slot: BigInt, g: LedgerGlobals): LedgerState =
    advance(s, g, rewards = false, (gl, epoch) => gl.firstSlot(epoch), (_, _) => slot)

  /** A pre-Conway era: its witness with the evidence of its rules. */
  private final class Pre[E <: EraTag](val era: ShelleyBasedEra[E], val pre: PreConway[E])

  /** The era a protocol version is in (the major version at which each began). */
  private def preConway(pv: BigInt): Option[Pre[?]] =
    if pv <= 2 then Some(Pre(ShelleyBasedEra.SShelley, summon[PreConway[Shelley]]))
    else if pv == 3 then Some(Pre(ShelleyBasedEra.SAllegra, summon[PreConway[Allegra]]))
    else if pv == 4 then Some(Pre(ShelleyBasedEra.SMary, summon[PreConway[Mary]]))
    else if pv <= 6 then Some(Pre(ShelleyBasedEra.SAlonzo, summon[PreConway[Alonzo]]))
    else if pv <= 8 then Some(Pre(ShelleyBasedEra.SBabbage, summon[PreConway[Babbage]]))
    else None

  private def globalsOf(ctx: TxEnv, env: MirrorEnv): Globals =
    Globals(
      LedgerGlobals(
        TestLedger.Supply,
        100,
        Rational(1, 2),
        network = ctx.network,
        updateQuorum = ctx.updateQuorum
      ),
      env.geometry
    )

  /** What the test builders cannot say of a state: a broken conversion is a bug of the test or of
    * the mirror, not a verdict of the rules.
    */
  private def broken(why: String): Nothing = throw new AssertionError(why)

  private def newTx(s: LedgerState, t: Tx, ctx: TxEnv): Either[LedgerFailure, LedgerState] =
    val env = FlatStateGen.env.copy(network = ctx.network, quorum = ctx.updateQuorum)
    val globals = globalsOf(ctx, env)
    preConway(s.params.protocolMajor) match
      case Some(p: Pre[e]) => shelleyTx[e](s, t, ctx, env, globals)(using p.era, p.pre)
      case None            => conwayTx(s, t, ctx, env, globals)

  private def shelleyTx[E <: EraTag](
      s: LedgerState,
      t: Tx,
      ctx: TxEnv,
      env: MirrorEnv,
      globals: Globals
  )(using era: ShelleyBasedEra[E], pre: PreConway[E]): Either[LedgerFailure, LedgerState] =
    val image = mirror.to[E](at, s).run(env).fold(e => broken(s"to the mirror: ${e.show}"), _.value)
    val nes = image.nes
    Sts(ShelleyLedger.sts[E])(
      globals,
      ShelleyLedgerEnv(ctx, nes.es.chainAccount),
      Counted(nes.es.ls, s.plutus),
      t
    ).map { after =>
      // what the rules maintain that the old state derives must be what it derives
      val delegators = Invariants.poolDelegators(pre.cert.get(after.state))
      if delegators.nonEmpty then broken(s"delegators: $delegators")
      val next = image.copy(
        nes = nes.copy(es = nes.es.copy(ls = after.state)),
        extras = image.extras.copy(plutus = after.plutus)
      )
      mirror
        .from[E](at, next)
        .run(env)
        .fold(e => broken(s"back from the mirror: ${e.show}"), identity)
    }

  private def conwayTx(
      s: LedgerState,
      t: Tx,
      ctx: TxEnv,
      env: MirrorEnv,
      globals: Globals
  ): Either[LedgerFailure, LedgerState] =
    val image =
      mirror.to[Conway](at, s).run(env).fold(e => broken(s"to the mirror: ${e.show}"), _.value)
    val nes = image.nes
    Sts(Ledger)(
      globals,
      LedgerEnv(ctx, nes.es.chainAccount.treasury),
      Counted(nes.es.ls, s.plutus),
      t
    ).map { after =>
      // before protocol version 11 the reference does not keep the VRF key hashes of the pools
      // (the hard fork into 11 populates them): the mirror of the old state derives them from
      // version 10, so they are derived here too. From 11 the new side keeps the reference's own
      // map, which is not always the one the pools imply (a re-registration with the pool's own
      // key and then another in the same epoch drops the key in use, `Pool.hs:274-286`; finding
      // J2.6-1, asserted in `ConwayLedgerSuite`); the old state cannot hold it, so a suite that
      // takes the state on to a next transaction goes on from the derived one
      val ls =
        if s.params.protocolMajor >= 10 then
          ConwayLedgerState.pState.modify(vrfFromPools)(after.state)
        else after.state
      val next = image.copy(
        nes = nes.copy(es = nes.es.copy(ls = ls)),
        extras = image.extras.copy(plutus = after.plutus)
      )
      mirror
        .from[Conway](at, next)
        .run(env)
        .fold(e => broken(s"back from the mirror: ${e.show}"), identity)
    }

  /** The VRF key hashes as the mirror derives them: once for each pool that uses a key, as
    * registered or as re-registered.
    */
  private def vrfFromPools(ps: PState): PState =
    val ids = ps.stakePools.keySet ++ ps.future.keySet
    val keys = ids.toList.flatMap { id =>
      (ps.stakePools.get(id).map(_.vrf) ++
        ps.future.get(id).flatMap(f => VRFVerKeyHash.fromBytes(f.vrfKeyHash).toOption)).toSet
    }
    ps.copy(vrfKeyHashes = Sorted.map(keys.groupMapReduce(identity)(_ => 1L)(_ + _).toList.flatMap {
      (k, n) => NonZero.from(Word64.fromUnsigned(n)).toOption.map(k -> _)
    }))
