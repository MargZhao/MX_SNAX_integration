package mx.array

import chisel3._
import chisel3.util._
import mx_simple.{SimpleDPU, DPUConfig, ElemOperand, ScaleOperand, BF16}
import mx_simple.{Elem => SElem, Scale => SScale}
import mx.mac.{ScaleAddConfig, ElementType}
import mx.requant.{RequantFP8, RequantConfig, RequantINT8, RequantINT8Config}

// ============================================================
// SimpleDPU-based PE array — drop-in replacement for mx.array.PEArrayWrapper*
// ============================================================
//
// Same external module ("PE_Array") and port list as the FDPU wrappers, so the
// orchestrator-generated PE_Array_wrapper.sv, the snax_mx_alu shell, and the
// runthru flow need NO changes — only which Chisel wrapper EmitTensorCore emits.
//
// Internals differ:
//   * PEs are SimpleDPU (BF16 accumulator, no bit-width optimisation) instead
//     of FDPU (narrow-FP M_acc accumulator).
//   * SimpleDPU uses an active-HIGH async reset; the cluster/shell drive an
//     active-LOW reset on .reset(rst_ni).  We reconcile by instantiating each
//     SimpleDPU inside `withReset((!reset).asAsyncReset)` so its accreg clears
//     when the wrapper's reset port is 0 (active-low), matching FDPU/PEArray.
//   * SimpleDPU has no validOut; we synthesise the same registered `peValidOut`
//     the FDPU wrappers tap (validReg := validIn), then reuse the identical
//     accumulation-count → resultDone gate.
//   * The BF16 accumulator feeds the SAME RequantFP8 / RequantINT8 blocks
//     (inputMantWidth = 7 for FP8/FP6; 8 via a 1-bit zero-pad for INT8), or is
//     packed straight through for BF16 / FP32(-passthrough) output.

sealed trait SimpleOut
object SimpleOut {
  /** MXFP8/FP6 block-requantised output (E5M2/E4M3/E3M2/E2M3/E2M1). */
  final case class FP8(outType: ElementType) extends SimpleOut
  case object INT8 extends SimpleOut
  case object BF16 extends SimpleOut
  /** BF16 accumulator right-padded to IEEE-754 FP32 (mant 7 → 23). */
  case object FP32 extends SimpleOut
}

/** Config for the SimpleDPU array.  `macCfg` supplies the mx element/scale
 *  types (for naming + requant); the mac_simple DPUConfig is derived by name. */
case class SimpleArrayConfig(
  macCfg:     ScaleAddConfig,
  vectorSize: Int,
  tileRows:   Int,
  tileCols:   Int,
  out:        SimpleOut,
  blockSize:  Int = 16,
) {
  val dpuCfg: DPUConfig = DPUConfig(
    A = SElem.byName(macCfg.elementTypeA.name),
    W = SElem.byName(macCfg.elementTypeB.name),
    S = SScale.byName(macCfg.stype.name),
    N = vectorSize)

  // ── Format-parity assertions (mx_simple vs mx.MXFormats/ScaleFormats) ──
  private val wAsimple = 1 + dpuCfg.A.e + dpuCfg.A.m
  private val wBsimple = 1 + dpuCfg.W.e + dpuCfg.W.m
  require(wAsimple == macCfg.elementTypeA.totalWidth,
    s"element A width mismatch: mac_simple ${dpuCfg.A.name}=$wAsimple vs mx ${macCfg.elementTypeA.name}=${macCfg.elementTypeA.totalWidth}")
  require(wBsimple == macCfg.elementTypeB.totalWidth,
    s"element W width mismatch: mac_simple=$wBsimple vs mx=${macCfg.elementTypeB.totalWidth}")
  require(dpuCfg.S.e + dpuCfg.S.m == macCfg.stype.totalScaleWidth,
    s"scale width mismatch: mac_simple ${dpuCfg.S.name}=${dpuCfg.S.e + dpuCfg.S.m} vs mx=${macCfg.stype.totalScaleWidth}")

  val srcWidthA  = wAsimple * vectorSize
  val srcWidthB  = wBsimple * vectorSize
  val scaleWidth = macCfg.stype.totalScaleWidth
  val bf16W      = 1 + BF16.expBits + BF16.mantBits           // 16

  /** Requant input mantissa width fed from the BF16 accumulator. */
  val rqInMantW: Int = out match {
    case SimpleOut.INT8 => 8                                  // RequantINT8 requires >= 8
    case _              => BF16.mantBits                      // 7 for FP8/FP6
  }
  val hasSharedScale: Boolean = out match {
    case SimpleOut.FP8(_) | SimpleOut.INT8 => true
    case _                                 => false
  }
  val resultW: Int = out match {
    case SimpleOut.FP8(t) => tileRows * blockSize * t.totalWidth
    case SimpleOut.INT8   => tileRows * blockSize * 8
    case SimpleOut.BF16   => tileRows * tileCols * 16
    case SimpleOut.FP32   => tileRows * tileCols * 32
  }
  require(blockSize % vectorSize == 0,
    s"blockSize ($blockSize) must be divisible by vectorSize ($vectorSize)")
}

class SimplePEArray(cfg: SimpleArrayConfig) extends Module {
  override def desiredName = "PE_Array"

  private val A  = cfg.dpuCfg.A
  private val W  = cfg.dpuCfg.W
  private val S  = cfg.dpuCfg.S
  private val N  = cfg.vectorSize

  val io = IO(new Bundle {
    // ── CSR & Control (identical to PEArrayWrapper) ──────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))
    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())
    val accumulation_count_i = Input(UInt(32.W))

    // ── Handshakes ───────────────────────────────────────────────────────
    val A_valid_i = Input(Bool())
    val B_valid_i = Input(Bool())
    val A_ready_o = Output(Bool())
    val B_ready_o = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    val op_a_i         = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i         = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── Output ───────────────────────────────────────────────────────────
    val shared_scale_out =
      if (cfg.hasSharedScale) Some(Output(UInt((cfg.tileRows * cfg.scaleWidth).W))) else None
    val result    = Output(UInt(cfg.resultW.W))
    val valid_out = Output(Bool())
  })

  // ── Handshake logic (identical to PEArrayWrapper) ──────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── Bit unpackers: packed operand/scale UInt → SimpleDPU bundles ───────
  private def unpackElem(bits: UInt, el: SElem, lane: Int): ElemOperand = {
    val w = 1 + el.e + el.m
    val slice = bits(w * (lane + 1) - 1, w * lane)
    val o = Wire(new ElemOperand(el))
    o.sign := slice(w - 1)
    o.mant := slice(el.m - 1, 0)
    o.exp  := (if (el.e > 0) slice(w - 2, el.m) else 0.U(0.W))
    o
  }
  private def unpackScale(bits: UInt, sc: SScale): ScaleOperand = {
    val o = Wire(new ScaleOperand(sc))
    o.exp  := bits(sc.e + sc.m - 1, sc.m)
    o.mant := (if (sc.m > 0) bits(sc.m - 1, 0) else 0.U(0.W))
    o
  }

  // ── PE array: tileRows × tileCols SimpleDPU (reset polarity flipped) ───
  val peValidOut = Wire(Bool())
  val accBF16    = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.bf16W.W))))

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      // Active-low reconciliation: SimpleDPU's internal reset.asAsyncReset now
      // asserts when the wrapper's reset port is 0 (cluster active-low rst_ni).
      val pe = withReset((!reset.asBool).asAsyncReset)(Module(new SimpleDPU(cfg.dpuCfg)))
      for (i <- 0 until N) {
        pe.io.a(i) := unpackElem(io.op_a_i(r), A, i)
        pe.io.w(i) := unpackElem(io.op_b_i(c), W, i)
      }
      pe.io.scaleA   := unpackScale(io.shared_exp_A_i(r), S)
      pe.io.scaleW   := unpackScale(io.shared_exp_B_i(c), S)
      pe.io.enable   := internal_valid
      pe.io.clearAcc := io.acc_reset_i

      accBF16(r)(c) := Cat(pe.io.accOut.sign, pe.io.accOut.exp, pe.io.accOut.mant)
    }
  }

  // SimpleDPU has no validOut — synthesise the FDPU-equivalent registered
  // peValidOut (validReg := validIn), same active-low reset convention.
  private val validReg = withReset((!reset.asBool).asAsyncReset)(RegInit(false.B))
  when(io.acc_reset_i)        { validReg := false.B }
    .elsewhen(internal_valid) { validReg := true.B }
    .otherwise                { validReg := false.B }
  peValidOut := validReg

  // ── Accumulation-count → resultDone gate (identical to FDPU wrappers) ──
  private val rqAsyncRstN = (!reset.asBool).asAsyncReset
  private val accCnt      = withReset(rqAsyncRstN)(RegInit(0.U(32.W)))
  private val resultDone  = WireDefault(false.B)
  when(peValidOut) {
    when(accCnt === io.accumulation_count_i - 1.U) {
      accCnt     := 0.U
      resultDone := true.B
    }.otherwise {
      accCnt := accCnt + 1.U
    }
  }
  when(io.acc_reset_i) { accCnt := 0.U }

  // Flat row-major packing of the BF16 accumulator outputs (big-endian).
  private val accFlat: Seq[UInt] =
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols) yield accBF16(r)(c)

  // ── Output stage per mode ──────────────────────────────────────────────
  cfg.out match {
    case SimpleOut.FP8(outType) =>
      val rqCfg = RequantConfig(cfg.blockSize, cfg.tileRows, cfg.tileCols,
                                outType, cfg.macCfg.stype, inputMantWidth = cfg.rqInMantW)
      val rq = Module(new RequantFP8(rqCfg))
      rq.io.fp32_in  := Cat(accFlat)                          // 16-bit each = inputWidth
      rq.io.valid_in := resultDone
      io.shared_scale_out.get := rq.io.shared_scale_out
      io.result               := rq.io.elem_out
      io.valid_out            := rq.io.valid_out

    case SimpleOut.INT8 =>
      val rqCfg = RequantINT8Config(cfg.blockSize, cfg.tileRows, cfg.tileCols,
                                    cfg.macCfg.stype, inputMantWidth = cfg.rqInMantW)
      val rq = Module(new RequantINT8(rqCfg))
      rq.io.fp32_in  := Cat(accFlat.map(x => Cat(x, 0.U(1.W))))  // 17-bit (mant 7→8)
      rq.io.valid_in := resultDone
      io.shared_scale_out.get := rq.io.shared_scale_out
      io.result               := rq.io.int8_out
      io.valid_out            := rq.io.valid_out

    case SimpleOut.BF16 =>
      // SimpleDPU is already BF16 — register the packed output on resultDone.
      val outReg   = withReset(rqAsyncRstN)(RegInit(0.U(cfg.resultW.W)))
      val validOut = withReset(rqAsyncRstN)(RegInit(false.B))
      when(resultDone) { outReg := Cat(accFlat); validOut := true.B }
        .otherwise     { validOut := false.B }
      io.result    := outReg
      io.valid_out := validOut

    case SimpleOut.FP32 =>
      // BF16 → IEEE-754 FP32 pass-through: zero-pad mantissa 7 → 23.
      val fp32Flat = accFlat.map { bf =>
        val sign = bf(15); val exp = bf(14, 7); val mant7 = bf(6, 0)
        Cat(sign, exp, mant7, 0.U(16.W))                      // 32-bit
      }
      val outReg   = withReset(rqAsyncRstN)(RegInit(0.U(cfg.resultW.W)))
      val validOut = withReset(rqAsyncRstN)(RegInit(false.B))
      when(resultDone) { outReg := Cat(fp32Flat); validOut := true.B }
        .otherwise     { validOut := false.B }
      io.result    := outReg
      io.valid_out := validOut
  }
}
