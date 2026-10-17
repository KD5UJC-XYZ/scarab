// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.arrow.FunctionK
import org.scalacheck.Arbitrary
import org.scalacheck.Prop.{forAll, propBoolean}
import org.typelevel.discipline.Laws
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  MirrorExtras,
  StateMirror as MirrorOf,
  StateMirrorError
}
import xyz.kd5ujc.scarab.ledger.state.Gap

/** The laws of the frozen conversion [[StateMirror]] over any effect `F` that has the capabilities
  * (`run` interprets it). The flat state of the old rules is brought to the mirror and not back
  * (the older snapshots are migrated, and nothing converts the other way), so the laws state what the
  * mirror says of the state it was made of:
  *
  *   - the anchor is the tip of the header state, and the era the state is in is the era of the
  *     telescopes;
  *   - the kind of extras is the kind of the ledger state (Byron's, or a Shelley-based era's), and a
  *     Shelley-based era's hold the phase-2 counters of the state;
  *   - the gaps are exactly the ones [[StateMirror.gaps]] says, and the Shelley transition is one
  *     exactly when the mirror has a Shelley-based state.
  *
  * (The ledger side, [[LedgerMirror]], is held to retraction by `MirrorLaws`, which reads its image
  * back through the test support [[LedgerMirrorBack]].)
  */
final class StateMirrorLaws[F[_]](
    mirror: StateMirror[F],
    run: FunctionK[F, [A] =>> Either[StateMirrorError, A]],
    env: ConsensusMirrorEnv
) extends Laws:

  private def to(l: Located) = run(mirror.toMirror(l.anchor, l.state))

  def laws(using located: Arbitrary[Located]): RuleSet =
    new SimpleRuleSet(
      "state mirror",
      "the anchor is the tip, and the telescopes are in the era of the state" -> forAll {
        (l: Located) =>
          val result = to(l)
          result.exists { m =>
            MirrorOf.anchorOf(m.value.ext.headerState.tip) == l.anchor &&
            m.value.ext.ledgerState.era == StateMirror.eraOf(env, l.state) &&
            m.value.ext.headerState.chainDep.era == m.value.ext.ledgerState.era
          } :| s"$result"
      },
      "the extras are of the kind of the ledger state and hold the counters of the state" ->
        forAll { (l: Located) =>
          val result = to(l)
          result.exists { m =>
            (m.value.ext.ledgerState.state, m.value.extras) match
              case (_: EraLedgerState.ByronCurrent, _: MirrorExtras.InByron) => true
              case (_: EraLedgerState.InShelleyEra[?], x: MirrorExtras.InShelley) =>
                x.ledger.plutus == l.state.ledger.plutus
              case _ => false
          } :| s"$result"
        },
      "the gaps are the ones the state has, the transition when there is a Shelley-based state" ->
        forAll { (l: Located) =>
          val result = to(l)
          result.exists { m =>
            val shelley = m.value.extras match
              case _: MirrorExtras.InShelley => true
              case _: MirrorExtras.InByron   => false
            m.gaps == StateMirror.gaps(env, l.state) && m.gaps.contains(
              Gap.ShelleyTransition
            ) == shelley
          } :| s"$result"
        }
    )
