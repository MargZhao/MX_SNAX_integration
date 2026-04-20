package mx.requant

import chisel3._
import chisel3.util._

// ============================================================
// FP32 → MXFP8 single-element converter
// ============================================================
/**
 * Converts one IEEE-754 FP32 value to MXFP8 (E5M2 or E4M3) given the
 * block's shared scale.
 *
 * Scale formats (selected by cfg.scaleType at elaboration time):
 *   UE8M0 — shared_scale is the max biased FP32 exponent in the block
 *            (pure power-of-2 scale, original behaviour).
 *   ExMy  — shared_scale = {biased_exp[E-1:0], mant[M-1:0]} where
 *            the scale value is the floor-to-ExMy of the block's
 *            maximum-magnitude FP32 element.
 *
 * Rounding : round-to-nearest-even (RNE).
 * Saturation: clamp to ±max-normal when re-biased exponent > fp8MaxNormalExp.
 * Underflow : flush to ±0 when re-biased exponent ≤ 0.
 */
class FP32ToMXFP8(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"FP32ToMXFP8_${cfg.outputType.name}_${cfg.scaleType.name}"

  private val fp8ExpBits      = cfg.outputType.elementWidthExp
  private val fp8MantBits     = cfg.outputType.elementWidthMant
  private val fp8Bias         = cfg.outputType.bias
  private val fp8MaxNormalExp = (1 << fp8ExpBits) - 2

  val io = IO(new Bundle {
    val fp32_in      = Input(UInt(32.W))
    val shared_scale = Input(UInt(8.W))   // always 8 bits; format depends on cfg.scaleType
    val fp8_out      = Output(UInt(cfg.outputType.totalWidth.W))
  })

  // ── Unpack FP32 ──────────────────────────────────────────
  val sign     = io.fp32_in(31)
  val fp32_exp = io.fp32_in(30, 23)   // biased exponent, 0-255
  val fp32_man = io.fp32_in(22, 0)    // 23-bit fractional mantissa

  val isZeroOrSubnormal = fp32_exp === 0.U
  val fp32_exp_s        = fp32_exp.zext   // SInt(9)

  val maxNormalMant = ((1 << fp8MantBits) - 1).U(fp8MantBits.W)
  val maxNormalExpU = fp8MaxNormalExp.U(fp8ExpBits.W)

  if (cfg.scaleType.mantScaleWidth == 0) {
    // ── UE8M0 path (original, unchanged) ──────────────────
    //
    // shared_scale = max biased FP32 exponent in the block.
    // fp8_biased_exp = fp32_biased_exp − shared_scale + fp8Bias
    val shared_exp_s = io.shared_scale.zext     // SInt(9)
    val fp8_exp_full = fp32_exp_s - shared_exp_s + fp8Bias.S

    val underflow = isZeroOrSubnormal || fp8_exp_full <= 0.S
    val overflow  = fp8_exp_full > fp8MaxNormalExp.S

    val fp8_mant_raw = fp32_man(22, 23 - fp8MantBits)
    val guardBit     = fp32_man(22 - fp8MantBits)
    val stickyBits   =
      if (22 - fp8MantBits > 0) fp32_man(21 - fp8MantBits, 0).orR
      else false.B
    val roundUp  = guardBit && (fp8_mant_raw(0) || stickyBits)
    val fp8_mant = Mux(roundUp, fp8_mant_raw + 1.U, fp8_mant_raw)

    val fp8_exp_clamped = fp8_exp_full.asUInt(fp8ExpBits - 1, 0)
    val normalResult    = Cat(sign, fp8_exp_clamped, fp8_mant)
    val maxResult       = Cat(sign, maxNormalExpU, maxNormalMant)

    io.fp8_out := Mux(underflow, 0.U, Mux(overflow, maxResult, normalResult))

  } else {
    // ── ExMy path ──────────────────────────────────────────
    //
    // shared_scale = {biased_scale_exp[E-1:0], scale_mant[M-1:0]}
    // where E = expScaleWidth, M = mantScaleWidth, E+M = 8.
    //
    // Scale value  = (1.scale_mant) × 2^(scale_biased_exp − scale_bias)
    // FP32 value   = (1.fp32_mant)  × 2^(fp32_biased_exp  − 127)
    // Element      = FP32 / scale
    //              = [(1.fp32_mant)/(1.scale_mant)]
    //                × 2^(fp32_biased_exp − scale_biased_exp + correction)
    //
    // correction = scale_bias − 127 + fp8Bias  (design-time constant).
    //
    // The mantissa quotient q = (1.fp32_mant)/(1.scale_mant) is in [0.5, 2).
    // We compute q_int = fp32FullMant × 2^EXTRA / scale_full_mant as an integer.
    // The implicit-1 of q lands at bit IMPL = 23 − M + EXTRA of q_int.

    val M          = cfg.scaleType.mantScaleWidth   // 1..6
    val E          = cfg.scaleType.expScaleWidth    // = 8 − M
    val scaleBias  = cfg.scaleType.bias             // (1 << (E-1)) − 1
    val correction = scaleBias - 127 + fp8Bias      // design-time Scala Int

    val scale_biased_exp = io.shared_scale(7, M)          // E bits
    val scale_mant_raw   = io.shared_scale(M - 1, 0)     // M bits
    // Implicit-1 for normal (biased_exp > 0); 0 for subnormal/zero
    val scale_full_mant  = Cat(scale_biased_exp.orR, scale_mant_raw)  // (M+1) bits

    val fp8_exp_raw = fp32_exp_s - scale_biased_exp.zext + correction.S  // SInt

    // ── Mantissa division q = (1.fp32_mant) / (1.scale_mant) ──
    //
    // EXTRA provides fp8MantBits fractional bits + 3 guard/round/sticky bits.
    // Integer representation:
    //   q_int  = fp32FullMant × 2^EXTRA / scale_full_mant
    //   value  = q_int × 2^(M − 23 − EXTRA)
    //
    // MSB of q_int (= implicit-1 of q) is at:
    //   IMPL     = 23 − M + EXTRA   when q ≥ 1
    //   IMPL − 1                    when q ∈ [0.5, 1)
    val EXTRA = fp8MantBits + 3
    val IMPL  = 23 - M + EXTRA      // e.g. E4M3+UE7M1: 23-1+6 = 28

    val fp32FullMant = Cat(fp32_exp.orR, fp32_man)    // 24 bits (0 for zero/subnormal)
    val qNum         = Cat(fp32FullMant, 0.U(EXTRA.W))  // (24+EXTRA) bits

    val safeDenom = Mux(scale_full_mant === 0.U, 1.U((M + 1).W), scale_full_mant)
    val q_int     = qNum / safeDenom
    val q_rem     = qNum % safeDenom

    val qGeq1 = q_int(IMPL)   // 1 iff q_frac ≥ 1.0

    // Mantissa bits and rounding (all bit-indices are Scala-time constants)
    val fp8_mant_raw = Mux(
      qGeq1,
      q_int(IMPL - 1,         IMPL - fp8MantBits),
      q_int(IMPL - 2,         IMPL - 1 - fp8MantBits)
    )
    val guardBit = Mux(qGeq1,
      q_int(IMPL - fp8MantBits - 1),
      q_int(IMPL - fp8MantBits - 2))
    val roundBit = Mux(qGeq1,
      q_int(IMPL - fp8MantBits - 2),
      q_int(IMPL - fp8MantBits - 3))

    // Sticky: lower q_int bits + division remainder
    // For qGeq1: IMPL−fp8MantBits−3 = 23−M  (≥ 17 for all valid M)
    // For qLt1:  IMPL−fp8MantBits−4 = 22−M  (≥ 16 for all valid M)
    val stickyQ_geq1 = q_int(IMPL - fp8MantBits - 3, 0).orR
    val stickyQ_lt1  = q_int(IMPL - fp8MantBits - 4, 0).orR
    val stickyBit    = (q_rem =/= 0.U) || Mux(qGeq1, stickyQ_geq1, stickyQ_lt1)

    val roundUp        = guardBit && (fp8_mant_raw(0) || roundBit || stickyBit)
    val fp8_mant_carry = fp8_mant_raw +& roundUp        // (fp8MantBits+1) bits
    val mantOverflow   = fp8_mant_carry(fp8MantBits)
    val fp8_mant       = fp8_mant_carry(fp8MantBits - 1, 0)

    // Apply normalisation shift (−1 when q < 1) and mantissa carry-out
    val normAdj      = Mux(qGeq1, 0.S(2.W), (-1).S(2.W))
    val fp8_exp_full = fp8_exp_raw + normAdj + mantOverflow.zext

    val underflow = isZeroOrSubnormal || fp8_exp_full <= 0.S
    val overflow  = fp8_exp_full > fp8MaxNormalExp.S

    val fp8_exp_clamped = fp8_exp_full.asUInt(fp8ExpBits - 1, 0)
    val normalResult    = Cat(sign, fp8_exp_clamped, fp8_mant)
    val maxResult       = Cat(sign, maxNormalExpU, maxNormalMant)

    io.fp8_out := Mux(underflow, 0.U, Mux(overflow, maxResult, normalResult))
  }
}

// ============================================================
// Block shared-scale finder (combinational reduction tree)
// ============================================================
/**
 * Finds the shared scale for one MX block of blockSize FP32 elements.
 *
 * UE8M0: output is the maximum biased FP32 exponent across the block.
 *
 * ExMy:  output is the maximum-magnitude FP32 value floor-encoded as
 *        an ExMy scale: {biased_exp[E-1:0], mant[M-1:0]}.
 *        scale_biased_exp = clamp(max_fp32_biased_exp − 127 + scale_bias, 0, 2^E−1)
 *        scale_mant       = top M bits of max_fp32_mantissa
 */
class MaxScaleFinder(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"MaxScaleFinder_${cfg.scaleType.name}_${cfg.outputType.name}_blk${cfg.blockSize}"

  val io = IO(new Bundle {
    val fp32_in   = Input(Vec(cfg.blockSize, UInt(32.W)))
    val max_scale = Output(UInt(8.W))
  })

  // Generic balanced max-tree for any UInt width
  def maxTree(vals: Seq[UInt]): UInt =
    if (vals.length == 1) vals.head
    else {
      val next = vals.grouped(2).map {
        case Seq(a, b) => Mux(a >= b, a, b)
        case Seq(a)    => a
        case g         => g.head  // unreachable
      }.toSeq
      maxTree(next)
    }

  if (cfg.scaleType.mantScaleWidth == 0) {
    // ── UE8M0: max biased FP32 exponent ──────────────────
    io.max_scale := maxTree(io.fp32_in.map(_(30, 23)))

  } else {
    // ── ExMy: floor-encode max-magnitude FP32 to ExMy ────
    val M            = cfg.scaleType.mantScaleWidth
    val E            = cfg.scaleType.expScaleWidth
    val scaleBias    = cfg.scaleType.bias
    val maxScaleExpV = (1 << E) - 1

    // Find FP32 with largest absolute value (sign-stripped 31-bit comparison)
    val maxMag31     = maxTree(io.fp32_in.map(_(30, 0)))
    val maxBiasedExp = maxMag31(30, 23)
    val maxMant23    = maxMag31(22, 0)

    // Rebase to ExMy biased exponent, clamped to [0, 2^E − 1]
    val expRaw = maxBiasedExp.zext - 127.S + scaleBias.S
    val expClamped = Mux(expRaw <= 0.S,         0.U(E.W),
                    Mux(expRaw >= maxScaleExpV.S, maxScaleExpV.U(E.W),
                        expRaw.asUInt(E - 1, 0)))

    // Floor-encode FP32 mantissa to M bits (top M bits of 23-bit field)
    val mantEnc = maxMant23(22, 23 - M)

    io.max_scale := Cat(expClamped, mantEnc)
  }
}

// ============================================================
// One-row block requantizer (combinational)
// ============================================================
/**
 * Requantizes blockSize FP32 values for one tile row.
 * Purely combinational: MaxScaleFinder → blockSize × FP32ToMXFP8.
 */
class RequantBlock(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"RequantBlock_${cfg.outputType.name}_${cfg.scaleType.name}_blk${cfg.blockSize}"

  val io = IO(new Bundle {
    val fp32_in      = Input(Vec(cfg.blockSize, UInt(32.W)))
    val shared_scale = Output(UInt(8.W))
    val fp8_out      = Output(Vec(cfg.blockSize, UInt(cfg.outputType.totalWidth.W)))
  })

  val scaleFinder = Module(new MaxScaleFinder(cfg))
  scaleFinder.io.fp32_in := io.fp32_in
  io.shared_scale        := scaleFinder.io.max_scale

  for (i <- 0 until cfg.blockSize) {
    val conv = Module(new FP32ToMXFP8(cfg))
    conv.io.fp32_in      := io.fp32_in(i)
    conv.io.shared_scale := scaleFinder.io.max_scale
    io.fp8_out(i)        := conv.io.fp8_out
  }
}

// ============================================================
// Top-level: buffer + controller + tileRows parallel blocks
// ============================================================
/**
 * RequantFP8: receives the full TileRows × TileCols FP32 output of
 * a PE array each valid cycle, buffers it, and fires MXFP8
 * requantization once blockSize elements per row have arrived.
 *
 * The shared-scale format is selected at elaboration time via
 * cfg.scaleType (UE8M0, UE7M1, UE6M2, …).
 *
 * After batchesPerBlock = blockSize / tileCols valid pulses, valid_out
 * fires for one cycle:
 *   shared_scale_out[i]  — 8-bit scale for row i (format per cfg.scaleType)
 *   fp8_out[i][k]        — k-th MXFP8 element of row i
 */
class RequantFP8(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"RequantFP8_${cfg.outputType.name}_${cfg.scaleType.name}_blk${cfg.blockSize}" +
    s"_${cfg.tileRows}x${cfg.tileCols}"

  private val B    = cfg.batchesPerBlock
  private val fp8W = cfg.outputType.totalWidth
  private val nIn  = cfg.tileRows * cfg.tileCols

  val io = IO(new Bundle {
    val fp32_in          = Input(UInt((nIn * 32).W))
    val valid_in         = Input(Bool())
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    val fp8_out          = Output(UInt((cfg.tileRows * cfg.blockSize * fp8W).W))
    val valid_out        = Output(Bool())
  })

  def extractFP32(row: Int, col: Int): UInt = {
    val k = row * cfg.tileCols + col
    io.fp32_in(nIn * 32 - k * 32 - 1, nIn * 32 - (k + 1) * 32)
  }

  val buffer   = Reg(Vec(cfg.tileRows, Vec(64, UInt(32.W))))
  val batchCnt = RegInit(0.U(6.W))
  val blockDone = io.valid_in && (batchCnt === (B - 1).U)

  when(io.valid_in) {
    for (batch <- 0 until B) {
      when(batchCnt === batch.U) {
        for (row <- 0 until cfg.tileRows; col <- 0 until cfg.tileCols)
          buffer(row)(batch * cfg.tileCols + col) := extractFP32(row, col)
      }
    }
    batchCnt := Mux(blockDone, 0.U, batchCnt + 1.U)
  }

  val sharedScaleWire = Wire(Vec(cfg.tileRows, UInt(8.W)))
  val fp8Wire         = Wire(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(fp8W.W))))

  for (row <- 0 until cfg.tileRows) {
    val rq = Module(new RequantBlock(cfg))
    for (col <- 0 until cfg.blockSize) {
      val batchIdx   = col / cfg.tileCols
      val colInBatch = col % cfg.tileCols
      rq.io.fp32_in(col) := (
        if (batchIdx == B - 1)
          Mux(blockDone, extractFP32(row, colInBatch), buffer(row)(col))
        else
          buffer(row)(col)
      )
    }
    sharedScaleWire(row) := rq.io.shared_scale
    fp8Wire(row)         := rq.io.fp8_out
  }

  val validOutReg    = RegNext(blockDone, init = false.B)
  val sharedScaleReg = Reg(Vec(cfg.tileRows, UInt(8.W)))
  val fp8OutReg      = Reg(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(fp8W.W))))

  when(blockDone) {
    sharedScaleReg := sharedScaleWire
    fp8OutReg      := fp8Wire
  }

  io.valid_out        := validOutReg
  io.shared_scale_out := Cat(sharedScaleReg.toSeq)
  io.fp8_out := Cat(
    for (row <- 0 until cfg.tileRows; col <- 0 until cfg.blockSize)
      yield fp8OutReg(row)(col)
  )
}

// ============================================================
// Emission helpers
// ============================================================

/** Emit the three baseline UE8M0 configs. */
object RequantFP8Main extends App {
  import DefaultRequantConfigs._

  Seq(e5m2_block32_4x4, e4m3_block16_8x8, e4m3_block64_4x4).foreach { cfg =>
    println(s"Generating RequantFP8: ${cfg.outputType.name} ${cfg.scaleType.name} " +
            s"blk${cfg.blockSize} ${cfg.tileRows}x${cfg.tileCols}")
    emitVerilog(
      new RequantFP8(cfg),
      Array("--target-dir",
            s"generated/requant/${cfg.outputType.name}_${cfg.scaleType.name}" +
            s"_blk${cfg.blockSize}_${cfg.tileRows}x${cfg.tileCols}")
    )
  }
}

/** Emit Verilog for a matrix of output-type × scale-type × geometry combinations. */
object AllRequantFP8Main extends App {
  import mx.mac.{MXFormats, ScaleFormats}

  // (elemType, scaleType, blockSize, tileRows, tileCols)
  val configs = Seq(
    (MXFormats.E5M2, ScaleFormats.UE8M0, 32, 4, 4),
    (MXFormats.E4M3, ScaleFormats.UE8M0, 16, 8, 8),
    (MXFormats.E4M3, ScaleFormats.UE8M0, 64, 4, 4),
    (MXFormats.E5M2, ScaleFormats.UE7M1, 32, 4, 4),
    (MXFormats.E4M3, ScaleFormats.UE7M1, 64, 4, 4),
    (MXFormats.E5M2, ScaleFormats.UE6M2, 32, 4, 4),
    (MXFormats.E4M3, ScaleFormats.UE6M2, 64, 4, 4),
    (MXFormats.E5M2, ScaleFormats.UE4M4, 32, 4, 4),
    (MXFormats.E4M3, ScaleFormats.UE4M4, 64, 4, 4),
  )

  for ((elemType, scaleType, blockSize, tileRows, tileCols) <- configs) {
    val cfg = RequantConfig(blockSize, tileRows, tileCols, elemType, scaleType)
    println(s"Generating: ${elemType.name} ${scaleType.name} blk${blockSize} ${tileRows}x${tileCols}")
    emitVerilog(
      new RequantFP8(cfg),
      Array("--target-dir",
            s"generated/requant/${elemType.name}_${scaleType.name}" +
            s"_blk${blockSize}_${tileRows}x${tileCols}")
    )
  }
}
