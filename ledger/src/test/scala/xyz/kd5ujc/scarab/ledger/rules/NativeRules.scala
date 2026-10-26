// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  LedgerFailure,
  LedgerGlobals,
  PlutusStats,
  TestLedger,
  Tx,
  TxEnv,
  TxIn,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** The era-indexed rules as the rule suites run them, on the native state they build: the LEDGER
  * rules on a transaction, and the tick (with the epoch boundaries and the era translations on the
  * way) to a slot. A refusal is the typed [[LedgerFailure]] of the rules; a tick never fails on the
  * small states of a suite, so its failure is a broken test.
  *
  * The era a state is in is the one its protocol version names, as the hard-fork configuration of
  * the suites says ([[eraAt]]).
  */
object NativeRules:
  /** What a test cannot run: a failure of the test itself, never a verdict of the rules. */
  def broken(why: String): Nothing = throw new AssertionError(why)

  /** The era of a protocol version (the major version at which each began). */
  def eraOf(major: BigInt): Era =
    if major <= 2 then Era.Shelley
    else if major == 3 then Era.Allegra
    else if major == 4 then Era.Mary
    else if major <= 6 then Era.Alonzo
    else if major <= 8 then Era.Babbage
    else Era.Conway

  /** The hard-fork configuration of the suites: each era from its protocol version. */
  val eraAt: Hfc.EraAt = (_, major) => eraOf(major)

  /** The state of a suite: an empty chain in epoch 5 with the test parameters of `protocolMajor` in
    * force, `funds` as its UTxO set and the reserves what makes the total [[TestLedger.Supply]].
    */
  def state(protocolMajor: Int, funds: Vector[(TxIn, TxOut)]): EraNes =
    def inEra[E <: EraTag](era: ShelleyBasedEra[E]): EraNes =
      EraNes.InEra(era, stateIn[E](protocolMajor, funds)(using era))
    eraOf(protocolMajor) match
      case Era.Shelley => inEra(ShelleyBasedEra.SShelley)
      case Era.Allegra => inEra(ShelleyBasedEra.SAllegra)
      case Era.Mary    => inEra(ShelleyBasedEra.SMary)
      case Era.Alonzo  => inEra(ShelleyBasedEra.SAlonzo)
      case Era.Babbage => inEra(ShelleyBasedEra.SBabbage)
      case Era.Conway  => inEra(ShelleyBasedEra.SConway)
      case Era.Byron   => broken(s"protocol version $protocolMajor is not of a Shelley-based era")

  /** [[state]] in the era `E`. */
  def stateIn[E <: EraTag](protocolMajor: Int, funds: Vector[(TxIn, TxOut)])(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val nes = Build.funded(Build.nes[E](5, TestLedger.params(protocolMajor)), funds.toMap)
    val reserves = Coin(TestLedger.Supply - nes.es.ls.utxo.utxo.utxo.coin)
    nes.copy(es = nes.es.copy(chainAccount = nes.es.chainAccount.copy(reserves = reserves)))

  /** The constants of the rules for a transaction context: [[TestLedger.globals]] on its network
    * and with its update quorum.
    */
  def globalsOf(ctx: TxEnv): Globals =
    TestLedger.globals.copy(ledger =
      TestLedger.globals.ledger.copy(network = ctx.network, updateQuorum = ctx.updateQuorum)
    )

  /** The LEDGER rules of the era on one transaction: the state after it, or its refusal. */
  def tx(
      e: EraNes,
      t: Tx,
      ctx: TxEnv,
      plutus: PlutusStats = PlutusStats.zero
  ): Either[LedgerFailure, Counted[EraNes]] =
    e match
      case i: EraNes.InEra[x] =>
        given ShelleyBasedEra[x] = i.era
        txIn[x](i.nes, t, ctx, plutus).map(c => Counted(EraNes.InEra(i.era, c.state), c.plutus))

  /** [[tx]] in the era `E`. */
  def txIn[E <: EraTag](
      nes: NewEpochState[E],
      t: Tx,
      ctx: TxEnv,
      plutus: PlutusStats = PlutusStats.zero
  )(using era: ShelleyBasedEra[E]): Either[LedgerFailure, Counted[NewEpochState[E]]] =
    val globals = globalsOf(ctx)
    def after(c: Counted[LedgerState[E]]) =
      Counted(nes.copy(es = nes.es.copy(ls = c.state)), c.plutus)
    era match
      case ShelleyBasedEra.SConway =>
        Sts(Ledger)(
          globals,
          LedgerEnv(ctx, nes.es.chainAccount.treasury),
          Counted(nes.es.ls, plutus),
          t
        ).map(after)
      case ShelleyBasedEra.SShelley =>
        shelley[E](nes, t, ctx, plutus, globals)(using era, summon[PreConway[Shelley]]).map(after)
      case ShelleyBasedEra.SAllegra =>
        shelley[E](nes, t, ctx, plutus, globals)(using era, summon[PreConway[Allegra]]).map(after)
      case ShelleyBasedEra.SMary =>
        shelley[E](nes, t, ctx, plutus, globals)(using era, summon[PreConway[Mary]]).map(after)
      case ShelleyBasedEra.SAlonzo =>
        shelley[E](nes, t, ctx, plutus, globals)(using era, summon[PreConway[Alonzo]]).map(after)
      case ShelleyBasedEra.SBabbage =>
        shelley[E](nes, t, ctx, plutus, globals)(using era, summon[PreConway[Babbage]]).map(after)

  private def shelley[E <: EraTag](
      nes: NewEpochState[E],
      t: Tx,
      ctx: TxEnv,
      plutus: PlutusStats,
      globals: Globals
  )(using ShelleyBasedEra[E], PreConway[E]): Either[LedgerFailure, Counted[LedgerState[E]]] =
    Sts(ShelleyLedger.sts[E])(
      globals,
      ShelleyLedgerEnv(ctx, nes.es.chainAccount),
      Counted(nes.es.ls, plutus),
      t
    )

  /** The rules' constants with the epoch length and the reward window of `g`: the security
    * parameter is the one that makes the reward window (4k/f) what `startRewards` says (2 where it
    * says nothing).
    */
  def globals(g: LedgerGlobals): Globals =
    val f = BigDecimal(g.activeSlotsCoeff.numerator) / BigDecimal(g.activeSlotsCoeff.denominator)
    val k =
      if g.startRewards.signum == 0 then 2
      else (BigDecimal(g.startRewards) * f / 4).setScale(0, BigDecimal.RoundingMode.CEILING).toInt
    val geometry = EpochGeometry(java.time.Instant.EPOCH, 1000L, g.epochLength.toLong, k, f)
    if g.startRewards.signum != 0 && geometry.randomnessStabilisationWindow != g.startRewards then
      broken(s"no security parameter makes the reward window ${g.startRewards} of $f")
    Globals(g, geometry)

  /** The constants the boundary suites use by default: [[TestLedger.globals]]' ledger. */
  val ledgerGlobals: LedgerGlobals =
    LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2)

  /** The state after the tick to `slot` (the boundaries and translations on the way). */
  def tick(e: EraNes, slot: BigInt, g: LedgerGlobals = ledgerGlobals): EraNes =
    SlotNo
      .fromBigInt(slot)
      .left
      .map(v => s"$v")
      .flatMap(s => Hfc.tick(globals(g), eraAt)(e, s).left.map(f => s"$f"))
      .fold(why => broken(s"the tick refuses: $why"), identity)

  /** The state one epoch later, as the boundary into the next epoch makes it. The state's own
    * reward update, if it has one, is applied; none is made.
    */
  def boundary(e: EraNes, g: LedgerGlobals = ledgerGlobals): EraNes =
    tick(e, globals(g).firstSlot(e.epoch.toBigInt + 1), g)

  /** The state after the first block past the reward window of its epoch (RUPD makes the epoch's
    * reward update in it).
    */
  def rewards(e: EraNes, g: LedgerGlobals = ledgerGlobals): EraNes =
    val gl = globals(g)
    if gl.randomnessStabilisationWindow + 1 >= g.epochLength then
      broken(s"the reward window ${gl.randomnessStabilisationWindow} leaves no slot")
    tick(e, gl.firstSlot(e.epoch.toBigInt) + gl.randomnessStabilisationWindow + 1, g)

  /** The state one epoch later with the epoch's rewards: the reward update made in the epoch and
    * applied at the boundary. The state must not have one already.
    */
  def epoch(e: EraNes, g: LedgerGlobals = ledgerGlobals): EraNes =
    boundary(rewards(e, g), g)

  /** Every outstanding deposit of a state: of the accounts, the pools, the DReps and the proposals.
    */
  def obligation[E <: EraTag](nes: NewEpochState[E])(using era: ShelleyBasedEra[E]): BigInt =
    val ls = nes.es.ls
    val accounts = EraLedger.keyDeposits(ls)
    val pools = LedgerState.pState[E].get(ls).stakePools.valuesIterator.map(_.deposit.toBigInt).sum
    val governance = era match
      case ShelleyBasedEra.SConway =>
        nes.es.ls.cert.vState.dreps.valuesIterator.map(_.deposit.toBigInt).sum +
          nes.es.ls.utxo.gov.proposals.totalDeposits
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        BigInt(0)
    accounts + pools + governance
