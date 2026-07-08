package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Numerical check for FusedScaleAccumulator's hand-derived align/LZD/round/
 *  exponent math.  Drives realistic (near-normalised) raw terms plus a valid
 *  FPn accumulator and compares the decoded output against a double-precision
 *  reference of  round_FPn(acc + term).  A gross exponent/index bug shows up as
 *  a factor-of-2 (or worse) error, far outside the ~1 ULP tolerance.
 */
class FusedScaleAccumulatorSpec extends AnyFlatSpec with ChiselScalatestTester {

  // Config identical to FDPUPostScaleMain (non-UE8M0 → fused path, acc9b).
  val scfg       = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
  val vectorSize = 8
  val K          = 32
  // M_acc is explicit now (K-based AccPrecision removed); the accumulator-width
  // correctness this spec checks is independent of the exact value.
  val w          = FDPUWidthMath(scfg, vectorSize, K,
                     accMantBits = 12, noEarlyRNE = false, widenUE8M0 = true)

  val mantBits  = w.actualAccMantBits
  val effTreeM  = w.effectiveTreeOutMantW
  val effExpW   = w.effectiveExpW
  val termMantW = effTreeM + scfg.resScaleMantWidth
  val termExpW  = math.max(scfg.resScaleAddExpWidth, effExpW + 2)

  // Same fracBits / topBitBias convention as the RTL (inherited from ScaleToFPn).
  def elemFrac(t: ElementType): Int = if (t.name == "INT8") 0 else t.elementWidthMant
  val fracBits   = elemFrac(scfg.elementTypeA) + elemFrac(scfg.elementTypeB) +
                   2 * scfg.stype.mantScaleWidth
  val topBitBias = 127 + termMantW - 1 - fracBits
  val accW       = 1 + 8 + mantBits

  def decodeFPn(bits: BigInt): Double = {
    val s = ((bits >> (8 + mantBits)) & 1).toInt
    val e = ((bits >> mantBits) & 0xFF).toInt
    val m = bits & ((BigInt(1) << mantBits) - 1)
    if (e == 0) 0.0
    else if (e == 255) (if (s == 1) Double.NegativeInfinity else Double.PositiveInfinity)
    else (if (s == 1) -1.0 else 1.0) * (1.0 + m.toDouble / math.pow(2, mantBits)) * math.pow(2, e - 127)
  }

  def encodeFPn(sign: Int, exp: Int, mant: BigInt): BigInt =
    (BigInt(sign) << (8 + mantBits)) | (BigInt(exp) << mantBits) | mant

  def termValue(sign: Int, exp: Int, mant: BigInt): Double =
    (if (sign == 1) -1.0 else 1.0) * mant.toDouble * math.pow(2, exp - fracBits)

  "FusedScaleAccumulator" should "match a double reference within ~1 ULP" in {
    test(new FusedScaleAccumulator(scfg, mantBits, termMantW, termExpW)) { c =>
      val rng = new scala.util.Random(1234)
      val ulp = math.pow(2, -(mantBits - 1))          // 2 * 2^-mantBits, generous
      var checked = 0

      for (_ <- 0 until 4000) {
        // ── Valid normal accumulator ──
        val accSign = rng.nextInt(2)
        val accExp  = 100 + rng.nextInt(50)            // [100,149], safe band
        val accMant = BigInt(rng.nextInt(1 << mantBits))
        val accBits = encodeFPn(accSign, accExp, accMant)
        val accVal  = decodeFPn(accBits)

        // ── Near-normalised term like (M_SA×M_SB)×treeMant: 0–2 leading zeros.
        //    Leading zeros + opposite signs exercise the subtraction-borrow path. ──
        val tSign   = rng.nextInt(2)
        val lead    = rng.nextInt(3)                    // 0, 1 or 2 leading zeros
        val topBit  = BigInt(1) << (termMantW - 1 - lead)
        val tMant   = topBit | BigInt(termMantW - 1 - lead, rng)
        // Choose termExp so the term's top-bit exp lands near accExp (exercises
        // real alignment + cancellation), within a ±8 window.
        val targetEt = accExp - 8 + rng.nextInt(17)
        val tExp     = targetEt - topBitBias
        val tVal     = termValue(tSign, tExp, tMant)

        c.io.accIn.poke(accBits.U(accW.W))
        c.io.termSign.poke(tSign.U)
        c.io.termExp.poke(tExp.S(termExpW.W))
        c.io.termMant.poke(tMant.U(termMantW.W))
        c.clock.step(1)

        val got     = decodeFPn(c.io.accOut.peek().litValue)
        val exact   = accVal + tVal

        // Skip boundary cases that legitimately over/underflow the FPn range.
        val exExp = if (exact == 0.0) 0 else math.getExponent(exact) + 127
        if (exExp > 1 && exExp < 254) {
          val tol = ulp * math.abs(exact) + 1e-30
          assert(math.abs(got - exact) <= tol,
            f"acc=$accVal%.6g term=$tVal%.6g exact=$exact%.6g got=$got%.6g " +
            f"(accBits=$accBits tSign=$tSign tExp=$tExp tMant=$tMant)")
          checked += 1
        }
      }
      assert(checked > 2000, s"too few in-range checks: $checked")
      println(s"[FusedScaleAccumulator] verified $checked randomized accumulate ops within ~1 ULP")
    }
  }

  it should "handle subtraction borrow: unnormalised term is 'far' by exponent but smaller" in {
    test(new FusedScaleAccumulator(scfg, mantBits, termMantW, termExpW)) { c =>
      val rng = new scala.util.Random(2025)
      val ulp = math.pow(2, -(mantBits - 1))
      var checked = 0
      for (_ <- 0 until 3000) {
        val accSign = rng.nextInt(2)
        val accExp  = 100 + rng.nextInt(50)
        val accMant = BigInt(rng.nextInt(1 << mantBits))
        val accBits = encodeFPn(accSign, accExp, accMant)
        val accVal  = decodeFPn(accBits)

        // Term: OPPOSITE sign, 2 leading zeros, top-bit exp AT or just ABOVE acc
        // (so it is chosen "far" yet its true magnitude is below acc).
        val tSign  = 1 - accSign
        val lead   = 2
        val topBit = BigInt(1) << (termMantW - 1 - lead)
        val tMant  = topBit | BigInt(termMantW - 1 - lead, rng)
        val tExp   = (accExp + rng.nextInt(3)) - topBitBias   // Et ∈ {Ea, Ea+1, Ea+2}
        val tVal   = termValue(tSign, tExp, tMant)

        c.io.accIn.poke(accBits.U(accW.W))
        c.io.termSign.poke(tSign.U)
        c.io.termExp.poke(tExp.S(termExpW.W))
        c.io.termMant.poke(tMant.U(termMantW.W))
        c.clock.step(1)

        val got   = decodeFPn(c.io.accOut.peek().litValue)
        val exact = accVal + tVal
        val exExp = if (exact == 0.0) 0 else math.getExponent(exact) + 127
        if (exExp > 1 && exExp < 254) {
          assert(math.abs(got - exact) <= ulp * math.abs(exact) + 1e-30,
            f"borrow case: acc=$accVal%.6g term=$tVal%.6g exact=$exact%.6g got=$got%.6g " +
            f"(accBits=$accBits tSign=$tSign tExp=$tExp tMant=$tMant)")
          checked += 1
        }
      }
      assert(checked > 1500, s"too few in-range borrow checks: $checked")
      println(s"[FusedScaleAccumulator] verified $checked subtraction-borrow ops within ~1 ULP")
    }
  }

  it should "reproduce the accumulator exactly when the term is zero" in {
    test(new FusedScaleAccumulator(scfg, mantBits, termMantW, termExpW)) { c =>
      val rng = new scala.util.Random(7)
      for (_ <- 0 until 200) {
        val accSign = rng.nextInt(2)
        val accExp  = 1 + rng.nextInt(253)
        val accMant = BigInt(rng.nextInt(1 << mantBits))
        val accBits = encodeFPn(accSign, accExp, accMant)
        c.io.accIn.poke(accBits.U(accW.W))
        c.io.termSign.poke(0.U)
        c.io.termExp.poke(0.S(termExpW.W))
        c.io.termMant.poke(0.U(termMantW.W))       // zero term
        c.clock.step(1)
        assert(c.io.accOut.peek().litValue == accBits,
          s"zero-term should pass acc through: got ${c.io.accOut.peek().litValue}, exp $accBits")
      }
    }
  }

  it should "return the term (as FPn) when the accumulator is zero" in {
    test(new FusedScaleAccumulator(scfg, mantBits, termMantW, termExpW)) { c =>
      val rng = new scala.util.Random(99)
      val ulp = math.pow(2, -(mantBits - 1))
      for (_ <- 0 until 500) {
        val tSign = rng.nextInt(2)
        val hi    = BigInt(1) << (termMantW - 1)
        val tMant = hi | BigInt(termMantW - 1, rng)
        val tExp  = (110 - topBitBias) + rng.nextInt(20)
        c.io.accIn.poke(0.U(accW.W))               // zero accumulator
        c.io.termSign.poke(tSign.U)
        c.io.termExp.poke(tExp.S(termExpW.W))
        c.io.termMant.poke(tMant.U(termMantW.W))
        c.clock.step(1)
        val got   = decodeFPn(c.io.accOut.peek().litValue)
        val exact = termValue(tSign, tExp, tMant)
        val exExp = math.getExponent(exact) + 127
        if (exExp > 1 && exExp < 254)
          assert(math.abs(got - exact) <= ulp * math.abs(exact) + 1e-30,
            f"zero-acc: exact=$exact%.6g got=$got%.6g")
      }
    }
  }
}
