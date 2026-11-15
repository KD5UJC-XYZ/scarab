// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.assertions

import cats.effect.{IO, Ref}
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*

/** An assertion as a site made it: the property, its condition (false for an unreachable one) and
  * its details as they encode.
  */
final case class Made(property: Property, condition: Boolean, details: JsonObject)

/** Assertions that keep every call, in order, and write nothing: the interpreter a site's test
  * checks its call with.
  */
object Collecting:
  /** The assertions, and what they were asked so far. */
  def create: IO[(Assertions[IO], IO[Vector[Made]])] =
    Ref.of[IO, Vector[Made]](Vector.empty).map { made =>
      val assertions = new Assertions[IO]:
        def sometimes[D: Encoder.AsObject](
            property: Property.Sometimes,
            condition: Boolean,
            details: => D
        ): IO[Unit] = made.update(_ :+ Made(property, condition, details.asJsonObject))
        def always[D: Encoder.AsObject](
            property: Property.Always,
            condition: Boolean,
            details: => D
        ): IO[Unit] = made.update(_ :+ Made(property, condition, details.asJsonObject))
        def unreachable[D: Encoder.AsObject](
            property: Property.Unreachable,
            details: => D
        ): IO[Unit] =
          made.update(_ :+ Made(property, false, details.asJsonObject))
      (assertions, made.get)
    }
