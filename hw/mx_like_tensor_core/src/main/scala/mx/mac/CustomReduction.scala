package mx.mac

import chisel3._
import chisel3.util._

/** Sign-magnitude floating-point bundle used in the custom reduction tree.
 *  Represents the value:  (-1)^sign * mant * 2^exp
 *  where mant is an unnormalized unsigned integer and exp is a biased SInt.
 *  This matches the output format of ScaleComposition exactly.
 */
class CustomFP(val expW: Int, val mantW: Int) extends Bundle {
  val sign = UInt(1.W)
  val exp  = SInt(expW.W)
  val mant = UInt(mantW.W)
}

/** Fixed-point reduction tree for CustomFP inputs.
 *
 *  Replaces a conventional per-node FP adder tree (align + LZC + round at every node)
 *  with a single-pass approach:
 *    1. Find maxExp across all N inputs (log-depth comparator tree).
 *    2. Align each mantissa once: right-shift by (maxExp − expᵢ), zero-extending with
 *       (productExpRange + G) fractional/guard bits below.
 *    3. Sum as 2's-complement signed integers (N−1 plain adders, no normalisation).
 *    4. One LZC + barrel-shift normalisation, then RNE round to outMantW bits.
 *
 *  This eliminates (N−1) × (alignment shift + LZC + round) blocks, replacing them with
 *  N bounded alignment shifts and a single normalisation path.
 *
 *  Class mapping (determined by productExpRange, see ScaleAddConfig):
 *    INT8×INT8  → productExpRange=0  → pure integer adder tree (alignment shifts are 0)
 *    INT8×FP8   → small range (~13–29) → narrow bounded shifts
 *    FP8×FP8    → moderate/wide range → wider integer accumulator, still one normalisation
 *
 *  @param expW           SInt exponent field width (bits); matches CustomFP.exp.
 *  @param inMantW        Input mantissa width (bits); matches CustomFP.mant from CustomOperator.
 *  @param outMantW       Output mantissa width (bits).  Historically ≤ inMantW
 *                        (the tree only narrowed down to product precision),
 *                        but may now exceed inMantW so the tree exposes
 *                        additional precision computed in its internal
 *                        alignment/sum.  The hard bound is
 *                        outMantW + G ≤ absMagW  (where G=3 is the RNE
 *                        guard); absMagW depends on arch / productExpRange
 *                        and is checked inside each builder.
 *  @param vectorSize     Number of parallel inputs N (≥ 1).
 *  @param productExpRange Maximum possible exponent spread across the N inputs
 *                        (= maxProductExp − minProductExp from ScaleAddConfig).
 */
class FixedFPReductionTree(
  val expW: Int,
  val inMantW: Int,
  val outMantW: Int,
  val vectorSize: Int,
  val productExpRange: Int,
  val arch: TreeArch = TreeArch.Generic,
  val skipFinalRound: Boolean = false
) extends Module {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(outMantW >= 1, "outMantW must be >= 1")
  // The legacy invariant outMantW ≤ inMantW (tree narrows to product precision)
  // has been relaxed to allow targeted widening: outMantW > inMantW exposes
  // additional bits computed in the tree's internal absMagW.  The bound
  // outMantW + G ≤ absMagW is checked per-builder where the arch-specific
  // absMagW is in scope.  The skipFinalRound path still requires outMantW
  // == absMagW exactly (no rounding).

  // Legacy desiredName kept byte-for-byte so existing emits and testbench
  // module-name references stay valid.  The wide-output (skipFinalRound)
  // variant gets a `_wide` suffix.
  override def desiredName = {
    val base = s"FixedFPTree_exp${expW}_mant${inMantW}_out${outMantW}_vec${vectorSize}_range${productExpRange}"
    val widetag = if (skipFinalRound) "_wide" else ""
    s"${base}${widetag}"
  }

  val io = IO(new Bundle {
    val inputs = Input(Vec(vectorSize, new CustomFP(expW, inMantW)))
    val out    = Output(new CustomFP(expW, outMantW))
  })

  // ── Build ────────────────────────────────────────────────────────────────
  // Only the Generic architecture is deployed (single full barrel align +
  // signed adder tree + one LZC normalise + RNE).  The specialised alignment
  // variants (IntOnly / SmallFixedShift / TwoStageBarrel / Kulisch) were pruned
  // — they were bit-equivalent area/delay explorations off the figure's path.
  buildGeneric()

  // ── Generic implementation (current baseline, unchanged) ─────────────────
  // Single full barrel align + balanced binary signed adder tree + single
  // LZC normalize + RNE round.  Always correct across all (productExpRange,
  // mantW) combinations; specialised archs trade generality for area/delay.
  private def buildGeneric(): Unit = {
    val G       = 3   // RNE guard bits (used by wideningExtra + require below)
    val log2N   = log2Ceil(vectorSize.max(2))
    // fracBits: bits below the mantissa MSB in the integer representation.
    // = (a) productExpRange alignment headroom + (b) widening headroom when
    //   outMantW > inMantW + log2N + productExpRange.
    //
    // Note: we deliberately do NOT add G to fracBitsBase.  The shiftAmt is
    // capped at fracBits, but in practice diffRaw = maxExp − exp_i never
    // exceeds productExpRange (which is the *definition* of productExpRange).
    // So shiftAmt ≤ productExpRange always, the lane mantissas land no lower
    // than bit `fracBits − productExpRange + inMantW − 1` ≥ 0, and the bottom
    // G bits of absMag would stay permanently zero — pure waste.  RNE sticky
    // already OR's everything below the round bit, so widening fracBits by G
    // adds zero-contribution bits to that OR.
    val fracBitsBase    = productExpRange
    // Widening logic for the rounding path: when outMantW > inMantW+log2N we
    // need extra fracBits so the RNE has G guard bits below the extraction
    // window.  In skipFinalRound mode we emit the entire absMagW unrounded,
    // so the widening (which assumes outMantW < absMagW) does not apply.
    val wideningExtra   = if (skipFinalRound) 0
                          else math.max(0, outMantW + G - (inMantW + log2N) - fracBitsBase)
    val fracBits        = fracBitsBase + wideningExtra
    // Width of the magnitude accumulator (sign bit separate).
    // = inMantW integer bits + fracBits fractional bits + log2N carry-overflow bits.
    val absMagW  = inMantW + fracBits + log2N
    // RNE needs G=3 bits below the outMantW window for guard/round/sticky.
    if (!skipFinalRound) require(outMantW + G <= absMagW,
      s"buildGeneric: outMantW($outMantW) + G($G) must be <= absMagW($absMagW)" +
      s" = inMantW($inMantW) + fracBits($fracBits) + log2N($log2N)")

    // ── 1. Maximum exponent across all inputs ───────────────────────────────
    val maxExp = io.inputs.map(_.exp).reduce { (a, b) => Mux(a > b, a, b) }

    // ── 2. Align each input as a signed 2's-complement integer ───────────────
    // Layout in the absMagW-bit integer (before sign conversion):
    //   [absMagW-1 : fracBits+log2N]  ← inMantW bits of mantissa (most-significant input)
    //   [fracBits+log2N-1 : fracBits] ← log2N carry-overflow bits (normally 0 per input)
    //   [fracBits-1 : 0]              ← productExpRange fractional bits + G guard bits
    // After right-shifting by shiftAmt = (maxExp − expᵢ), the mantissa slides down
    // into the fractional region; all inMantW bits are preserved since shiftAmt ≤ fracBits.
    val aligned = Wire(Vec(vectorSize, SInt((absMagW + 1).W)))
    for (i <- 0 until vectorSize) {
      val diffRaw  = (maxExp - io.inputs(i).exp).asUInt        // ≥ 0, SInt subtraction
      val overflowed = diffRaw > fracBits.U
      val shiftAmt = Mux(overflowed, fracBits.U(log2Ceil(fracBits + 1).W),
                                     diffRaw(log2Ceil(fracBits + 1) - 1, 0))
      val extended = Cat(0.U(log2N.W), io.inputs(i).mant, 0.U(fracBits.W))  // absMagW bits
      // HARD DROP: when diffRaw > fracBits, the lane's TRUE contribution is
      // mant·2^(−true_diff) — by definition negligible vs the dominant lane.
      // Soft-cap (= extended >> fracBits) would represent it as mant·2^(−cap),
      // an amplified-and-WRONG contribution.  Zeroing it matches the true
      // contribution to within 2^(cap−true_diff)× precision.  Cost: one extra
      // Mux2; the comparator is shared with shiftAmt's cap.  When
      // fracBits = productExpRange (no override), `overflowed` is always
      // false for valid inputs, the Mux folds out — baseline RTL unchanged.
      val shiftedRaw = (extended >> shiftAmt)(absMagW - 1, 0)
      val shifted    = Mux(overflowed, 0.U(absMagW.W), shiftedRaw)
      // Sign-magnitude → 2's complement
      val posVal = shifted.zext.asSInt  // absMagW+1 SInt (MSB=0)
      aligned(i) := Mux(io.inputs(i).sign.asBool, -posVal, posVal)
    }

    // ── 3. Integer adder tree (plain signed adds, width grows by 1 per level) ──
    def addTree(vals: Seq[SInt]): SInt =
      if (vals.length == 1) vals.head
      else addTree(vals.grouped(2).map(g => if (g.length == 2) g(0) + g(1) else g(0)).toSeq)

    val rawSum = addTree(aligned.toSeq)
    // Truncate to absMagW+1 signed bits; sum magnitude ≤ N×2^(inMantW+fracBits) ≤ 2^absMagW.
    val sum = rawSum(absMagW, 0).asSInt

    // ── 4. Normalise + RNE round → CustomFP(expW, outMantW) ─────────────────
    val isNeg  = sum < 0.S
    val sumU   = sum.asUInt
    // Magnitude in absMagW bits (2's complement negation for negative sum)
    val absMag = Mux(isNeg, (~sumU + 1.U)(absMagW - 1, 0), sumU(absMagW - 1, 0))
    val isZero = absMag === 0.U

    // LZC: PriorityEncoder(Reverse(x)) = number of leading zeros in x.
    val lzc        = PriorityEncoder(Reverse(absMag))          // up to log2Ceil(absMagW+1) bits
    val normalized = (absMag << lzc)(absMagW - 1, 0)           // MSB aligned to top

    val (finalMant, mCarry) =
      if (skipFinalRound) {
        // ── Wide-output path: no RNE, no narrowing.  Output the full
        // post-LZC-normalised mantissa at absMagW bits.  Caller must declare
        // outMantW == absMagW so the IO width matches; downstream consumers
        // (ScaleComposition / FusedScaleAccumulator / DirectToFPn) absorb the rounding.
        require(outMantW == absMagW,
          s"skipFinalRound requires outMantW($outMantW) == absMagW($absMagW)")
        (normalized(absMagW - 1, 0), false.B)
      } else {
        // ── Standard RNE round from absMagW bits → outMantW ─────────────────
        //   mantissa bits:  normalized[absMagW-1 : absMagW-outMantW]
        //   guard bit:      normalized[absMagW-outMantW-1]
        //   round bit:      normalized[absMagW-outMantW-2]
        //   sticky bits:    OR(normalized[absMagW-outMantW-3 : 0])
        // absMagW-outMantW = fracBits+log2N ≥ G=3, so guard/round/sticky always exist.
        val gPos = absMagW - outMantW - 1  // guard bit position
        val rPos = absMagW - outMantW - 2  // round bit position
        val sTop = absMagW - outMantW - 3  // top of sticky region

        val mantRaw  = normalized(absMagW - 1, absMagW - outMantW)
        val guardBit = normalized(gPos).asBool
        val roundBit = if (rPos >= 0) normalized(rPos).asBool else false.B
        val stkyBits = if (sTop >= 0) normalized(sTop, 0).orR  else false.B
        val roundUp  = guardBit && (mantRaw(0).asBool || roundBit || stkyBits)
        val roundedM = (mantRaw +& roundUp.asUInt)               // outMantW+1 bits
        val carry    = roundedM(outMantW).asBool
        val mant = Mux(carry,
          (1 << (outMantW - 1)).U(outMantW.W),
          roundedM(outMantW - 1, 0))
        (mant, carry)
      }

    // outExp = maxExp + (inMantW + log2N − outMantW) − lzc  [+ 1 if mCarry]
    // The (inMantW + log2N − outMantW) term accounts for the integer bit-position offset;
    // lzc corrects for leading zeros in the sum (normalization left-shift).
    val lzcS    = Cat(false.B, lzc).asSInt                      // non-negative SInt
    val expBase = maxExp + (inMantW + log2N - outMantW).S - lzcS
    val outExp  = Mux(mCarry, expBase + 1.S, expBase)

    io.out.sign := Mux(isZero, 0.U, isNeg.asUInt)
    io.out.mant := Mux(isZero, 0.U, finalMant)
    io.out.exp  := outExp(expW - 1, 0).asSInt
  }

}

/** Combinational adder for the CustomFP format.
 *
 *  Algorithm:
 *   1. Select far (larger |exp|) and near operands.
 *   2. Append 3 guard bits to each mantissa, right-shift near to align with far.
 *   3. Add or subtract mantissas based on signs.
 *   4. Absorb a possible addition carry by right-shifting and incrementing exp.
 *   5. Round to mantW bits using RNE (round-to-nearest-even).
 *   6. Handle rounding overflow: store as (1<<(mantW-1)) * 2^(exp+1).
 *
 *  Does NOT normalize: leading zeros in mant are preserved so the tree can
 *  defer normalization to the single ScaleToFP32 at the end.
 *
 *  @param expW  SInt exponent field width (bits)
 *  @param mantW UInt mantissa field width (bits, unnormalized)
 */
class CustomFPAdder(val expW: Int, val mantW: Int) extends Module {
  override def desiredName = s"CustomFPAdder_exp${expW}_mant${mantW}"

  val io = IO(new Bundle {
    val a   = Input(new CustomFP(expW, mantW))
    val b   = Input(new CustomFP(expW, mantW))
    val out = Output(new CustomFP(expW, mantW))
  })

  private val G   = 3           // guard bits
  private val EXT = mantW + G   // extended mantissa width

  // ── 1. Select far (larger |value|) and near operands ───────────────────
  // A zero mantissa means the value is 0 regardless of exp. A zero operand
  // must never be selected as "far" over a non-zero operand; doing so would
  // place the non-zero value in the near slot and compute (0 − near) as an
  // unsigned subtraction, which wraps and corrupts the result.
  val expDiff = io.a.exp - io.b.exp          // SInt(expW+1)
  val aIsZero = io.a.mant === 0.U
  val bIsZero = io.b.mant === 0.U
  // a has larger magnitude when:
  //   (a non-zero AND b zero), OR
  //   (both non-zero AND a has larger exp, or same exp with larger mant)
  val aLarger = (!aIsZero && bIsZero) ||
                (!aIsZero && !bIsZero &&
                  ((expDiff > 0.S) || (expDiff === 0.S && io.a.mant >= io.b.mant)))

  val farSign  = Mux(aLarger, io.a.sign, io.b.sign)
  val farExp   = Mux(aLarger, io.a.exp,  io.b.exp)   // SInt(expW)
  val farMant  = Mux(aLarger, io.a.mant, io.b.mant)
  val nearSign = Mux(aLarger, io.b.sign, io.a.sign)
  val nearMant = Mux(aLarger, io.b.mant, io.a.mant)

  // ── 2. Align near mantissa ──────────────────────────────────────────────
  val nearExt = Cat(nearMant, 0.U(G.W))   // EXT bits: mantissa ++ 3 guard zeros
  val farExt  = Cat(farMant,  0.U(G.W))   // EXT bits

  // |expDiff|, clamped to EXT so the shift can't exceed the extended width
  val absShift   = Mux(expDiff > 0.S, expDiff.asUInt, (-expDiff).asUInt)
  val shiftCap   = EXT.U
  val clampedSh  = Mux(absShift > shiftCap, shiftCap, absShift)
  // Truncate to log2Ceil(EXT+1) bits to bound the width of downstream expressions
  private val LOG_EXT = log2Ceil(EXT + 1)
  val shiftBits  = clampedSh(LOG_EXT - 1, 0)

  val aligned    = nearExt >> shiftBits   // EXT bits

  // Sticky: OR of every bit shifted off the right edge of nearExt
  val stickyMask = ((1.U((EXT + 1).W) << shiftBits) - 1.U)(EXT - 1, 0)
  val stickyRaw  = (nearExt & stickyMask).orR
  val stickyOver = absShift > shiftCap    // entire value shifted out
  val sticky0    = stickyRaw || stickyOver

  // ── 3. Add / subtract in sign-magnitude ────────────────────────────────
  val isSub     = (farSign ^ nearSign).asBool
  // Addition: keep carry bit → EXT+1 result
  val addResult = farExt +& aligned
  // Subtraction: mantissas are UNNORMALIZED, so farExt may be < aligned even
  // when farExp >= nearExp (e.g. mant_far=1,exp=5 vs mant_near=3,exp=4).
  // Detect borrow via the MSB of the EXT+1-bit result; when it is set,
  // negate via 2's-complement (~x + 1) so resMag is always non-negative.
  val subFwd    = Cat(0.U(1.W), farExt) - Cat(0.U(1.W), aligned)  // EXT+1 bits
  val subBorrow = subFwd(EXT).asBool
  val subResult = Mux(subBorrow, (~subFwd + 1.U)(EXT, 0), subFwd) // EXT+1 bits, always >= 0

  val resMag    = Mux(isSub, subResult, addResult)   // EXT+1 bits

  // ── 4. Absorb addition carry ────────────────────────────────────────────
  val carry       = resMag(EXT).asBool
  val shifted     = Mux(carry, (resMag >> 1)(EXT - 1, 0), resMag(EXT - 1, 0))  // EXT bits
  // When carry=1, resMag[0] is shifted out — fold it into sticky for correct RNE.
  val stickyCarry = carry && resMag(0).asBool
  val expAddCarry = Mux(carry, farExp + 1.S, farExp)                            // SInt(expW+1)

  // ── 4b. Post-subtraction normalization ──────────────────────────────────
  // Subtracting near-equal operands leaves leading zeros at farExp, wasting
  // mantissa bits and causing large relative errors (catastrophic cancellation).
  // Fix: count the leading zeros (LZC), left-shift the result to move the MSB
  // to the top, and decrement the exponent by the same amount.
  // This step is skipped for the addition path (carry absorption already
  // normalized it) and for the zero result (handled by isZero below).
  val isNonzero   = shifted.orR
  // PriorityEncoder(Reverse(x)) gives the number of leading zeros in x.
  // The output is log2Ceil(EXT) bits, sufficient to represent 0..EXT-1.
  // We do NOT truncate further here because (LOG_EXT-1) can exceed that width
  // when EXT is a power-of-two (log2Ceil(EXT+1) = log2Ceil(EXT) + 1).
  val lzc         = PriorityEncoder(Reverse(shifted))  // # leading zeros from MSB
  val normShift   = Mux(isSub && !carry && isNonzero, lzc, 0.U)
  val shiftedNorm = (shifted << normShift)(EXT - 1, 0)   // EXT bits, MSB-aligned
  val expNorm     = expAddCarry - normShift.zext           // SInt(expW+2)

  // ── 5. RNE rounding from EXT bits down to mantW bits ───────────────────
  // Layout of 'shiftedNorm': [EXT-1 : G] = mantW-bit mantissa, [2] = G, [1] = R, [0] = S-low
  val mantRaw     = shiftedNorm(EXT - 1, G)   // mantW bits
  val guardBit    = shiftedNorm(2).asBool
  val roundBit    = shiftedNorm(1).asBool
  val stickyFinal = shiftedNorm(0).asBool || sticky0 || stickyCarry

  val roundUp    = guardBit && (mantRaw(0).asBool || roundBit || stickyFinal)
  val roundedM   = mantRaw +& roundUp.asUInt   // mantW+1 bits (keeps carry)
  val mantCarry  = roundedM(mantW).asBool

  // Rounding overflow: 2^mantW * 2^exp → represent as (1<<(mantW-1)) * 2^(exp+1)
  val finalMant  = Mux(mantCarry, (1 << (mantW - 1)).U(mantW.W), roundedM(mantW - 1, 0))
  val finalExpW  = Mux(mantCarry, expNorm + 1.S, expNorm)  // SInt(expW+3)

  // ── 6. Output (truncate exp back to expW; extreme over/underflow saturated by ScaleToFP32) ──
  val isZero  = resMag === 0.U
  // When subtraction borrows, the near operand was actually larger, so the
  // result carries near's sign rather than far's.
  val outSign = Mux(isSub && subBorrow, nearSign, farSign)

  io.out.sign := Mux(isZero, 0.U, outSign)
  io.out.exp  := finalExpW(expW - 1, 0).asSInt
  io.out.mant := Mux(isZero, 0.U, finalMant)
}
