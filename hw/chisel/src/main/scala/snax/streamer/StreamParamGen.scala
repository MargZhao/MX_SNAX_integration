// Copyright 2024 KU Leuven.
// Solderpad Hardware License, Version 0.51, see LICENSE for details.
// SPDX-License-Identifier: SHL-0.51


package snax.streamer
 
import snax.readerWriter._
import snax.reqRspManager._
import snax.utils._
 
import chisel3._
import chisel3.util._

// Streamer parameters
// tcdm_size in KB
object StreamerParametersGen {

// constrain: all the reader and writer needs to have same config of crossClockDomain
  def hasCrossClockDomain = false

  def readerParams = Seq(
    new ReaderWriterParam(
      spatialBounds = List(
        2
      ),
      temporalDimension = 3,
      tcdmDataWidth = 64,
      tcdmSize = 128,
      tcdmLogicWordSize = Seq(256),
      numChannel = 2,
      addressBufferDepth = 8,
      dataBufferDepth = 8,
      configurableChannel = false,
      dynamicPriority = true,
      higherStaticPriority = false,
      crossClockDomain = hasCrossClockDomain
   ), 
    new ReaderWriterParam(
      spatialBounds = List(
        1
      ),
      temporalDimension = 3,
      tcdmDataWidth = 64,
      tcdmSize = 128,
      tcdmLogicWordSize = Seq(256),
      numChannel = 1,
      addressBufferDepth = 8,
      dataBufferDepth = 8,
      configurableChannel = false,
      dynamicPriority = true,
      higherStaticPriority = false,
      crossClockDomain = hasCrossClockDomain
   ), 
    new ReaderWriterParam(
      spatialBounds = List(
        1
      ),
      temporalDimension = 4,
      tcdmDataWidth = 64,
      tcdmSize = 128,
      tcdmLogicWordSize = Seq(256),
      numChannel = 1,
      addressBufferDepth = 8,
      dataBufferDepth = 8,
      configurableChannel = false,
      dynamicPriority = true,
      higherStaticPriority = false,
      crossClockDomain = hasCrossClockDomain
    )
  )

  def writerParams = Seq(
    new ReaderWriterParam(
      spatialBounds = List(
        8
      ),
      temporalDimension = 2,
      tcdmDataWidth = 64,
      tcdmSize = 128,
      tcdmLogicWordSize = Seq(256),
      numChannel = 8,
      addressBufferDepth = 8,
      dataBufferDepth = 8,
      configurableChannel = false,
      dynamicPriority = true,
      higherStaticPriority = false,
      crossClockDomain = hasCrossClockDomain
    )
  )

  def readerWriterParams = Seq()

  def tagName = "snax_mx_alu_"
  def headerFilepath = "../../target/snitch_cluster/sw/snax/snax-mx/include"
}
