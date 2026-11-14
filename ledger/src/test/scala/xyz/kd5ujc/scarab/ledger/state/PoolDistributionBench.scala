// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}

/** Repeated reads of one immutable snapshot, with the uncached calculation as the control.
  * `ledger/Test/runMain xyz.kd5ujc.scarab.ledger.state.PoolDistributionBench`
  */
object PoolDistributionBench extends IOApp.Simple:
  private enum InputError extends RuntimeException:
    case Hex(cause: Bytes.HexError)
    case Vocabulary(cause: VocabError)

  private def key[R <: KeyRole](i: Int): IO[KeyHash[R]] =
    IO.fromEither(Bytes.fromHex(f"$i%056x").leftMap(e => InputError.Hex(e)))
      .flatMap(b => IO.fromEither(KeyHash.fromBytes[R](b).leftMap(e => InputError.Vocabulary(e))))

  private def snapshot: IO[SnapShot] =
    for
      vrf <- IO.fromEither(
        VRFVerKeyHash
          .fromBytes(Bytes.fromArray(Array.fill[Byte](32)(1)))
          .leftMap(e => InputError.Vocabulary(e))
      )
      stake <- IO.fromEither(
        NonZero.from(CompactCoin.fromUnsigned(100)).leftMap(InputError.Vocabulary(_))
      )
      entries <- (1 to 2000).toVector.traverse { i =>
        (key[StakePool](i), key[Staking](i)).mapN { (pool, staking) =>
          val credential = Credential.KeyHashObj[Staking](staking)
          val record = StakePoolSnapShot(
            CompactCoin.fromUnsigned(100),
            Rational(1, 2000),
            Sorted.emptySet,
            Coin.zero,
            vrf,
            PoolBlsKey.Leios(None),
            Coin.zero,
            Coin.zero,
            Rational.zero,
            Word64.fromUnsigned(1),
            credential
          )
          (
            (pool, record),
            (
              credential,
              StakeWithDelegation(
                stake,
                pool
              )
            )
          )
        }
      }
    yield SnapShot(ActiveStake(Sorted.map(entries.map(_._2))), Sorted.map(entries.map(_._1)))

  def run: IO[Unit] = snapshot.flatMap { s =>
    val iterations = 1000
    val workloads = Vector[(String, SnapShot => PoolDistr)](
      "uncached" -> PoolDistr.calculate,
      "cached" -> PoolDistr.of
    )
    workloads.traverse_ { (name, calculate) =>
      val work = IO.delay(
        (0 until iterations).foldLeft(0L)((sum, _) => sum + calculate(s).distribution.size)
      )
      work.replicateA_(2) *> (1 to 5).toVector.traverse_ { sample =>
        work.timed.flatMap { (elapsed, checksum) =>
          IO.println(
            s"$name sample=$sample pools=2000 iterations=$iterations ns=${elapsed.toNanos} checksum=$checksum"
          )
        }
      }
    }
  }
