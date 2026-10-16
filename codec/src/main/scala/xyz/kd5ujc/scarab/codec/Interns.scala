// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** A kind of value that one decode shares: the credentials, say, or the hashes. A kind is a value
  * declared once (a `val` of the codec that decodes it) and is the key of one table in the
  * [[Interns]] of each decode, so the tables of two kinds never meet and the type `A` is the type
  * the table holds.
  */
final class Interned[A]

/** The values of one decode that are equal, shared: the first instance decoded stands for all the
  * later ones, which are dropped as soon as they are found equal. A ledger state holds the same
  * credential in its accounts, its delegations, its three stake snapshots and every vote on a
  * proposal, and each occurrence decodes to an object of its own unless it is shared; the Haskell
  * decoder shares them the same way, against a table of what it has decoded
  * (`Cardano.Ledger.Binary.Decoding.Sharing`, `Interns` and `internsFromMap`, `Sharing.hs:53-66,
  * 111-135` of `cardano-ledger-binary` 1.9.1.0).
  *
  * The table belongs to a [[Document]], so it lives as long as the decode and no longer, and
  * nothing global exists. The values are immutable and equal to what they stand for, so a decoded
  * state is `==` with and without sharing. A table is mutable and is not thread-safe, as a decode
  * is not.
  */
private[codec] final class Interns:
  private val tables = new java.util.IdentityHashMap[Interned[?], Interns.Table]

  /** The first value equal to `a` that was given for this `kind`, or `a`. */
  def share[A](kind: Interned[A])(a: A): A =
    // the one cast: a table is only ever reached through its own kind, which fixes what it holds
    tables
      .computeIfAbsent(kind, _ => new Interns.Table)
      .share(a.asInstanceOf[AnyRef])
      .asInstanceOf[A]

private[codec] object Interns:
  /** A set of values with open addressing and linear probing, which keeps no entry object: the
    * values are its slots, 6 to 12 bytes a value at the load it keeps (a `java.util.HashMap` takes
    * about 45), and a lookup reads a slot and the value in it and no more. The decode that shares a
    * million credentials holds this table for as long as it runs, beside the result.
    */
  private final class Table:
    private var bits = 10
    private var slots = new Array[AnyRef](1 << bits)
    private var count = 0

    /** The slot to begin at: Fibonacci hashing, whose top bits depend on all of the hash's (the
      * hashes here are polynomial and their low bits are not to be trusted).
      */
    private def home(h: Int, bits: Int): Int = (h * 0x9e3779b9) >>> (32 - bits)

    def share(a: AnyRef): AnyRef =
      var i = home(a.hashCode, bits)
      val mask = slots.length - 1
      var found: AnyRef = null
      while found == null && slots(i) != null do
        if slots(i).equals(a) then found = slots(i)
        else i = (i + 1) & mask
      if found != null then found
      else
        slots(i) = a
        count += 1
        // at most two thirds full, so that a probe is short
        if count * 3 > slots.length * 2 then grow()
        a

    private def grow(): Unit =
      val old = slots
      bits += 1
      slots = new Array[AnyRef](1 << bits)
      val mask = slots.length - 1
      var k = 0
      while k < old.length do
        val v = old(k)
        if v != null then
          var i = home(v.hashCode, bits)
          while slots(i) != null do i = (i + 1) & mask
          slots(i) = v
        k += 1
