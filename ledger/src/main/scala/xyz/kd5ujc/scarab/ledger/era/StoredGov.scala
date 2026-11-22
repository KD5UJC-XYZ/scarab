// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, EpochState, LedgerState, UTxOState}

/** Why the node's flat record of a Conway governance state does not read into Dijkstra's
  * [[ConwayGovState]] where the chain enters Dijkstra (`translateEra`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs:70-80`). Not a verdict on a
  * block: the reference translates its own record of the era before (`Dijkstra/Governance.hs:29`),
  * so it does not arise there.
  */
enum StoredGovError:
  /** The record does not read into the era's ([[GovStateReadError]]): only a damaged record gives
    * it, as the reader's own causes say.
    */
  case Read(cause: GovStateReadError)

/** Dijkstra's governance state in the node's ledger state: the era's record itself
  * ([[ConwayGovState]], `Dijkstra/Governance.hs:29`, cardano-ledger 9df9f98c), which the state's
  * codec reads and writes as the reference does (#85, FMT-1). The tick works on it with the
  * new-epoch state beside it.
  */
object StoredGov:
  /** The governance state of a Dijkstra epoch state. */
  def recordOf: Lens[EpochState[Dijkstra], ConwayGovState[Dijkstra]] =
    EpochState.ls[Dijkstra].andThen(LedgerState.utxo[Dijkstra]).andThen(UTxOState.gov[Dijkstra])

  /** `es` with `gov` as its governance state: what the node's rules that read the epoch state
    * during the tick, the reward update and RUPD (`Rewards.start`), read of the governance state
    * the tick works on beside it.
    */
  def synced(es: EpochState[Dijkstra], gov: ConwayGovState[Dijkstra]): EpochState[Dijkstra] =
    recordOf.replace(gov)(es)

  /** `rule`, which works on `S` with the governance state beside it (`G`, made by `pair` and taken
    * apart by `unpair`), as a rule on `S` whose governance state `record` names: the state taken
    * out before the rule and the one the rule leaves put back after it.
    */
  def onRecord[S, G, Sig](
      rule: Sts[Unit, G, Sig, TickFailure],
      record: Lens[S, ConwayGovState[Dijkstra]],
      pair: (S, ConwayGovState[Dijkstra]) => G,
      unpair: G => (S, ConwayGovState[Dijkstra])
  ): Sts[Unit, S, Sig, TickFailure] =
    new Sts[Unit, S, Sig, TickFailure]:
      def transition[F[_]: Monad](env: Unit, signal: Sig)(using
          ask: Ask[F, Globals],
          st: Stateful[F, S],
          failures: Raise[F, TickFailure]
      ): F[Unit] =
        for
          before <- st.get
          globals <- Sts.globals[F]
          after <- Sts.lift[F, TickFailure, G](
            Sts(rule)(globals, env, pair(before, record.get(before)), signal)
          )
          (s, g) = unpair(after)
          _ <- st.set(record.replace(g)(s))
        yield ()
