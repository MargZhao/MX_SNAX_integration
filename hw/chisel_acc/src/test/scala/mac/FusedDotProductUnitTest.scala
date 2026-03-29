package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import scala.util.Random

class FusedDotProductUnitTest extends AnyFunSuite with ChiselScalatestTester {

  // -----------------------------------------------------------------------
  // Log file — verbose output here; stdout only for failures
  // -----------------------------------------------------------------------
  private val _logWriter: java.io.PrintWriter = {
    val fw = new java.io.FileWriter("fused_dot_product_unit_test.log", false)
    new java.io.PrintWriter(fw, true)
  }
  private def log(msg: String): Unit    = _logWriter.println(msg)
  private def logErr(msg: String): Unit = { _logWriter.println(msg); println(msg) }

  // -----------------------------------------------------------------------
  // Software golden model
  // -----------------------------------------------------------------------

  /** Decode raw bit-pattern → Double (MX element format). */
  def decodeElement(raw: Int, t: ElementType): Double = {
    val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
    if (t.name == "INT8") {
      val mag = raw & ((1 << (t.totalWidth - 1)) - 1)
      sign * mag.toDouble
    } else {
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0)
        sign * (mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, 1 - t.bias)
      else
        sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, exp - t.bias)
    }
  }

  /** Decode raw bit-pattern → Double (MX scale format, no sign bit). */
  def decodeScale(raw: Int, s: ScaleType): Double = {
    val expBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) {
      Math.pow(2, expBits - s.bias)
    } else {
      val mantBits  = raw & ((1 << s.mantScaleWidth) - 1)
      val implicit1 = if (expBits > 0) 1.0 else 0.0
      (implicit1 + mantBits.toDouble / (1 << s.mantScaleWidth)) * Math.pow(2, expBits - s.bias)
    }
  }

  /** Encode Double → raw bits for an ElementType (best-effort, clamps extremes). */
  def encodeElement(value: Double, t: ElementType): Int = {
    if (value == 0.0) return 0
    val sign   = if (value < 0) 1 else 0
    val absVal = math.abs(value)
    if (t.name == "INT8") {
      (sign << 7) | math.round(absVal).toInt.min(127)
    } else {
      val expUnbiased = math.floor(math.log(absVal) / math.log(2)).toInt
      val expBiased   = expUnbiased + t.bias
      if (expBiased <= 0) {
        val mantInt = math.round(absVal / math.pow(2, 1 - t.bias) * (1 << t.elementWidthMant))
                          .toInt.min((1 << t.elementWidthMant) - 1)
        (sign << (t.totalWidth - 1)) | mantInt
      } else {
        val expClamped = expBiased.min((1 << t.elementWidthExp) - 1)
        val mantInt    = math.round((absVal / math.pow(2, expUnbiased) - 1.0) * (1 << t.elementWidthMant))
                              .toInt.min((1 << t.elementWidthMant) - 1).max(0)
        (sign << (t.totalWidth - 1)) | (expClamped << t.elementWidthMant) | mantInt
      }
    }
  }

  /** Encode positive Double → raw bits for a ScaleType. */
  def encodeScale(value: Double, s: ScaleType): Int = {
    val expUnbiased = math.floor(math.log(value) / math.log(2)).toInt
    val expBiased   = (expUnbiased + s.bias).max(0).min((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) expBiased
    else {
      val mantInt = math.round((value / math.pow(2, expUnbiased) - 1.0) * (1 << s.mantScaleWidth))
                         .toInt.min((1 << s.mantScaleWidth) - 1).max(0)
      (expBiased << s.mantScaleWidth) | mantInt
    }
  }

  /**
   * SW golden model for one fused-dot-product cycle:
   *   result = (Σ_i  decodeA(as_i) × decodeB(bs_i)) × decodeScale(sA) × decodeScale(sB)
   *
   * Scale factors are broadcast to all lanes (MX block-float convention).
   */
  def swFusedProduct(cfg: ScaleAddConfig)(
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int
  ): Float = {
    val sA     = decodeScale(scaleA, cfg.stype)
    val sB     = decodeScale(scaleB, cfg.stype)
    val dotSum = as.zip(bs).map { case (a, b) =>
      decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB)
    }.sum
    (dotSum * sA * sB).toFloat
  }

  /** Read accOut as IEEE 754 Float. */
  def peekFloat(dut: FusedDotProductUnit): Float =
    intBitsToFloat(dut.io.accOut.peek().litValue.toInt)

  /**
   * Bit-exact check for a single FP32Adder operation.
   * Asserts that HW output bits == expectedBits and logs context on failure.
   */
  def checkFP32Add(dut: FP32Adder, aBits: Int, bBits: Int, expectedBits: Int): Unit = {
    val aF   = intBitsToFloat(aBits)
    val bF   = intBitsToFloat(bBits)
    val expF = intBitsToFloat(expectedBits)
    dut.io.a.poke(aBits.U)
    dut.io.b.poke(bBits.U)
    val hw  = dut.io.out.peek().litValue.toInt
    val hwF = intBitsToFloat(hw)
    assert(hw == expectedBits,
      f"FP32Add($aF%g + $bF%g): " +
      f"got 0x${hw.toHexString} ($hwF%g), " +
      f"expected 0x${expectedBits.toHexString} ($expF%g)")
  }

  /**
   * Drive reset=1 so asyncRstN = ~1 = 0 (not in reset) for normal operation.
   * Required because generator uses active-low async reset (!reset.asBool),
   * but ChiselTest holds reset=0 during normal operation, which would keep
   * asyncRstN permanently high and freeze all registers at 0.
   */
  def initDut(dut: FusedDotProductUnit): Unit = {
    dut.reset.poke(true.B)
    dut.io.validIn.poke(false.B)
    dut.io.resetAcc.poke(false.B)
  }

  /** Pack element raw values into a single UInt (element 0 at LSB). */
  def packElements(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  /** Drive one fused-MAC cycle (all vectorSize lanes) and advance the clock. */
  def driveOne(
    dut: FusedDotProductUnit, cfg: ScaleAddConfig,
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int
  ): Unit = {
    dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
    dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
    dut.io.share_exp_A_i.poke(scaleA.U)
    dut.io.share_exp_B_i.poke(scaleB.U)
    dut.io.validIn.poke(true.B)
    dut.clock.step()
    dut.io.validIn.poke(false.B)
  }

  /**
   * Drive a sequence of fused-MAC cycles and check the running accumulator
   * against the SW golden model after every cycle.
   *
   * @param cycles  Seq of (as, bs, rawScaleA, rawScaleB) — one entry per cycle.
   * @param tol5pct Use 5 % relative + 1e-37 absolute tolerance (same as DotProductUnitTest).
   */
  def runCycles(
    dut: FusedDotProductUnit, cfg: ScaleAddConfig,
    cycles: Seq[(Seq[Int], Seq[Int], Int, Int)],
    header: String = ""
  ): Unit = {
    if (header.nonEmpty) {
      log("=" * 70)
      log(header)
      log("=" * 70)
    }
    var swAcc = 0.0f
    for (((as, bs, sA, sB), i) <- cycles.zipWithIndex) {
      val cycleVal = swFusedProduct(cfg)(as, bs, sA, sB)
      driveOne(dut, cfg, as, bs, sA, sB)
      swAcc += cycleVal
      val hw     = peekFloat(dut)
      val tol    = math.abs(swAcc) * 0.05f + 1e-37f
      val isFail = math.abs(hw - swAcc) > tol
      val line   = f"Cycle $i%2d | cycleSum=$cycleVal%9.4f | SW=$swAcc%10.4f | HW=$hw%10.4f" +
                   (if (isFail) " *** MISMATCH ***" else "")
      if (isFail) logErr(line) else log(line)
      assert(!isFail, s"Mismatch at cycle $i: hw=$hw expected=$swAcc (tol=$tol)")
    }
  }

  // -----------------------------------------------------------------------
  // MX block quantization helpers
  // -----------------------------------------------------------------------

  /** Maximum representable (positive) value for an element type.
   *
   *  For INT8 the magnitude field is 7 bits → max = 127.
   *  For floating-point types the all-ones biased exponent is reserved
   *  (NaN/Inf), so max biased exp = 2^expBits − 2.
   */
  def maxElemRepr(t: ElementType): Double =
    if (t.name == "INT8") {
      ((1 << (t.totalWidth - 1)) - 1).toDouble   // 127
    } else {
      val maxExpBiased = (1 << t.elementWidthExp) - 2   // all-1s reserved
      val maxMant      = (1 << t.elementWidthMant) - 1
      (1.0 + maxMant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, maxExpBiased - t.bias)
    }

  /** Quantize a block of floating-point values to an MX format.
   *
   *  MX convention: 32 elements share one scale factor.  The shared scale
   *  is chosen as the smallest power-of-2 (or scale-type-representable value)
   *  such that every element divided by that scale fits inside the element
   *  type's range.
   *
   *  @param values  Raw FP values — normally 32 for a full MX block.
   *  @param et      Target element type.
   *  @param st      Target scale type.
   *  @return        (encoded elements, encoded shared scale)
   */
  def quantizeBlock(values: Seq[Double], et: ElementType, st: ScaleType): (Seq[Int], Int) = {
    val maxAbs = values.map(math.abs).max.max(1e-38)   // guard against log(0)

    // Ideal (real-valued) scale: map [max_abs] onto [maxElemRepr]
    // For a pure power-of-2 scale we floor to the nearest power-of-2.
    val idealScale   = maxAbs / maxElemRepr(et)
    val scaleExp     = math.floor(math.log(idealScale.max(1e-38)) / math.log(2)).toInt
    // Clamp to what the scale type can actually represent
    val minScaleExp  = -(st.bias)
    val maxScaleExp  = (1 << st.expScaleWidth) - 1 - st.bias
    val clampedExp   = scaleExp.max(minScaleExp).min(maxScaleExp)
    val scaleDouble  = math.pow(2.0, clampedExp)

    // Encode the scale and decode back so element quantization uses the
    // same rounding as the hardware will see.
    val rawScale    = encodeScale(scaleDouble, st)
    val decodedScale = decodeScale(rawScale, st)

    val rawElems = values.map(v => encodeElement(v / decodedScale, et))
    (rawElems, rawScale)
  }

  // -----------------------------------------------------------------------
  // Pre-encoded constants (default E4M3 × E2M1 / UE5M3)
  // -----------------------------------------------------------------------
  val defaultScfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)

  val e4m3_1    = encodeElement(1.0,  MXFormats.E4M3)
  val e4m3_neg1 = encodeElement(-1.0, MXFormats.E4M3)
  val e4m3_2    = encodeElement(2.0,  MXFormats.E4M3)
  val e4m3_1p5  = encodeElement(1.5,  MXFormats.E4M3)
  val e2m1_1    = encodeElement(1.0,  MXFormats.E2M1)
  val e2m1_2    = encodeElement(2.0,  MXFormats.E2M1)
  val ue5m3_1   = encodeScale(1.0,    ScaleFormats.UE5M3)
  val ue5m3_2   = encodeScale(2.0,    ScaleFormats.UE5M3)

  // -----------------------------------------------------------------------
  // Pre-encoded constants — E5M2 × E5M2 / UE8M0, vec=4
  //   E5M2 : 1.5.2, bias 15 — representable fractions are 0, 0.25, 0.5, 0.75
  //   UE8M0: pure power-of-2 scale (8-bit exponent, no mantissa)
  // -----------------------------------------------------------------------
  val e5m2Scfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)

  val e5m2_0p5   = encodeElement( 0.5,  MXFormats.E5M2)
  val e5m2_0p75  = encodeElement( 0.75, MXFormats.E5M2)
  val e5m2_1     = encodeElement( 1.0,  MXFormats.E5M2)
  val e5m2_1p25  = encodeElement( 1.25, MXFormats.E5M2)
  val e5m2_1p5   = encodeElement( 1.5,  MXFormats.E5M2)
  val e5m2_1p75  = encodeElement( 1.75, MXFormats.E5M2)
  val e5m2_2     = encodeElement( 2.0,  MXFormats.E5M2)
  val e5m2_3     = encodeElement( 3.0,  MXFormats.E5M2)
  val e5m2_n0p5  = encodeElement(-0.5,  MXFormats.E5M2)
  val e5m2_n0p75 = encodeElement(-0.75, MXFormats.E5M2)
  val e5m2_n1    = encodeElement(-1.0,  MXFormats.E5M2)
  val e5m2_n1p25 = encodeElement(-1.25, MXFormats.E5M2)
  val e5m2_n1p5  = encodeElement(-1.5,  MXFormats.E5M2)
  val e5m2_n1p75 = encodeElement(-1.75, MXFormats.E5M2)
  val e5m2_n2    = encodeElement(-2.0,  MXFormats.E5M2)
  val ue8m0_0p5  = encodeScale(0.5, ScaleFormats.UE8M0)
  val ue8m0_1    = encodeScale(1.0, ScaleFormats.UE8M0)
  val ue8m0_2    = encodeScale(2.0, ScaleFormats.UE8M0)
  val ue8m0_4    = encodeScale(4.0, ScaleFormats.UE8M0)

  // -----------------------------------------------------------------------
  // Test 1: Reset clears accumulator
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: Reset clears accumulator") {
    log("\n[TEST 1] Reset clears accumulator")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.accOut.expect(0.U, "initial accOut should be 0")

        dut.io.resetAcc.poke(true.B)
        dut.clock.step()
        dut.io.accOut.expect(0.U, "accOut should stay 0 after reset")
        dut.io.resetAcc.poke(false.B)
      }
      log("[PASSED] Reset clears accumulator")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Reset clears accumulator: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 2: validOut handshake timing
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: validOut handshake timing") {
    log("\n[TEST 2] validOut handshake timing (vec=4)")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.op_a_i.poke(packElements(Seq.fill(4)(e4m3_1), defaultScfg.elementTypeA.totalWidth).U)
        dut.io.op_b_i.poke(packElements(Seq.fill(4)(e2m1_1), defaultScfg.elementTypeB.totalWidth).U)
        dut.io.share_exp_A_i.poke(ue5m3_1.U)
        dut.io.share_exp_B_i.poke(ue5m3_1.U)

        dut.io.validOut.expect(false.B, "validOut must be false when validIn=0 (before any cycle)")

        dut.io.validIn.poke(true.B)
        dut.clock.step()
        dut.io.validIn.poke(false.B)
        dut.io.validOut.expect(true.B, "validOut must be true one cycle after validIn pulse")

        dut.clock.step()
        dut.io.validOut.expect(false.B, "validOut must return false when validIn=0")
      }
      log("[PASSED] validOut handshake timing")
    } catch { case e: Exception =>
      logErr(s"[FAILED] validOut handshake timing: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 3: vec=1 scalar equivalence — matches DotProductUnit behaviour
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=1 scalar equivalence (1×1×1×1 = 1.0)") {
    log("\n[TEST 3] vec=1 scalar equivalence")
    try {
      test(new FusedDotProductUnit(defaultScfg, 1, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val expected = swFusedProduct(defaultScfg)(Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected: $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=1 scalar equivalence")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=1 scalar equivalence: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 4: vec=4 all-ones fused → 4.0
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=4 all-ones fused → 4.0") {
    log("\n[TEST 4] vec=4 all-ones (1×1×1×1 per lane) → 4.0")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 all-ones fused → 4.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 5: vec=8 all-ones fused → 8.0
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=8 all-ones fused → 8.0") {
    log("\n[TEST 5] vec=8 all-ones → 8.0")
    try {
      test(new FusedDotProductUnit(defaultScfg, 8, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(8)(e4m3_1)
        val bs       = Seq.fill(8)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=8 all-ones fused → 8.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=8 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 6: vec=4 mixed signs — partial cancellation
  //   Lane values: [+1, -1, +1, -1] × [1, 1, 1, 1] = 0
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=4 mixed signs → near-zero (cancellation)") {
    log("\n[TEST 6] vec=4 mixed signs [+1,-1,+1,-1] × [1,1,1,1] → 0.0")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq(e4m3_1, e4m3_neg1, e4m3_1, e4m3_neg1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        // Allow small absolute residual for exact cancellation in FP32
        val tol = math.abs(expected) * 0.01f + 1e-37f + 1e-6f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 mixed signs cancellation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 mixed signs cancellation: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 7: vec=4 scale amplification — scaleA=2, scaleB=2 → 4×1 × 4 = 16.0
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=4 scale=2×2 amplification → 16.0") {
    log("\n[TEST 7] vec=4, all-ones, scale 2×2 → 4×1×2×2 = 16.0")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_2, ue5m3_2)
        driveOne(dut, defaultScfg, as, bs, ue5m3_2, ue5m3_2)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 scale=2×2 amplification")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 scale amplification: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 8: Reset mid-accumulation
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: Reset mid-accumulation (vec=4)") {
    log("\n[TEST 8] Reset mid-accumulation (vec=4)")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)

        // First accumulation cycle
        driveOne(dut, defaultScfg, Seq.fill(4)(e4m3_2), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1)
        val before = dut.io.accOut.peek().litValue.toInt
        log(s"Before reset: 0x${before.toHexString}  (${intBitsToFloat(before)})")
        assert(before != 0, "accOut should be non-zero after accumulation")

        // Reset
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.accOut.expect(0.U, "accOut should be 0 after reset")
        dut.io.resetAcc.poke(false.B)

        // Single cycle after reset
        val as1      = Seq.fill(4)(e4m3_1)
        val bs1      = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as1, bs1, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as1, bs1, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"After reset + 1 cycle: HW=$hw%.6f expected=$expected%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch after reset: hw=$hw expected=$expected")
      }
      log("[PASSED] Reset mid-accumulation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Reset mid-accumulation: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 9: vec=4 multi-cycle accumulation — 4 cycles each adding 4.0 → 16.0
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=4 multi-cycle (4 cycles × 4.0 → 16.0, VCD)") {
    log("\n[TEST 9] vec=4 multi-cycle: 4 cycles of (4×1×1×1×1) → 16.0")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val cycles = Seq.fill(4)(
          (Seq.fill(4)(e4m3_1), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1)
        )
        runCycles(dut, defaultScfg, cycles, "vec=4 multi-cycle (4 cycles)")
      }
      log("[PASSED] vec=4 multi-cycle accumulation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 multi-cycle: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 10: vec=4 mixed-value multi-cycle — negative contributions
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: vec=4 mixed-value multi-cycle (negative/positive)") {
    log("\n[TEST 10] vec=4 mixed-value multi-cycle")
    try {
      test(new FusedDotProductUnit(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val cycles = Seq(
          // cycle 0: [1, 2, -1, 1] × [1, 1, 1, 1], scale 1×1 → sum = 3.0
          (Seq(e4m3_1, e4m3_2, e4m3_neg1, e4m3_1), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1),
          // cycle 1: [1, 1, 1, 1] × [2, 2, 2, 2], scale 1×1 → sum = 8.0
          (Seq.fill(4)(e4m3_1), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1),
          // cycle 2: [-1, -1, -1, -1] × [1, 1, 1, 1], scale 2×1 → sum = -8.0
          (Seq.fill(4)(e4m3_neg1), Seq.fill(4)(e2m1_1), ue5m3_2, ue5m3_1),
          // cycle 3: [1, 1.5, -1, 2] × [1, 1, 1, 1], scale 1×1 → sum = 3.5
          (Seq(e4m3_1, e4m3_1p5, e4m3_neg1, e4m3_2), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1)
        )
        runCycles(dut, defaultScfg, cycles, "vec=4 mixed-value multi-cycle")
      }
      log("[PASSED] vec=4 mixed-value multi-cycle")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 mixed-value multi-cycle: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 11: E5M2 × E4M3 / UE8M0 — vec=4, 3-cycle mixed
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: E5M2 x E4M3 / UE8M0 vec=4 mixed values") {
    log("\n[TEST 11] E5M2 x E4M3 / UE8M0 vec=4 3-cycle mixed")
    try {
      val cfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
      val vsize = 4
      val cycles = Seq(
        (Seq(1.0, 2.0, -1.0, 1.5),  Seq(1.0, 1.0,  2.0, 1.0), 1.0, 1.0),
        (Seq(1.0, 1.0,  1.0, 1.0),  Seq(2.0, 1.0, -1.0, 2.0), 2.0, 1.0),
        (Seq(2.0, 1.5,  1.0, -1.0), Seq(1.0, 2.0,  1.0, 1.0), 1.0, 2.0)
      ).map { case (as, bs, sA, sB) =>
        (as.map(encodeElement(_, cfg.elementTypeA)),
         bs.map(encodeElement(_, cfg.elementTypeB)),
         encodeScale(sA, cfg.stype),
         encodeScale(sB, cfg.stype))
      }
      test(new FusedDotProductUnit(cfg, vsize, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "E5M2 x E4M3 / UE8M0 vec=4")
      }
      log("[PASSED] E5M2 x E4M3 / UE8M0 vec=4")
    } catch { case e: Exception =>
      logErr(s"[FAILED] E5M2 x E4M3 / UE8M0 vec=4: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 12: E3M2 × E2M3 / UE6M2 — vec=4, 4-cycle with fractional mantissa
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: E3M2 x E2M3 / UE6M2 vec=4 fractional mantissa") {
    log("\n[TEST 12] E3M2 x E2M3 / UE6M2 vec=4 fractional mantissa")
    try {
      val cfg   = ScaleAddConfig(MXFormats.E3M2, MXFormats.E2M3, ScaleFormats.UE6M2)
      val vsize = 4
      val cycles = Seq(
        (Seq(1.0,  1.25, -1.5, 2.0),  Seq(1.0,  1.0,  1.0, 1.0), 1.0, 1.0),
        (Seq(1.5,  1.0,   1.0, 1.0),  Seq(1.25, 1.0, -1.0, 1.5), 1.0, 1.5),
        (Seq(2.0, -1.0,   1.5, 1.0),  Seq(1.0,  2.0,  1.0, 1.0), 1.5, 1.0),
        (Seq(-1.0, 1.0,  -1.0, 1.0),  Seq(1.0,  1.0,  1.5, 1.0), 2.0, 2.0)
      ).map { case (as, bs, sA, sB) =>
        (as.map(encodeElement(_, cfg.elementTypeA)),
         bs.map(encodeElement(_, cfg.elementTypeB)),
         encodeScale(sA, cfg.stype),
         encodeScale(sB, cfg.stype))
      }
      test(new FusedDotProductUnit(cfg, vsize, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "E3M2 x E2M3 / UE6M2 vec=4")
      }
      log("[PASSED] E3M2 x E2M3 / UE6M2 vec=4")
    } catch { case e: Exception =>
      logErr(s"[FAILED] E3M2 x E2M3 / UE6M2 vec=4: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 13: Power-of-2 vectorSizes — one-cycle all-1 sum equals vectorSize
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: Power-of-2 vectorSizes 1..16 — fused sum = vecSize") {
    log("\n[TEST 13] Power-of-2 vectorSizes (1, 2, 4, 8, 16) — 1-cycle sum = vecSize")
    try {
      for (vsize <- Seq(1, 2, 4, 8, 16)) {
        test(new FusedDotProductUnit(defaultScfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw = peekFloat(dut)
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f")

          val tol = math.abs(expected) * 0.01f + 1e-37f
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 14: Non-power-of-2 vectorSizes — tests odd-lane pass-through in tree
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: Non-power-of-2 vectorSizes (3, 5, 7) — reduction tree") {
    log("\n[TEST 14] Non-power-of-2 vectorSizes (3, 5, 7) — odd lane pass-through")
    try {
      for (vsize <- Seq(3, 5, 7)) {
        test(new FusedDotProductUnit(defaultScfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw = peekFloat(dut)
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f")

          val tol = math.abs(expected) * 0.01f + 1e-37f
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Non-power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Non-power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 15: 8-element vector dot product simulation (vec=8, 1 cycle)
  //   Mirrors DotProductUnitTest Test 12 but completes in a single cycle.
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: 8-element vector dot product in 1 cycle (shared scale=2.0)") {
    log("\n[TEST 15] 8-element dot product in 1 cycle (scale=2.0 x 2.0)")
    try {
      val vecA         = Seq(1.0, -1.0, 2.0, 1.0, -1.0, 1.0, 2.0, -2.0)
      val vecB         = Seq(1.0,  1.0, 1.0, 2.0,  2.0, 1.0, 1.0,  1.0)
      val sharedScaleA = 2.0
      val sharedScaleB = 2.0

      val rawAs = vecA.map(encodeElement(_, defaultScfg.elementTypeA))
      val rawBs = vecB.map(encodeElement(_, defaultScfg.elementTypeB))
      val rawSA = encodeScale(sharedScaleA, defaultScfg.stype)
      val rawSB = encodeScale(sharedScaleB, defaultScfg.stype)

      val dotNoScale    = vecA.zip(vecB).map { case (a, b) => a * b }.sum
      val expectedTotal = (dotNoScale * sharedScaleA * sharedScaleB).toFloat
      log(f"Dot product (no scale) = $dotNoScale%.4f")
      log(f"Expected (×scale²)    = $expectedTotal%.4f")

      test(new FusedDotProductUnit(defaultScfg, 8, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        driveOne(dut, defaultScfg, rawAs, rawBs, rawSA, rawSB)
        val hw  = peekFloat(dut)
        val tol = math.abs(expectedTotal) * 0.05f + 1e-37f
        log(f"HW result = $hw%.4f  expected = $expectedTotal%.4f  tol = $tol%.6f")
        assert(math.abs(hw - expectedTotal) <= tol,
          s"Final dot product mismatch: hw=$hw expected=$expectedTotal")
      }
      log("[PASSED] 8-element vector dot product in 1 cycle")
    } catch { case e: Exception =>
      logErr(s"[FAILED] 8-element vector dot product: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 16: All type combinations — vec=4, 5-cycle random accumulation
  //   6 × 6 × 7 = 252 configurations; each gets 5 random cycles.
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: All type combinations vec=4 — 5-cycle random accumulation") {
    log("\n[TEST 16] All type combinations vec=4 — 5-cycle random accumulation")

    val rng      = new Random(42)
    val vsize    = 4
    val numCycles = 5

    def randElement(t: ElementType): Int = {
      val sign = rng.nextInt(2)
      if (t.name == "INT8") {
        (sign << 7) | (1 + rng.nextInt(15)) // magnitude 1..15
      } else {
        val expRange = 1 << t.elementWidthExp
        val expLo    = (t.bias + 1).max(1).min(expRange - 1)
        val expHi    = (t.bias + 4).max(expLo).min(expRange - 1)
        val exp      = expLo + rng.nextInt(expHi - expLo + 1)
        val mant     = rng.nextInt(1 << t.elementWidthMant)
        (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
      }
    }

    def randScale(s: ScaleType): Int = {
      val expRange = 1 << s.expScaleWidth
      val expLo    = (s.bias - 2).max(1).min(expRange - 1)
      val expHi    = (s.bias + 2).max(expLo).min(expRange - 1)
      val exp      = expLo + rng.nextInt(expHi - expLo + 1)
      val mant     = if (s.mantScaleWidth == 0) 0 else rng.nextInt(1 << s.mantScaleWidth)
      (exp << s.mantScaleWidth) | mant
    }

    val allElem  = MXFormats.allElementTypes
    val allScale = ScaleFormats.allScaleTypes
    val total    = allElem.size * allElem.size * allScale.size
    var passed   = 0
    var failed   = 0

    for (etA <- allElem; etB <- allElem; st <- allScale) {
      val cfg   = ScaleAddConfig(etA, etB, st)
      val label = s"${etA.name} x ${etB.name} / ${st.name} vec=$vsize"
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElement(etA))
        val bs = Seq.fill(vsize)(randElement(etB))
        (as, bs, randScale(st), randScale(st))
      }
      try {
        test(new FusedDotProductUnit(cfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          runCycles(dut, cfg, cycles, label)
        }
        log(s"[OK] $label")
        passed += 1
      } catch { case e: Exception =>
        logErr(s"[FAIL] $label: ${e.getMessage}")
        failed += 1
      }
    }

    val summary = s"Summary: $passed passed, $failed failed out of $total combinations"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0,
      s"$failed combination(s) failed — see fused_dot_product_unit_test.log for details")
    log("[PASSED] All type combinations vec=4")
  }

  // -----------------------------------------------------------------------
  // Test 17: E5M2 × E5M2 / UE8M0 — vec=4, 6-cycle randomized
  //
  //   Each cycle uses distinct sign patterns, fractional magnitudes drawn from
  //   the full E5M2 mantissa alphabet {0, 0.25, 0.50, 0.75} × {0.5,1,1.5,2,3}
  //   and varying UE8M0 scale pairs to exercise the scale multiplier path.
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: E5M2 x E5M2 / UE8M0 vec=4 randomized 6-cycle") {
    log("\n[TEST 17] E5M2 x E5M2 / UE8M0 vec=4 randomized 6-cycle")
    try {
      val cfg   = e5m2Scfg
      val vsize = 4
      // Cycles are listed as raw pre-encoded integers (constants defined above).
      // Sign, magnitude, and scale vary independently each cycle to cover:
      //   - all four E5M2 mantissa fractions (0.00, 0.25, 0.50, 0.75)
      //   - positive and negative elements in both operands
      //   - UE8M0 scale factors spanning {0.5, 1.0, 2.0, 4.0}
      val cycles = Seq(
        // cycle 0: mixed signs, fractional mantissa diversity; scale 2×1
        //   dot = 1.5×(−0.5) + (−0.75)×1.75 + 2.0×(−1.0) + (−1.5)×0.75
        //       = −0.75 − 1.3125 − 2.0 − 1.125 = −5.1875  × 2 × 1
        (Seq(e5m2_1p5,  e5m2_n0p75, e5m2_2,    e5m2_n1p5),
         Seq(e5m2_n0p5, e5m2_1p75,  e5m2_n1,   e5m2_0p75),  ue8m0_2, ue8m0_1),

        // cycle 1: smaller magnitudes with opposite-sign lanes; scale 1×0.5
        //   dot = 0.75×2.0 + 1.0×(−1.5) + (−1.5)×0.75 + 0.5×(−1.0)
        //       = 1.5 − 1.5 − 1.125 − 0.5 = −1.625  × 1 × 0.5
        (Seq(e5m2_0p75, e5m2_1,    e5m2_n1p5,  e5m2_0p5),
         Seq(e5m2_2,    e5m2_n1p5, e5m2_0p75,  e5m2_n1),    ue8m0_1, ue8m0_0p5),

        // cycle 2: large negative A values, asymmetric signs; scale 0.5×2
        //   dot = (−2.0)×1.0 + 1.5×(−0.75) + 0.75×1.5 + (−1.0)×2.0
        //       = −2.0 − 1.125 + 1.125 − 2.0 = −4.0  × 0.5 × 2
        (Seq(e5m2_n2,   e5m2_1p5,   e5m2_0p75, e5m2_n1),
         Seq(e5m2_1,    e5m2_n0p75, e5m2_1p5,  e5m2_2),     ue8m0_0p5, ue8m0_2),

        // cycle 3: high-scale (4×1), near-cancellation between lanes
        //   dot = 1.75×(−1.5) + (−0.5)×1.0 + (−1.0)×2.0 + 1.5×(−0.75)
        //       = −2.625 − 0.5 − 2.0 − 1.125 = −6.25  × 4 × 1
        (Seq(e5m2_1p75, e5m2_n0p5,  e5m2_n1,   e5m2_1p5),
         Seq(e5m2_n1p5, e5m2_1,     e5m2_2,    e5m2_n0p75), ue8m0_4, ue8m0_1),

        // cycle 4: near-zero net sum (cancellation), scale 1×4
        //   dot = (−0.75)×0.75 + 2.0×(−2.0) + 1.0×1.5 + (−1.75)×1.0
        //       = −0.5625 − 4.0 + 1.5 − 1.75 = −4.8125  × 1 × 4
        (Seq(e5m2_n0p75, e5m2_2,    e5m2_1,    e5m2_n1p75),
         Seq(e5m2_0p75,  e5m2_n2,   e5m2_1p5,  e5m2_1),     ue8m0_1, ue8m0_4),

        // cycle 5: uses e5m2_3 (exp+1 row), sign-alternating A and B; scale 2×2
        //   dot = 1.0×(−1.0) + (−1.0)×1.5 + 1.5×(−0.75) + (−2.0)×1.0
        //       = −1.0 − 1.5 − 1.125 − 2.0 = −5.625  × 2 × 2
        (Seq(e5m2_1,    e5m2_n1,   e5m2_1p5,  e5m2_n2),
         Seq(e5m2_n1,   e5m2_1p5,  e5m2_n0p75, e5m2_1),     ue8m0_2, ue8m0_2)
      )
      test(new FusedDotProductUnit(cfg, vsize, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "E5M2 x E5M2 / UE8M0 vec=4 randomized 6-cycle")
      }
      log("[PASSED] E5M2 x E5M2 / UE8M0 vec=4 randomized 6-cycle")
    } catch { case e: Exception =>
      logErr(s"[FAILED] E5M2 x E5M2 / UE8M0 vec=4 randomized 6-cycle: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 18: MX block-quantized dot product (32-element block, vec=8, 4 cycles)
  //
  //   In the MX spec a block of 32 elements shares one scale factor.
  //   This test:
  //     1. Starts from raw FP32 values for two 32-element vectors (A and B).
  //     2. Calls quantizeBlock() to encode each vector into MX elements
  //        + one shared scale — exactly what a real quantization pass would do.
  //     3. Splits the 32 encoded elements into 4 groups of 8 (= 4 cycles
  //        with vectorSize=8), each cycle reusing the same shared scale.
  //     4. Verifies the accumulated dot product against the SW golden model.
  //
  //   Using E5M2 × E4M3 / UE8M0 because UE8M0 is a pure power-of-2 scale
  //   (no mantissa), making the quantization boundaries easy to verify manually.
  // -----------------------------------------------------------------------
  test("FusedDotProductUnit: MX block-quantized 32-element dot product (vec=8, 4 cycles)") {
    log("\n[TEST 18] MX block-quantized 32-element dot product (vec=8, 4 cycles)")
    try {
      val cfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
      val vsize = 8
      val blockSize = 32   // canonical MX block size

      // ---- raw FP32 source data ----
      // Values are chosen to span a wide dynamic range so that the shared
      // scale is non-trivial (not 1.0).  Both positive and negative entries
      // appear in both vectors.
      val rawA: Seq[Double] = Seq(
         3.2,  -1.6,   0.8,   4.0,  -2.4,  1.2,  -0.6,  3.6,
         2.0,  -3.0,   1.5,  -0.5,   2.5, -1.0,   0.4,  1.8,
        -2.2,   0.9,  -1.4,   3.1,  -0.7,  2.8,  -1.1,  0.3,
         1.7,  -2.9,   0.6,  -1.3,   2.1, -0.8,   1.9, -3.5
      )
      val rawB: Seq[Double] = Seq(
         1.0,   2.0,  -1.0,   0.5,   1.5, -0.5,   2.0, -1.0,
         0.75, -1.25,  1.5,   2.0,  -0.75, 1.0,  -2.0,  0.5,
         2.0,  -1.0,   0.5,  -1.5,   1.0,  0.25, -1.0,  2.0,
        -0.5,   1.5,  -2.0,   1.0,   0.5, -1.5,   1.0, -0.25
      )
      require(rawA.size == blockSize && rawB.size == blockSize)

      // ---- MX block quantization ----
      val (encA, rawScaleA) = quantizeBlock(rawA, cfg.elementTypeA, cfg.stype)
      val (encB, rawScaleB) = quantizeBlock(rawB, cfg.elementTypeB, cfg.stype)

      val scaleAf = decodeScale(rawScaleA, cfg.stype)
      val scaleBf = decodeScale(rawScaleB, cfg.stype)
      log(f"Block max |A| = ${rawA.map(math.abs).max}%.4f  → shared scale_A = $scaleAf%.6f  (raw=0x${rawScaleA.toHexString})")
      log(f"Block max |B| = ${rawB.map(math.abs).max}%.4f  → shared scale_B = $scaleBf%.6f  (raw=0x${rawScaleB.toHexString})")
      log(f"maxElemRepr(E5M2) = ${maxElemRepr(cfg.elementTypeA)}%.1f   maxElemRepr(E4M3) = ${maxElemRepr(cfg.elementTypeB)}%.1f")

      // Log quantized vs original to show quantization error per element
      log("-" * 70)
      log("%-6s %8s %10s  %8s %10s  %14s  %14s".format("Lane", "rawA", "decA", "rawB", "decB", "A*B (quant)", "A*B (exact)"))
      log("-" * 70)
      var exactDot = 0.0
      for (idx <- 0 until blockSize) {
        val dA   = decodeElement(encA(idx), cfg.elementTypeA) * scaleAf
        val dB   = decodeElement(encB(idx), cfg.elementTypeB) * scaleBf
        val exact = rawA(idx) * rawB(idx)
        exactDot += exact
        log(f"$idx%-6d 0x${encA(idx).toHexString}%6s ${dA}%10.4f  0x${encB(idx).toHexString}%6s ${dB}%10.4f  ${dA * dB}%14.4f  $exact%14.4f")
      }
      log("-" * 70)
      log(f"Exact dot (no quantization error) = $exactDot%.4f")

      // ---- split into 4 cycles of vsize=8 ----
      val cycles: Seq[(Seq[Int], Seq[Int], Int, Int)] =
        (0 until (blockSize / vsize)).map { c =>
          val slice = c * vsize until (c + 1) * vsize
          (encA.slice(slice.start, slice.end),
           encB.slice(slice.start, slice.end),
           rawScaleA, rawScaleB)   // same shared scale for all cycles in the block
        }

      test(new FusedDotProductUnit(cfg, vsize, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "MX block-quantized vec=8 × 4 cycles")
      }
      log("[PASSED] MX block-quantized 32-element dot product")
    } catch { case e: Exception =>
      logErr(s"[FAILED] MX block-quantized 32-element dot product: ${e.getMessage}"); throw e
    }
  }
}
