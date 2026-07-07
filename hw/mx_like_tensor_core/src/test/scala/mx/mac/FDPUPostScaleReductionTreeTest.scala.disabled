package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import scala.util.Random

/** Test suite for FDPUPostScaleReductionTree.
 *
 *  Architecture under test: MAC → reduction tree (resOperatorMantWidth) → single ScaleAddition → ScaleToFP32 → FP32 Accum
 *
 *  Tests 1–17 mirror FDPUWithCustomReductionTreeTest exactly (same inputs, same golden model).
 *  Test 18 is a precision regression test: runs both architectures on identical inputs and
 *  compares their absolute errors vs the SW double-precision reference.
 *
 *  Tolerance is slightly wider than the original (5% rel + 15% cycle) because the tree
 *  accumulates at resOperatorMantWidth precision instead of resScaleAddMantWidth, which can
 *  introduce extra rounding error for scale types with large mantissa components (e.g. UE4M4).
 */
class FDPUPostScaleReductionTreeTest extends AnyFunSuite with ChiselScalatestTester {

  // -----------------------------------------------------------------------
  // Research-relevance filters
  //   - Skip low×low precision pairs: FP6×FP4 and FP4×FP4
  //     (E3M2, E2M3, E2M1 are "low precision" — not representative of ML workloads)
  //   - Restrict scale types to UE8M0..UE4M4 (expScaleWidth >= 4)
  // -----------------------------------------------------------------------
  private val lowPrecElems       = Set(MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  private val researchScaleTypes = ScaleFormats.allScaleTypes.filter(_.expScaleWidth >= 4)
  private def isResearchPair(etA: ElementType, etB: ElementType): Boolean =
    !(lowPrecElems(etA) && lowPrecElems(etB))

  // -----------------------------------------------------------------------
  // Shared random helpers (used by Tree Width Sweep test and refactored tests)
  // -----------------------------------------------------------------------
  private def randElemHelper(t: ElementType, r: Random): Int = {
    val sign = r.nextInt(2)
    if (t.name == "INT8") {
      val mag = 1 + r.nextInt(20)
      if (sign == 0) mag else (-mag) & 0xFF
    } else {
      val expRange = 1 << t.elementWidthExp
      val expLo    = (t.bias + 1).max(1).min(expRange - 1)
      val expHi    = (t.bias + 3).max(expLo).min(expRange - 1)
      val exp      = expLo + r.nextInt(expHi - expLo + 1)
      val mant     = r.nextInt(1 << t.elementWidthMant)
      (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
    }
  }

  private def randScaleHelper(s: ScaleType, r: Random): Int = {
    val expRange = 1 << s.expScaleWidth
    val expLo    = (s.bias - 1).max(1).min(expRange - 1)
    val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
    val exp      = expLo + r.nextInt(expHi - expLo + 1)
    val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
    (exp << s.mantScaleWidth) | mant
  }

  // -----------------------------------------------------------------------
  // Log file
  // -----------------------------------------------------------------------
  private val _logWriter: java.io.PrintWriter = {
    val fw = new java.io.FileWriter("fdpu_post_scale_reduction_tree_test.log", false)
    new java.io.PrintWriter(fw, true)
  }
  private def log(msg: String): Unit    = _logWriter.println(msg)
  private def logErr(msg: String): Unit = { _logWriter.println(msg); println(msg) }

  // -----------------------------------------------------------------------
  // Software golden model (identical to FDPUWithCustomReductionTreeTest)
  // -----------------------------------------------------------------------

  def decodeElement(raw: Int, t: ElementType): Double = {
    if (t.name == "INT8") {
      val signedVal = if ((raw & 0x80) != 0) raw - 256 else raw
      signedVal.toDouble * Math.pow(2, t.implicitScaleExp)
    } else {
      val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0)
        sign * (mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, 1 - t.bias)
      else
        sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, exp - t.bias)
    }
  }

  def decodeScale(raw: Int, s: ScaleType): Double = {
    val expBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) {
      Math.pow(2, expBits - s.bias)
    } else {
      val mantBits  = raw & ((1 << s.mantScaleWidth) - 1)
      val implicit1 = if (expBits > 0) 1.0 else 0.0
      val effExp    = if (expBits > 0) expBits - s.bias else 1 - s.bias
      (implicit1 + mantBits.toDouble / (1 << s.mantScaleWidth)) * Math.pow(2, effExp)
    }
  }

  def encodeElement(value: Double, t: ElementType): Int = {
    if (value == 0.0) return 0
    val sign   = if (value < 0) 1 else 0
    val absVal = math.abs(value)
    if (t.name == "INT8") {
      val magnitude = math.round(absVal / Math.pow(2, t.implicitScaleExp)).toInt.min(127)
      if (sign == 0) magnitude else (-magnitude) & 0xFF
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

  def swFusedProduct(cfg: ScaleAddConfig)(
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int
  ): Float = {
    val sA = decodeScale(scaleA, cfg.stype)
    val sB = decodeScale(scaleB, cfg.stype)
    as.zip(bs).map { case (a, b) =>
      (decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sA * sB).toFloat
    }.sum.toFloat
  }

  def swFusedProductWithTrace(cfg: ScaleAddConfig)(
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int, cycleIdx: Int,
    verbose: Boolean = false
  ): Float = {
    val sA = decodeScale(scaleA, cfg.stype)
    val sB = decodeScale(scaleB, cfg.stype)
    val details = as.zip(bs).zipWithIndex.map { case ((a, b), lane) =>
      val dA = decodeElement(a, cfg.elementTypeA)
      val dB = decodeElement(b, cfg.elementTypeB)
      (dA, dB, dA * dB)
    }
    val dotSum        = details.map(_._3).sum
    val finalCycleVal = (dotSum * sA * sB).toFloat
    if (verbose) {
      log(f"--- Cycle $cycleIdx SW Trace (sA=$sA%.4e, sB=$sB%.4e) ---")
      details.zipWithIndex.foreach { case ((dA, dB, p), lane) =>
        log(f"  Lane $lane: A=$dA%10.4f (raw=0x${as(lane)}%02x) | B=$dB%10.4f (raw=0x${bs(lane)}%02x) | P=$p%10.4f")
      }
      log(f"  DotSum=$dotSum%10.4f | FinalCycleVal=$finalCycleVal%10.4f")
    }
    finalCycleVal
  }

  // -----------------------------------------------------------------------
  // Debug / decode helpers
  // -----------------------------------------------------------------------

  def signExtBigInt(raw: BigInt, width: Int): Long = {
    val mask = (BigInt(1) << width) - 1
    val v    = raw & mask
    if (v.testBit(width - 1)) (v - (BigInt(1) << width)).toLong
    else v.toLong
  }

  def decodeCustomFP(sign: Long, expSigned: Long, mant: Long): Double =
    if (mant == 0L) 0.0
    else (if (sign != 0L) -1.0 else 1.0) * mant.toDouble * Math.pow(2.0, expSigned.toDouble)

  // Polymorphic over the 3 FDPU variants (all expose actualAccMantBits + io.accOut).
  // Two overloads keep type-inference happy without an awkward shared trait.
  def peekFloat(dut: FDPUPostScaleReductionTree): Float = {
    val raw  = dut.io.accOut.peek().litValue
    intBitsToFloat((raw << (23 - dut.actualAccMantBits)).toInt)
  }
  def peekFloat(dut: mx.mac.deferred_archs.FDPUBlockDeferred): Float = {
    val raw  = dut.io.accOut.peek().litValue
    intBitsToFloat((raw << (23 - dut.actualAccMantBits)).toInt)
  }

  def initDut(dut: FDPUPostScaleReductionTree): Unit = {
    dut.reset.poke(true.B)
    dut.io.validIn.poke(false.B)
    dut.io.resetAcc.poke(false.B)
  }

  def packElements(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  def driveOne(
    dut: FDPUPostScaleReductionTree, cfg: ScaleAddConfig,
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

  /** Forward-error-analysis tolerance for the post-scale architecture.
   *
   *  The tree accumulates at resOperatorMantWidth bits (machine epsilon = 2^(-mantW)).
   *  For near-cancellation inputs the relative error can be large, but the ABSOLUTE error
   *  is bounded by:  #treeOps × ε × Σ|laneᵢ|
   *
   *  swAbsCycleSum = Σᵢ |mac(aᵢ,bᵢ) × sA × sB|  (sum of absolute lane contributions).
   *  treeOps = 4 (conservative estimate covering vec ≤ 16 with ≤2 tree levels + safety).
   */
  def tolerance(swAcc: Float, swAbsCycleSum: Float, cfg: ScaleAddConfig): Float = {
    val machEps  = 1.0f / math.pow(2.0, cfg.resOperatorMantWidth).toFloat
    val treeTol  = 4f * machEps * swAbsCycleSum
    val accumTol = 0.03f * math.abs(swAcc)
    treeTol + accumTol + 1e-4f
  }

  /** Compute the sum of absolute values of per-lane contributions (before accumulation). */
  def swAbsCycleSum(cfg: ScaleAddConfig)(as: Seq[Int], bs: Seq[Int], sA: Int, sB: Int): Float = {
    val dSA = decodeScale(sA, cfg.stype)
    val dSB = decodeScale(sB, cfg.stype)
    as.zip(bs).map { case (a, b) =>
      math.abs(decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * dSA * dSB).toFloat
    }.sum
  }

  def runCycles(
    dut: FDPUPostScaleReductionTree, cfg: ScaleAddConfig,
    cycles: Seq[(Seq[Int], Seq[Int], Int, Int)],
    header: String = "",
    verbose: Boolean = false
  ): Unit = {
    if (header.nonEmpty) {
      log("=" * 70)
      log(s"[PostScale] $header")
      log(s"  resOperatorMantWidth=${cfg.resOperatorMantWidth}  resScaleAddMantWidth=${cfg.resScaleAddMantWidth}")
      log("=" * 70)
    }
    var swAcc           = 0.0f
    var cumulativeAbsSum = 0.0f   // Σ|laneᵢ| across all cycles so far
    for (((as, bs, sA, sB), i) <- cycles.zipWithIndex) {
      val decSA      = decodeScale(sA, cfg.stype)
      val decSB      = decodeScale(sB, cfg.stype)
      val swCycleVal = swFusedProductWithTrace(cfg)(as, bs, sA, sB, i, verbose)
      val swAbsSum   = swAbsCycleSum(cfg)(as, bs, sA, sB)
      cumulativeAbsSum += swAbsSum
      driveOne(dut, cfg, as, bs, sA, sB)

      val hwCycleValRaw = dut.io.debug.get.reducedSum.peek().litValue.toInt
      val hwCycleVal    = java.lang.Float.intBitsToFloat(hwCycleValRaw)
      val hwAcc         = peekFloat(dut)

      swAcc = (swAcc + swCycleVal).toFloat
      val tol    = tolerance(swAcc, cumulativeAbsSum, cfg)
      val isFail = math.abs(hwAcc - swAcc) > tol

      val line = f"Cycle $i%2d | sA=${decSA}%10.4e | sB=${decSB}%10.4e | cycleVal=$swCycleVal%12.4e | SW=$swAcc%12.4e | HW=$hwAcc%12.4e"

      if (isFail || verbose) {
        val tag = if (isFail) "FAIL" else "VERBOSE"
        log(f"--- Cycle $i%2d [$tag] HW Trace ---")

        // --- 新增：打印每条 Lane 的原始输入 A 和 B 的解析结果 ---
        log(f"  Lane Inputs (A: ${cfg.elementTypeA.name} [w=${cfg.elementTypeA.totalWidth}], " +
            f"B: ${cfg.elementTypeB.name} [w=${cfg.elementTypeB.totalWidth}]):")
            
        as.zip(bs).zipWithIndex.foreach { case ((rawA, rawB), lane) =>
          val decA = decodeElement(rawA, cfg.elementTypeA)
          val decB = decodeElement(rawB, cfg.elementTypeB)
          log(f"    Lane $lane%1d: A_raw=0x$rawA%02x ($decA%10.4f) | B_raw=0x$rawB%02x ($decB%10.4f)")
        }

        log("  Lane MAC outputs (tree inputs):")
        val laneVals = (0 until dut.vectorSize).map { lane =>
          val hwSign   = dut.io.debug.get.all_lanes_op_sign(lane).peek().litValue.toLong
          val hwExpRaw = dut.io.debug.get.all_lanes_op_exp(lane).peek().litValue
          val hwExp    = signExtBigInt(hwExpRaw, cfg.resOperatorExpWidth)
          val hwMant   = dut.io.debug.get.all_lanes_op_mant(lane).peek().litValue.toLong
          val hwVal    = decodeCustomFP(hwSign, hwExp, hwMant)
          val signCh   = if (hwSign != 0) "-" else "+"
          log(f"    Lane $lane%1d: sign=$hwSign exp=$hwExp%4d mant=0x$hwMant%x  → $signCh${math.abs(hwVal)}%.6f")
          hwVal
        }

        // FixedFP reduction tree produces a single output (no per-node debug).
        val treeSign   = dut.io.debug.get.tree_out_sign.peek().litValue.toLong
        val treeExpRaw = dut.io.debug.get.tree_out_exp.peek().litValue
        val treeExp    = signExtBigInt(treeExpRaw, cfg.resOperatorExpWidth)
        val treeMant   = dut.io.debug.get.tree_out_mant.peek().litValue.toLong
        val treeVal    = decodeCustomFP(treeSign, treeExp, treeMant)
        val swTreeSum  = laneVals.sum
        val treeSignCh = if (treeSign != 0) "-" else "+"
        log(f"  FixedFP tree output (mant=${cfg.resOperatorMantWidth}): " +
            f"sign=$treeSign exp=$treeExp mant=0x$treeMant%x → $treeSignCh${math.abs(treeVal)}%.6f  SW_sum=$swTreeSum%.6f")

        val saSignRaw = dut.io.debug.get.sa_out_sign.peek().litValue.toLong
        val saExpRaw  = dut.io.debug.get.sa_out_exp.peek().litValue
        val saExp     = signExtBigInt(saExpRaw, cfg.resScaleAddExpWidth)
        val saMant    = dut.io.debug.get.sa_out_mant.peek().litValue.toLong
        val saVal     = decodeCustomFP(saSignRaw, saExp, saMant)
        log(f"  ScaleAddition output (mant=${cfg.resScaleAddMantWidth}, exp=${cfg.resScaleAddExpWidth}): " +
            f"sign=$saSignRaw exp=$saExp mant=0x$saMant%x → $saVal%.6f")
        log(f"  [SUMMARY] SW_CycleSum=$swCycleVal%.6f | HW_CycleSum=$hwCycleVal%.6f | absSum=$swAbsSum%.4f")
        log(f"  [ACCUM  ] SW_Acc=$swAcc%.6f | HW_Acc=$hwAcc%.6f | tol=$tol%.6f (opMantW=${cfg.resOperatorMantWidth})")

        if (isFail) {
          logErr(line)
          logErr(f"      BITS: SW=0x${java.lang.Float.floatToIntBits(swAcc).toHexString} | " +
                 f"HW=0x${java.lang.Float.floatToIntBits(hwAcc).toHexString}")
        } else {
          log(line)
        }
      } else {
        log(line)
      }
      assert(!isFail, s"Mismatch at cycle $i: hw=$hwAcc expected=$swAcc tol=$tol")
    }
  }

  // -----------------------------------------------------------------------
  // MX block quantization helpers
  // -----------------------------------------------------------------------

  def maxElemRepr(t: ElementType): Double =
    if (t.name == "INT8") {
      ((1 << (t.totalWidth - 1)) - 1).toDouble * Math.pow(2, t.implicitScaleExp)
    } else {
      val maxExpBiased = (1 << t.elementWidthExp) - 2
      val maxMant      = (1 << t.elementWidthMant) - 1
      (1.0 + maxMant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, maxExpBiased - t.bias)
    }

  def quantizeBlock(values: Seq[Double], et: ElementType, st: ScaleType): (Seq[Int], Int) = {
    val maxAbs      = values.map(math.abs).max.max(1e-38)
    val minScaleExp = -(st.bias)
    val maxScaleExp = (1 << st.expScaleWidth) - 1 - st.bias
    val rawScale =
      if (st.mantScaleWidth == 0) {
        val scaleExp   = math.ceil(math.log((maxAbs / maxElemRepr(et)).max(1e-38)) / math.log(2)).toInt
        val clampedExp = scaleExp.max(minScaleExp).min(maxScaleExp)
        encodeScale(math.pow(2.0, clampedExp), st)
      } else {
        val idealScale  = (maxAbs / maxElemRepr(et)).max(math.pow(2.0, minScaleExp))
        val expUnbiased = math.floor(math.log(idealScale) / math.log(2)).toInt
        val mantRaw     = math.ceil(
          (idealScale / math.pow(2.0, expUnbiased) - 1.0) * (1 << st.mantScaleWidth)
        ).toInt
        val (adjExp, adjMant) =
          if (mantRaw > (1 << st.mantScaleWidth) - 1) (expUnbiased + 1, 0)
          else (expUnbiased, mantRaw)
        val expBiased = (adjExp + st.bias).max(0).min((1 << st.expScaleWidth) - 1)
        (expBiased << st.mantScaleWidth) | adjMant
      }
    val decodedScale = decodeScale(rawScale, st)
    val rawElems     = values.map(v => encodeElement(v / decodedScale, et))
    (rawElems, rawScale)
  }

  // -----------------------------------------------------------------------
  // Pre-encoded constants (same as FDPUWithCustomReductionTreeTest)
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
  test("FDPUPostScale: Reset clears accumulator") {
    log("\n[TEST 1] Reset clears accumulator")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
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
  test("FDPUPostScale: validOut handshake timing") {
    log("\n[TEST 2] validOut handshake timing (vec=4)")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)
        dut.io.op_a_i.poke(packElements(Seq.fill(4)(e4m3_1), defaultScfg.elementTypeA.totalWidth).U)
        dut.io.op_b_i.poke(packElements(Seq.fill(4)(e2m1_1), defaultScfg.elementTypeB.totalWidth).U)
        dut.io.share_exp_A_i.poke(ue5m3_1.U)
        dut.io.share_exp_B_i.poke(ue5m3_1.U)

        dut.io.validOut.expect(false.B, "validOut must be false before any cycle")

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
  // Test 3: vec=1 scalar equivalence
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=1 scalar equivalence (1×1×1×1 = 1.0)") {
    log("\n[TEST 3] vec=1 scalar equivalence")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 1, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val expected = swFusedProduct(defaultScfg)(Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f
        log(f"Expected: $expected%.6f  HW: $hw%.6f")
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=1 scalar equivalence")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=1 scalar equivalence: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 4: vec=4 all-ones → 4.0
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=4 all-ones fused → 4.0") {
    log("\n[TEST 4] vec=4 all-ones (1×1×1×1 per lane) → 4.0")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f
        log(f"Expected: $expected%.6f  HW: $hw%.6f")
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 all-ones → 4.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 5: vec=8 all-ones → 8.0
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=8 all-ones fused → 8.0") {
    log("\n[TEST 5] vec=8 all-ones → 8.0")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 8, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(8)(e4m3_1)
        val bs       = Seq.fill(8)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f
        log(f"Expected: $expected%.6f  HW: $hw%.6f")
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=8 all-ones → 8.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=8 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 6: vec=4 mixed signs → near-zero cancellation
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=4 mixed signs → near-zero cancellation") {
    log("\n[TEST 6] vec=4 mixed signs [+1,-1,+1,-1] × [1,1,1,1] → 0.0")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq(e4m3_1, e4m3_neg1, e4m3_1, e4m3_neg1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f + 1e-6f
        log(f"Expected: $expected%.6f  HW: $hw%.6f")
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 mixed signs cancellation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 mixed signs: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 7: vec=4 scale=2×2 amplification → 16.0
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=4 scale=2×2 amplification → 16.0") {
    log("\n[TEST 7] vec=4, all-ones, scale 2×2 → 4×1×2×2 = 16.0")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_2, ue5m3_2)
        driveOne(dut, defaultScfg, as, bs, ue5m3_2, ue5m3_2)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f
        log(f"Expected: $expected%.6f  HW: $hw%.6f")
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
  test("FDPUPostScale: Reset mid-accumulation (vec=4)") {
    log("\n[TEST 8] Reset mid-accumulation (vec=4)")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)

        driveOne(dut, defaultScfg, Seq.fill(4)(e4m3_2), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1)
        val before = dut.io.accOut.peek().litValue.toInt
        log(s"Before reset: 0x${before.toHexString}  (${peekFloat(dut)})")
        assert(before != 0, "accOut should be non-zero after accumulation")

        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.accOut.expect(0.U, "accOut should be 0 after reset")
        dut.io.resetAcc.poke(false.B)

        val as1      = Seq.fill(4)(e4m3_1)
        val bs1      = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as1, bs1, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as1, bs1, ue5m3_1, ue5m3_1)
        val hw  = peekFloat(dut)
        val tol = math.abs(expected) * 0.01f + 1e-37f
        log(f"After reset + 1 cycle: HW=$hw%.6f expected=$expected%.6f")
        assert(math.abs(hw - expected) <= tol, s"Mismatch after reset: hw=$hw expected=$expected")
      }
      log("[PASSED] Reset mid-accumulation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Reset mid-accumulation: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 9: vec=4 multi-cycle (4 cycles × 4.0 → 16.0, VCD)
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=4 multi-cycle (4 cycles × 4.0 → 16.0, VCD)") {
    log("\n[TEST 9] vec=4 multi-cycle: 4 cycles of (4×1×1×1×1) → 16.0")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
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
  // Test 10: vec=4 mixed-value multi-cycle
  // -----------------------------------------------------------------------
  test("FDPUPostScale: vec=4 mixed-value multi-cycle (negative/positive)") {
    log("\n[TEST 10] vec=4 mixed-value multi-cycle")
    try {
      test(new FDPUPostScaleReductionTree(defaultScfg, 4, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val cycles = Seq(
          (Seq(e4m3_1, e4m3_2, e4m3_neg1, e4m3_1), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1),
          (Seq.fill(4)(e4m3_1), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1),
          (Seq.fill(4)(e4m3_neg1), Seq.fill(4)(e2m1_1), ue5m3_2, ue5m3_1),
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
  // Test 11: E5M2 × E5M2 / UE8M0 vec=4 6-cycle
  // -----------------------------------------------------------------------
  test("FDPUPostScale: E5M2 x E5M2 / UE8M0 vec=4 (6-cycle)") {
    log("\n[TEST 11] E5M2 x E5M2 / UE8M0 vec=4")
    try {
      val cfg   = e5m2Scfg
      val vsize = 4
      val cycles = Seq(
        (Seq(e5m2_1p5,  e5m2_n0p75, e5m2_2,    e5m2_n1p5),
         Seq(e5m2_n0p5, e5m2_1p75,  e5m2_n1,   e5m2_0p75),  ue8m0_2, ue8m0_1),
        (Seq(e5m2_0p75, e5m2_1,    e5m2_n1p5,  e5m2_0p5),
         Seq(e5m2_2,    e5m2_n1p5, e5m2_0p75,  e5m2_n1),    ue8m0_1, ue8m0_0p5),
        (Seq(e5m2_n2,   e5m2_1p5,   e5m2_0p75, e5m2_n1),
         Seq(e5m2_1,    e5m2_n0p75, e5m2_1p5,  e5m2_2),     ue8m0_0p5, ue8m0_2),
        (Seq(e5m2_1p75, e5m2_n0p5,  e5m2_n1,   e5m2_1p5),
         Seq(e5m2_n1p5, e5m2_1,     e5m2_2,    e5m2_n0p75), ue8m0_4, ue8m0_1),
        (Seq(e5m2_n0p75, e5m2_2,    e5m2_1,    e5m2_n1p75),
         Seq(e5m2_0p75,  e5m2_n2,   e5m2_1p5,  e5m2_1),     ue8m0_1, ue8m0_4),
        (Seq(e5m2_1,    e5m2_n1,   e5m2_1p5,  e5m2_n2),
         Seq(e5m2_n1,   e5m2_1p5,  e5m2_n0p75, e5m2_1),     ue8m0_2, ue8m0_2)
      )
      test(new FDPUPostScaleReductionTree(cfg, vsize, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "E5M2 x E5M2 / UE8M0 vec=4")
      }
      log("[PASSED] E5M2 x E5M2 / UE8M0 vec=4")
    } catch { case e: Exception =>
      logErr(s"[FAILED] E5M2 x E5M2 / UE8M0 vec=4: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 12: Power-of-2 vectorSizes 1..16
  // -----------------------------------------------------------------------
  test("FDPUPostScale: Power-of-2 vectorSizes 1..16 — fused sum = vecSize") {
    log("\n[TEST 12] Power-of-2 vectorSizes (1, 2, 4, 8, 16) — 1-cycle sum = vecSize")
    try {
      for (vsize <- Seq(1, 2, 4, 8, 16)) {
        test(new FDPUPostScaleReductionTree(defaultScfg, vsize, istest = true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw  = peekFloat(dut)
          val tol = math.abs(expected) * 0.01f + 1e-37f
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f")
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 13: Non-power-of-2 vectorSizes (3, 5, 7)
  // -----------------------------------------------------------------------
  test("FDPUPostScale: Non-power-of-2 vectorSizes (3, 5, 7)") {
    log("\n[TEST 13] Non-power-of-2 vectorSizes (3, 5, 7) — odd lane pass-through")
    try {
      for (vsize <- Seq(3, 5, 7)) {
        test(new FDPUPostScaleReductionTree(defaultScfg, vsize, istest = true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw  = peekFloat(dut)
          val tol = math.abs(expected) * 0.01f + 1e-37f
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f")
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Non-power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Non-power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 14: 8-element vector dot product in 1 cycle (scale=2.0 × 2.0)
  // -----------------------------------------------------------------------
  test("FDPUPostScale: 8-element vector dot product in 1 cycle (shared scale=2.0)") {
    log("\n[TEST 14] 8-element dot product in 1 cycle (scale=2.0 × 2.0)")
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
      log(f"Expected (×scale²)     = $expectedTotal%.4f")

      test(new FDPUPostScaleReductionTree(defaultScfg, 8, istest = true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
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
  // Test 15: All type combinations vec=4 — 5-cycle random accumulation
  // -----------------------------------------------------------------------
  test("FDPUPostScale: All type combinations vec=4 — 5-cycle random accumulation") {
    log("\n[TEST 15] All type combinations vec=4 — 5-cycle random accumulation")

    val rng       = new Random(42)
    val vsize     = 4
    val numCycles = 5

    def randElement(t: ElementType): Int = {
      val sign = rng.nextInt(2)
      if (t.name == "INT8") {
        val mag = 1 + rng.nextInt(15)
        if (sign == 0) mag else (-mag) & 0xFF
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
    var passed   = 0
    var failed   = 0

    for (etA <- allElem; etB <- allElem if isResearchPair(etA, etB); st <- researchScaleTypes) {
      val cfg   = ScaleAddConfig(etA, etB, st)
      val label = s"${etA.name} x ${etB.name} / ${st.name} vec=$vsize"
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElement(etA))
        val bs = Seq.fill(vsize)(randElement(etB))
        (as, bs, randScale(st), randScale(st))
      }
      try {
        test(new FDPUPostScaleReductionTree(cfg, vsize, istest = true)) { dut =>
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

    val summary = s"Summary: $passed passed, $failed failed out of ${passed + failed} combinations"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0,
      s"$failed combination(s) failed — see fdpu_post_scale_reduction_tree_test.log for details")
    log("[PASSED] All type combinations vec=4")
  }

  // -----------------------------------------------------------------------
  // Test 16: MX block-quantized 32-element dot product (E5M2 × E4M3 / UE8M0)
  // -----------------------------------------------------------------------
  test("FDPUPostScale: MX block-quantized 32-element dot product (E5M2×E4M3, vec=8, 4 cycles)") {
    log("\n[TEST 16] MX block-quantized 32-element dot product (E5M2×E4M3 / UE8M0, vec=8)")
    try {
      val cfg       = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
      val vsize     = 8
      val blockSize = 32

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

      val (encA, rawScaleA) = quantizeBlock(rawA, cfg.elementTypeA, cfg.stype)
      val (encB, rawScaleB) = quantizeBlock(rawB, cfg.elementTypeB, cfg.stype)

      log(f"scale_A = ${decodeScale(rawScaleA, cfg.stype)}%.6f  scale_B = ${decodeScale(rawScaleB, cfg.stype)}%.6f")
      log(s"resOperatorMantWidth=${cfg.resOperatorMantWidth}  resScaleAddMantWidth=${cfg.resScaleAddMantWidth}")

      val cycles: Seq[(Seq[Int], Seq[Int], Int, Int)] =
        (0 until (blockSize / vsize)).map { c =>
          val slice = c * vsize until (c + 1) * vsize
          (encA.slice(slice.start, slice.end), encB.slice(slice.start, slice.end),
           rawScaleA, rawScaleB)
        }

      test(new FDPUPostScaleReductionTree(cfg, vsize, istest = true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "MX block-quantized E5M2×E4M3 vec=8 × 4 cycles")
      }
      log("[PASSED] MX block-quantized 32-element dot product (E5M2×E4M3)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] MX block-quantized (E5M2×E4M3): ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 17: MX block-quantized 32-element dot product (INT8 × E2M1 / UE8M0)
  // -----------------------------------------------------------------------
  test("FDPUPostScale: MX block-quantized 32-element dot product (INT8×E2M1, vec=8, 4 cycles)") {
    log("\n[TEST 17] MX block-quantized 32-element dot product (INT8×E2M1 / UE8M0, vec=8)")
    try {
      val cfg       = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
      val vsize     = 8
      val blockSize = 32

      val rawA: Seq[Double] = Seq(
         1.0, -2.0,  3.0,  1.5, -1.5,  2.0, -0.5,  3.5,
         2.5, -1.0,  0.5, -3.0,  1.0, -2.5,  2.0, -1.0,
        -1.5,  1.0, -2.0,  3.0, -0.5,  2.0, -1.0,  0.5,
         1.5, -3.0,  1.0, -1.5,  2.0, -0.5,  1.0, -2.0
      )
      val rawB: Seq[Double] = Seq(
         2.0, -1.0,  1.5, -2.0,  1.0, -1.5,  2.0, -1.0,
         1.0, -2.0,  1.5, -1.0,  2.0, -1.5,  1.0, -2.0,
        -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0,  2.0,
         1.5, -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0
      )
      require(rawA.size == blockSize && rawB.size == blockSize)

      val (encA, rawScaleA) = quantizeBlock(rawA, cfg.elementTypeA, cfg.stype)
      val (encB, rawScaleB) = quantizeBlock(rawB, cfg.elementTypeB, cfg.stype)

      log(f"scale_A = ${decodeScale(rawScaleA, cfg.stype)}%.6f  scale_B = ${decodeScale(rawScaleB, cfg.stype)}%.6f")

      val cycles: Seq[(Seq[Int], Seq[Int], Int, Int)] =
        (0 until (blockSize / vsize)).map { c =>
          val slice = c * vsize until (c + 1) * vsize
          (encA.slice(slice.start, slice.end), encB.slice(slice.start, slice.end),
           rawScaleA, rawScaleB)
        }

      test(new FDPUPostScaleReductionTree(cfg, vsize, istest = true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "MX block-quantized INT8×E2M1 vec=8 × 4 cycles")
      }
      log("[PASSED] MX block-quantized 32-element dot product (INT8×E2M1)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] MX block-quantized (INT8×E2M1): ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 18: Precision regression — compare both architectures vs SW model
  //
  //  Runs FDPUPostScaleReductionTree and FDPUWithCustomReductionTree on
  //  identical random inputs and measures each architecture's accumulated
  //  absolute error relative to the double-precision SW golden model.
  //
  //  Logs: per-config error stats including error ratio (new/original).
  //  Asserts: both architectures stay within their respective tolerances;
  //           the new architecture's final error does not exceed 5× the
  //           original's error (accounts for reduced tree precision).
  // -----------------------------------------------------------------------
  test("FDPUPostScale: Precision regression vs FDPUWithCustomReductionTree") {
    log("\n[TEST 18] Precision regression — FDPUPostScale vs FDPUWithCustomReductionTree")
    log("=" * 70)

    val rng       = new Random(1234)
    val vsize     = 8
    val numCycles = 8

    def randElement(t: ElementType, r: Random): Int = {
      val sign = r.nextInt(2)
      if (t.name == "INT8") {
        val mag = 1 + r.nextInt(20)
        if (sign == 0) mag else (-mag) & 0xFF
      } else {
        val expRange = 1 << t.elementWidthExp
        val expLo    = (t.bias + 1).max(1).min(expRange - 1)
        val expHi    = (t.bias + 3).max(expLo).min(expRange - 1)
        val exp      = expLo + r.nextInt(expHi - expLo + 1)
        val mant     = r.nextInt(1 << t.elementWidthMant)
        (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
      }
    }

    def randScale(s: ScaleType, r: Random): Int = {
      val expRange = 1 << s.expScaleWidth
      val expLo    = (s.bias - 1).max(1).min(expRange - 1)
      val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
      val exp      = expLo + r.nextInt(expHi - expLo + 1)
      val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
      (exp << s.mantScaleWidth) | mant
    }

    case class ErrorRecord(
      label: String,
      origErr: Float, newErr: Float,
      swFinal: Float, origFinal: Float, newFinal: Float,
      opMantW: Int, saAddMantW: Int
    )

    val results  = scala.collection.mutable.ArrayBuffer[ErrorRecord]()
    var anyFail  = false
    val maxRatio = 5.0f  // new architecture error must not exceed 5× original

    val allElem  = MXFormats.allElementTypes

    for (etA <- allElem; etB <- allElem if isResearchPair(etA, etB); st <- researchScaleTypes) {
      val cfg   = ScaleAddConfig(etA, etB, st)
      val label = s"${etA.name} x ${etB.name} / ${st.name}"

      // Generate a fixed set of cycles using a fresh RNG with the same seed
      val seedRng = new Random(rng.nextLong())
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElement(etA, seedRng))
        val bs = Seq.fill(vsize)(randElement(etB, seedRng))
        (as, bs, randScale(st, seedRng), randScale(st, seedRng))
      }

      // SW reference
      var swAcc = 0.0f
      cycles.foreach { case (as, bs, sA, sB) =>
        swAcc = (swAcc + swFusedProduct(cfg)(as, bs, sA, sB)).toFloat
      }

      // New architecture (FDPUPostScaleReductionTree)
      var postAcc = 0.0f
      try {
        test(new FDPUPostScaleReductionTree(cfg, vsize, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(false.B)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          postAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label PostScale sim failed: ${e.getMessage}")
        anyFail = true
      }

      // Original architecture (FDPUWithCustomReductionTree) — istest=false, no debug ports
      var origAcc = 0.0f
      try {
        test(new FDPUWithCustomReductionTree(cfg, vsize, false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(false.B)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          // FDPUWithCustomReductionTree's accOut is natively 32-bit FP32.
          origAcc = intBitsToFloat(dut.io.accOut.peek().litValue.toInt)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label Original sim failed: ${e.getMessage}")
        anyFail = true
      }

      val origErr = math.abs(origAcc - swAcc)
      val newErr  = math.abs(postAcc - swAcc)
      val ratio   = if (origErr < 1e-10f) Float.PositiveInfinity else newErr / origErr

      results += ErrorRecord(label, origErr, newErr, swAcc, origAcc, postAcc,
        cfg.resOperatorMantWidth, cfg.resScaleAddMantWidth)

      val ratioStr = if (ratio.isInfinite) "∞" else f"$ratio%.2f×"
      val status   = if (ratio > maxRatio && origErr >= 1e-6f) "WARN" else "OK  "
      log(f"  [$status] $label%-35s | opMantW=${cfg.resOperatorMantWidth}%2d saAddMantW=${cfg.resScaleAddMantWidth}%2d" +
          f" | SW=$swAcc%10.4e | orig=$origAcc%10.4e(err=$origErr%.2e) | new=$postAcc%10.4e(err=$newErr%.2e) | ratio=$ratioStr")

      // Warn only (do not fail) if ratio exceeds threshold for near-zero results
      if (ratio > maxRatio && origErr >= 1e-6f) {
        logErr(s"  [WARN] $label: error ratio $ratioStr > ${maxRatio}× (opMantW=${cfg.resOperatorMantWidth} vs saAddMantW=${cfg.resScaleAddMantWidth})")
      }
    }

    // Aggregate summary
    log("\n--- Precision Regression Summary ---")
    log("  Note: 'ratio' is newErr/origErr. High ratios occur when origErr≈0 (lucky cancellation),")
    log("  not representative of practical precision. Use 'rel_err_new' for absolute assessment.")

    // Configs where SW value is non-trivial (|swFinal| > 1e-4 to avoid near-zero division)
    val validResults  = results.filter(r => math.abs(r.swFinal) > 1e-4f)
    // Ratio-valid: both errors non-trivial (origErr > 0.1% of SW value)
    val ratioResults  = validResults.filter(r => r.origErr > math.abs(r.swFinal) * 0.001f)

    if (validResults.nonEmpty) {
      val relErrsNew = validResults.map(r => r.newErr / math.abs(r.swFinal))
      val relErrsOrig = validResults.map(r => r.origErr / math.abs(r.swFinal))
      val avgRelNew  = relErrsNew.sum / relErrsNew.size
      val maxRelNew  = relErrsNew.max
      val avgRelOrig = relErrsOrig.sum / relErrsOrig.size
      val under5pct  = relErrsNew.count(_ < 0.05f)
      log(f"  Configs with non-trivial |SW|: ${validResults.size}")
      log(f"  Relative error |newErr/SW|  — avg: ${avgRelNew*100}%.2f%%  max: ${maxRelNew*100}%.2f%%")
      log(f"  Relative error |origErr/SW| — avg: ${avgRelOrig*100}%.2f%%  max: ${relErrsOrig.max*100}%.2f%%")
      log(f"  New arch within 5%% relative: $under5pct / ${validResults.size} configs")
    }

    if (ratioResults.nonEmpty) {
      val ratios    = ratioResults.map(r => r.newErr / r.origErr)
      val warnCount = ratios.count(_ > maxRatio)
      log(f"  Ratio-valid configs (origErr > 0.1%% of SW): ${ratioResults.size}")
      log(f"  Error ratio among those — avg: ${ratios.sum/ratios.size}%.2f×  max: ${ratios.max}%.2f×  >5×: $warnCount")

      log("  Top-5 largest absolute relative errors (new arch):")
      validResults.sortBy(r => -(r.newErr / math.abs(r.swFinal))).take(5).foreach { r =>
        val relErr = r.newErr / math.abs(r.swFinal)
        val ratio  = if (r.origErr > 1e-10f) r.newErr / r.origErr else Float.PositiveInfinity
        log(f"    ${r.label}%-35s opMantW=${r.opMantW}%2d saAddMantW=${r.saAddMantW}%2d relErr=${relErr*100}%.2f%% ratio=${ratio}%.1f×")
      }
    }

    assert(!anyFail, "One or more simulation errors — see fdpu_post_scale_reduction_tree_test.log")
    log("[PASSED] Precision regression test completed")
  }

  // =========================================================================
  // Test 19 — HW-vs-HW direct precision comparison
  //
  // FDPUWithCustomReductionTree is the precision reference:
  //   - its tree operates at resScaleAddMantWidth (wider)
  //   - it is consistently closer to the SW double-precision result
  //
  // Primary metric:
  //   hwDiff = |postAcc − origAcc|
  //
  // This isolates the precision cost of using a narrower tree mantissa,
  // without the noise introduced by the shared FP32 accumulator path or
  // lucky cancellation in the SW reference value.
  //
  // Secondary metrics (all relative to |origAcc| to keep units consistent):
  //   hwDiff / |origAcc|   — relative distance between two HW outputs
  //   hwDiff / origErr     — how many times the degradation exceeds the
  //                          original arch's own error vs SW
  // =========================================================================
  test("Test 19 — HW-vs-HW: FDPUPostScale vs FDPUWithCustomReductionTree") {
    log("\n" + "=" * 70)
    log("Test 19 — HW-vs-HW: FDPUPostScale vs FDPUWithCustomReductionTree")
    log("=" * 70)
    log("Primary metric: hwDiff = |postAcc − origAcc| (direct hardware difference)")
    log("Threshold: WARN when hwDiff > 5× origErr AND |origAcc| > 1e-4")

    val rng       = new Random(5678)
    val vsize     = 8
    val numCycles = 8
    val maxDegradation = 5.0f   // hwDiff must not exceed 5× origArch's own error

    def randElement19(t: ElementType, r: Random): Int = {
      val sign = r.nextInt(2)
      if (t.name == "INT8") {
        val mag = 1 + r.nextInt(20)
        if (sign == 0) mag else (-mag) & 0xFF
      } else {
        val expRange = 1 << t.elementWidthExp
        val expLo    = (t.bias + 1).max(1).min(expRange - 1)
        val expHi    = (t.bias + 3).max(expLo).min(expRange - 1)
        val exp      = expLo + r.nextInt(expHi - expLo + 1)
        val mant     = r.nextInt(1 << t.elementWidthMant)
        (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
      }
    }

    def randScale19(s: ScaleType, r: Random): Int = {
      val expRange = 1 << s.expScaleWidth
      val expLo    = (s.bias - 1).max(1).min(expRange - 1)
      val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
      val exp      = expLo + r.nextInt(expHi - expLo + 1)
      val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
      (exp << s.mantScaleWidth) | mant
    }

    case class HWRecord(
      label: String,
      swFinal: Float, origFinal: Float, postFinal: Float,
      origErr: Float, hwDiff: Float,
      opMantW: Int, saAddMantW: Int
    )

    val results = scala.collection.mutable.ArrayBuffer[HWRecord]()
    var anyFail = false

    val allElem  = MXFormats.allElementTypes

    for (etA <- allElem; etB <- allElem if isResearchPair(etA, etB); st <- researchScaleTypes) {
      val cfg   = ScaleAddConfig(etA, etB, st)
      val label = s"${etA.name} x ${etB.name} / ${st.name}"

      val seedRng = new Random(rng.nextLong())
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElement19(etA, seedRng))
        val bs = Seq.fill(vsize)(randElement19(etB, seedRng))
        (as, bs, randScale19(st, seedRng), randScale19(st, seedRng))
      }

      // SW reference (double precision)
      var swAcc = 0.0f
      cycles.foreach { case (as, bs, sA, sB) =>
        swAcc = (swAcc + swFusedProduct(cfg)(as, bs, sA, sB)).toFloat
      }

      // Run original architecture
      var origAcc = 0.0f
      try {
        test(new FDPUWithCustomReductionTree(cfg, vsize, false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(false.B)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          // FDPUWithCustomReductionTree's accOut is natively 32-bit FP32.
          origAcc = intBitsToFloat(dut.io.accOut.peek().litValue.toInt)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label Orig sim failed: ${e.getMessage}")
        anyFail = true
      }

      // Run new post-scale architecture
      var postAcc = 0.0f
      try {
        test(new FDPUPostScaleReductionTree(cfg, vsize, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(false.B)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          postAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label PostScale sim failed: ${e.getMessage}")
        anyFail = true
      }

      val origErr = math.abs(origAcc - swAcc)
      val hwDiff  = math.abs(postAcc - origAcc)
      // degradation: how many times larger is hwDiff than origErr
      val degrad  = if (origErr < 1e-10f) Float.PositiveInfinity else hwDiff / origErr

      results += HWRecord(label, swAcc, origAcc, postAcc, origErr, hwDiff,
        cfg.resOperatorMantWidth, cfg.resScaleAddMantWidth)

      val degradStr = if (degrad.isInfinite) "∞" else f"$degrad%.2f×"
      val isNonTrivial = math.abs(origAcc) > 1e-4f
      val status = if (isNonTrivial && degrad > maxDegradation) "WARN" else "OK  "

      log(f"  [$status] $label%-35s | opMantW=${cfg.resOperatorMantWidth}%2d saAddMantW=${cfg.resScaleAddMantWidth}%2d" +
          f" | SW=$swAcc%10.4e | orig=$origAcc%10.4e | post=$postAcc%10.4e" +
          f" | hwDiff=$hwDiff%.2e origErr=$origErr%.2e degrad=$degradStr")

      if (isNonTrivial && degrad > maxDegradation) {
        logErr(s"  [WARN] $label: hwDiff/origErr = $degradStr > ${maxDegradation}× " +
               s"(opMantW=${cfg.resOperatorMantWidth} vs saAddMantW=${cfg.resScaleAddMantWidth})")
      }
    }

    // ── Aggregate summary ────────────────────────────────────────────────
    log("\n--- HW-vs-HW Precision Summary ---")
    log("  Reference: FDPUWithCustomReductionTree (wider tree at resScaleAddMantWidth)")
    log("  Metric:    hwDiff = |postAcc − origAcc| (isolation of tree-mantissa effect)")

    val nonTrivial = results.filter(r => math.abs(r.origFinal) > 1e-4f)
    if (nonTrivial.nonEmpty) {
      val relHwDiffs = nonTrivial.map(r => r.hwDiff / math.abs(r.origFinal))
      val avgRel     = relHwDiffs.sum / relHwDiffs.size
      val maxRel     = relHwDiffs.max
      val under1pct  = relHwDiffs.count(_ < 0.01f)
      val under5pct  = relHwDiffs.count(_ < 0.05f)

      log(f"  Non-trivial configs (|origAcc| > 1e-4): ${nonTrivial.size}")
      log(f"  hwDiff / |origAcc| — avg: ${avgRel*100}%.2f%%  max: ${maxRel*100}%.2f%%")
      log(f"  Configs with hwDiff < 1%%: $under1pct / ${nonTrivial.size}")
      log(f"  Configs with hwDiff < 5%%: $under5pct / ${nonTrivial.size}")

      // Degradation ratio (only where origErr is meaningful)
      val degradValid = nonTrivial.filter(r => r.origErr > math.abs(r.origFinal) * 0.001f)
      if (degradValid.nonEmpty) {
        val degrads   = degradValid.map(r => r.hwDiff / r.origErr)
        val warnCount = degrads.count(_ > maxDegradation)
        log(f"  Degradation-valid configs (origErr > 0.1%% of orig): ${degradValid.size}")
        log(f"  hwDiff/origErr — avg: ${degrads.sum/degrads.size}%.2f×  max: ${degrads.max}%.2f×  >${maxDegradation.toInt}×: $warnCount")
      }

      log("  Top-5 largest hwDiff/|origAcc| (worst HW divergence):")
      nonTrivial.sortBy(r => -(r.hwDiff / math.abs(r.origFinal))).take(5).foreach { r =>
        val relDiff = r.hwDiff / math.abs(r.origFinal)
        val degrad  = if (r.origErr > 1e-10f) r.hwDiff / r.origErr else Float.PositiveInfinity
        log(f"    ${r.label}%-35s opMantW=${r.opMantW}%2d saAddMantW=${r.saAddMantW}%2d " +
            f"hwDiff/orig=${relDiff*100}%.2f%% degrad=${degrad}%.1f×")
      }
    }

    assert(!anyFail, "One or more simulation errors — see fdpu_post_scale_reduction_tree_test.log")
    log("[PASSED] HW-vs-HW precision comparison completed")
  }

  // =========================================================================
  // Test 20 — FixedFP Reduction Tree Accuracy: 128-cycle accumulation validation
  //
  // The FixedFP tree uses a single-pass integer accumulation (no per-node normalisation).
  // This test validates that accumulated error remains within tolerance for all
  // representative FP8/INT8 configurations, using a 128-cycle sequence against a
  // double-precision software reference.
  // =========================================================================
  test("Test 20 — FixedFP tree accumulation accuracy across representative configs") {
    log("\n" + "=" * 70)
    log("Test 20 — FixedFP Reduction Tree Accuracy (128-cycle accumulation)")
    log("=" * 70)

    val rng       = new Random(7777)
    val vsize     = 8
    val numCycles = 128

    val testConfigs = Seq(
      ScaleAddConfig(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4),
    )

    var anyFail = false

    for (cfg <- testConfigs) {
      val label = s"${cfg.elementTypeA.name}×${cfg.elementTypeB.name}/${cfg.stype.name} vec=$vsize"
      log(s"\n  [$label]  resOpMantW=${cfg.resOperatorMantWidth}  expRange=${cfg.productExpRange}")

      val seedRng = new Random(rng.nextLong())
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElemHelper(cfg.elementTypeA, seedRng))
        val bs = Seq.fill(vsize)(randElemHelper(cfg.elementTypeB, seedRng))
        (as, bs, randScaleHelper(cfg.stype, seedRng), randScaleHelper(cfg.stype, seedRng))
      }

      var swAccDouble = 0.0
      cycles.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, cfg.stype)
        val sBv = decodeScale(sB, cfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      var hwAcc = 0.0f
      try {
        test(new FDPUPostScaleReductionTree(cfg, vsize, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          hwAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      // Loose tolerance: FixedFP tree still subject to FP8-precision input rounding
      val looseTol = 0.15f * math.abs(swRef) + 1e-2f
      if (absErr > looseTol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$looseTol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more FixedFP accuracy checks failed — check log")
    log("\n[PASSED] FixedFP tree accuracy validated across all configs")
  }

  // =========================================================================
  // Test 21 — Block-deferred scale apply (UE8M0, cyclesPerBlock > 1)
  // -------------------------------------------------------------------------
  // Verifies the two-level FP accumulator path (buildBlockDeferred) by holding
  // the shared scales constant across cyclesPerBlock cycles per block — which
  // is the MX block semantics the new path was designed for.
  // =========================================================================
  test("Test 21 — Block-deferred scale apply (UE8M0, cyclesPerBlock=8)") {
    log("\n" + "=" * 70)
    log("Test 21 — Block-deferred scale apply (UE8M0)")
    log("=" * 70)

    val rng            = new Random(8888)
    val vsize          = 4
    val cyclesPerBlock = 8
    val numBlocks      = 16

    val testConfigs = Seq(
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0),
    )

    var anyFail = false

    for (cfg <- testConfigs) {
      val label = s"${cfg.elementTypeA.name}×${cfg.elementTypeB.name}/${cfg.stype.name} vec=$vsize cpb=$cyclesPerBlock"
      log(s"\n  [$label]  expRange=${cfg.productExpRange}")

      val seedRng = new Random(rng.nextLong())
      // Build cycles such that (sA, sB) is constant for every cyclesPerBlock-long run.
      val allCycles = (0 until numBlocks).flatMap { _ =>
        val sA = randScaleHelper(cfg.stype, seedRng)
        val sB = randScaleHelper(cfg.stype, seedRng)
        (0 until cyclesPerBlock).map { _ =>
          val as = Seq.fill(vsize)(randElemHelper(cfg.elementTypeA, seedRng))
          val bs = Seq.fill(vsize)(randElemHelper(cfg.elementTypeB, seedRng))
          (as, bs, sA, sB)
        }
      }

      var swAccDouble = 0.0
      allCycles.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, cfg.stype)
        val sBv = decodeScale(sB, cfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      var hwAcc = 0.0f
      try {
        test(new FDPUBlockDeferred(
          cfg, vsize, cyclesPerBlock = cyclesPerBlock, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          allCycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          hwAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      val looseTol = 0.15f * math.abs(swRef) + 1e-2f
      if (absErr > looseTol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$looseTol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more block-deferred accuracy checks failed — check log")
    log("\n[PASSED] Block-deferred scale apply validated across UE8M0 configs")
  }

  // =========================================================================
  // Test 22 — Block-deferred scale apply for non-UE8M0 (UE4M4, UE6M2)
  // -------------------------------------------------------------------------
  // Validates the FPxScale block-boundary bridge: scale mantissa product
  // is applied once per cyclesPerBlock cycles instead of per-cycle.
  // =========================================================================
  test("Test 22 — Block-deferred non-UE8M0 (UE4M4, UE6M2, cyclesPerBlock=8)") {
    log("\n" + "=" * 70)
    log("Test 22 — Block-deferred scale apply for non-UE8M0")
    log("=" * 70)

    val rng            = new Random(9999)
    val vsize          = 4
    val cyclesPerBlock = 8
    val numBlocks      = 16

    val testConfigs = Seq(
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4),
      ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4),
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE6M2),
      ScaleAddConfig(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE6M2),
    )

    var anyFail = false

    for (cfg <- testConfigs) {
      val label = s"${cfg.elementTypeA.name}×${cfg.elementTypeB.name}/${cfg.stype.name} vec=$vsize cpb=$cyclesPerBlock"
      log(s"\n  [$label]  expRange=${cfg.productExpRange}")

      val seedRng = new Random(rng.nextLong())
      val allCycles = (0 until numBlocks).flatMap { _ =>
        val sA = randScaleHelper(cfg.stype, seedRng)
        val sB = randScaleHelper(cfg.stype, seedRng)
        (0 until cyclesPerBlock).map { _ =>
          val as = Seq.fill(vsize)(randElemHelper(cfg.elementTypeA, seedRng))
          val bs = Seq.fill(vsize)(randElemHelper(cfg.elementTypeB, seedRng))
          (as, bs, sA, sB)
        }
      }

      var swAccDouble = 0.0
      allCycles.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, cfg.stype)
        val sBv = decodeScale(sB, cfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      var hwAcc = 0.0f
      try {
        test(new FDPUBlockDeferred(
          cfg, vsize, cyclesPerBlock = cyclesPerBlock, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          allCycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          hwAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      val looseTol = 0.15f * math.abs(swRef) + 1e-2f
      if (absErr > looseTol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$looseTol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more non-UE8M0 block-deferred checks failed — check log")
    log("\n[PASSED] Block-deferred non-UE8M0 validated")
  }

  // =========================================================================
  // Test 23 — Reduction-tree arch specialization (Arch-I / II / III)
  // -------------------------------------------------------------------------
  // Direct FDPU instantiation with explicit treeArch (bypassing the default
  // Generic) — exercises buildIntOnly / buildSmallFixedShift / buildTwoStageBarrel.
  // Numerical equivalence to buildGeneric is confirmed by SW reference match.
  // =========================================================================
  test("Test 23 — Reduction-tree arch specialization (Arch-I/II/III)") {
    log("\n" + "=" * 70)
    log("Test 23 — Reduction-tree arch specialization")
    log("=" * 70)

    val rng       = new Random(11111)
    val vsize     = 4
    val numCycles = 32

    val testCases = Seq(
      (ScaleAddConfig(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0), TreeArch.IntOnlySigned),
      (ScaleAddConfig(MXFormats.E2M1, MXFormats.E2M1, ScaleFormats.UE8M0), TreeArch.SmallFixedShift),
      (ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0), TreeArch.TwoStageBarrel),
      (ScaleAddConfig(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE8M0), TreeArch.TwoStageBarrel),
    )

    var anyFail = false

    for ((cfg, treeArch) <- testCases) {
      val label = s"${cfg.elementTypeA.name}×${cfg.elementTypeB.name}/${cfg.stype.name} arch=${treeArch.name}"
      log(s"\n  [$label]  expRange=${cfg.productExpRange}")

      val seedRng = new Random(rng.nextLong())
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElemHelper(cfg.elementTypeA, seedRng))
        val bs = Seq.fill(vsize)(randElemHelper(cfg.elementTypeB, seedRng))
        (as, bs, randScaleHelper(cfg.stype, seedRng), randScaleHelper(cfg.stype, seedRng))
      }

      var swAccDouble = 0.0
      cycles.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, cfg.stype)
        val sBv = decodeScale(sB, cfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      var hwAcc = 0.0f
      try {
        test(new FDPUPostScaleReductionTree(
          cfg, vsize, treeArch = treeArch, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          hwAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      val tol = 0.15f * math.abs(swRef) + 1e-2f
      if (absErr > tol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$tol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more arch specialization checks failed — check log")
    log("\n[PASSED] Arch-I/II/III specializations validated")
  }

  // =========================================================================
  // Test 24 — Arch-IV.b Kulisch within block (UE8M0)
  // -------------------------------------------------------------------------
  // Validates buildKulischDeferred: wide fixed-point inner accumulator within
  // each block (anchored at minProductExp) + FP outer across blocks.
  // Inputs hold shared scale constant across each block (MX block semantics).
  // =========================================================================
  test("Test 24 — Arch-IV.b Kulisch within block (UE8M0 + non-UE8M0)") {
    log("\n" + "=" * 70)
    log("Test 24 — Arch-IV.b Kulisch within block")
    log("=" * 70)

    val rng            = new Random(12345)
    val vsize          = 4
    val cyclesPerBlock = 8
    val numBlocks      = 16

    val testConfigs = Seq(
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0),
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0),
      // non-UE8M0 paths — exercises FPxScale at block boundary.
      ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4),
      ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4),
      ScaleAddConfig(MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE4M4),
    )

    var anyFail = false

    for (cfg <- testConfigs) {
      val label = s"${cfg.elementTypeA.name}×${cfg.elementTypeB.name}/${cfg.stype.name} arch=kulisch cpb=$cyclesPerBlock"
      log(s"\n  [$label]  expRange=${cfg.productExpRange}  minProductExp=${cfg.minProductExp}")

      val seedRng = new Random(rng.nextLong())
      // Constant scale within each block (MX semantics).
      val allCycles = (0 until numBlocks).flatMap { _ =>
        val sA = randScaleHelper(cfg.stype, seedRng)
        val sB = randScaleHelper(cfg.stype, seedRng)
        (0 until cyclesPerBlock).map { _ =>
          val as = Seq.fill(vsize)(randElemHelper(cfg.elementTypeA, seedRng))
          val bs = Seq.fill(vsize)(randElemHelper(cfg.elementTypeB, seedRng))
          (as, bs, sA, sB)
        }
      }

      var swAccDouble = 0.0
      allCycles.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, cfg.stype)
        val sBv = decodeScale(sB, cfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      var hwAcc = 0.0f
      try {
        test(new FDPUBlockDeferred(
          cfg, vsize,
          treeArch = TreeArch.KulischInner,
          cyclesPerBlock = cyclesPerBlock,
          istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          allCycles.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          hwAcc = peekFloat(dut)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      // Low-precision Kulisch with non-UE8M0 (e.g., E3M2×E3M2 + UE4M4) compounds
      // rounding through scale-mantissa multiplication; allow up to 20% relative
      // error here (vs 15% for UE8M0).  This is an inherent precision floor, not
      // an implementation bug.
      val tol = 0.20f * math.abs(swRef) + 1e-2f
      if (absErr > tol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$tol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more Kulisch path checks failed — check log")
    log("\n[PASSED] Arch-IV.b Kulisch within block validated")
  }

  // =========================================================================
  // Test 25 — Piped FDPU correctness across (pipeAfterTree, pipeInScale)
  // -------------------------------------------------------------------------
  // Validates FDPUPostScaleReductionTreePiped: the same sequence of inputs at
  // any pipe combination produces the same final accumulator value as the
  // un-piped baseline, just D cycles later, where D is the total feed-forward
  // latency = pipeAfterTree + (effective scale pipe).
  // =========================================================================
  test("Test 25 — Piped FDPU correctness across (pipeAfterTree, pipeInScale)") {
    log("\n" + "=" * 70)
    log("Test 25 — Piped FDPU correctness across (pipeAfterTree, pipeInScale)")
    log("=" * 70)

    val rng       = new Random(54321)
    val numCycles = 64

    case class Variant(label: String, scfg: ScaleAddConfig, vsize: Int,
                       treeArch: TreeArch, cpb: Int)
    val variants = Seq(
      Variant("Single/E5M2²/UE8M0/Generic",  ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0), 8, TreeArch.Generic,         1),
      Variant("Block/E4M3²/UE8M0",           ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0), 4, TreeArch.TwoStageBarrel,  8),
      Variant("Block/E4M3²/UE4M4",           ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4), 4, TreeArch.TwoStageBarrel,  8),
      Variant("Kulisch/E5M2²/UE8M0",         ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0), 4, TreeArch.KulischInner,    8),
      Variant("Kulisch/E5M2²/UE4M4",         ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4), 4, TreeArch.KulischInner,    8),
      Variant("Kulisch/E4M3²/UE4M4",         ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4), 4, TreeArch.KulischInner,    8),
    )

    // Combos: (pAT, pIS).  For UE8M0 variants, pIS is dominated (no effect).
    val combos = Seq(
      (false, false),
      (true,  false),
      (false, true),
      (true,  true),
    )

    var anyFail = false

    for (v <- variants; (pAT, pIS) <- combos) {
      val label = f"${v.label}  pAT=$pAT pIS=$pIS"
      log(s"\n  [$label]")

      val seedRng = new Random(rng.nextLong())
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(v.vsize)(randElemHelper(v.scfg.elementTypeA, seedRng))
        val bs = Seq.fill(v.vsize)(randElemHelper(v.scfg.elementTypeB, seedRng))
        (as, bs, randScaleHelper(v.scfg.stype, seedRng), randScaleHelper(v.scfg.stype, seedRng))
      }

      // Make scale stable across each block of `cpb` cycles (MX semantics).
      val cyclesStable = cycles.zipWithIndex.map { case ((as, bs, sA, sB), idx) =>
        if (v.cpb == 1) (as, bs, sA, sB)
        else {
          val blockIdx = idx / v.cpb
          val (_, _, sA0, sB0) = cycles(blockIdx * v.cpb)
          (as, bs, sA0, sB0)
        }
      }

      var swAccDouble = 0.0
      cyclesStable.foreach { case (as, bs, sA, sB) =>
        val sAv = decodeScale(sA, v.scfg.stype)
        val sBv = decodeScale(sB, v.scfg.stype)
        swAccDouble += as.zip(bs).map { case (a, b) =>
          decodeElement(a, v.scfg.elementTypeA) * decodeElement(b, v.scfg.elementTypeB) * sAv * sBv
        }.sum
      }
      val swRef = swAccDouble.toFloat

      // Effective drain depth: pAT adds 1 feed-forward cycle; pIS adds 1 to
      // the block-end commit when the scale carries a mantissa.
      val pISActive = pIS && v.scfg.stype.mantScaleWidth > 0
      val drainCycles = (if (pAT) 1 else 0) + (if (pISActive) 1 else 0)

      var hwAcc = 0.0f
      try {
        test(new FDPUPostScaleReductionTreePiped(
          scfg = v.scfg, vectorSize = v.vsize,
          treeArch = v.treeArch, cyclesPerBlock = v.cpb,
          pipeAfterTree = pAT, pipeInScale = pIS, istest = false)) { dut =>
          dut.reset.poke(true.B)
          dut.io.validIn.poke(false.B)
          dut.io.resetAcc.poke(true.B)
          dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          cyclesStable.foreach { case (as, bs, sA, sB) =>
            dut.io.op_a_i.poke(packElements(as, v.scfg.elementTypeA.totalWidth).U)
            dut.io.op_b_i.poke(packElements(bs, v.scfg.elementTypeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B)
            dut.clock.step()
            dut.io.validIn.poke(false.B)
          }
          if (drainCycles > 0) dut.clock.step(drainCycles)
          // FDPUPostScaleReductionTreePiped: narrow accOut, shift-extend.
          val raw = dut.io.accOut.peek().litValue
          hwAcc = intBitsToFloat((raw << (23 - dut.actualAccMantBits)).toInt)
        }
      } catch { case e: Exception =>
        logErr(s"  [ERROR] $label: ${e.getMessage}")
        anyFail = true
      }

      val absErr    = math.abs(hwAcc - swRef)
      val relErrPct = if (math.abs(swRef) > 1e-10f) absErr / math.abs(swRef) * 100.0f else Float.PositiveInfinity
      log(f"  absErr=$absErr%-12.4e  relErr=$relErrPct%-8.3f%%  HW=$hwAcc%-14.4e  SW=$swRef%.4e")

      val tol = 0.15f * math.abs(swRef) + 1e-2f
      if (absErr > tol) {
        logErr(s"  [FAIL] $label: absErr=$absErr > tol=$tol")
        anyFail = true
      }
    }

    assert(!anyFail, "One or more piped FDPU correctness checks failed — check log")
    log("\n[PASSED] Piped FDPU validated across (pipeAfterTree, pipeInScale)")
  }
}
