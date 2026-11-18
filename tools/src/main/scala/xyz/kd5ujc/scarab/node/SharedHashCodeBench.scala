// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.{Cbor, CollidingBytes}
import xyz.kd5ujc.scarab.ledger.{BlockTransport, ByteStrings, DecodeField, Utxo, Value}
import xyz.kd5ujc.scarab.node.mempool.{RelayedTransactions, Source, TxKit}
import xyz.kd5ujc.scarab.node.ntc.NtcQuery
import xyz.kd5ujc.scarab.protocol.{NtcVersion, StateLayout}

import SharedHashCodeInputs.*

/** What the inputs of `SharedHashCodeSuite` cost, byte strings of one hash code against random ones
  * of the same sizes, at several sizes. Not part of `check`: run it with
  * `sbt "tools/runMain xyz.kd5ujc.scarab.node.SharedHashCodeBench [scale]"`, where `scale` (default
  * 1) multiplies the sizes; it prints the milliseconds of one run of each, after one run to warm
  * up.
  */
object SharedHashCodeBench extends IOApp:
  private def millis(d: FiniteDuration): Long = d.toMillis

  private def once[A](body: => A): IO[Long] = IO(body).timed.map((d, _) => millis(d))

  private def row(label: String, size: Int)(run: Strings => () => Any): IO[Unit] =
    for
      random <- IO(run(CollidingBytes.random(_, _)))
      colliding <- IO(run(CollidingBytes.of))
      _ <- once(random())
      r <- once(random())
      c <- once(colliding())
      _ <- IO.println(f"BENCH $label%-34s n=$size%8d  one-hash ${c}%8d ms  random ${r}%6d ms")
    yield ()

  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val scale = args.headOption.flatMap(_.toIntOption).getOrElse(1)
    val sizes = Vector(10000, 20000, 40000).map(_ * scale)
    val inputs = Vector(20, 40, 80).map(_ * scale)
    val rows =
      sizes.traverse_(n =>
        row("fetched body, datums", n) { s =>
          val raw = datumsBody(s, n)
          () => BlockTransport.check(conway.header, raw)
        } *> row("fetched body, certificates", n) { s =>
          val raw = certificatesBody(s, n)
          () => BlockTransport.check(conway.header, raw)
        } *> row("fetched body, proposals", n) { s =>
          val raw = proposalsBody(s, n)
          () => BlockTransport.check(conway.header, raw)
        } *> row("fetched body, auxiliary keys", n) { s =>
          val raw = auxiliaryKeysBody(s, n)
          () => BlockTransport.check(conway.header, raw)
        } *> row("mint field over the size bound", n) { s =>
          val node = Cbor.decode(mint(s, n)).fold(e => sys.error(e.message), identity)
          () =>
            Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Rejecting)(using
              ByteStrings.Definite
            )
        } *> row("inputs missing from the set", n) { s =>
          val asked = SharedHashCodeInputs.inputs(s, n)
          () => Utxo.empty.missing(asked)
        } *> row("query, GetStakePoolParams", n) { s =>
          val raw = poolsQuery(s, n)
          () => NtcQuery.decode(raw, StateLayout.Node11_1, NtcVersion.V23)
        } *> row("query, GetFilteredDelegations...", n) { s =>
          val raw = accountsQuery(s, n)
          () => NtcQuery.decode(raw, StateLayout.Node11_1, NtcVersion.V23)
        }
      ) *> Vector(10, 40, 160)
        .map(_ * scale)
        .traverse_(k =>
          // bounded by what a value may hold and still compact; kept, as the stored values keep the
          // maps they were read into
          row("fetched body, outputs of 1,480 names", k) { s =>
            val raw = outputsBody(s, k)
            () => BlockTransport.check(conway.header, raw)
          }
        ) *> sizes.traverse_(n =>
        // below decoder version 9 names of quantity zero are dropped after the maps are built
        row("pre-Conway mint, names of zero", n) { s =>
          val node = Cbor.decode(mint(s, n, 0)).fold(e => sys.error(e.message), identity)
          () =>
            Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Pruning)(using
              ByteStrings.Definite
            )
        }
      ) *> sizes.traverse_(n =>
        row("pre-Conway mint, empty policies", n) { s =>
          val node = Cbor
            .decode(emptyPolicies(s, n), Cbor.Limits.Unbounded)
            .fold(e => sys.error(e.message), identity)
          () =>
            Value.multiasset(node, DecodeField.Mint, signed = true, Value.Decoding.Pruning)(using
              ByteStrings.Definite
            )
        }
      ) *> row("relayed transaction, datums", 9300) { s =>
        val raw = relayed(s, 9300)
        () => RelayedTransactions.read(raw)
      } *> inputs.traverse_(k =>
        row("mempool add, unbalanced (inputs)", k) { s =>
          val (pool, raw) = unbalanced(s, k)
          () => pool.add(raw, TxKit.env, Source.Remote)
        }
      )
    rows.as(cats.effect.ExitCode.Success)
