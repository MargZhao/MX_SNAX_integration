// mx_like_simple_core — format definitions and per-config width derivation.
//
// Widths are derived from Lutz Eq (5) and Cuyckens SoP_FIXED_WIDTH pattern.
// Python reference: `scripts/datapath_widths.py`. Sanity check reproduces
// Lutz FP8 E5M3 numbers (SoP=69, final_adder=94 with FP32 accreg).
//
// Design principle: every width MUST be a monotone function of format
// parameters. No caps, no shared-worst-case, no per-config branches.
//
// Element formats supported: FP (E5M2, E4M3, E3M2, E2M3, E2M1) + INT8.

package mx_simple

/** Element (A or W operand) format.
  *
  * FP formats: `e > 0`, has hidden bit, subnormal support.
  * INT8:        `e == 0`, no hidden bit, implicit scale exp `impSc`.
  */
final case class Elem(
  name:    String,
  e:       Int,      // exp bits; 0 for INT
  m:       Int,      // mant fractional bits (FP) or magnitude bits (INT)
  resvNaN: Boolean,  // FP: top-exp reserved for NaN?
  impSc:   Int = 0,  // INT: implicit scale exponent; unused for FP
) {
  def isInt: Boolean = e == 0
  def hasHiddenBit: Boolean = !isInt

  def bias: Int = if (isInt) 0 else (1 << (e - 1)) - 1

  /** Max value's exponent in normalized-FP form (2^k s.t. max_value < 2^(k+1)).
    * FP: max_biased_exp - bias.
    * INT: m + impSc (max int = ~2^m, times 2^impSc).
    */
  def maxOpValExp: Int = {
    if (isInt) m + impSc
    else {
      val reserved = if (resvNaN) 2 else 1
      ((1 << e) - reserved) - bias
    }
  }

  /** Min non-zero value's exponent (2^k = smallest representable magnitude).
    * FP: 2^(1-bias) * 2^(-m) = 2^(1-bias-m) for subnormal min.
    * INT: 2^impSc for min non-zero int = 1 * 2^impSc.
    */
  def minOpValExp: Int = {
    if (isInt) impSc
    else 1 - bias - m
  }
}

object Elem {
  val E5M2 = Elem("E5M2", 5, 2, resvNaN = true)
  val E4M3 = Elem("E4M3", 4, 3, resvNaN = false)
  val E3M2 = Elem("E3M2", 3, 2, resvNaN = false)
  val E2M3 = Elem("E2M3", 2, 3, resvNaN = false)
  val E2M1 = Elem("E2M1", 2, 1, resvNaN = false)
  val INT8 = Elem("INT8", 0, 7, resvNaN = false, impSc = -6)  // MX INT8 convention

  val byName: Map[String, Elem] = Seq(E5M2, E4M3, E3M2, E2M3, E2M1, INT8)
                                    .map(e => e.name -> e).toMap
}

/** Block scale format (shared across N lanes). Always unsigned (no sign bit). */
final case class Scale(name: String, e: Int, m: Int) {
  def bias: Int = (1 << (e - 1)) - 1
}

object Scale {
  val UE8M0 = Scale("UE8M0", 8, 0)
  val UE7M1 = Scale("UE7M1", 7, 1)
  val UE6M2 = Scale("UE6M2", 6, 2)
  val UE5M3 = Scale("UE5M3", 5, 3)
  val UE4M4 = Scale("UE4M4", 4, 4)
  val UE4M3 = Scale("UE4M3", 4, 3)

  val byName: Map[String, Scale] = Seq(UE8M0, UE7M1, UE6M2, UE5M3, UE4M4, UE4M3)
                                     .map(s => s.name -> s).toMap
}

/** Fixed for this codebase: BF16 output accumulator. */
object BF16 {
  val expBits:  Int = 8
  val mantBits: Int = 7
  val sigBits:  Int = mantBits + 1   // 1 hidden + 7 fraction
  val bias:     Int = 127
}

/** DPU configuration knob. */
final case class DPUConfig(A: Elem, W: Elem, S: Scale, N: Int = 4) {
  //require(N == 4, "Only N=4 supported in this codebase.")
  def log2N: Int = chisel3.util.log2Ceil(N.max(2))
}

/** Datapath widths derived from a DPUConfig — mirrors `datapath_widths.py`. */
final case class Widths(cfg: DPUConfig) {
  private val A = cfg.A; private val W = cfg.W; private val S = cfg.S

  // ── Product level ──────────────────────────────────────────
  /** Product mantissa magnitude width.
    * FP:  (1.mA) * (1.mW) fits in mA+mW+2 bits.
    * INT: (mA-bit magnitude) * (mW-bit magnitude) fits in mA+mW bits.
    * We use mA+mW+2 uniformly (over-provision INT by 2 bits, harmless).
    */
  val prodMantW: Int = (A.m + 1) + (W.m + 1)

  /** Max product exponent (Lutz max{Pexp}). +1 for mant overflow when
    * (1.mA)(1.mW) can produce 2 integer bits.
    */
  val pMax: Int = A.maxOpValExp + W.maxOpValExp + 1

  /** Min product's leading-bit exponent (2^k = smallest representable
    * product magnitude). Sum of the two minOpValExp.
    */
  val pMinVal: Int = A.minOpValExp + W.minOpValExp

  // ── Anchor + SoP field (Lutz Eq 5 + Cuyckens SoP_FIXED_WIDTH) ──
  /** Anchor position from Eq 5 = max{Pexp} + log2(N) + 1. */
  val aboveAnchor: Int = pMax + cfg.log2N + 1
  /** Fractional bits below anchor = |pMinVal|. Covers 2^-1 .. 2^pMinVal. */
  val belowAnchor: Int = math.abs(pMinVal)
  /** Integer positions above the "1x2^0" line, inclusive of 2^0. */
  val intBits: Int = pMax + 1

  /** Total SoP field width = signed sum of N aligned products. */
  val sopFieldW: Int = 1 + cfg.log2N + intBits + belowAnchor

  // ── Post-tree scale mantissa multiply (our extension over Lutz) ──
  /** Unsigned scale mant product (1.mS_A)(1.mS_W). 0 for UE8M0. */
  val scaleMantProdW: Int =
    if (S.m == 0) 0 else 2 * (S.m + 1)
  /** Scaled term width.
    *
    * UE8M0 (mS=0): scaledTerm = sopField unchanged, width = sopFieldW.
    * Fractional scale: sopField (signed, sopFieldW) is multiplied by
    * `smp.zext` (SInt of width scaleMantProdW+1, top bit is 0 = positive).
    * Chisel signed×signed result width = a+b, so scaledTermW must be
    * `sopFieldW + scaleMantProdW + 1` to hold the full magnitude without
    * truncation. (An earlier off-by-one truncated the MSB and made hw output
    * ~2^S.m× larger than the FP64 golden for m>=2 configs.)
    */
  val scaledTermW: Int =
    if (scaleMantProdW == 0) sopFieldW
    else sopFieldW + scaleMantProdW + 1

  // ── Final adder combining scaled_term with BF16 accreg ─────
  /** Lutz FIXED_SUM_WIDTH pattern: 1 sign + acc_sig + scaled_term body. */
  val finalAdderW: Int = 1 + BF16.sigBits + scaledTermW

  // ── Placement bookkeeping (for barrel shifters in the datapath) ──
  /** Number of fractional bits each operand's significand carries BELOW the
    * "1" of "1.mant". For FP this is `m` (mA fractional bits under 1.);
    * for INT8 this is 0 (the mant field IS the integer magnitude, no hidden
    * fractional interpretation).
    */
  val fracBitsA: Int = if (A.hasHiddenBit) A.m else 0
  val fracBitsW: Int = if (W.hasHiddenBit) W.m else 0

  /** Constant left-shift applied to each product to align it in the SoP field.
    * Matches Cuyckens: SOP_SHIFT = ANCHOR - 2*SUPER_MAN_BITS. Generalized to
    * asymmetric operands and INT8: `SOP_SHIFT = belowAnchor - fracBitsA - fracBitsW`.
    * shiftAmt = SOP_SHIFT + prodExp puts product's "2^0" bit at field bit
    * `belowAnchor` for FP (and `belowAnchor + impSc_A + impSc_W = 0` for INT).
    */
  val sopShift: Int = belowAnchor - fracBitsA - fracBitsW

  /** Signed product width before the barrel shift (mant + sign). */
  val signedProdW: Int = prodMantW + 1

  /** Position of the "1 x 2^0" bit in the SoP field (fractional bits below). */
  val sopUnitPos: Int = belowAnchor

  /** For the scaled term, the 2^0 position sits further down by the scale
    * mant's fractional bits (2 * mS from squaring two mS-bit fractionals).
    */
  val termUnitPos: Int = belowAnchor + 2 * S.m

  /** Signed exp width covering both max and min product exp magnitudes. */
  val expSignedW: Int = {
    val maxAbs = math.max(math.abs(pMax), math.abs(pMinVal))
    chisel3.util.log2Ceil(maxAbs + 1) + 2  // +1 sign, +1 headroom
  }

  /** Signed scale-exp-sum width (for accreg alignment). */
  val scaleExpSumW: Int = S.e + 2

  // ── Sanity print (used by the emit main / test) ────────────
  def show(): String = {
    val header = f"config=${A.name}/${W.name}/${S.name} (N=${cfg.N})"
    val rows = Seq(
      f"prodMantW      = $prodMantW%3d   (1.mA)(1.mW) product magnitude",
      f"pMax           = $pMax%3d   max{Pexp}",
      f"pMinVal        = $pMinVal%3d   min product magnitude exp (signed)",
      f"intBits        = $intBits%3d   pMax + 1",
      f"belowAnchor    = $belowAnchor%3d   |pMinVal|",
      f"aboveAnchor    = $aboveAnchor%3d   Lutz Eq 5",
      f"sopFieldW      = $sopFieldW%3d   1 + log2N + intBits + belowAnchor",
      f"sopShift       = $sopShift%3d   const left-shift per product",
      f"scaleMantProdW = $scaleMantProdW%3d   2*(mS+1); 0 for UE8M0",
      f"scaledTermW    = $scaledTermW%3d   after post-tree scale mult",
      f"finalAdderW    = $finalAdderW%3d   with BF16 accreg",
    )
    (header +: rows).mkString("\n  ")
  }
}
