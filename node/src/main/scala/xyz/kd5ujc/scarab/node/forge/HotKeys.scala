// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.PraosConfig
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.ledger.{ForgeError, OCertError, OCertRules}

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
  def read: F[Either[ForgeError, HotCredentials]]

  /** Write the key, evolved in memory, back to where it was read from (see [[KesFiles]]). */
  def persist(key: Sum6KesSigning.SigningKey): F[Persisted]

object HotKeySource:
  /** The key and certificate files given with `--kes-skey` and `--opcert`. */
  def files[F[_]: Async](kesFile: Path, opCertFile: Path): F[HotKeySource[F]] =
    val F = Async[F]
    F.blocking(KesFiles.resolved(kesFile)).flatMap { first =>
      Ref.of[F, Path](first.getOrElse(kesFile)).map { target =>
        new HotKeySource[F]:
          def read = F.blocking(KesFiles.readHot(kesFile, opCertFile)).flatMap { r =>
            // the key is written back where it was read from, even if a link moves afterwards
            F.blocking(KesFiles.resolved(kesFile)).flatMap(_.traverse_(target.set)).as(r)
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
  */
final class HotKeys[F[_]: Async] private (
    state: Ref[F, PoolCredentials],
    lock: Mutex[F],
    source: Option[HotKeySource[F]],
    unwritten: Ref[F, Boolean],
    config: PraosConfig,
    events: ForgeEvents[F]
):
  private val F = Async[F]

  def current: F[PoolCredentials] = state.get

  /** Run `use` with the credentials, which cannot be replaced meanwhile. */
  def locked[A](use: PoolCredentials => F[A]): F[A] =
    lock.lock.surround(state.get.flatMap(use))

  /** The key written back after an evolution; a failure is reported and retried at the next slot.
    */
  private def write(c: PoolCredentials, kesPeriod: BigInt): F[Unit] =
    source.traverse_ { s =>
      s.persist(c.kes).flatMap {
        case Persisted.Failed(e) =>
          unwritten.set(true) *> events.emit(ForgeEvent.KesPersistFailed(kesPeriod, e))
        case Persisted.Written | Persisted.Unchanged | Persisted.Superseded =>
          unwritten.set(false)
      }
    }

  /** Evolve the key to the period of `kesPeriod` and write it back (the lock is held). A key that
    * cannot get there (it is already past it) is reported by the caller's signing, not here.
    */
  private[forge] def evolved(c: PoolCredentials, kesPeriod: BigInt): F[Either[ForgeError, Unit]] =
    val relative = (kesPeriod - c.opCert.kesPeriod).toInt
    F.delay(c.kes.period).flatMap { before =>
      F.blocking(c.kes.evolveTo(relative).leftMap(e => ForgeError.KesFailure(e.toString)))
        .flatTap(_.traverse_ { _ =>
          (write(c, kesPeriod) *> events.emit(ForgeEvent.KesEvolved(kesPeriod, relative)))
            .whenA(before != relative)
        })
    }

  /** At a slot: evolve to the slot's period when it has moved on (inside the certificate's window),
    * so the earlier key is gone from memory and disk as soon as the period changes, whether or not
    * the pool leads a slot in it; and write again a key that could not be written.
    */
  def advance(kesPeriod: BigInt): F[Unit] =
    lock.lock.surround(
      state.get.flatMap { c =>
        val relative = kesPeriod - c.opCert.kesPeriod
        val inside =
          relative >= 0 && relative < config.maxKesEvolutions && relative < Sum6KesSigning.Periods
        if inside && relative.toInt > c.kes.period then evolved(c, kesPeriod).void
        else unwritten.get.flatMap(write(c, kesPeriod).whenA)
      }
    )

  /** Re-read the key and certificate and switch to them if they are good (see
    * [[HotKeys.validate]]); otherwise keep the old ones and say why. `counter` is the pool's issue
    * counter on the chain.
    */
  def reload(kesPeriod: BigInt, counter: Option[BigInt]): F[Unit] =
    source.traverse_ { s =>
      s.read.flatMap {
        case Left(e) => refuse(ReloadRefusal.Unreadable(e))
        case Right(candidate) =>
          lock.lock.surround(
            state.get.flatMap { cur =>
              HotKeys.validate(cur, candidate, kesPeriod, counter, config) match
                case Left(why) => F.delay(candidate.kes.forget()) *> refuse(why)
                case Right(_)  => adopt(cur, candidate, kesPeriod)
            }
          )
      }
    }

  private def refuse(why: ReloadRefusal): F[Unit] = events.emit(ForgeEvent.KesReloadRefused(why))

  private def adopt(cur: PoolCredentials, candidate: HotCredentials, kesPeriod: BigInt): F[Unit] =
    // a certificate re-issued for the key already in use keeps that key, which is further on than
    // the file's copy; a new key replaces it and the old one is erased from memory
    val sameKey = candidate.kes.verificationKey == cur.kes.verificationKey
    val kes = if sameKey then cur.kes else candidate.kes
    val next = cur.withHot(HotCredentials(kes, candidate.opCert, cur.coldVkey))
    val relative = kesPeriod - candidate.opCert.kesPeriod
    for
      _ <- F.delay(if sameKey then candidate.kes.forget() else cur.kes.forget())
      _ <- state.set(next)
      _ <- unwritten.set(false)
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
    (Ref.of[F, PoolCredentials](credentials), Mutex[F], Ref.of[F, Boolean](false)).mapN {
      (state, lock, unwritten) => new HotKeys[F](state, lock, source, unwritten, config, events)
    }

  /** May a node forging for `current` 's pool switch to `candidate` at `kesPeriod`, when the pool's
    * issue counter on the chain is `counter` (none: not seen yet)?
    *
    * The same rules the chain applies to the headers it will carry (OCERT, `doValidateKESSignature`
    * of ouroboros-consensus `Protocol/Praos.hs`), so a certificate that passes here is not refused
    * there: the cold key is the pool's; the certificate names the KES key and carries the cold
    * key's signature; the period is inside `[start, start + maxKESEvolutions)`; and the counter is
    * the on-chain one or the one above ([[OCertRules.checkCounter]], the function the header rules
    * use).
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
      signed = OperationalCertificate.verify(
        candidate.coldVkey,
        candidate.opCert.hotVkey,
        candidate.opCert.sequence,
        start,
        candidate.opCert.sigma
      )
      _ <- Either.cond(
        signed == Right(OperationalCertificate.Result.OperationalCertificateSignatureVerified),
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
