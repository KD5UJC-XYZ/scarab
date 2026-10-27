// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
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

/** Where the credentials rotation replaces come from and where the evolved KES key is written back.
  * The node reads from it at a reload (SIGHUP, as the Haskell node does); a test supplies its own.
  */
trait HotKeySource[F[_]]:
  /** The key and certificate the operator installed now. */
  def read: F[Either[ForgeError, HotKeySource.Candidate[F]]]

  /** Write the key, evolved in memory, back to where it was read from (see [[KesFiles]]). */
  def persist(key: Sum6KesSigning.SigningKey): F[Persisted]

object HotKeySource:
  /** What a read found: the credentials, and `adopt`, which the node runs once it has switched to
    * them. Where the evolved key is written back changes only then: a reload that is refused leaves
    * it where it was, so the pair that stays in force keeps being written to its own files.
    */
  final case class Candidate[F[_]](credentials: HotCredentials, adopt: F[Unit])

  /** The key and certificate files given with `--kes-skey` and `--opcert`. */
  def files[F[_]: Async](kesFile: Path, opCertFile: Path): F[HotKeySource[F]] =
    val F = Async[F]
    F.blocking(KesFiles.resolved(kesFile)).flatMap { first =>
      Ref.of[F, Path](first.getOrElse(kesFile)).map { target =>
        new HotKeySource[F]:
          def read = F.blocking(KesFiles.readHot(kesFile, opCertFile)).flatMap { r =>
            // the key is written back where it was read from, even if a link moves afterwards
            F.blocking(KesFiles.resolved(kesFile)).map { where =>
              r.map(HotKeySource.Candidate(_, where.traverse_(target.set)))
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
  * never twice in a period with different key states: the key only moves forward (a period's
  * signature is deterministic in the key, which does not change within a period) and it is written
  * back, and its predecessor overwritten, before it signs in the new period.
  *
  * @param poisoned
  *   The key's state when it was poisoned, as the reference poisons a key whose window has passed
  *   (`HotKey.hs` 405-410): the key is erased from memory, every later [[update]] fails with
  *   `KESKeyAlreadyPoisoned`, and a [[reload]] that installs a new key ends it. Unlike the first
  *   failure of `state`, it is not fatal.
  */
final class HotKeys[F[_]: Async] private (
    state: Ref[F, Either[Fatal, PoolCredentials]],
    poisoned: Ref[F, Option[KesInfo]],
    lock: Mutex[F],
    source: Option[HotKeySource[F]],
    config: PraosConfig,
    events: ForgeEvents[F]
):
  private val F = Async[F]

  def current(using Raise[F, Fatal]): F[PoolCredentials] =
    state.get.flatMap(_.fold(_.raise[F, PoolCredentials], F.pure))

  /** The first failure poisons the mutable key while its lock is held. A queued signer or reload
    * observes the same failure even before Main has received and acted on the terminal result.
    */
  private def remember(fatal: Fatal): F[Fatal] = state.modify {
    case Left(first) => (Left(first), first)
    case Right(_)    => (Left(fatal), fatal)
  }

  private def usingKey[A](use: PoolCredentials => Raise[F, Fatal] ?=> F[A])(using
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

  /** Run `use` with healthy credentials, which cannot be replaced meanwhile. */
  def locked[A](use: PoolCredentials => Raise[F, Fatal] ?=> F[A])(using Raise[F, Fatal]): F[A] =
    usingKey(use)

  /** Persist the evolved key before signing. A durability failure is terminal: another slot cannot
    * repair the safety guarantee that the previous key has been erased from disk.
    */
  private def write(c: PoolCredentials, kesPeriod: BigInt)(using Raise[F, Fatal]): F[Unit] =
    source.traverse_ { s =>
      s.persist(c.kes).flatMap {
        case Persisted.Failed(e) =>
          remember(HotKeys.persistenceFailure(e)).flatMap { fatal =>
            events.emit(ForgeEvent.KesPersistFailed(kesPeriod, e)) *> fatal.raise[F, Unit]
          }
        case Persisted.Written | Persisted.Unchanged | Persisted.Superseded => F.unit
      }
    }

  /** Evolve the key to the period of `kesPeriod` and write it back (the lock is held). A key that
    * cannot get there (it is already past it) is reported by the caller's signing, not here.
    */
  private[forge] def evolved(c: PoolCredentials, kesPeriod: BigInt)(using
      Raise[F, Fatal]
  ): F[Either[ForgeError, Unit]] =
    val relative = (kesPeriod - c.opCert.kesPeriod).toInt
    current *> F.delay(c.kes.period).flatMap { before =>
      F.blocking(c.kes.evolveTo(relative).leftMap(ForgeError.KesEvolution(_)))
        .flatTap(_.traverse_ { _ =>
          (write(c, kesPeriod) *> events.emit(ForgeEvent.KesEvolved(kesPeriod, relative)))
            .whenA(before != relative)
        })
    }

  /** `updateForgeState` of the reference at a slot whose KES period is `kesPeriod`: `HotKey.evolve`
    * (`evolveKey`, consensus 82ecba329 `Protocol/Ledger/HotKey.hs` 385-418, called from
    * `Shelley/Node/Praos.hs` 83-85). Before the certificate's window the key is left as it is (the
    * leader check then finds it not usable yet); inside it, the key evolves to the period when that
    * is later than its own, and is written back before anything signs with it; at or past the end
    * of the window it is poisoned and the update fails (`KESCouldNotEvolve`), as every later one
    * does (`KESKeyAlreadyPoisoned`) until a reload installs another key. The key's state after the
    * update is the value, or a [[ForgeError]] the reference has no counterpart for (a key that
    * cannot reach a period the certificate allows). A failed write stops this forging worker.
    */
  def update(kesPeriod: BigInt)(using
      Raise[F, Fatal]
  ): F[Either[ForgeError, Either[KesEvolutionError, KesInfo]]] =
    usingKey { c =>
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
    }

  /** Re-read the key and certificate and switch to them if they are good (see
    * [[HotKeys.validate]]); otherwise keep the old ones and say why. `counter` is the pool's issue
    * counter on the chain.
    */
  def reload(kesPeriod: BigInt, counter: Option[BigInt])(using Raise[F, Fatal]): F[Unit] =
    usingKey { cur =>
      source.traverse_ { s =>
        s.read.flatMap {
          case Left(e) =>
            HotKeys.reloadFailure(e).fold(refuse(ReloadRefusal.Unreadable(e)))(_.raise[F, Unit])
          case Right(candidate) =>
            HotKeys.validate(cur, candidate.credentials, kesPeriod, counter, config) match
              case Left(why) => F.delay(candidate.credentials.kes.forget()) *> refuse(why)
              case Right(_)  => adopt(cur, candidate, kesPeriod)
        }
      }
    }

  private def refuse(why: ReloadRefusal)(using Raise[F, Fatal]): F[Unit] =
    val fatal = why match
      case ReloadRefusal.CertificateFailure(cause) =>
        HotKeys.infrastructureFailure(ForgeError.CertificateFailure(cause))
      case _ => None
    fatal.fold(events.emit(ForgeEvent.KesReloadRefused(why)))(_.raise[F, Unit])

  private def adopt(
      cur: PoolCredentials,
      found: HotKeySource.Candidate[F],
      kesPeriod: BigInt
  )(using Raise[F, Fatal]): F[Unit] =
    // a certificate re-issued for the key already in use keeps that key, which is further on than
    // the file's copy; a new key replaces it and the old one is erased from memory
    val candidate = found.credentials
    val sameKey = candidate.kes.verificationKey == cur.kes.verificationKey
    val kes = if sameKey then cur.kes else candidate.kes
    val next = cur.withHot(HotCredentials(kes, candidate.opCert, cur.coldVkey))
    val relative = kesPeriod - candidate.opCert.kesPeriod
    for
      _ <- F.delay(if sameKey then candidate.kes.forget() else cur.kes.forget())
      _ <- state.set(Right(next))
      _ <- poisoned.set(None)
      _ <- found.adopt
      // the key in use, further on than the file it was read again from, is written there too
      _ <- write(next, kesPeriod).whenA(sameKey)
      // a key installed at period 0 is brought to the present at once; its file is rewritten
      _ <- evolved(next, kesPeriod).void.whenA(relative.toInt > kes.period)
      _ <- events.emit(
        ForgeEvent.KesReloaded(
          candidate.opCert.sequence,
          candidate.opCert.kesPeriod,
          config.maxKesEvolutions,
          kesPeriod
        )
      )
    yield ()

object HotKeys:
  def create[F[_]: Async](
      credentials: PoolCredentials,
      source: Option[HotKeySource[F]],
      config: PraosConfig,
      events: ForgeEvents[F]
  ): F[HotKeys[F]] =
    (
      Ref.of[F, Either[Fatal, PoolCredentials]](Right(credentials)),
      Ref.of[F, Option[KesInfo]](None),
      Mutex[F]
    ).mapN { (state, poisoned, lock) =>
      new HotKeys[F](state, poisoned, lock, source, config, events)
    }

  /** The `KESInfo` of the key in use: its certificate's window and its evolution. */
  def info(c: PoolCredentials, config: PraosConfig): KesInfo =
    KesInfo(c.opCert.kesPeriod, c.opCert.kesPeriod + config.maxKesEvolutions, c.kes.period)

  /** An absent replacement file is an operator refusal on reload only. Other I/O is a storage
    * failure; an unexpected exception remains a defect, even if the file adapter wrapped it.
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

  /** May a node forging for `current` 's pool switch to `candidate` at `kesPeriod`, when the pool's
    * issue counter on the chain is `counter` (none: not seen yet)?
    *
    * The same rules the chain applies to the headers it will carry (OCERT, `doValidateKESSignature`
    * of ouroboros-consensus-protocol `Protocol/Praos.hs`, tag release-ouroboros-consensus-4.2.1.0,
    * lines 620 to 647), so a certificate that passes here is not refused there: the cold key is the
    * pool's; the certificate names the KES key and carries the cold key's signature; the period is
    * inside `[start, start + maxKESEvolutions)`; and the counter is the on-chain one or the one
    * above ([[OCertRules.checkCounter]], the function the header rules use).
    */
  def validate(
      current: PoolCredentials,
      candidate: HotCredentials,
      kesPeriod: BigInt,
      counter: Option[BigInt],
      config: PraosConfig
  ): Either[ReloadRefusal, Unit] =
    val start = candidate.opCert.kesPeriod
    val found = Blake2b.hash224.hash(candidate.coldVkey)
    val keyPeriod =
      if candidate.kes.verificationKey == current.kes.verificationKey then current.kes.period
      else candidate.kes.period
    for
      _ <- Either.cond(
        found == current.poolId,
        (),
        ReloadRefusal.WrongPool(current.poolId, found)
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
        keyPeriod <= kesPeriod - start,
        (),
        ReloadRefusal.KeyPastPeriod(keyPeriod, kesPeriod - start)
      )
      _ <- OCertRules
        .checkCounter(
          counter,
          current.poolId,
          candidate.opCert.sequence,
          known = true,
          bounded = true
        )
        .leftMap(ReloadRefusal.Counter(_))
    yield ()
