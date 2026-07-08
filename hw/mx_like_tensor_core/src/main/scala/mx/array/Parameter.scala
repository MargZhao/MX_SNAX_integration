package mx.array

import mx.mac.{ScaleAddConfig, MXFormats, ScaleFormats, TreeArch}
import mx.requant.{RequantConfig, RequantINT8Config}

/** DSE override for the per-PE accumulator strategy.  When supplied to a
 *  PEArrayConfig / PEArrayINT8Config, the FDPU instantiation uses these
 *  values instead of the wrapper-derived defaults.  Used by the
 *  architecture sweep to compare baseline (single-cycle scale apply),
 *  block-deferred, and Kulisch back-to-back under identical wrappers. */
case class ArchOverride(treeArch: TreeArch, cyclesPerBlock: Int)

/**
 * Combined elaboration-time configuration for a PEArrayWrapper (FP8/FP6 output).
 *
 * @param macCfg     ScaleAddConfig for element/scale types driving each PE.
 * @param vectorSize Number of parallel MACs per PE (>= 1).
 * @param tileRows   Number of PE rows.
 * @param tileCols   Number of PE columns.
 * @param requantCfg RequantConfig for the FP32→MXFP8/FP6 requantization block.
 *                   Its tileRows/tileCols must match the array dimensions.
 */
case class PEArrayConfig(
  macCfg:        ScaleAddConfig,
  vectorSize:    Int,
  tileRows:      Int,
  tileCols:      Int,
  requantCfg:    RequantConfig,
  K:             Int               = 16384,
  exposeResults: Boolean           = false,  // expose FP32 results_o IO for testing
  archOverride:  Option[ArchOverride] = None,
  /** Override the PE accumulator mantissa width (default -1 = derive from
   *  AccPrecision.recommended).  Must match requantCfg.inputMantWidth for
   *  the pad-free narrow path; otherwise wrapper zero-extends PE output
   *  to requant input width. */
  accMantBitsOverride: Int = -1
) {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")
  require(tileRows == requantCfg.tileRows,
    s"PEArrayConfig tileRows ($tileRows) must match requantCfg.tileRows (${requantCfg.tileRows})")
  require(tileCols == requantCfg.tileCols,
    s"PEArrayConfig tileCols ($tileCols) must match requantCfg.tileCols (${requantCfg.tileCols})")

  /** Total packed input width for operand A (vectorSize elements). */
  val srcWidthA  = macCfg.elementTypeA.totalWidth * vectorSize
  /** Total packed input width for operand B (vectorSize elements). */
  val srcWidthB  = macCfg.elementTypeB.totalWidth * vectorSize
  /** Shared scale factor bit width. */
  val scaleWidth = macCfg.stype.totalScaleWidth
  /** Effective accumulator mantissa bits used by both FDPU and dstWidth. */
  val effAccMantBits: Int =
    if (accMantBitsOverride > 0) accMantBitsOverride
    else throw new IllegalArgumentException(
      "accMantBitsOverride must be provided (M_acc from macc_final_selection.csv); K-based AccPrecision was removed")
  /** PE accumulator output width: 1 sign + 8 exp + accMantBits (narrow,
   *  matches FDPUPostScaleReductionTree.io.accOut). The requant wrapper
   *  zero-extends this to requantCfg.inputWidth before feeding the
   *  requant block.  Pad-free when requantCfg.inputMantWidth == accMantBits. */
  val dstWidth   = 1 + 8 + effAccMantBits
  require(requantCfg.inputWidth >= dstWidth,
    s"requantCfg.inputWidth (${requantCfg.inputWidth}) must be >= PE dstWidth ($dstWidth)")
  /** Single output element bit width. */
  val fp8Width   = requantCfg.outputType.totalWidth
  /** Number of PE-cycles spanning one MX block — used by FDPU's block-deferred
   *  scale-apply path so scale_A × scale_B is applied only at block boundary
   *  instead of every PE-cycle.  See FDPUPostScaleReductionTree.buildBlockDeferred. */
  val cyclesPerBlock = requantCfg.blockSize / vectorSize
  require(requantCfg.blockSize % vectorSize == 0,
    s"blockSize (${requantCfg.blockSize}) must be divisible by vectorSize ($vectorSize)")
}

/**
 * Combined elaboration-time configuration for a PEArrayWrapperINT8.
 *
 * @param macCfg     ScaleAddConfig for element/scale types driving each PE.
 * @param vectorSize Number of parallel MACs per PE (>= 1).
 * @param tileRows   Number of PE rows.
 * @param tileCols   Number of PE columns.
 * @param requantCfg RequantINT8Config for FP32→INT8 requantization.
 */
case class PEArrayINT8Config(
  macCfg:        ScaleAddConfig,
  vectorSize:    Int,
  tileRows:      Int,
  tileCols:      Int,
  requantCfg:    RequantINT8Config,
  K:             Int               = 16384,
  exposeResults: Boolean           = false,  // expose FP32 results_o IO for testing
  archOverride:  Option[ArchOverride] = None,
  accMantBitsOverride: Int = -1
) {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")
  require(tileRows == requantCfg.tileRows,
    s"PEArrayINT8Config tileRows ($tileRows) must match requantCfg.tileRows (${requantCfg.tileRows})")
  require(tileCols == requantCfg.tileCols,
    s"PEArrayINT8Config tileCols ($tileCols) must match requantCfg.tileCols (${requantCfg.tileCols})")

  val srcWidthA  = macCfg.elementTypeA.totalWidth * vectorSize
  val srcWidthB  = macCfg.elementTypeB.totalWidth * vectorSize
  val scaleWidth = macCfg.stype.totalScaleWidth
  val effAccMantBits: Int =
    if (accMantBitsOverride > 0) accMantBitsOverride
    else throw new IllegalArgumentException(
      "accMantBitsOverride must be provided (M_acc from macc_final_selection.csv); K-based AccPrecision was removed")
  val dstWidth   = 1 + 8 + effAccMantBits
  require(requantCfg.inputWidth >= dstWidth,
    s"requantCfg.inputWidth (${requantCfg.inputWidth}) must be >= PE dstWidth ($dstWidth)")
  /** PE-cycles per MX block (block-deferred scale apply). See PEArrayConfig. */
  val cyclesPerBlock = requantCfg.blockSize / vectorSize
  require(requantCfg.blockSize % vectorSize == 0,
    s"blockSize (${requantCfg.blockSize}) must be divisible by vectorSize ($vectorSize)")
}

/**
 * Combined elaboration-time configuration for a PEArrayWrapperBF16.
 *
 * No requant config needed: BF16 is a per-element combinational pass-through.
 *
 * @param macCfg     ScaleAddConfig for element/scale types driving each PE.
 * @param vectorSize Number of parallel MACs per PE (>= 1).
 * @param tileRows   Number of PE rows (4 or 8).
 * @param tileCols   Number of PE columns (4 or 8).
 */

/**
 * Combined elaboration-time configuration for a PEArrayWrapperFP32 (no requant).
 *
 * Used by quantize_mode = 0: the PE array's FP32 accumulator output IS the
 * primary result, packed [tileRows × tileCols × 32]. No requant block, no
 * shared-scale output.
 */
case class PEArrayFP32Config(
  macCfg:     ScaleAddConfig,
  vectorSize: Int,
  tileRows:   Int,
  tileCols:   Int,
  K:          Int = 16384,
  accMantBitsOverride: Int = -1
) {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  val srcWidthA  = macCfg.elementTypeA.totalWidth * vectorSize
  val srcWidthB  = macCfg.elementTypeB.totalWidth * vectorSize
  val scaleWidth = macCfg.stype.totalScaleWidth
  val effAccMantBits: Int =
    if (accMantBitsOverride > 0) accMantBitsOverride
    else throw new IllegalArgumentException(
      "accMantBitsOverride must be provided (M_acc from macc_final_selection.csv); K-based AccPrecision was removed")
  /** Narrow FP word width: 1 sign + 8 exp + accMantBits. Downstream
   *  consumers must zero-extend to 32 bits if FP32 is required. */
  val dstWidth   = 1 + 8 + effAccMantBits
  /** FP32 pass-through carries no block structure here — keep scale-apply
   *  per-cycle for simplicity (cyclesPerBlock=1 ⇒ FDPU uses buildSingleAcc). */
  val cyclesPerBlock = 1
}

case class PEArrayBF16Config(
  macCfg:        ScaleAddConfig,
  vectorSize:    Int,
  tileRows:      Int,
  tileCols:      Int,
  K:             Int     = 16384,
  exposeResults: Boolean = false,  // expose FP32 results_o IO for testing
  accMantBitsOverride: Int = -1
) {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")
  require(Seq(4, 8, 16).contains(tileRows), s"tileRows must be 4 or 8; got $tileRows")
  require(Seq(4, 8, 16).contains(tileCols), s"tileCols must be 4 or 8; got $tileCols")

  val srcWidthA  = macCfg.elementTypeA.totalWidth * vectorSize
  val srcWidthB  = macCfg.elementTypeB.totalWidth * vectorSize
  val scaleWidth = macCfg.stype.totalScaleWidth
  val effAccMantBits: Int =
    if (accMantBitsOverride > 0) accMantBitsOverride
    else throw new IllegalArgumentException(
      "accMantBitsOverride must be provided (M_acc from macc_final_selection.csv); K-based AccPrecision was removed")
  val dstWidth   = 1 + 8 + effAccMantBits
  /** BF16 wrapper has no requantCfg.blockSize exposed — keep scale-apply
   *  per-cycle for simplicity (cyclesPerBlock=1 ⇒ FDPU uses buildSingleAcc). */
  val cyclesPerBlock = 1
}
