// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes

/** Scripts whose decoded program is many times larger than their bytes, each in distinct variants:
  * the shapes that bound the heap of a cache of decoded programs, and the lists of `Data`. The
  * first three are admitted, and evaluate to `()`, in V1 to V3 from protocol 10 to 12; [[deepType]]
  * is admitted at protocol 10, [[constrs]] by V3 and [[emptyValues]] by V3 at protocol 12.
  *
  *   - [[pairTree]]: `[(lam x ()) (con (list (pair (pair L L) (pair L (pair L L)))) [...])]`, with
  *     `L = list bool` and every `L` empty, six bits an element (type size 29);
  *   - [[lams]]: `(lam (lam ... ()))`, four bits a term;
  *   - [[emptyLists]]: `[(lam x ()) (con (list (list bool)) [[], [], ...])]`, two bits an element;
  *   - [[deepType]]: `[(lam x ()) (con (list (list ... (list bool))) [])]`, ten bits a level of the
  *     type, which only protocols before 11 admit at this depth;
  *   - [[constrs]]: `(constr 0 [(constr 0 [... ()])])`, program version 1.1.0, fourteen bits a
  *     level;
  *   - [[dataChain]]: `[(lam x ()) (con data (List [List [... List []]]))]`, a byte a level;
  *   - [[dataList]]: `[(lam x ()) (con (list data) [List [], List [], ...])]`, four bytes an
  *     element;
  *   - [[dataPairs]]: `[(lam x ()) (con (list (pair data data)) [(List [List [], List []], ...),
  *     ...])]`, twelve bytes an element;
  *   - [[emptyValues]]: `[(lam x ()) (con (list value) [{}, {}, ...])]`, two bits an element.
  *
  * Variant `v` of a shape has `v` elements, levels or terms fewer than variant 0, so variants
  * differ.
  */
object AmplifyingScripts:
  private val Bool = Vector(7, 5, 4)
  private val PairTree =
    Vector(7, 5, 7, 7, 6, 7, 7, 6) ++ Bool ++ Bool ++ Vector(7, 7, 6) ++ Bool ++ Vector(7, 7, 6) ++
      Bool ++ Bool

  /** The bits of the program's version, 1.0.0. */
  private def versioned: FlatProgram.Bits =
    val b = FlatProgram.Bits()
    Vector(1, 0, 0).foreach(b.natural(_))
    b

  private def unit(b: FlatProgram.Bits): Unit =
    b.bits(4, 4)
    tags(b, Vector(3))

  private def tags(b: FlatProgram.Bits, types: Vector[Int]): Unit =
    types.foreach { tag =>
      b.bit(1)
      b.bits(tag, 4)
    }
    b.bit(0)

  /** `[(lam x ()) (con <types> <elements>)]` of about `bytes` bytes: a list of as many elements of
    * `element` bits as fit.
    */
  private def listOf(bytes: Int, variant: Int, types: Vector[Int], element: Vector[Int]): Bytes =
    val b = versioned
    b.bits(3, 4)
    b.bits(2, 4)
    unit(b)
    b.bits(4, 4)
    tags(b, types)
    val head = 8 * 3 + 4 + 4 + 4 + 6 + 4 + 5 * types.size + 1
    val n = (bytes * 8 - head - 9) / element.size - variant
    (0 until n).foreach(_ => element.foreach(b.bit(_)))
    b.bit(0)
    Bytes.fromArray(FlatProgram.cborByteString(b.bytes))

  def pairTree(bytes: Int, variant: Int): Bytes =
    listOf(bytes, variant, PairTree, Vector(1, 0, 0, 0, 0, 0))

  def emptyLists(bytes: Int, variant: Int): Bytes =
    listOf(bytes, variant, Vector(7, 5, 7, 5, 4), Vector(1, 0))

  def lams(bytes: Int, variant: Int): Bytes =
    val b = versioned
    (0 until bytes * 2 - 20 - variant).foreach(_ => b.bits(2, 4))
    unit(b)
    Bytes.fromArray(FlatProgram.cborByteString(b.bytes))

  def deepType(bytes: Int, variant: Int): Bytes =
    val levels = (bytes * 8 - 80) / 10 - variant
    val b = versioned
    b.bits(3, 4)
    b.bits(2, 4)
    unit(b)
    b.bits(4, 4)
    tags(b, Vector.fill(levels)(Vector(7, 5)).flatten :+ 4)
    b.bit(0)
    Bytes.fromArray(FlatProgram.cborByteString(b.bytes))

  def constrs(bytes: Int, variant: Int): Bytes =
    val levels = (bytes * 8 - 60) / 14 - variant
    val b = FlatProgram.Bits()
    Vector(1, 1, 0).foreach(b.natural(_))
    (0 until levels).foreach { _ =>
      b.bits(8, 4)
      b.natural(0)
      b.bit(1)
    }
    unit(b)
    (0 until levels).foreach(_ => b.bit(0))
    Bytes.fromArray(FlatProgram.cborByteString(b.bytes))

  /** `[(lam x ()) (con <types> ...)]` with the constant's value written by `value`. */
  private def applied(types: Vector[Int])(value: FlatProgram.Bits => Unit): Bytes =
    val b = versioned
    b.bits(3, 4)
    b.bits(2, 4)
    unit(b)
    b.bits(4, 4)
    tags(b, types)
    value(b)
    Bytes.fromArray(FlatProgram.cborByteString(b.bytes))

  private val EmptyList = Array(0x80.toByte)

  def dataChain(bytes: Int, variant: Int): Bytes =
    val levels = (bytes - 32) * 255 / 256 - variant
    applied(Vector(8))(_.byteString(Array.fill(levels)(0x81.toByte) :+ 0x80.toByte))

  def dataList(bytes: Int, variant: Int): Bytes =
    applied(Vector(7, 5, 8)) { b =>
      (0 until (bytes - 16) / 4 - variant).foreach { _ =>
        b.bit(1)
        b.byteString(EmptyList)
      }
      b.bit(0)
    }

  private val ListOfTwo = Array(0x82.toByte, 0x80.toByte, 0x80.toByte)

  def dataPairs(bytes: Int, variant: Int): Bytes =
    applied(Vector(7, 5, 7, 7, 6, 8, 8)) { b =>
      (0 until (bytes - 16) / 12 - variant).foreach { _ =>
        b.bit(1)
        b.byteString(ListOfTwo)
        b.byteString(ListOfTwo)
      }
      b.bit(0)
    }

  def emptyValues(bytes: Int, variant: Int): Bytes =
    applied(Vector(7, 5, 13)) { b =>
      (0 until (bytes - 16) * 4 - variant).foreach { _ =>
        b.bit(1)
        b.bit(0)
      }
      b.bit(0)
    }
