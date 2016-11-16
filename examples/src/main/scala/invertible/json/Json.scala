/*
 * Copyright 2015 - 2016 Moss Prescott
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package invertible.json

import invertible._

import scala.collection.immutable.ListMap

sealed abstract class Json[A]
final case class True[A]()                                      extends Json[A]
final case class False[A]()                                     extends Json[A]
final case class Null[A]()                                      extends Json[A]
final case class Str[A](value: String)                          extends Json[A]
final case class Num[A](value: BigDecimal, exp: Option[BigInt]) extends Json[A]
final case class Arr[A](value: Vector[A])                       extends Json[A]
final case class Obj[A](value: ListMap[String, A])              extends Json[A]


// TODO: use matryoshka's Fix
final case class Fix[F[_]](unFix: F[Fix[F]])

object Json {
  val syntax: Syntax[Fix[Json]] = new Syntax[Fix[Json]] {
    def apply[P[_]: Transcriber] = {
      import Iso._
      import Syntax._

      def fix[F[_], A] = Iso.total[F[Fix[F]], Fix[F]](Fix(_), _.unFix)

      val obj_   = gen.iso1[Json[Fix[Json]], Obj[Fix[Json]]].apply
      val arr_   = gen.iso1[Json[Fix[Json]], Arr[Fix[Json]]].apply
      val str_   = gen.iso1[Json[Fix[Json]], Str[Fix[Json]]].apply
      val num_   = gen.iso2[Json[Fix[Json]], Num[Fix[Json]]].apply
      val true_  = gen.iso0[Json[Fix[Json]], True[Fix[Json]]].apply
      val false_ = gen.iso0[Json[Fix[Json]], False[Fix[Json]]].apply
      val null_  = gen.iso0[Json[Fix[Json]], Null[Fix[Json]]].apply


      val ws = (text(" ") | text("\t") | text("\n")).label("white space")
      val skipWS = ws.many ^ ignore(List[Unit]())
      def optWS  = ws.many ^ ignore(List(()))
      def sepWS  = ws <* skipWS

      val hexDigitP =
        (char ^ subset(c => c >= '0' && c <= '9') ^ total[Char, Int](_ - '0', x => (x + '0').toChar) |
          char ^ subset(c => c >= 'A' && c <= 'F') ^ total[Char, Int](_ - 'A', x => (x + 'A').toChar) |
          char ^ subset(c => c >= 'a' && c <= 'f') ^ total[Char, Int](_ - 'a', x => (x + 'a').toChar))
            .label("hex digit (0-9, A-Z)")
      val escapedP =
        text("\"") ^ element('"') |
        text("\\") ^ element('\\') |
        text("/") ^ element('/') |
        text("b") ^ element('\b') |
        text("f") ^ element('\f') |
        text("n") ^ element('\n') |
        text("r") ^ element('\r') |
        text("t") ^ element('\t') |
        (text("u") *> hexDigitP * hexDigitP * hexDigitP * hexDigitP) ^ partial(
          { case (((d1, d2), d3), d4) => ((d1 << 12) | (d2 << 8) | (d3 << 4) | d4).toChar },
          { case c =>
            val x = c.toInt
            (((x >> 12, (x >> 8) & 0x07), (x >> 4) & 0x07), x & 0x07)
          }
        )


      val charP  =
        char ^ subset(c => c >= 32 && c != '"' && c != '\\') |
        (text("\\") *> escapedP)
      val strP   = (text("\"") *> charP.many <* text("\"")) ^ chars
      val jStrP  = strP ^ str_ ^ fix

      val digitP = (char ^ subset(c => c >= '0' && c <= '9')).label("digit")
      val nonZeroP = (char ^ subset(c => c >= '1' && c <= '9')).label("non-zero digit")
      val unpackNum: Iso[(((Int, String), Option[String]), Option[(Int, String)]), (BigDecimal, Option[BigInt])] = Iso.partial(
        {
          case (((sign, ip), fp), exp) =>
            (sign*BigDecimal(ip + fp.fold("")("." + _)), exp.map { case (expSign, exp) => expSign*BigInt(exp) })
        },
        {
          // NB: this is all pretty dodgy; the crux is that we need to pull out
          // the portions of the number as characters that will match up with the
          // syntax. That's the essence of the guarantee of isomorphism. But it’s
          // awfully painful to make that happen with complex typoes like
          // BigDecimal. Moreover if something’s wrong here the printer will just
          // fail without explanation.
          case (dec, exp) =>
            val sign = if (dec.signum == 0) 1 else dec.signum
            val ipStr = dec.abs.toBigInt.toString
            val fracStr = dec.abs.toString.replace(ipStr, "")  // questionable
            val expT = exp.map(x =>
              (if (x.signum == 0) 1 else x.signum,
                x.abs.toString))
            (((sign, ipStr), if (fracStr != "") Some(fracStr.substring(1)) else None), expT)
        })
      val numP   = (pure(1) |
                    text("-") ^ element(-1)) *
                  (text("0") ^ element("0") |
                    (nonZeroP * digitP.many) ^ cons ^ chars) *
                  (text(".") *> digitP.many1 ^ chars).optional *
                  ((text("e") | text("E")) *>
                    (pure(1) |
                      text("+") ^ element(1) |
                      text("-") ^ element(-1)) *
                    (digitP.many1 ^ chars)).optional ^
                  unpackNum


      // NB: space handling is as follows to avoid ambiguity. There is
      // (optional) space before the first element, each element is followed
      // by (skipped) space, and each comma is followed by (optional) space.
      def commaSep[A](p: P[A]): P[List[A]] =
        optWS *> ((p <* skipWS) sepBy (text(",") *> optWS))

      def arrP   = (text("[") *> commaSep(atomP) <* text("]")) ^ vector

      def objP   = (text("{") *>
                    commaSep(strP <*> skipWS *> text(":") *> optWS *> atomP)
                    <* text("}")) ^ listMap

      lazy val atomP: P[Fix[Json]] =
        text("true") ^ true_ ^ fix |
        text("false") ^ false_ ^ fix |
        text("null") ^ null_ ^ fix |
        strP ^ str_ ^ fix |
        numP ^ num_ ^ fix |
        arrP ^ arr_ ^ fix |
        objP ^ obj_ ^ fix

      skipWS *> atomP <* skipWS
    }
  }
}
