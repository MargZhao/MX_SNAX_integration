package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.io.Source

/** Per-PE accumulation testbench driven by production-quantized data.
 *
 *  Stimulus is produced by test/gen_pe_testvectors.py — it reuses the snax-mx
 *  `gen_workload` (transformer-like "fitted" distribution) + `quantize_mx_v6`
 *  (NVFP4-style round-up block scale) to build one length-K dot product for a
 *  single BFP_PE, serialised as per-accumulate-cycle stimulus + a float64
 *  golden (the ideal dequantized dot product).
 *
 *  Oracle (per user): the current PE is lossy (anchored fixed-point tree +
 *  early RNE + narrow-FP FusedScaleAccumulator), so accOut is decoded to
 *  double and compared to the golden within a relative tolerance — same style
 *  as the (disabled) FDPUPostScaleReductionTreeTest.  Timing (validOut vs
 *  validIn, resetAcc) is checked cycle-by-cycle.
 *
 *  Vectors live under the test/vectors directory (.tv files, relative to the
 *  sbt project root).
 */
class PEAccumTestbenchSpec extends AnyFlatSpec with ChiselScalatestTester {

  // ── Name → hardware type ────────────────────────────────────────────────
  private def elemOf(n: String): ElementType = n match {
    case "E5M2" => MXFormats.E5M2
    case "E4M3" => MXFormats.E4M3
    case "E3M2" => MXFormats.E3M2
    case "E2M3" => MXFormats.E2M3
    case "E2M1" => MXFormats.E2M1
    case "INT8" => MXFormats.INT8
    case o      => throw new IllegalArgumentException(s"unknown element $o")
  }
  private def scaleOf(n: String): ScaleType = n match {
    case "UE8M0" => ScaleFormats.UE8M0
    case "UE7M1" => ScaleFormats.UE7M1
    case "UE6M2" => ScaleFormats.UE6M2
    case "UE5M3" => ScaleFormats.UE5M3
    case "UE4M4" => ScaleFormats.UE4M4
    case "UE4M3" => ScaleFormats.UE4M3
    case o       => throw new IllegalArgumentException(s"unknown scale $o")
  }

  // ── .tv parser ──────────────────────────────────────────────────────────
  case class Cyc(opA: BigInt, opB: BigInt, scaleA: BigInt, scaleB: BigInt)
  case class TV(act: String, weight: String, scale: String, mAcc: Int,
                wA: Int, wB: Int, vectorSize: Int, K: Int, golden: Double,
                cycles: Seq[Cyc])

  private def parseTV(path: java.io.File): TV = {
    val hdr = scala.collection.mutable.Map[String, String]()
    val cyc = scala.collection.mutable.ArrayBuffer[Cyc]()
    var inCycles = false
    val src = Source.fromFile(path)
    try {
      for (raw <- src.getLines()) {
        val line = raw.trim
        if (line.isEmpty) inCycles = true
        else if (!inCycles) {
          val sp = line.indexOf(' ')
          hdr(line.substring(0, sp)) = line.substring(sp + 1)
        } else {
          val t = line.split("\\s+")
          cyc += Cyc(BigInt(t(0)), BigInt(t(1)), BigInt(t(2)), BigInt(t(3)))
        }
      }
    } finally src.close()
    TV(hdr("act"), hdr("weight"), hdr("scale"), hdr("m_acc").toInt,
       hdr("wA").toInt, hdr("wB").toInt, hdr("vector_size").toInt,
       hdr("K").toInt, hdr("golden_dot").toDouble, cyc.toSeq)
  }

  // ── Decode narrow-FP accOut {sign, exp[8], mant[mAcc]} → Double ──────────
  private def decodeFPn(bits: BigInt, mAcc: Int): Double = {
    val s = ((bits >> (8 + mAcc)) & 1).toInt
    val e = ((bits >> mAcc) & 0xFF).toInt
    val m = bits & ((BigInt(1) << mAcc) - 1)
    if (e == 0) 0.0
    else if (e == 255) (if (s == 1) Double.NegativeInfinity else Double.PositiveInfinity)
    else (if (s == 1) -1.0 else 1.0) * (1.0 + m.toDouble / math.pow(2, mAcc)) * math.pow(2, e - 127)
  }

  private val vectorsDir = new java.io.File("test/vectors")
  private val tvFiles =
    Option(vectorsDir.listFiles((_, n) => n.endsWith(".tv"))).getOrElse(Array.empty).sortBy(_.getName)

  // Relative tolerance for the lossy datapath vs the ideal double golden.
  // (Reported per-combo below so the value can be calibrated with real data.)
  private val relTol = 0.05
  private val absTol = 1e-3

  if (tvFiles.isEmpty)
    it should "find generated .tv vectors" in {
      fail("no test/vectors/*.tv found — run: python3 test/gen_pe_testvectors.py --act .. --weight .. --scale ..")
    }

  for (f <- tvFiles) {
    val tv = parseTV(f)
    s"BFP_PE[${tv.act}x${tv.weight}/${tv.scale}]" should
      s"accumulate ${tv.cycles.length} cycles within tol and time correctly (${f.getName})" in {
      val scfg = ScaleAddConfig(elemOf(tv.act), elemOf(tv.weight), scaleOf(tv.scale))
      test(new FDPU(scfg, vectorSize = tv.vectorSize, K = tv.K, accMantBits = tv.mAcc)) { dut =>
        val mAcc = dut.actualAccMantBits
        val wA   = elemOf(tv.act).totalWidth
        val wB   = elemOf(tv.weight).totalWidth
        val sW   = scaleOf(tv.scale).totalScaleWidth

        // The PE uses an active-low async reset (asyncRstN = (!reset).asAsyncReset):
        // hold the implicit reset HIGH so the registers operate, and clear the
        // accumulator via io.resetAcc (matches the disabled test's initDut).
        dut.reset.poke(true.B)
        dut.io.validIn.poke(false.B)
        dut.io.resetAcc.poke(true.B)
        dut.clock.step(1)
        dut.io.validOut.expect(false.B, "validOut must be low after resetAcc")
        dut.io.resetAcc.poke(false.B)

        // drive the accumulation, one vector per cycle
        for (cy <- tv.cycles) {
          dut.io.op_a_i.poke(cy.opA.U((tv.vectorSize * wA).W))
          dut.io.op_b_i.poke(cy.opB.U((tv.vectorSize * wB).W))
          dut.io.share_exp_A_i.poke(cy.scaleA.U(sW.W))
          dut.io.share_exp_B_i.poke(cy.scaleB.U(sW.W))
          dut.io.validIn.poke(true.B)
          dut.clock.step(1)
          dut.io.validOut.expect(true.B, "validOut must follow validIn (1-cycle latency)")
        }
        dut.io.validIn.poke(false.B)
        dut.clock.step(1)
        dut.io.validOut.expect(false.B, "validOut must drop when validIn deasserts")

        val got    = decodeFPn(dut.io.accOut.peek().litValue, mAcc)
        val golden = tv.golden
        val relErr = math.abs(got - golden) / (math.abs(golden) + 1e-30)
        info(f"${tv.act}x${tv.weight}/${tv.scale}  M_acc=$mAcc  golden=$golden%.6g  got=$got%.6g  relErr=${relErr * 100}%.3f%%")
        assert(math.abs(got - golden) <= relTol * math.abs(golden) + absTol,
          f"result out of tolerance: golden=$golden%.6g got=$got%.6g relErr=${relErr * 100}%.2f%% (tol ${relTol * 100}%.0f%% + $absTol)")
      }
    }
  }
}
