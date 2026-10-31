// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.NotNull
import cats.syntax.all.*
import scala.reflect.ClassTag

/** What a library call threw, as a value: the boundary between the libraries that signal failure by
  * exception (Scalus's machine, the JDK, the native crypto loader) and the typed results here.
  */
private[plutus] object Thrown:
  /** `body` 's value, or the non-fatal exception it threw, or the `T` it threw: a JVM error that
    * `NonFatal` does not match (`StackOverflowError`, `LinkageError`) and the caller has a case
    * for.
    */
  def orAlso[T >: Null <: Throwable: ClassTag: NotNull, A](body: => A): Either[Throwable, A] =
    Either.catchOnly[T](Either.catchNonFatal(body)).leftWiden[Throwable].flatten
