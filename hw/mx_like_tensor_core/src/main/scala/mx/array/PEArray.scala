package mx.array

import chisel3._
import chisel3.util._
import mx.mac.{FDPU, TreeArch}
import mx.requant.{RequantFP8, RequantINT8, RequantBF16}

// ============================================================
// FP8 / FP6 output wrapper
// ============================================================
/**
 * Top-level PE array wrapper using FDPUWithCustomReductionTree PEs and
 * FP32→MXFP8/FP6 (RequantFP8) requantization.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → RequantFP8
 *       → result / shared_scale_out / valid_out
 */
class PEArrayWrapper(cfg: PEArrayConfig) extends Module {
  override def desiredName = "PE_Array"

  val io = IO(new Bundle {
    // ── CSR & Control ────────────────────────────────────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))

    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())
    // Runtime threshold: number of PE-cycles per dot product (= K / vectorSize).
    // Driven by snax_mx_alu_shell_wrapper's csr_reg_set_buffer[1].  When
    // peValidOut has fired this many times since acc_reset_i, the wrapper
    // pulses RequantFP8.valid_in (resultDone) so it samples a complete dot
    // product instead of a partial sum.  cfg.K is no longer used at runtime —
    // the PE accumulator M_acc now comes from macc_final_selection.csv (CSV).
    val accumulation_count_i = Input(UInt(32.W))

    // ── Handshakes ────────────────────────────────────────────────────────
    val A_valid_i        = Input(Bool())
    val B_valid_i        = Input(Bool())
    val A_ready_o        = Output(Bool())
    val B_ready_o        = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    // [0:TileRows-1][srcWidthA-1:0] — matches SV op_a_i
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    // [0:TileCols-1][srcWidthB-1:0] — matches SV op_b_i
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    // [0:TileRows-1][SCALE_WIDTH-1:0] — matches SV shared_exp_A_i
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    // [0:TileCols-1][SCALE_WIDTH-1:0] — matches SV shared_exp_B_i
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── Debug FP32 Accumulator Output ────────────────────────────────────
    // Only present when cfg.exposeResults — the production module emitted by
    // mx.GeneratePEArray drops this so PE_Array_wrapper has no FP32 ports to
    // tie off and snax_mx_alu_shell_wrapper sees a clean interface.
    val results_o        = if (cfg.exposeResults)
                             Some(Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W)))))
                           else None

    // ── Requantized FP8/FP6 Output ────────────────────────────────────────
    // One shared scale per tile row (width = scaleType.totalScaleWidth bits,
    // 8 for OCP MX UE7M1..UE4M4, 7 for NVFP4-style UE4M3).
    val shared_scale_out = Output(UInt((cfg.tileRows * cfg.macCfg.stype.totalScaleWidth).W))
    // Flat packed output: tileRows × blockSize elements
    val result           = Output(UInt((cfg.tileRows * cfg.requantCfg.blockSize * cfg.fp8Width).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array: tileRows × tileCols FDPU units ───────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())
  // Internal FP32 accumulator results — fed to RequantFP8 and optionally
  // exposed via io.results_o for testbench peeks.
  val results = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

  // archOverride lets the DSE sweep force the tree arch (baseline path only;
  // cyclesPerBlock field is ignored — production PEArray always uses cycleFP).
  val peTreeArch = cfg.archOverride.map(_.treeArch)
                      .getOrElse(TreeArch.Generic)

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPU(
        cfg.macCfg, cfg.vectorSize, K = cfg.K,
        treeArch = peTreeArch,
        accMantBits = cfg.effAccMantBits,
        istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      results(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  io.results_o.foreach { ro =>
    ro := results
    dontTouch(ro)
  }

  // ── Accumulator-aware "result valid" gate ───────────────────────────────
  // The PE accumulator runs accReg += MAC every PE-cycle until acc_reset_i
  // clears it; one full dot product takes K/vectorSize PE-cycles.  The
  // threshold is supplied at RUNTIME via io.accumulation_count_i (= K/vec,
  // driven by snax shell's csr_reg_set_buffer[1]) — NOT derived from cfg.K
  // at elaboration time.  cfg.K no longer sizes M_acc (that comes from the CSV);
  // the FP32 accumulator mantissa for the worst-case K.
  //
  // Reset convention matches FDPU / RequantFP8: the
  // implicit Chisel reset is treated as ACTIVE-LOW (dut.reset=true means
  // "not in reset"), so we lift it through (!reset).asAsyncReset to get
  // accCnt held at 0 only when reset is deasserted at the IO.
  val rqAsyncRstN  = (!reset.asBool).asAsyncReset
  val accCnt       = withReset(rqAsyncRstN)(RegInit(0.U(32.W)))
  val resultDone   = WireDefault(false.B)
  when (peValidOut) {
    when (accCnt === io.accumulation_count_i - 1.U) {
      accCnt     := 0.U
      resultDone := true.B
    } .otherwise {
      accCnt := accCnt + 1.U
    }
  }
  when (io.acc_reset_i) {
    accCnt := 0.U
  }

  // ── RequantFP8: PE narrow-FP → MXFP8/FP6 ───────────────────────────────
  val rq = Module(new RequantFP8(cfg.requantCfg))

  // PE.accOut is narrow ({sign[1], exp[8], mant[accMantBits]}).  When
  // requantCfg.inputMantWidth matches the PE's accMantBits, fp32Pad = 0
  // and the wires connect pad-free.  Otherwise zero-extend each element to
  // requantCfg.inputWidth before packing.
  private val fp32Pad = cfg.requantCfg.inputWidth - cfg.dstWidth
  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield (if (fp32Pad > 0) Cat(results(r)(c), 0.U(fp32Pad.W)) else results(r)(c))
  )
  rq.io.valid_in := resultDone

  io.shared_scale_out := rq.io.shared_scale_out
  io.result           := rq.io.elem_out
  io.valid_out        := rq.io.valid_out
}

// ============================================================
// INT8 output wrapper
// ============================================================
/**
 * PE array with FDPUWithCustomReductionTree PEs and FP32→INT8 requantization.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → RequantINT8
 *       → result / shared_scale_out / valid_out
 */
class PEArrayWrapperINT8(cfg: PEArrayINT8Config) extends Module {
  override def desiredName = "PE_Array"

  val io = IO(new Bundle {
    // ── CSR & Control ────────────────────────────────────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))

    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())
    // Runtime threshold = K/vectorSize.  See PEArrayWrapper for full rationale.
    val accumulation_count_i = Input(UInt(32.W))

    // ── Handshakes ────────────────────────────────────────────────────────
    val A_valid_i        = Input(Bool())
    val B_valid_i        = Input(Bool())
    val A_ready_o        = Output(Bool())
    val B_ready_o        = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── Debug FP32 Accumulator Output (only when cfg.exposeResults) ──────
    val results_o        = if (cfg.exposeResults)
                             Some(Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W)))))
                           else None

    // ── Requantized INT8 Output ───────────────────────────────────────────
    // One shared scale per tile row (width = scaleType.totalScaleWidth bits).
    val shared_scale_out = Output(UInt((cfg.tileRows * cfg.macCfg.stype.totalScaleWidth).W))
    // Flat packed INT8: tileRows × blockSize elements (two's complement)
    val result           = Output(UInt((cfg.tileRows * cfg.requantCfg.blockSize * 8).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array ──────────────────────────────────────────────────────────────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())
  // Internal FP32 accumulator results — fed to RequantINT8 and optionally
  // exposed via io.results_o for testbench peeks.
  val results = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

  // archOverride lets the DSE sweep force the tree arch (baseline path only).
  val peTreeArch = cfg.archOverride.map(_.treeArch)
                      .getOrElse(TreeArch.Generic)

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPU(
        cfg.macCfg, cfg.vectorSize, K = cfg.K,
        treeArch = peTreeArch,
        accMantBits = cfg.effAccMantBits,
        istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      results(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  io.results_o.foreach { ro =>
    ro := results
    dontTouch(ro)
  }

  // ── Accumulator-aware "result valid" gate ───────────────────────────────
  // See PEArrayWrapper for the rationale + reset convention.  Runtime
  // threshold via io.accumulation_count_i (no compile-time K/vec).
  val rqAsyncRstN  = (!reset.asBool).asAsyncReset
  val accCnt       = withReset(rqAsyncRstN)(RegInit(0.U(32.W)))
  val resultDone   = WireDefault(false.B)
  when (peValidOut) {
    when (accCnt === io.accumulation_count_i - 1.U) {
      accCnt     := 0.U
      resultDone := true.B
    } .otherwise {
      accCnt := accCnt + 1.U
    }
  }
  when (io.acc_reset_i) {
    accCnt := 0.U
  }

  // ── RequantINT8: PE narrow-FP → INT8 ──────────────────────────────────────
  val rq = Module(new RequantINT8(cfg.requantCfg))

  // Narrow→requant zero-extend.  See PEArrayWrapper above for rationale.
  private val fp32Pad = cfg.requantCfg.inputWidth - cfg.dstWidth
  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield (if (fp32Pad > 0) Cat(results(r)(c), 0.U(fp32Pad.W)) else results(r)(c))
  )
  rq.io.valid_in := resultDone

  io.shared_scale_out := rq.io.shared_scale_out
  io.result           := rq.io.int8_out
  io.valid_out        := rq.io.valid_out
}

// ============================================================
// BF16 output wrapper
// ============================================================

/**
 * PE array with FDPUWithCustomReductionTree PEs and FP32→BF16 requantization.
 *
 * BF16 is a purely combinational per-element pass-through (top 16 bits of FP32
 * with RNE rounding). No block buffering, no shared scale.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → RequantBF16
 *       → result / valid_out
 */
class PEArrayWrapperBF16(cfg: PEArrayBF16Config) extends Module {
  override def desiredName = "PE_Array"

  val io = IO(new Bundle {
    // ── CSR & Control ────────────────────────────────────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))

    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())
    // Runtime threshold = K/vectorSize.  See PEArrayWrapper for full rationale.
    val accumulation_count_i = Input(UInt(32.W))

    // ── Handshakes ────────────────────────────────────────────────────────
    val A_valid_i        = Input(Bool())
    val B_valid_i        = Input(Bool())
    val A_ready_o        = Output(Bool())
    val B_ready_o        = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── Debug FP32 Accumulator Output (only when cfg.exposeResults) ──────
    val results_o        = if (cfg.exposeResults)
                             Some(Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W)))))
                           else None

    // ── Requantized BF16 Output ───────────────────────────────────────────
    // Flat packed BF16: tileRows × tileCols elements, big-endian
    val result           = Output(UInt((cfg.tileRows * cfg.tileCols * 16).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array ──────────────────────────────────────────────────────────────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())
  // Internal FP32 accumulator results — fed to RequantBF16 and optionally
  // exposed via io.results_o for testbench peeks.
  val results = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPU(
        cfg.macCfg, cfg.vectorSize, K = cfg.K,
        treeArch = TreeArch.Generic,
        accMantBits = cfg.effAccMantBits,
        // baseline cycleFP (cpb=1 implicit)
        istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      results(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  io.results_o.foreach { ro =>
    ro := results
    dontTouch(ro)
  }

  // ── Accumulator-aware "result valid" gate ───────────────────────────────
  // BF16 mode is per-element pass-through (no block buffering), but valid_out
  // must still fire only when accReg holds a complete dot product, not on
  // every PE-cycle.  Runtime threshold via io.accumulation_count_i; see
  // PEArrayWrapper for full rationale + reset convention.
  val rqAsyncRstN  = (!reset.asBool).asAsyncReset
  val accCnt       = withReset(rqAsyncRstN)(RegInit(0.U(32.W)))
  val resultDone   = WireDefault(false.B)
  when (peValidOut) {
    when (accCnt === io.accumulation_count_i - 1.U) {
      accCnt     := 0.U
      resultDone := true.B
    } .otherwise {
      accCnt := accCnt + 1.U
    }
  }
  when (io.acc_reset_i) {
    accCnt := 0.U
  }

  // ── RequantBF16: FP32 → BF16 ─────────────────────────────────────────────
  val rq = Module(new RequantBF16(cfg.tileRows, cfg.tileCols))

  // Narrow→FP32 zero-extend; see PEArrayWrapper for rationale. Note BF16
  // reads only the top 16 bits so this pad is doubly inert.
  private val fp32Pad = 32 - cfg.dstWidth
  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield (if (fp32Pad > 0) Cat(results(r)(c), 0.U(fp32Pad.W)) else results(r)(c))
  )
  rq.io.valid_in := resultDone

  io.result    := rq.io.bf16_out
  io.valid_out := rq.io.valid_out
}

// ============================================================
// FP32 pass-through wrapper (quantize_mode = 0, no requant)
// ============================================================

/**
 * PE array with FDPU PEs and NO requantization — the
 * narrow accumulator outputs are packed straight into `result`.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPU (tileRows × tileCols)
 *       → result (tileRows × tileCols × dstWidth, big-endian flat UInt)
 *         where dstWidth = 1 + 8 + accMantBits (see PEArrayFP32Config.dstWidth).
 *         Consumers must zero-extend each element's mantissa to 23 bits if
 *         IEEE-754 FP32 is required downstream.
 *       → valid_out
 *
 * No `shared_scale_out` — the narrow FP word carries its own per-element exponent.
 * No `results_o` debug port — `result` already exposes the accumulator path.
 */
class PEArrayWrapperFP32(cfg: PEArrayFP32Config) extends Module {
  override def desiredName = "PE_Array"

  val io = IO(new Bundle {
    // ── CSR & Control ────────────────────────────────────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))

    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())
    // Runtime threshold = K/vectorSize.  See PEArrayWrapper for full rationale.
    val accumulation_count_i = Input(UInt(32.W))

    // ── Handshakes ────────────────────────────────────────────────────────
    val A_valid_i        = Input(Bool())
    val B_valid_i        = Input(Bool())
    val A_ready_o        = Output(Bool())
    val B_ready_o        = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── Narrow FP Output ──────────────────────────────────────────────────
    // Flat packed: tileRows × tileCols elements of dstWidth bits, big-endian.
    // dstWidth = 1 + 8 + accMantBits (NOT 32). Consumers must zero-extend the
    // mantissa to 23 bits to obtain IEEE-754 FP32.
    val result           = Output(UInt((cfg.tileRows * cfg.tileCols * cfg.dstWidth).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array ──────────────────────────────────────────────────────────────
  val peValidOut = Wire(Bool())
  val results    = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPU(
        cfg.macCfg, cfg.vectorSize, K = cfg.K,
        treeArch = TreeArch.Generic,
        accMantBits = cfg.effAccMantBits,
        // baseline cycleFP (cpb=1 implicit)
        istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      results(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  // Pack narrow outputs row-major, big-endian: (row=0, col=0) at the MSB.
  io.result := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield results(r)(c)
  )

  // Accumulator-aware valid_out: fire only on the K/vec-th peValidOut so the
  // downstream consumer sees a complete dot product, not a partial sum.
  // Runtime threshold via io.accumulation_count_i; reset convention: see
  // PEArrayWrapper.
  val rqAsyncRstN = (!reset.asBool).asAsyncReset                  // ① 反向 reset
  val accCnt      = withReset(rqAsyncRstN)(RegInit(0.U(32.W)))    // ② 32-bit 计数器
  val resultDone  = WireDefault(false.B)                           // ③ 默认 false 的 wire

  when (peValidOut) {                                              // ④ 只在 PE 输出有效时计数
    when (accCnt === io.accumulation_count_i - 1.U) {              //   ⑤ 数到 N-1 ⇒ 完整 dot product
      accCnt     := 0.U                                            //   ⑥ 清零计数器
      resultDone := true.B                                         //   ⑦ 单周期脉冲,通知 rq 采样
    } .otherwise {
      accCnt := accCnt + 1.U                                       //   ⑧ 否则继续累加
    }
  }
  when (io.acc_reset_i) {                                          // ⑨ 外部 acc_reset 来 → 同步重置
    accCnt := 0.U
  }
  io.valid_out := resultDone    
}
// ============================================================
// Emission helpers — shared directory-name utility
// ============================================================

private object EmitDir {
  /** Common target-dir pattern: <root>/<rows>x<cols>_<typeA>_<typeB>_<scale>_vec<v>_<outTag>.
   *  `root` defaults to "generated/pe_array" so existing emitters are unaffected;
   *  pass `root = "generated/baseline"` (or another tag) to snapshot a sweep alongside.
   */
  def fp8(cfg: PEArrayConfig, root: String = "generated/pe_array"): String =
    s"$root/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}" +
    s"_${cfg.requantCfg.outputType.name}_blk${cfg.requantCfg.blockSize}"

  def int8(cfg: PEArrayINT8Config, root: String = "generated/pe_array"): String =
    s"$root/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}" +
    s"_INT8_blk${cfg.requantCfg.blockSize}"

  def bf16(cfg: PEArrayBF16Config, root: String = "generated/pe_array"): String =
    s"$root/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}_BF16"

  def fp32(cfg: PEArrayFP32Config, root: String = "generated/pe_array"): String =
    s"$root/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}_FP32"
}

// ============================================================
// Emission helpers — generation App objects
// ============================================================
