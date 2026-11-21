// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{Committee, GovActionId, GovActionState, LedgerDefect, TxEnv}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxCert
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, focus}
import xyz.kd5ujc.scarab.ledger.state.{CertState, Dijkstra, EpochNo, EraTag, PState}

// Dijkstra's certificate rules of a sub-transaction as the reference writes them (cardano-ledger
// 9df9f98c, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{SubDeleg,SubPool,SubGovCert,
// SubCert,SubCerts}.hs`). SUBDELEG, SUBPOOL and SUBGOVCERT are the top-level rules' transitions
// (`Conway.conwayDelegTransition`, Dijkstra's `poolTransition`, `Conway.conwayGovCertTransition`)
// at rules whose failure is a newtype over the top-level rule's: each injection of the reference is
// that newtype after the top-level rule's injection, so each transition here is the top-level one
// with its failures and events wrapped. SUBCERT and SUBCERTS are Dijkstra's own, and every injection
// of theirs is total.

/** `newtype DijkstraSubDelegPredFailure era` (`Dijkstra/Rules/SubDeleg.hs:50`, cardano-ledger
  * 9df9f98c): DELEG's failure at Dijkstra.
  */
final case class DijkstraSubDelegPredFailure[E <: EraTag](failure: DelegPredFailure[E])

object DijkstraSubDelegPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubDelegPredFailure[E], DijkstraSubDelegPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubPoolPredFailure era` (`Dijkstra/Rules/SubPool.hs:49`, cardano-ledger
  * 9df9f98c): Dijkstra's POOL's failure.
  */
final case class DijkstraSubPoolPredFailure[E <: EraTag](failure: PoolPredFailure[E])

object DijkstraSubPoolPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubPoolPredFailure[E], DijkstraSubPoolPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubPoolEvent era` (`Dijkstra/Rules/SubPool.hs:69`, cardano-ledger 9df9f98c). */
final case class DijkstraSubPoolEvent[E <: EraTag](event: PoolRuleEvent[E])

object DijkstraSubPoolEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubPoolEvent[E], DijkstraSubPoolEvent[E]] =
    CanEqual.derived

/** `newtype DijkstraSubGovCertPredFailure era` (`Dijkstra/Rules/SubGovCert.hs:53-54`,
  * cardano-ledger 9df9f98c): Dijkstra's GOVCERT's failure.
  */
final case class DijkstraSubGovCertPredFailure[E <: EraTag](failure: GovCertPredFailure[E])

object DijkstraSubGovCertPredFailure:
  given [E <: EraTag]: CanEqual[
    DijkstraSubGovCertPredFailure[E],
    DijkstraSubGovCertPredFailure[E]
  ] = CanEqual.derived

/** `data DijkstraSubCertPredFailure era` (`Dijkstra/Rules/SubCert.hs:61-65`, cardano-ledger
  * 9df9f98c), its constructors in the reference's order; their tags are 1 to 3 (`:103-107`).
  */
enum DijkstraSubCertPredFailure[E <: EraTag]:
  case SubDelegFailure(failure: SubDelegPredFailure[E])
  case SubPoolFailure(failure: SubPoolPredFailure[E])
  case SubGovCertFailure(failure: SubGovCertPredFailure[E])

object DijkstraSubCertPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubCertPredFailure[E], DijkstraSubCertPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubCertEvent era` (`Dijkstra/Rules/SubCert.hs:144`, cardano-ledger 9df9f98c):
  * SUBPOOL's events, SUBDELEG's and SUBGOVCERT's having none.
  */
enum DijkstraSubCertEvent[E <: EraTag]:
  case SubPoolEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.SubPoolEvent[E])

object DijkstraSubCertEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubCertEvent[E], DijkstraSubCertEvent[E]] =
    CanEqual.derived

/** `newtype DijkstraSubCertsPredFailure era` (`Dijkstra/Rules/SubCerts.hs:54`, cardano-ledger
  * 9df9f98c), whose encoding is SUBCERT's (`deriving newtype`).
  */
final case class DijkstraSubCertsPredFailure[E <: EraTag](failure: SubCertPredFailure[E])

object DijkstraSubCertsPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraSubCertsPredFailure[E], DijkstraSubCertsPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraSubCertsEvent era` (`Dijkstra/Rules/SubCerts.hs:96`, cardano-ledger 9df9f98c).
  */
final case class DijkstraSubCertsEvent[E <: EraTag](event: SubCertEvent[E])

object DijkstraSubCertsEvent:
  given [E <: EraTag]: CanEqual[DijkstraSubCertsEvent[E], DijkstraSubCertsEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "SUBDELEG" era` (`Dijkstra/Rules/SubDeleg.hs:53`, cardano-ledger 9df9f98c). */
type SubDelegPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubDelegPredFailure[Dijkstra]

/** `EraRuleFailure "SUBPOOL" era` (`Dijkstra/Rules/SubPool.hs:52`, cardano-ledger 9df9f98c). */
type SubPoolPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubPoolPredFailure[Dijkstra]

/** `EraRuleEvent "SUBPOOL" era` (`Dijkstra/Rules/SubPool.hs:54`, cardano-ledger 9df9f98c). */
type SubPoolEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubPoolEvent[Dijkstra]

/** `EraRuleFailure "SUBGOVCERT" era` (`Dijkstra/Rules/SubGovCert.hs:57`, cardano-ledger 9df9f98c).
  */
type SubGovCertPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubGovCertPredFailure[Dijkstra]

/** `EraRuleFailure "SUBCERT" era` (`Dijkstra/Rules/SubCert.hs:124`, cardano-ledger 9df9f98c). */
type SubCertPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubCertPredFailure[Dijkstra]

/** `EraRuleEvent "SUBCERT" era` (`Dijkstra/Rules/SubCert.hs:126`, cardano-ledger 9df9f98c). */
type SubCertEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubCertEvent[Dijkstra]

/** `EraRuleFailure "SUBCERTS" era` (`Dijkstra/Rules/SubCerts.hs:82`, cardano-ledger 9df9f98c). */
type SubCertsPredFailure[E <: EraTag] = E match
  case Dijkstra => DijkstraSubCertsPredFailure[Dijkstra]

/** `EraRuleEvent "SUBCERTS" era` (`Dijkstra/Rules/SubCerts.hs:84`, cardano-ledger 9df9f98c). */
type SubCertsEvent[E <: EraTag] = E match
  case Dijkstra => DijkstraSubCertsEvent[Dijkstra]

/** `InjectRuleFailure "SUBDELEG" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubDelegFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubDelegPredFailure[E]

object InjectSubDelegFailure:
  /** `Dijkstra/Rules/SubDeleg.hs:60`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubDelegFailure[DijkstraSubDelegPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubDelegPredFailure[Dijkstra]
    ): SubDelegPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubDeleg.hs:62-63`, cardano-ledger 9df9f98c: the newtype. */
  given dijkstraConway: InjectSubDelegFailure[ConwayDelegPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayDelegPredFailure[Dijkstra]): SubDelegPredFailure[Dijkstra] =
      DijkstraSubDelegPredFailure(failure)

  /** `Dijkstra/Rules/SubDeleg.hs:57-58`, cardano-ledger 9df9f98c: the newtype of Conway's
    * `DelegAccountAlreadyRegistered` (`StakeKeyRegisteredDELEG` here), as DELEG's injection is
    * ([[InjectDelegFailure.dijkstra]]).
    */
  given dijkstraShelley: InjectSubDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra] with
    def injectFailure(failure: AccountAlreadyRegistered[Dijkstra]): SubDelegPredFailure[Dijkstra] =
      DijkstraSubDelegPredFailure(
        ConwayDelegPredFailure.StakeKeyRegisteredDELEG(failure.credential)
      )

/** `InjectRuleFailure "SUBPOOL" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubPoolFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubPoolPredFailure[E]

object InjectSubPoolFailure:
  /** `Dijkstra/Rules/SubPool.hs:56`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubPoolFailure[DijkstraSubPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraSubPoolPredFailure[Dijkstra]): SubPoolPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/SubPool.hs:61-62`, cardano-ledger 9df9f98c: the newtype. */
  given dijkstraPool: InjectSubPoolFailure[DijkstraPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraPoolPredFailure[Dijkstra]): SubPoolPredFailure[Dijkstra] =
      DijkstraSubPoolPredFailure(failure)

  /** `Dijkstra/Rules/SubPool.hs:58-59`, cardano-ledger 9df9f98c: the newtype after POOL's injection
    * ([[InjectPool.dijkstra]]).
    */
  given dijkstraShelley: InjectSubPoolFailure[ShelleyPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ShelleyPoolPredFailure[Dijkstra]): SubPoolPredFailure[Dijkstra] =
      DijkstraSubPoolPredFailure(InjectPool.dijkstra.injectFailure(failure))

/** `InjectRuleFailure "SUBGOVCERT" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubGovCertFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubGovCertPredFailure[E]

object InjectSubGovCertFailure:
  /** `Dijkstra/Rules/SubGovCert.hs:64`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubGovCertFailure[DijkstraSubGovCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubGovCertPredFailure[Dijkstra]
    ): SubGovCertPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubGovCert.hs:66-67`, cardano-ledger 9df9f98c: the newtype. */
  given dijkstraGovCert: InjectSubGovCertFailure[DijkstraGovCertPredFailure[Dijkstra], Dijkstra]
  with
    def injectFailure(
        failure: DijkstraGovCertPredFailure[Dijkstra]
    ): SubGovCertPredFailure[Dijkstra] = DijkstraSubGovCertPredFailure(failure)

  /** `Dijkstra/Rules/SubGovCert.hs:61-62`, cardano-ledger 9df9f98c: the newtype after
    * `conwayToDijkstraGovCertPredFailure`, GOVCERT's injection ([[InjectGovCert.dijkstra]]).
    */
  given dijkstraConway: InjectSubGovCertFailure[ConwayGovCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: ConwayGovCertPredFailure[Dijkstra]
    ): SubGovCertPredFailure[Dijkstra] =
      DijkstraSubGovCertPredFailure(InjectGovCert.dijkstra.injectFailure(failure))

/** `InjectRuleFailure "SUBCERT" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubCertFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubCertPredFailure[E]

object InjectSubCertFailure:
  /** `Dijkstra/Rules/SubCert.hs:128`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubCertFailure[DijkstraSubCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraSubCertPredFailure[Dijkstra]): SubCertPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/SubCert.hs:130-131`, cardano-ledger 9df9f98c. */
  given dijkstraSubDeleg: InjectSubCertFailure[DijkstraSubDelegPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubDelegPredFailure[Dijkstra]
    ): SubCertPredFailure[Dijkstra] = DijkstraSubCertPredFailure.SubDelegFailure(failure)

  /** `Dijkstra/Rules/SubCert.hs:133-134`, cardano-ledger 9df9f98c. */
  given dijkstraSubPool: InjectSubCertFailure[DijkstraSubPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraSubPoolPredFailure[Dijkstra]): SubCertPredFailure[Dijkstra] =
      DijkstraSubCertPredFailure.SubPoolFailure(failure)

  /** `Dijkstra/Rules/SubCert.hs:136-137`, cardano-ledger 9df9f98c. */
  given dijkstraSubGovCert: InjectSubCertFailure[
    DijkstraSubGovCertPredFailure[Dijkstra],
    Dijkstra
  ] with
    def injectFailure(
        failure: DijkstraSubGovCertPredFailure[Dijkstra]
    ): SubCertPredFailure[Dijkstra] = DijkstraSubCertPredFailure.SubGovCertFailure(failure)

  /** `Dijkstra/Rules/SubCert.hs:139-140`, cardano-ledger 9df9f98c:
    * [[DijkstraSUBCERT.conwayToDijkstraSubCertPredFailure]].
    */
  given dijkstraConway: InjectSubCertFailure[ConwayCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayCertPredFailure[Dijkstra]): SubCertPredFailure[Dijkstra] =
      DijkstraSUBCERT.conwayToDijkstraSubCertPredFailure(failure)

/** `InjectRuleFailure "SUBCERTS" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c). */
trait InjectSubCertsFailure[F, E <: EraTag]:
  def injectFailure(failure: F): SubCertsPredFailure[E]

object InjectSubCertsFailure:
  /** `Dijkstra/Rules/SubCerts.hs:86`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectSubCertsFailure[DijkstraSubCertsPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubCertsPredFailure[Dijkstra]
    ): SubCertsPredFailure[Dijkstra] = failure

  /** `Dijkstra/Rules/SubCerts.hs:88-89`, cardano-ledger 9df9f98c: `SubCertFailure`. */
  given dijkstraSubCert: InjectSubCertsFailure[DijkstraSubCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraSubCertPredFailure[Dijkstra]
    ): SubCertsPredFailure[Dijkstra] = DijkstraSubCertsPredFailure(failure)

  /** `Dijkstra/Rules/SubCerts.hs:91-92`, cardano-ledger 9df9f98c:
    * [[DijkstraSUBCERTS.conwayToDijkstraSubCertsPredFailure]].
    */
  given dijkstraConway: InjectSubCertsFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayCertsPredFailure[Dijkstra]): SubCertsPredFailure[Dijkstra] =
      DijkstraSUBCERTS.conwayToDijkstraSubCertsPredFailure(failure)

/** SUBDELEG (`Dijkstra/Rules/SubDeleg.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBDELEG:
  /** `transitionRules = [Conway.conwayDelegTransition]` (`Dijkstra/Rules/SubDeleg.hs:81`,
    * cardano-ledger 9df9f98c): DELEG's transition at Dijkstra, its failures in SUBDELEG's newtype,
    * which is SUBDELEG's every injection after DELEG's.
    */
  def subDelegTransition[F[_]: Monad](env: ConwayDelegEnv[Dijkstra], cert: ConwayDelegCert)(using
      st: Stateful[F, CertState[Dijkstra]],
      judge: Judge[F, SubDelegPredFailure[Dijkstra]]
  ): F[Unit] =
    DELEG.conwayDelegTransition[Dijkstra, F](env, cert)(using
      summon[Monad[F]],
      EraPParams.dijkstra,
      EraCertState.dijkstra,
      InjectDeleg.dijkstra,
      st,
      wrapped(judge)(DijkstraSubDelegPredFailure[Dijkstra](_))
    )

/** SUBPOOL (`Dijkstra/Rules/SubPool.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBPOOL:
  /** `transitionRules = [poolTransition]` (`Dijkstra/Rules/SubPool.hs:89`, cardano-ledger
    * 9df9f98c): Dijkstra's POOL's transition, its failures and events in SUBPOOL's newtypes, which
    * are SUBPOOL's every injection after POOL's. POOL's defects are raised as they are.
    */
  def subPoolTransition[F[_]: Monad](
      env: PoolEnv[Dijkstra],
      poolCert: PoolCert,
      validation: TxEnv.Validation
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, SubPoolPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubPoolEvent[Dijkstra]]]
  ): F[Unit] =
    DijkstraPOOL.poolTransition[Dijkstra, F](env, poolCert, validation)(using
      summon[Monad[F]],
      EraPParams.dijkstra,
      InjectDijkstraPool.dijkstra,
      ask,
      st,
      defect,
      poolDefect,
      wrapped(judge)(DijkstraSubPoolPredFailure[Dijkstra](_)),
      told(events)(DijkstraSubPoolEvent[Dijkstra](_))
    )

/** SUBGOVCERT (`Dijkstra/Rules/SubGovCert.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBGOVCERT:
  /** `transitionRules = [Conway.conwayGovCertTransition]` (`Dijkstra/Rules/SubGovCert.hs:86`,
    * cardano-ledger 9df9f98c): GOVCERT's transition at Dijkstra, its failures in SUBGOVCERT's
    * newtype, which is SUBGOVCERT's every injection after GOVCERT's.
    */
  def subGovCertTransition[F[_]: Monad](env: ConwayGovCertEnv[Dijkstra], cert: ConwayGovCert)(using
      st: Stateful[F, CertState[Dijkstra]],
      judge: Judge[F, SubGovCertPredFailure[Dijkstra]]
  ): F[Unit] =
    GOVCERT.conwayGovCertTransition[Dijkstra, F](env, cert)(using
      summon[Monad[F]],
      EraPParams.dijkstra,
      EraCertState.dijkstra,
      InjectGovCert.dijkstra,
      st,
      wrapped(judge)(DijkstraSubGovCertPredFailure[Dijkstra](_))
    )

/** SUBCERT (`Dijkstra/Rules/SubCert.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBCERT:
  /** `conwayToDijkstraSubCertPredFailure` (`Dijkstra/Rules/SubCert.hs:233-246`, cardano-ledger
    * 9df9f98c): each of CERT's failures as SUBCERT's, its rule's failure injected into the
    * sub-rule's.
    */
  def conwayToDijkstraSubCertPredFailure(
      failure: ConwayCertPredFailure[Dijkstra]
  ): DijkstraSubCertPredFailure[Dijkstra] = failure match
    case ConwayCertPredFailure.DelegFailure(f) =>
      DijkstraSubCertPredFailure.SubDelegFailure(
        InjectSubDelegFailure.dijkstraConway.injectFailure(f)
      )
    case ConwayCertPredFailure.PoolFailure(f) =>
      DijkstraSubCertPredFailure.SubPoolFailure(InjectSubPoolFailure.dijkstraPool.injectFailure(f))
    case ConwayCertPredFailure.GovCertFailure(f) =>
      DijkstraSubCertPredFailure.SubGovCertFailure(
        InjectSubGovCertFailure.dijkstraGovCert.injectFailure(f)
      )

  private val c = EraCertState.dijkstra

  /** `dijkstraSubCertTransition` (`Dijkstra/Rules/SubCert.hs:187-202`, cardano-ledger 9df9f98c):
    * one certificate, to the sub-rule of its kind: SUBDELEG on the certificate state, the
    * delegation certificate made Conway's (`dijkstraToConwayDelegCert`); SUBPOOL on the pool state
    * alone, under the policy `validation`; SUBGOVCERT on the certificate state. Each is a sub-rule
    * whose failures and events SUBCERT's embed (`SubCert.hs:204-231`); SUBPOOL's defects are raised
    * as they are.
    */
  def subCertTransition[F[_]: Monad](
      env: CertEnv[Dijkstra],
      txCert: DijkstraTxCert,
      validation: TxEnv.Validation
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[Dijkstra]],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, SubCertPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubCertEvent[Dijkstra]]]
  ): F[Unit] =
    st.get.flatMap { certState =>
      val pools = c.certPStateL.get(certState).stakePools
      txCert match
        case DijkstraTxCert.DijkstraTxCertDeleg(delegCert) =>
          judge.nest(
            DijkstraSUBDELEG.subDelegTransition[F](
              ConwayDelegEnv[Dijkstra](env.pp, pools),
              DijkstraTxCert.dijkstraToConwayDelegCert(delegCert)
            )(using
              summon[Monad[F]],
              st,
              wrapped(judge)(DijkstraSubCertPredFailure.SubDelegFailure[Dijkstra](_))
            )
          )
        case DijkstraTxCert.DijkstraTxCertPool(poolCert) =>
          judge.nest(
            DijkstraSUBPOOL.subPoolTransition[F](
              PoolEnv[Dijkstra](env.currentEpoch, env.pp),
              poolCert,
              validation
            )(using
              summon[Monad[F]],
              ask,
              st.focus(c.certPStateL),
              defect,
              poolDefect,
              wrapped(judge)(DijkstraSubCertPredFailure.SubPoolFailure[Dijkstra](_)),
              told(events)(DijkstraSubCertEvent.SubPoolEvent[Dijkstra](_))
            )
          )
        case DijkstraTxCert.DijkstraTxCertGov(govCertificate) =>
          judge.nest(
            DijkstraSUBGOVCERT.subGovCertTransition[F](
              ConwayGovCertEnv[Dijkstra](
                env.pp,
                env.currentEpoch,
                env.currentCommittee,
                env.committeeProposals
              ),
              govCertificate
            )(using
              summon[Monad[F]],
              st,
              wrapped(judge)(DijkstraSubCertPredFailure.SubGovCertFailure[Dijkstra](_))
            )
          )
    }

/** `SubCertsEnv` (`Dijkstra/Rules/SubCerts.hs:156-163`, cardano-ledger 9df9f98c): the
  * sub-transaction, the parameters, the epoch, and the committee and the proposals to update it
  * (the node's records, as in [[CertsEnv]]). No rule reads the sub-transaction. Of Dijkstra, the
  * one era with sub-transactions.
  */
final case class SubCertsEnv(
    tx: Tx[TxLevel.Sub, Dijkstra],
    pp: PParams[Dijkstra],
    currentEpoch: EpochNo,
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

/** SUBCERTS (`Dijkstra/Rules/SubCerts.hs`, cardano-ledger 9df9f98c). */
object DijkstraSUBCERTS:
  /** `conwayToDijkstraSubCertsPredFailure` (`Dijkstra/Rules/SubCerts.hs:183-190`, cardano-ledger
    * 9df9f98c): CERT's failure in CERTS's as SUBCERT's. The 11.1 build's withdrawals' case has no
    * value at Dijkstra ([[CertsWithdrawals]]), as its type at 9df9f98c has `CertFailure` alone.
    */
  def conwayToDijkstraSubCertsPredFailure(
      failure: ConwayCertsPredFailure[Dijkstra]
  ): DijkstraSubCertsPredFailure[Dijkstra] = failure match
    case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(ws) => ws
    case ConwayCertsPredFailure.CertFailure(f) =>
      DijkstraSubCertsPredFailure(InjectSubCertFailure.dijkstraConway.injectFailure(f))

  /** `dijkstraSubCertsTransition` (`Dijkstra/Rules/SubCerts.hs:130-143`, cardano-ledger 9df9f98c):
    * by recursion on the prefix (`gamma :|> txCert`), SUBCERTS of all but the last certificate as a
    * sub-rule, then SUBCERT of the last, so the first certificate is the deepest; the empty
    * sequence leaves the state as it is. The recursion is a fold, each step a program over the
    * previous, as CERTS's is ([[CERTS.conwayCertsTransition]]), which the interpreter runs in
    * constant stack. SUBCERT's failures and events are embedded in SUBCERTS's
    * (`SubCerts.hs:145-153`).
    */
  def subCertsTransition[F[_]: Monad](
      env: SubCertsEnv,
      certificates: Vector[DijkstraTxCert],
      validation: TxEnv.Validation
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[Dijkstra]],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      judge: Judge[F, SubCertsPredFailure[Dijkstra]],
      events: Tell[F, Chain[SubCertsEvent[Dijkstra]]]
  ): F[Unit] =
    val certEnv =
      CertEnv[Dijkstra](env.pp, env.currentEpoch, env.currentCommittee, env.committeeProposals)
    certificates.foldLeft(Monad[F].unit)((gamma, txCert) =>
      judge.nest(gamma) *>
        judge.nest(
          DijkstraSUBCERT.subCertTransition[F](certEnv, txCert, validation)(using
            summon[Monad[F]],
            ask,
            st,
            defect,
            poolDefect,
            wrapped(judge)(DijkstraSubCertsPredFailure[Dijkstra](_)),
            told(events)(DijkstraSubCertsEvent[Dijkstra](_))
          )
        )
    )
