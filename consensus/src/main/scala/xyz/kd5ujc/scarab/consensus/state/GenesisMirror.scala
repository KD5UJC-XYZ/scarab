// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.ConsensusExtras
import xyz.kd5ujc.scarab.ledger.PlutusStats
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{NodeExtras, WithOrigin}
import xyz.kd5ujc.scarab.protocol.StateLayout

/** The mirror at the origin of a chain whose first era is Shelley-based: the extended ledger state
  * the reference starts such a chain from (`injectInitialExtLedgerState`, ouroboros-consensus
  * 82ecba32 `HardFork/Combinator/Embed/Nary.hs:272-328`). The ledger telescope has every era before
  * the first one past and ending where it starts, and the chain-dependent state is aligned with it;
  * the tip is the origin and no block has been made after the voting deadline
  * (`ShelleyTransitionInfo{shelleyAfterVoting = 0}`, `CanHardFork.hs:361`).
  *
  * A chain that starts in Byron starts from [[ByronMirror]].
  *
  * What the consensus layer adds is in the layout of the start state's ledger ([[originExt]]).
  */
object GenesisMirror:

  /** The extended state's part that only the Leios layout has, as the chain's origin holds it: a
    * Peras state of no epoch context and no certificate (`initPerasEpochContextResolver` at the
    * origin, ouroboros-consensus 2416493b `Peras/Context.hs:686-698`, and the Byron start's
    * `SNothing`, `HardFork/Combinator/Embed/Nary.hs:347-357`, `Ledger/Peras.hs:56-71`). The bounds
    * of the origin are the era history's in the layout ([[EraHistory.summary]]), the transaction
    * bytes none yet ([[ShelleyLedgerState.Layout.atOrigin]]).
    */
  private[state] def originExt(layout: StateLayout): ExtLedgerState.Layout = layout match
    case StateLayout.Node11_1 => ExtLedgerState.Layout.Node11_1
    case StateLayout.Leios => ExtLedgerState.Layout.Leios(PerasState(PerasResolver.Disabled, None))

  /** The mirror of the ledger state `start` (the state of the era the chain starts in), with the
    * chain-dependent state `dep` of that era's protocol and the node's bookkeeping `consensus`.
    */
  def of(
      env: ConsensusMirrorEnv,
      start: EraNes,
      dep: ChainDepState,
      consensus: ConsensusExtras
  ): Either[StateMirrorError, MirrorState] =
    start match
      case i: EraNes.InEra[e] =>
        given xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra[e] = i.era
        val era = EraNes.eraOf(i.era)
        val layout = i.nes.layout.stateLayout
        val summaries =
          EraHistory.summary(
            env.ledger.geometry,
            env.byron,
            env.forks,
            env.observed,
            era,
            0,
            layout
          )
        for
          _ <- Either.cond(
            summon[EraIndexed[ChainDepState]].admits(era, dep),
            (),
            StateMirrorError.ChainDepFamily(era)
          )
          past <- summaries.zipWithIndex
            .take(era.index)
            .traverse((summary, index) =>
              summary.end
                .toRight(StateMirrorError.OpenPastEra(index))
                .map(end => Past(summary.start, end))
            )
          current <- summaries.lift(era.index).toRight(StateMirrorError.MissingEra(era))
          ledger <- Telescope
            .make(
              past,
              Current(
                current.start,
                EraLedgerState.inEra[e](
                  ShelleyLedgerState[e](
                    WithOrigin.Origin,
                    i.nes,
                    ShelleyTransition(Word32.fromUnsigned(0)),
                    ShelleyLedgerState.Layout.atOrigin(i.nes.layout.stateLayout)
                  )
                )
              )
            )
            .leftMap(StateMirrorError.Telescope(_))
          header <- Telescope
            .make(past, Current(current.start, dep))
            .leftMap(StateMirrorError.Telescope(_))
        yield MirrorState(
          ExtLedgerState(
            ledger,
            HeaderState(WithOrigin.Origin, header),
            originExt(layout)
          ),
          MirrorExtras.InShelley(NodeExtras(PlutusStats.zero), consensus),
          MirrorProvenance.complete
        )
