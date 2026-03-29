package mx.requant

import mx.mac.{ElementType, MXFormats}

/**
 * Configuration for the FP32 → MXFP8 requantization block.
 *
 * @param blockSize  Number of elements sharing one MX scale: 16, 32, or 64.
 * @param tileRows   Tile rows of the MAC array: 4 or 8.
 * @param tileCols   Tile columns of the MAC array: 4 or 8.
 * @param outputType Target MXFP8 element format: E5M2 or E4M3.
 */
case class RequantConfig(
  blockSize:  Int,
  tileRows:   Int,
  tileCols:   Int,
  outputType: ElementType
) {
  require(Seq(16, 32, 64).contains(blockSize),
    s"blockSize must be 16, 32, or 64; got $blockSize")
  require(Seq(4, 8).contains(tileRows),
    s"tileRows must be 4 or 8; got $tileRows")
  require(Seq(4, 8).contains(tileCols),
    s"tileCols must be 4 or 8; got $tileCols")
  require(Seq("E5M2", "E4M3").contains(outputType.name),
    s"outputType must be E5M2 or E4M3; got ${outputType.name}")
  require(blockSize % tileCols == 0,
    s"blockSize ($blockSize) must be divisible by tileCols ($tileCols)")

  /** How many PE-array outputs (each of width tileCols) fill one MX block. */
  val batchesPerBlock: Int = blockSize / tileCols
}

object DefaultRequantConfigs {
  /** 4×4 tile, block-32, E5M2 output */
  val e5m2_block32_4x4 = RequantConfig(
    blockSize  = 32,
    tileRows   = 4,
    tileCols   = 4,
    outputType = MXFormats.E5M2
  )

  /** 8×8 tile, block-16, E4M3 output */
  val e4m3_block16_8x8 = RequantConfig(
    blockSize  = 16,
    tileRows   = 8,
    tileCols   = 8,
    outputType = MXFormats.E4M3
  )

  /** 4×4 tile, block-64, E4M3 output */
  val e4m3_block64_4x4 = RequantConfig(
    blockSize  = 64,
    tileRows   = 4,
    tileCols   = 4,
    outputType = MXFormats.E4M3
  )
}
