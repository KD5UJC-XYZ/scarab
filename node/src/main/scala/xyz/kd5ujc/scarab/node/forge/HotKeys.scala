// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.consensus.leios.VotingKey
import cats.Functor
import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import java.nio.file.{NoSuchFileException, Path}
import java.io.IOException
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.PraosConfig
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosVrfCertificate}
import xyz.kd5ujc.scarab.crypto.witness.VerificationError
import xyz.kd5ujc.scarab.ledger.{
  ForgeError,
  ForgeKeyProblem,
  KesFileFailureCause,
  OCertError,
  OCertRules,
  StorageError
}
import xyz.kd5ujc.scarab.node.Fatal
import xyz.kd5ujc.scarab.network.Fibers

/** Why a reload of the KES key and operational certificate was refused. The old credentials stay in
  * force; the refusal is reported as a [[ForgeEvent.KesReloadRefused]].
  */
enum ReloadRefusal:
  /** The files could not be read or are malformed. */
  case Unreadable(error: ForgeError)

  /** The certificate was issued by another cold key than the pool's. */
  case WrongPool(expected: Bytes, found: Bytes)

  /** The KES key is not the one the certificate names. */
  case KeyNotCertified

  /** Malformed certificate input or a verifier failure, distinct from signature rejection. */
  case CertificateFailure(cause: OperationalCertificate.Failure)

  /** The certificate's signature does not verify under its cold key. */
  case BadCertificateSignature

  /** The certificate's first KES period has not begun. */
  case NotYetValid(period: BigInt, start: BigInt)

  /** The certificate's last KES period has passed. */
  case Expired(period: BigInt, start: BigInt, evolutions: BigInt)

  /** The KES key has already been evolved beyond the period it would have to sign in. */
  case KeyPastPeriod(keyPeriod: Int, wanted: BigInt)

  /** The issue counter is not the pool's on-chain counter or the one above it. */
  case Counter(error: OCertError)

  def message: String = this match
    case Unreadable(e)        => e.message
    case WrongPool(want, got) => s"certificate of pool ${got.hex}, this node forges for ${want.hex}"
    case KeyNotCertified      => "the certificate names another KES verification key"
    case CertificateFailure(e)   => s"operational certificate: ${e.message}"
    case BadCertificateSignature => "the certificate is not signed by the pool's cold key"
    case NotYetValid(p, s)       => s"the certificate starts at KES period $s, now is $p"
    case Expired(p, s, e)        => s"KES period $p is past the certificate's window [$s, ${s + e})"
    case KeyPastPeriod(k, w) => s"the KES key is at period $k, past the period $w it must sign in"
    case Counter(e)          => e.message

/** An attempt's admission (#1101): when it began, or was made again, the forger's clock fiber had
  * published `through`, or the slot driver had taken that publication, so every slot up to it is
  * admitted, as the reference's blockchain time had handed it to the forging thread. Only an
  * admitted attempt changes the KES state for its slot (the key evolved, or poisoned and erased, or
  * a period's update made for the forger to record); it keeps its admission for its duration, as
  * the per-slot loop goes on with a slot it has begun should the clock go back meanwhile (after a
  * spell in which the slot was unknown, as the reference's can, `checkValidClockChange`). Made only
  * from the clock fiber's published slot, which the slot driver keeps as it takes it, and for the
  * per-slot decision of a slot, admitted at the slot it decides.
  */
final class Admitted private (val through: BigInt):
  /** Whether it admits a change for `slot`. */
  def admits(slot: BigInt): Boolean = slot <= through

object Admitted:
  /** The admission of `slot` if the clock fiber has published it, or a later slot, now. */
  private[forge] def now[F[_]: Functor](
      slot: BigInt,
      shared: Forger.Shared[F]
  ): F[Option[Admitted]] =
    shared.clock.get.map(_.filter(_ >= slot).map(new Admitted(_)))

  /** Each slot the clock fiber publishes, as the admission of it and of every slot before it. */
  private[forge] def published[F[_]](shared: Forger.Shared[F]): Stream[F, Admitted] =
    shared.clock.discrete.collect { case Some(slot) => new Admitted(slot) }

  /** The per-slot decision of `slot` and the tests: admitted at the slot it decides. */
  private[forge] def at(slot: BigInt): Admitted = new Admitted(slot)

/** A change of the KES state for `slot` not made: the attempt was not admitted at it. */
final case class NotAdmitted(slot: BigInt)

/** A slot whose ledger view the node can forecast from the chain it would forge on. The reference's
  * forging loop makes that check (`forecastFor` of `ledgerViewForecastAt`, `TraceNoLedgerView`
  * otherwise) before its forge state update: consensus 82ecba329 `NodeKernel.hs` 611-631, then
  * `checkShouldForge` at 646-669, whose first step is `updateForgeState` (`Block/Forging.hs`
  * 192-202); the same order at 808e4005 (`NodeKernel/Forge.hs` 100-111 and 334-353,
  * `Block/Forging.hs` 175-185). Made only by [[Forecastable.check]]: the evolved KES key is written
  * back only at a period no later than such a slot's ([[HotKeys.confirm]]).
  */
final class Forecastable private (val slot: BigInt)

object Forecastable:
  /** `ledgerViewForecastAt` of the Praos eras (consensus 82ecba329
    * `Shelley/Ledger/SupportsProtocol.hs` 100-127) from a ledger state at `at`, none at Origin: it
    * answers for `at` itself and for every slot before `maxFor`, the stability window `window`
    * after the successor of `at` (`succWithOrigin`, zero at Origin), and for no other.
    */
  def check(
      at: Option[BigInt],
      window: BigInt,
      slot: BigInt
  ): Either[OutsideForecastRange, Forecastable] =
    val maxFor = at.fold(BigInt(0))(_ + 1) + window
    Either.cond(
      at.contains(slot) || slot < maxFor,
      new Forecastable(slot),
      OutsideForecastRange(at, maxFor, slot)
    )

/** Where the credentials rotation replaces come from and where the evolved KES key is written back.
  * The node reads from it at a reload (SIGHUP, as the Haskell node does); a test supplies its own.
  */
trait HotKeySource[F[_]]:
  /** The key and certificate the operator installed now, or the credential file that is absent. */
  def read: F[Either[ForgeError, HotKeySource.Reading[F]]]

  /** Write the key, evolved in memory, back to where it was read from (see [[KesFiles]]). */
  def persist(key: Sum6KesSigning.SigningKey): F[Persisted]

object HotKeySource:
  /** What a read found: the credentials, the VRF key, and `adopt`, which the node runs once it has
    * switched to them. Where the evolved key is written back changes only then: a reload that is
    * refused leaves it where it was, so the pair that stays in force keeps being written to its own
    * files.
    */
  final case class Candidate[F[_]](
      credentials: HotCredentials,
      vrf: Draft03Prover.SigningKey,
      adopt: F[Unit],
      voting: Vector[VotingKey] = Vector.empty
  )

  /** What a read found: credentials to check, or a credential file that does not exist, which
    * disables forging as the reference's `FileDoesNotExistError` does (see [[HotKeys.reload]]).
    */
  enum Reading[F[_]]:
    case Found(candidate: Candidate[F])
    case Absent(file: Path)

  /** The files given with `--vrf-skey`, `--kes-skey`, `--opcert` and `--shelley-bls-key`, read
    * again in the reference's order ([[KesFiles.reread]]).
    */
  def files[F[_]: Async](
      vrfFile: Path,
      kesFile: Path,
      opCertFile: Path,
      blsFile: Option[Path] = None
  ): F[HotKeySource[F]] =
    val F = Async[F]
    F.blocking(KesFiles.resolved(kesFile)).flatMap { first =>
      Ref.of[F, Path](first.getOrElse(kesFile)).map { target =>
        new HotKeySource[F]:
          def read = F.blocking(KesFiles.reread(vrfFile, kesFile, opCertFile, blsFile)).flatMap {
            case Left(error)                         => F.pure(Left(error))
            case Right(KesFiles.Reread.Absent(file)) => F.pure(Right(Reading.Absent(file)))
            case Right(KesFiles.Reread.Found(hot, vrf, voting)) =>
              // the key is written back where it was read from, even if a link moves afterwards
              F.blocking(KesFiles.resolved(kesFile)).map { where =>
                Right(
                  Reading.Found(
                    HotKeySource.Candidate(hot, vrf, where.traverse_(target.set), voting)
                  )
                )
              }
          }
          def persist(key: Sum6KesSigning.SigningKey) =
            target.get.flatMap(path => F.blocking(KesFiles.persist(path, key)))
      }
    }

/** The KES key and operational certificate the forger signs with, which can be replaced while it
  * runs (H2a, #116).
  *
  * Every use of the key goes through [[locked]], which holds a lock; [[advance]] and [[reload]]
  * take the same lock. So a block is always signed with a key and certificate of one reload, and
  * never twice in a period with different key states: the key only moves forward in memory, except
  * that a reload starts again from the file, as the reference's does ([[HotKeys.kept]]); a period's
  * signature is deterministic in the key, which does not change within a period. It is written
  * back, and its predecessor overwritten, before it signs in the new period.
  *
  * The key evolves in memory; it is written back only at a period no later than that of a slot that
  * passed the forecast check ([[confirm]], [[HotKeys.Confirmed]]), so that a wrong reading of the
  * clock, at a period's first slot or at a reload, leaves the file as it was (#1101, the KES row of
  * 2026-11-18 in the decisions register).
  *
  * Forging can be switched off and on again while the node runs, as the reference's dynamic block
  * forging does it (`setBlockForging`, see [[reload]]): while it is off there is no key in memory,
  * nothing signs, and nothing is written back.
  *
  * @param state
  *   The credentials in use, none while forging is disabled; the first failure replaces them.
  * @param poisoned
  *   The key's state when it was poisoned, as the reference poisons a key whose window has passed
  *   (`HotKey.hs` 405-410): the key is erased from memory, every later [[update]] fails with
  *   `KESKeyAlreadyPoisoned`, and a [[reload]] that installs a new key ends it. Unlike the first
  *   failure of `state`, it is not fatal.
  * @param onDisk
  *   The period, relative to the certificate, of the key in the file the key in use is written back
  *   to, as it was read there or last written: a key at a later period is written back when a slot
  *   that passed the forecast check confirms it. It is set under the lock, with `state`.
  * @param poolId
  *   The pool this node forges for: the hash of the cold key of the credentials it started with.
  * @param renamed
  *   Whether a write-back has already replaced the key file by the rename, without the atomic
  *   exchange, which is said once.
  * @param installed
  *   Which credentials are in force: a number that every reload that switches them or disables
  *   forging moves on, and their VRF key. It is set under the lock, with `state`.
  */
final class HotKeys[F[_]: Async] private (
    state: Ref[F, Either[Fatal, Option[PoolCredentials]]],
    poisoned: Ref[F, Option[KesInfo]],
    onDisk: Ref[F, Int],
    lock: Mutex[F],
    source: Option[HotKeySource[F]],
    config: PraosConfig,
    events: ForgeEvents[F],
    poolId: Bytes,
    renamed: Ref[F, Boolean],
    installed: SignallingRef[F, HotKeys.Installed]
):
  private val F = Async[F]

  /** The credentials in force as they change (#1101): the forger's leader schedule is computed with
    * their VRF key, and a block built before its slot is handed over only if they have not changed
    * since.
    */
  def inForce: Signal[F, HotKeys.Installed] = installed

  /** The credentials in use; none while forging is disabled. */
  def current(using Raise[F, Fatal]): F[Option[PoolCredentials]] =
    state.get.flatMap(_.fold(_.raise[F, Option[PoolCredentials]], F.pure))

  /** False while forging is disabled. A failed state reads true, so that its failure is raised
    * where the key is used.
    */
  def enabled: F[Boolean] = state.get.map(_.forall(_.isDefined))

  /** The first failure poisons the mutable key while its lock is held. A queued signer or reload
    * observes the same failure even before Main has received and acted on the terminal result.
    */
  private def remember(fatal: Fatal): F[Fatal] = state.modify {
    case Left(first) => (Left(first), first)
    case Right(_)    => (Left(fatal), fatal)
  }

  private def guarded[A](use: Option[PoolCredentials] => Raise[F, Fatal] ?=> F[A])(using
      Raise[F, Fatal]
  ): F[A] = lock.lock.surround {
    current.flatMap { credentials =>
      Fibers
        .scoped[F, Fatal, A](Fatal.Defect(_))(use(credentials))
        .onCancel(remember(Fatal.Internal(ForgeError.KesUpdateInterrupted)).void)
        .flatMap {
          case Right(value) => F.pure(value)
          case Left(fatal)  => remember(fatal).flatMap(_.raise[F, A])
        }
    }
  }

  private def usingKey[A](use: PoolCredentials => Raise[F, Fatal] ?=> F[A])(using
      Raise[F, Fatal]
  ): F[Option[A]] = guarded(_.traverse(c => use(c)))

  /** Run `use` with healthy credentials, which cannot be replaced meanwhile; none while forging is
    * disabled.
    */
  def locked[A](use: PoolCredentials => Raise[F, Fatal] ?=> F[A])(using
      Raise[F, Fatal]
  ): F[Option[A]] =
    usingKey(use)

  /** Persist the evolved key before signing, the key a confirmation allows: never one at a later
    * period than a slot that passed the forecast check. A durability failure is terminal: another
    * slot cannot repair the safety guarantee that the previous key has been erased from disk. A key
    * file that is not there (moved away to stop forging, #918) is not made again; the key in memory
    * signs on, as the reference's would until a SIGHUP, and the node says so.
    */
  private def write(confirmed: HotKeys.Confirmed)(using Raise[F, Fatal]): F[Unit] =
    val kesPeriod = confirmed.kesPeriod
    source.traverse_ { s =>
      s.persist(confirmed.credentials.kes).flatMap {
        case Persisted.Failed(e) =>
          remember(HotKeys.persistenceFailure(e)).flatMap { fatal =>
            events.emit(ForgeEvent.KesPersistFailed(kesPeriod, e)) *> fatal.raise[F, Unit]
          }
        case Persisted.Absent(file) => events.emit(ForgeEvent.KesFileAbsent(kesPeriod, file))
        // the rename, where there is no atomic exchange, said once (#918)
        case Persisted.Written(WriteProtocol.Renamed(reason)) =>
          renamed
            .getAndSet(true)
            .flatMap(events.emit(ForgeEvent.KesWrittenByRename(reason)).unlessA(_))
        case Persisted.Written(WriteProtocol.Exchanged) | Persisted.Unchanged |
            Persisted.Superseded =>
          F.unit
      }
    } *> onDisk.set(confirmed.relative)

  /** The key of `c` written back if it has moved past the key in its file and `at`, a slot that
    * passed the forecast check, confirms its period (the lock is held). A key at a later period
    * than the slot's, as a wrong reading of the clock at a period's first slot or at a reload can
    * leave it, is not written: the file keeps the key it has, and a restart reads that. A key
    * erased from memory (poisoned) is never written.
    */
  private def persisted(c: PoolCredentials, at: Forecastable)(using Raise[F, Fatal]): F[Unit] =
    (poisoned.get, onDisk.get).flatMapN { (erased, filed) =>
      HotKeys
        .Confirmed(c, at, config.slotsPerKesPeriod)
        .filter(confirmed => erased.isEmpty && confirmed.relative > filed)
        .traverse_(write)
    }

  /** The write-back of the key in use at `at`, a slot that passed the forecast check, as the
    * reference makes its forge state update after that check ([[Forecastable]]): the key is written
    * back if it has moved past the key in its file and its period is not later than the slot's
    * ([[persisted]]). Nothing while forging is disabled.
    */
  def confirm(at: Forecastable)(using Raise[F, Fatal]): F[Unit] =
    usingKey(c => persisted(c, at)).void

  /** Evolve to the exact relative period, in memory (the lock is held): it is written back once a
    * slot that passed the forecast check confirms its period ([[persisted]]). An exhausted signing
    * lifetime is terminal, as `HotKey.hs:411-438` at consensus 82ecba32 fails when `updateKES`
    * cannot reach an in-window target. A backward target retains its crypto cause. Unchecked: for a
    * slot it is made only through [[evolvedFor]] or [[update]], and for a reload by [[adopt]].
    */
  private def evolved(c: PoolCredentials, kesPeriod: BigInt)(using
      Raise[F, Fatal]
  ): F[Either[ForgeError, Unit]] =
    val relative = kesPeriod - c.opCert.kesPeriod
    current *> F.delay(c.kes.period).flatMap { before =>
      F.blocking(c.kes.evolveTo(relative).leftMap(ForgeError.KesEvolution(_)))
        .flatMap {
          case Left(error) if relative >= Sum6KesSigning.Periods =>
            Fatal.Internal(error).raise[F, Either[ForgeError, Unit]]
          case result =>
            result
              .traverse_ { _ =>
                events
                  .emit(ForgeEvent.KesEvolved(kesPeriod, c.kes.period))
                  .whenA(before != relative)
              }
              .as(result)
        }
    }

  /** `updateForgeState` of the reference at `slot`, to its KES period: `HotKey.evolve`
    * (`evolveKey`, consensus 82ecba329 `Protocol/Ledger/HotKey.hs` 385-418, called from
    * `Shelley/Node/Praos.hs` 83-85). Made only for a slot `admitted` admits, checked under the
    * key's lock; otherwise it is [[NotAdmitted]] and nothing changes. Before the certificate's
    * window the key is left as it is (the leader check then finds it not usable yet); inside it,
    * the key evolves in memory to the period when that is later than its own, and is written back
    * once a slot that passed the forecast check confirms it ([[confirm]]), before anything signs
    * with it; at or past the end of the window it is poisoned and the update fails
    * (`KESCouldNotEvolve`), as every later one does (`KESKeyAlreadyPoisoned`) until a reload
    * installs another key. The key's state after the update is the value, none while forging is
    * disabled. An exhausted signing lifetime inside the configured window stops this forging worker
    * with its typed crypto cause; a failed write also stops the worker.
    */
  def update(slot: BigInt, admitted: Admitted)(using
      Raise[F, Fatal]
  ): F[Option[Either[NotAdmitted, HotKeys.KesUpdate]]] =
    usingKey(c => admittedFor(slot, Some(admitted))(updated(c, periodOf(slot))))

  /** [[update]], with the generation of the credentials it was made with ([[HotKeys.Installed]]),
    * read under the same lock: the forger records the period's update only from this.
    */
  def updateIn(slot: BigInt, admitted: Admitted)(using
      Raise[F, Fatal]
  ): F[Option[(Long, Either[NotAdmitted, HotKeys.KesUpdate])]] =
    usingKey(c =>
      installed.get.flatMap(i =>
        admittedFor(slot, Some(admitted))(updated(c, periodOf(slot))).map(i.generation -> _)
      )
    )

  /** The key of `c` at the KES period of `at` 's slot, to sign in it (the lock is held): as it is
    * where it is there already, which changes nothing and needs no admission; evolved to it where
    * it must move forward, only for a slot `admitted` admits, else [[NotAdmitted]] and nothing
    * changes; either way written back before it signs, if its file holds an earlier period. A key
    * past the period cannot go back, and stays as it is. The slot is one that passed the forecast
    * check, so the key is never written back ahead of it.
    */
  private[forge] def evolvedFor(c: PoolCredentials, at: Forecastable, admitted: Option[Admitted])(
      using Raise[F, Fatal]
  ): F[Either[NotAdmitted, Either[ForgeError, Unit]]] =
    val slot = at.slot
    val kesPeriod = periodOf(slot)
    F.delay(kesPeriod - c.opCert.kesPeriod > c.kes.period).flatMap { forward =>
      val moved =
        if forward then admittedFor(slot, admitted)(evolved(c, kesPeriod))
        else evolved(c, kesPeriod).map(Right(_))
      moved.flatTap(_.traverse_(_.traverse_(_ => persisted(c, at))))
    }

  /** `change`, made only for a slot `admitted` admits (the lock is held). */
  private def admittedFor[A](slot: BigInt, admitted: Option[Admitted])(
      change: => F[A]
  ): F[Either[NotAdmitted, A]] =
    if admitted.exists(_.admits(slot)) then change.map(Right(_))
    else F.pure(Left(NotAdmitted(slot)))

  private def periodOf(slot: BigInt): BigInt = slot / config.slotsPerKesPeriod

  private def updated(c: PoolCredentials, kesPeriod: BigInt)(using
      Raise[F, Fatal]
  ): F[HotKeys.KesUpdate] =
    val now = HotKeys.info(c, config)
    poisoned.get.flatMap {
      case Some(stale) =>
        F.pure(Right(Left(KesEvolutionError.KeyAlreadyPoisoned(stale, kesPeriod))))
      case None if kesPeriod < now.start => F.pure(Right(Right(now)))
      case None if kesPeriod >= now.end =>
        F.delay(c.kes.forget()) *> poisoned
          .set(Some(now))
          .as(Right(Left(KesEvolutionError.CouldNotEvolve(now, kesPeriod))))
      case None if kesPeriod - now.start <= c.kes.period => F.pure(Right(Right(now)))
      case None =>
        evolved(c, kesPeriod).map(_.map(_ => Right(HotKeys.info(c, config))))
    }

  /** Run `use` with healthy credentials and their generation, which cannot change meanwhile; none
    * while forging is disabled.
    */
  def lockedIn[A](use: (PoolCredentials, Long) => Raise[F, Fatal] ?=> F[A])(using
      Raise[F, Fatal]
  ): F[Option[A]] =
    usingKey(c => installed.get.flatMap(i => use(c, i.generation)))

  /** `updateBlockForging` of the reference (cardano-node 11.1.3,
    * `cardano-node/src/Cardano/Node/Run.hs` 620-668), run on SIGHUP: read the credentials again. A
    * credential file that does not exist (`FileDoesNotExistError`) disables forging
    * (`setBlockForging nodeKernel []`, lines 631-635), whether it was on or already off: the key in
    * use is erased from memory and nothing signs or is written back until a later reload finds the
    * files again. Credentials that are found are switched to if they are good (see
    * [[HotKeys.validate]], the owner's checks of ADR 0015, which the reference does not make), and
    * forging is enabled with them; otherwise the forger stays as it is, forging or disabled, and
    * says why (the reference's `BlockForgingUpdateError`). `counter` is the pool's issue counter on
    * the chain. The reference treats a failure to read its bulk credentials file as an absent file
    * too (`CredentialsReadError`, lines 664-667); Scarab reads no bulk credentials file. The key
    * checked is the one the reload installs ([[HotKeys.kept]]).
    */
  def reload(kesPeriod: BigInt, counter: Option[BigInt])(using Raise[F, Fatal]): F[Unit] =
    guarded { inUse =>
      source.traverse_ { s =>
        s.read.flatMap {
          case Left(e) =>
            HotKeys.reloadFailure(e).fold(refuse(ReloadRefusal.Unreadable(e)))(_.raise[F, Unit])
          case Right(HotKeySource.Reading.Absent(_)) => disable(inUse)
          case Right(HotKeySource.Reading.Found(candidate)) =>
            poisoned.get.flatMap { erased =>
              // the key the reload would install, which is the key the checks see
              val kept = HotKeys.kept(inUse, erased.isDefined, candidate.credentials, kesPeriod)
              HotKeys.validate(
                poolId,
                kept.map(_.kes),
                candidate.credentials,
                kesPeriod,
                counter,
                config
              ) match
                case Left(why) => F.delay(candidate.credentials.kes.forget()) *> refuse(why)
                case Right(_)  => adopt(inUse, kept, candidate, kesPeriod)
            }
        }
      }
    }

  /** Forging off: the key is erased from memory and forgotten with its poisoning, as the reference
    * drops its block forging. The trace follows the switch, so a reader of the log that sees it
    * knows that nothing signs any more.
    */
  private def disable(inUse: Option[PoolCredentials]): F[Unit] =
    inUse.traverse_(c => F.delay(c.kes.forget())) *> state.set(Right(None)) *>
      poisoned.set(None) *> installed.update(i => HotKeys.Installed(i.generation + 1, None)) *>
      events.emit(ForgeEvent.BlockForgingUpdate(BlockForgingState.DisabledBlockForging))

  private def refuse(why: ReloadRefusal)(using Raise[F, Fatal]): F[Unit] =
    val fatal = why match
      case ReloadRefusal.CertificateFailure(cause) =>
        HotKeys.infrastructureFailure(ForgeError.CertificateFailure(cause))
      case _ => None
    fatal.fold(events.emit(ForgeEvent.KesReloadRefused(why)))(_.raise[F, Unit])

  /** The switch to `found` at `kesPeriod`, keeping the key in use where [[HotKeys.kept]] says so
    * (`kept`): otherwise the file's key replaces it and the key in use is erased from memory. While
    * forging is disabled no key is in use, and the file's key is taken. The VRF key is the one
    * read, as the reference's new block forging takes it (`Run.hs` 640-648).
    */
  private def adopt(
      inUse: Option[PoolCredentials],
      kept: Option[PoolCredentials],
      found: HotKeySource.Candidate[F],
      kesPeriod: BigInt
  )(using Raise[F, Fatal]): F[Unit] =
    val candidate = found.credentials
    val kes = kept.fold(candidate.kes)(_.kes)
    // the voting keys read with the files, as the reference builds its block forging again from
    // them all (`Run.hs:701-736`, cardano-node b004fe0c)
    val next = PoolCredentials(found.vrf, kes, candidate.opCert, candidate.coldVkey, found.voting)
    val relative = kesPeriod - candidate.opCert.kesPeriod
    for
      // the period of the key in the file it was read from, to which the key in use goes now
      filed <- F.delay(candidate.kes.period)
      _ <- F.delay(if kept.isDefined then candidate.kes.forget() else inUse.foreach(_.kes.forget()))
      _ <- state.set(Right(Some(next)))
      _ <- poisoned.set(None)
      _ <- onDisk.set(filed)
      _ <- installed.update(i => HotKeys.Installed(i.generation + 1, Some(found.vrf)))
      _ <- found.adopt
      // a key installed at period 0 is brought to the wall clock's period at once, in memory; like
      // the key in use, further on than the file it was read again from, it is written there only
      // once a slot that passed the forecast check confirms its period (#1101, the KES row of
      // 2026-11-18): a reload on a wrong reading of the clock writes nothing
      _ <- evolved(next, kesPeriod)
        .flatMap(_.fold(error => Fatal.Internal(error).raise[F, Unit], _ => F.unit))
        .whenA(relative > kes.period)
      _ <- events.emit(
        ForgeEvent.KesReloaded(
          candidate.opCert.sequence,
          candidate.opCert.kesPeriod,
          config.maxKesEvolutions,
          kesPeriod
        )
      )
      _ <- events.emit(ForgeEvent.BlockForgingUpdate(BlockForgingState.EnabledBlockForging))
    yield ()

object HotKeys:
  /** The update of the KES key to a period: the key's state, its failure, or a failure of the
    * forger's own.
    */
  type KesUpdate = Either[ForgeError, Either[KesEvolutionError, KesInfo]]

  /** The credentials in force: `generation` moves on at every reload that switches them or disables
    * forging, and `vrf` is their VRF key, none while forging is disabled.
    */
  final case class Installed(generation: Long, vrf: Option[Draft03Prover.SigningKey])

  /** The key of `credentials`, at `relative` to its certificate and so at the KES period
    * `kesPeriod`, confirmed by a slot that passed the forecast check whose period is not earlier:
    * the only key the write-back takes, so that the period written to the key file is never later
    * than that of such a slot (#1101, the KES row of 2026-11-18). Made only by [[Confirmed.apply]],
    * under the key's lock, from the key as it is then.
    */
  final class Confirmed private (
      val credentials: PoolCredentials,
      val relative: Int,
      val kesPeriod: BigInt
  )

  object Confirmed:
    /** The key of `c` confirmed by `at`, if its period is not later than the slot's. */
    def apply(c: PoolCredentials, at: Forecastable, slotsPerKesPeriod: BigInt): Option[Confirmed] =
      val relative = c.kes.period
      val kesPeriod = c.opCert.kesPeriod + relative
      Option.when(kesPeriod <= at.slot / slotsPerKesPeriod)(new Confirmed(c, relative, kesPeriod))

  /** @param enabled
    *   Forge with `credentials` from the start; false for `--start-as-non-producing-node`, which
    *   reads and checks the credentials at start-up but does not forge until a reload finds them
    *   (cardano-node 11.1.3 `Run.hs` 395-398). The KES key read at start-up is then erased at once.
    */
  def create[F[_]: Async](
      credentials: PoolCredentials,
      source: Option[HotKeySource[F]],
      config: PraosConfig,
      events: ForgeEvents[F],
      enabled: Boolean = true
  ): F[HotKeys[F]] =
    val F = Async[F]
    F.delay(credentials.kes.forget()).unlessA(enabled) *> (
      Ref.of[F, Either[Fatal, Option[PoolCredentials]]](
        Right(Option.when(enabled)(credentials))
      ),
      Ref.of[F, Option[KesInfo]](None),
      F.delay(credentials.kes.period).flatMap(Ref.of[F, Int](_)),
      Mutex[F],
      Ref.of[F, Boolean](false),
      SignallingRef.of[F, Installed](Installed(0, Option.when(enabled)(credentials.vrf)))
    ).mapN { (state, poisoned, onDisk, lock, renamed, installed) =>
      new HotKeys[F](
        state,
        poisoned,
        onDisk,
        lock,
        source,
        config,
        events,
        credentials.poolId,
        renamed,
        installed
      )
    }

  /** The `KESInfo` of the key in use: its certificate's window and its evolution. */
  def info(c: PoolCredentials, config: PraosConfig): KesInfo =
    KesInfo(c.opCert.kesPeriod, c.opCert.kesPeriod + config.maxKesEvolutions, c.kes.period)

  /** A file that disappears between the check that it exists and its read is an operator refusal on
    * reload only, as the reference's `FileIOError` keeps the forging it has (a file absent when it
    * is checked disables forging, [[reload]]). Other I/O is a storage failure; an unexpected
    * exception remains a defect, even if the file adapter wrapped it.
    */
  private[forge] def reloadFailure(error: ForgeError): Option[Fatal] = error match
    case ForgeError.KeyFile(
          _,
          ForgeKeyProblem.Io(_: NoSuchFileException)
        ) =>
      None
    case ForgeError.KeyFile(file, ForgeKeyProblem.Io(cause)) => Some(fileFailure(file, cause))
    case _: ForgeError.KesFileFailure                        => Some(persistenceFailure(error))
    case _                                                   => infrastructureFailure(error)

  /** Typed nested crypto defects have the same terminal policy as raw exceptions. */
  private[forge] def infrastructureFailure(error: ForgeError): Option[Fatal] = error match
    case ForgeError.VrfInput(PraosVrfCertificate.Failure.InternalFailure(cause)) =>
      Some(Fatal.Defect(cause))
    case ForgeError.VrfProof(Draft03Prover.Failure.InternalFailure(cause)) =>
      Some(Fatal.Defect(cause))
    case ForgeError.KeyFile(_, ForgeKeyProblem.Vrf(Draft03Prover.Failure.InternalFailure(cause))) =>
      Some(Fatal.Defect(cause))
    case ForgeError.CertificateFailure(OperationalCertificate.Failure.Defect(cause)) =>
      Some(Fatal.Defect(cause))
    case ForgeError.CertificateFailure(
          OperationalCertificate.Failure.PrimitiveFailure(
            VerificationError.ImplementationFailure(cause)
          )
        ) =>
      Some(Fatal.Defect(cause))
    case ForgeError.CertificateFailure(
          _: OperationalCertificate.Failure.PrimitiveContractViolation
        ) =>
      Some(Fatal.Internal(error))
    case ForgeError.KesUpdateInterrupted => Some(Fatal.Internal(error))
    case _                               => None

  private def fileFailure(file: String, cause: Throwable): Fatal = cause match
    case _: IOException => Fatal.Storage(StorageError.Io(file, cause))
    case other          => Fatal.Defect(other)

  private[forge] def persistenceFailure(error: ForgeError): Fatal = error match
    case ForgeError.KeyFile(file, ForgeKeyProblem.Io(cause)) => fileFailure(file, cause)
    case ForgeError.KesFileFailure(file, _, KesFileFailureCause.FileSystem(cause)) =>
      fileFailure(file, cause)
    case other => infrastructureFailure(other).getOrElse(Fatal.Internal(other))

  /** The key in use that a reload to `candidate` at `kesPeriod` keeps: the key in use, when the
    * files hold the same key (a certificate issued again for it, or the same files read again; the
    * key in use is then as far on as the file's copy, or further), and it is neither past the
    * period it must sign in nor erased (`erased`: poisoned past its window, its memory zeroed).
    * Otherwise none, and the reload installs the key read from the file, as the reference's reload
    * always does: it builds its block forging again from the files (cardano-node 11.1.3 `Run.hs`
    * 626-648, `mkBlockForging`), each with a hot key made afresh from the file's key
    * (ouroboros-consensus 82ecba32 `Protocol/Praos/Common.hs` 317-326,
    * `instantiatePraosCredentials`, and `HotKey.hs` 215-225, `mkHotKey`; the same at 11.2.1,
    * `Run.hs` 651-683, and 808e4005 `Common.hs` 317-326). So a key in memory past the present, as a
    * reload while the clock read ahead can leave it, is replaced by the file's at the next reload,
    * as the reference's is (#1101, the KES row of 2026-11-18); [[validate]] refuses the file's key
    * too if it is past the period.
    */
  private[forge] def kept(
      inUse: Option[PoolCredentials],
      erased: Boolean,
      candidate: HotCredentials,
      kesPeriod: BigInt
  ): Option[PoolCredentials] =
    inUse.filter(c =>
      !erased && c.kes.verificationKey == candidate.kes.verificationKey &&
        c.kes.period <= kesPeriod - candidate.opCert.kesPeriod
    )

  /** May a node forging for the pool `poolId` switch to `candidate` at `kesPeriod`, when the pool's
    * issue counter on the chain is `counter` (none: not seen yet) and `inUse` is the KES key in use
    * that the reload keeps ([[kept]]; none while forging is disabled, or where the file's key is
    * installed)?
    *
    * The same rules the chain applies to the headers it will carry (OCERT, `doValidateKESSignature`
    * of ouroboros-consensus-protocol `Protocol/Praos.hs`, tag release-ouroboros-consensus-4.2.1.0,
    * lines 620 to 647), so a certificate that passes here is not refused there: the cold key is the
    * pool's; the certificate names the KES key and carries the cold key's signature; the period is
    * inside `[start, start + maxKESEvolutions)`; the key in the file is not past the period it must
    * sign in, whatever the key in use (the reference's reload signs with the file's key), and nor
    * is the key in use the reload keeps; and the counter is the on-chain one or the one above
    * ([[OCertRules.checkCounter]], the function the header rules use).
    */
  def validate(
      poolId: Bytes,
      inUse: Option[Sum6KesSigning.SigningKey],
      candidate: HotCredentials,
      kesPeriod: BigInt,
      counter: Option[BigInt],
      config: PraosConfig
  ): Either[ReloadRefusal, Unit] =
    val start = candidate.opCert.kesPeriod
    val found = Blake2b.hash224.hash(candidate.coldVkey)
    val wanted = kesPeriod - start
    // the file's key, whatever the key in use: the reference's reload signs with it
    val filePeriod = candidate.kes.period
    val keptPeriod = inUse.filter(_.verificationKey == candidate.kes.verificationKey).map(_.period)
    for
      _ <- Either.cond(
        found == poolId,
        (),
        ReloadRefusal.WrongPool(poolId, found)
      )
      _ <- Either.cond(
        candidate.kes.verificationKey == candidate.opCert.hotVkey,
        (),
        ReloadRefusal.KeyNotCertified
      )
      signed <- OperationalCertificate
        .verify(
          candidate.coldVkey,
          candidate.opCert.hotVkey,
          candidate.opCert.sequence,
          start,
          candidate.opCert.sigma
        )
        .leftMap(ReloadRefusal.CertificateFailure(_))
      _ <- Either.cond(
        signed == OperationalCertificate.Result.OperationalCertificateSignatureVerified,
        (),
        ReloadRefusal.BadCertificateSignature
      )
      _ <- Either.cond(start <= kesPeriod, (), ReloadRefusal.NotYetValid(kesPeriod, start))
      _ <- Either.cond(
        kesPeriod < start + config.maxKesEvolutions,
        (),
        ReloadRefusal.Expired(kesPeriod, start, config.maxKesEvolutions)
      )
      _ <- Either.cond(
        filePeriod <= wanted,
        (),
        ReloadRefusal.KeyPastPeriod(filePeriod, wanted)
      )
      _ <- keptPeriod.traverse_(kept =>
        Either.cond(kept <= wanted, (), ReloadRefusal.KeyPastPeriod(kept, wanted))
      )
      _ <- OCertRules
        .checkCounter(
          counter,
          poolId,
          candidate.opCert.sequence,
          known = true,
          bounded = true
        )
        .leftMap(ReloadRefusal.Counter(_))
    yield ()
