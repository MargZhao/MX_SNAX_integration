package mx.requant

import chisel3._
import chisel3.util._

// ============================================================
// FP32 → MXFP8 single-element converter
// ============================================================
/**
 * Converts one IEEE-754 FP32 value to MXFP8 (E5M2 or E4M3) given
 * the block's shared UE8M0 scale (the max biased FP32 exponent in
 * the block).
 *
 * Rounding: round-to-nearest-even (RNE) on the mantissa.
 * Saturation: values whose re-biased exponent exceeds the FP8
 *   maximum normal exponent are clamped to the max normal value.
 * Underflow: values whose re-biased exponent <= 0 (or whose FP32
 *   value is zero / subnormal) map to +-0.
 */
class FP32ToMXFP8(val cfg: RequantConfig) extends Module {
  private val fp8ExpBits      = cfg.outputType.elementWidthExp   // 5 (E5M2) or 4 (E4M3)
  private val fp8MantBits     = cfg.outputType.elementWidthMant  // 2 (E5M2) or 3 (E4M3)
  private val fp8Bias         = cfg.outputType.bias              // 15 (E5M2) or 7 (E4M3)
  // All-ones exponent is reserved for NaN/Inf; max normal exp is one below.
  private val fp8MaxNormalExp = (1 << fp8ExpBits) - 2           // 30 (E5M2) or 14 (E4M3)

  val io = IO(new Bundle {
    val fp32_in    = Input(UInt(32.W))
    val shared_exp = Input(UInt(8.W))   // UE8M0: max biased FP32 exp in block
    val fp8_out    = Output(UInt(cfg.outputType.totalWidth.W))  // 8 bits
  })

  // Unpack FP32
  val sign     = io.fp32_in(31)
  val fp32_exp = io.fp32_in(30, 23)   // biased exponent, range 0-255
  val fp32_man = io.fp32_in(22, 0)    // 23-bit fractional mantissa

  // Zero or FP32 subnormal -> underflow to 0
  val isZeroOrSubnormal = fp32_exp === 0.U

  // Re-bias exponent to FP8 space.
  // Zero-extend to 9-bit SInt to avoid sign-bit confusion on raw asSInt.
  val fp32_exp_s   = fp32_exp.zext             // SInt(9), range 0..255
  val shared_exp_s = io.shared_exp.zext        // SInt(9), range 0..255
  val fp8_exp_full = fp32_exp_s - shared_exp_s + fp8Bias.S  // SInt(~11)

  val underflow = isZeroOrSubnormal || fp8_exp_full <= 0.S
  val overflow  = fp8_exp_full > fp8MaxNormalExp.S

  // Truncate mantissa with RNE rounding.
  // fp32_man[22 : 23-fp8MantBits]  -- fp8 mantissa bits
  // fp32_man[22-fp8MantBits]        -- guard bit
  // fp32_man[21-fp8MantBits : 0]    -- sticky bits
  val fp8_mant_raw = fp32_man(22, 23 - fp8MantBits)
  val guardBit     = fp32_man(22 - fp8MantBits)
  val stickyBits   =
    if (22 - fp8MantBits > 0) fp32_man(21 - fp8MantBits, 0).orR
    else false.B
  val roundUp  = guardBit && (fp8_mant_raw(0) || stickyBits)
  val fp8_mant = Mux(roundUp, fp8_mant_raw + 1.U, fp8_mant_raw)

  val fp8_exp_clamped = fp8_exp_full.asUInt(fp8ExpBits - 1, 0)

  val maxNormalMant = ((1 << fp8MantBits) - 1).U(fp8MantBits.W)
  val maxNormalExp  = fp8MaxNormalExp.U(fp8ExpBits.W)

  val normalResult = Cat(sign, fp8_exp_clamped, fp8_mant)
  val maxResult    = Cat(sign, maxNormalExp, maxNormalMant)

  io.fp8_out := Mux(underflow, 0.U, Mux(overflow, maxResult, normalResult))
}

// ============================================================
// Max absolute-exponent finder (combinational reduction tree)
// ============================================================
/**
 * Finds the maximum biased FP32 exponent (bits [30:23]) across
 * blockSize values.  Sign bit is ignored (magnitude ordering).
 * Result is the UE8M0 shared scale for the MX block.
 */
class MaxExpFinder(val blockSize: Int) extends Module {
  val io = IO(new Bundle {
    val fp32_in = Input(Vec(blockSize, UInt(32.W)))
    val max_exp = Output(UInt(8.W))
  })

  def maxTree(vals: Seq[UInt]): UInt =
    if (vals.length == 1) vals.head
    else {
      val next = vals.grouped(2).map { grp =>
        if (grp.length == 2) Mux(grp(0) >= grp(1), grp(0), grp(1))
        else grp(0)
      }.toSeq
      maxTree(next)
    }

  io.max_exp := maxTree(io.fp32_in.map(v => v(30, 23)))
}

// ============================================================
// One-row block requantizer (combinational)
// ============================================================
/**
 * Requantizes blockSize FP32 values for one tile row.
 * Purely combinational: MaxExpFinder -> blockSize x FP32ToMXFP8.
 */
class RequantBlock(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"RequantBlock_${cfg.outputType.name}_blk${cfg.blockSize}"

  val io = IO(new Bundle {
    val fp32_in      = Input(Vec(cfg.blockSize, UInt(32.W)))
    val shared_scale = Output(UInt(8.W))
    val fp8_out      = Output(Vec(cfg.blockSize, UInt(cfg.outputType.totalWidth.W)))
  })

  val maxFinder = Module(new MaxExpFinder(cfg.blockSize))
  maxFinder.io.fp32_in := io.fp32_in
  io.shared_scale := maxFinder.io.max_exp

  for (i <- 0 until cfg.blockSize) {
    val conv = Module(new FP32ToMXFP8(cfg))
    conv.io.fp32_in    := io.fp32_in(i)
    conv.io.shared_exp := maxFinder.io.max_exp
    io.fp8_out(i)      := conv.io.fp8_out
  }
}

// ============================================================
// Top-level: buffer + controller + tileRows parallel blocks
// ============================================================
/**
 * RequantFP8: receives the full TileRows x TileCols FP32 output of
 * a PE array each valid cycle, buffers it, and fires MXFP8
 * requantization once blockSize elements per row have arrived.
 *
 * Connection to PE_Array_wrapper:
 *   fp32_in[i][j]  <->  results_o[i][j]   (TileRows x TileCols FP32)
 *   valid_in       <->  send_output_i      (one pulse per tile output)
 *
 * After batchesPerBlock = blockSize / tileCols valid pulses, one MX
 * block is complete for every row.  valid_out fires for one cycle:
 *   shared_scale_out[i]  - UE8M0 shared exponent for row i
 *   fp8_out[i][k]        - k-th MXFP8 element of row i
 *
 * Buffer layout (tileRows x 64 x 32-bit):
 *
 *   batch 0        batch 1       ...  batch B-1
 *   col 0..C-1  |  col C..2C-1  | .. | col (B-1)C..BC-1
 *   ────────────┼───────────────┼────┼──────────────────
 *   row 0       |               |    |
 *   ...         |               |    |
 *   row R-1     |               |    |
 *
 *   B = batchesPerBlock = blockSize / tileCols
 *   C = tileCols
 *
 * @param cfg  RequantConfig (blockSize must be divisible by tileCols).
 */
class RequantFP8(val cfg: RequantConfig) extends Module {
  override def desiredName =
    s"RequantFP8_${cfg.outputType.name}_blk${cfg.blockSize}" +
    s"_${cfg.tileRows}x${cfg.tileCols}"

  private val B       = cfg.batchesPerBlock
  private val fp8W    = cfg.outputType.totalWidth   // 8 bits
  private val nIn     = cfg.tileRows * cfg.tileCols // total input elements

  val io = IO(new Bundle {
    // Packed flat bus matching SV: [0:tileRows-1][0:tileCols-1][31:0]
    // Connect directly to PE_Array_wrapper results_o.
    val fp32_in  = Input(UInt((nIn * 32).W))
    val valid_in = Input(Bool())

    // Packed flat bus matching SV: [0:tileRows-1][7:0]
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    // Packed flat bus matching SV: [0:tileRows-1][0:blockSize-1][7:0]
    val fp8_out          = Output(UInt((cfg.tileRows * cfg.blockSize * fp8W).W))
    val valid_out        = Output(Bool())
  })

  // ── Unpack helper ─────────────────────────────────────────
  // SV [0:R-1][0:C-1][31:0]: element [i][j] is at the k-th slot
  // from the MSB, where k = i*C + j.
  // Chisel bit indices: hi = nIn*32 - k*32 - 1, lo = nIn*32 - (k+1)*32
  def extractFP32(row: Int, col: Int): UInt = {
    val k = row * cfg.tileCols + col
    io.fp32_in(nIn * 32 - k * 32 - 1, nIn * 32 - (k + 1) * 32)
  }

  // ── Buffer: tileRows x 64 FP32 registers ──────────────────
  val buffer = Reg(Vec(cfg.tileRows, Vec(64, UInt(32.W))))

  // ── Batch counter: 0 .. B-1 ──────────────────────────────
  val batchCnt = RegInit(0.U(6.W))

  val blockDone = io.valid_in && (batchCnt === (B - 1).U)

  // Buffer write: tileCols values per row per pulse.
  // Explicit when-per-batch keeps all buffer indices static.
  when(io.valid_in) {
    for (batch <- 0 until B) {
      when(batchCnt === batch.U) {
        for (row <- 0 until cfg.tileRows) {
          for (col <- 0 until cfg.tileCols) {
            buffer(row)(batch * cfg.tileCols + col) := extractFP32(row, col)
          }
        }
      }
    }
    batchCnt := Mux(blockDone, 0.U, batchCnt + 1.U)
  }

  // ── One RequantBlock per tile row (combinational) ─────────
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

  // ── Registered output ─────────────────────────────────────
  val validOutReg    = RegNext(blockDone, init = false.B)
  val sharedScaleReg = Reg(Vec(cfg.tileRows, UInt(8.W)))
  val fp8OutReg      = Reg(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(fp8W.W))))

  when(blockDone) {
    sharedScaleReg := sharedScaleWire
    fp8OutReg      := fp8Wire
  }

  io.valid_out := validOutReg

  // Pack outputs: row 0 at MSB, matching [0:tileRows-1][...] SV convention.
  io.shared_scale_out := Cat(sharedScaleReg.toSeq)
  io.fp8_out := Cat(
    for (row <- 0 until cfg.tileRows; col <- 0 until cfg.blockSize)
      yield fp8OutReg(row)(col)
  )
}

// ============================================================
// Emission helpers
// ============================================================

object RequantFP8Main extends App {
  import DefaultRequantConfigs._

  Seq(e5m2_block32_4x4, e4m3_block16_8x8, e4m3_block64_4x4).foreach { cfg =>
    println(s"Generating RequantFP8: ${cfg.outputType.name} blk${cfg.blockSize} " +
            s"${cfg.tileRows}x${cfg.tileCols}")
    emitVerilog(
      new RequantFP8(cfg),
      Array("--target-dir",
            s"generated/requant/${cfg.outputType.name}_blk${cfg.blockSize}" +
            s"_${cfg.tileRows}x${cfg.tileCols}")
    )
  }
}
