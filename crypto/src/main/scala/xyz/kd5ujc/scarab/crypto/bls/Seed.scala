// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import xyz.kd5ujc.scarab.codec.Bytes

/** A 32-byte key-generation seed (`SeedSizeDSIGN` of cardano-base's `BLS12381DSIGN`). */
opaque type Seed = Bytes

object Seed:
  val size: Int = 32
  def fromBytes(bytes: Bytes): Either[BlsError, Seed] =
    if bytes.size == size then Right(bytes)
    else Left(BlsError.WrongLength(Operand.Seed, size, bytes.size))
  extension (s: Seed) def bytes: Bytes = s
