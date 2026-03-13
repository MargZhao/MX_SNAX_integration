#!/usr/bin/env python3

# Copyright 2023 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# Zijun Zhao <zijun.zhao@student.kuleuven.be>

import sys
import argparse
import numpy as np
import pathlib
import hjson
import sys
import math
import struct
import os
import quantize  
from math import ceil
# import functions  # TODO: use functions.py for real MXFP8 decode

# Add data utility path
sys.path.append(os.path.join(os.path.dirname(__file__),
                "../../../../../../util/sim/"))
from data_utils import format_scalar_definition, \
                       format_vector_definition  # noqa: E402

###################################################################
###############    auxiliray functions   ##########################
###################################################################

def gen_channel_enable_CSR(channel_en_CSR, channel_en_bits):
    for i in range(channel_en_bits):
        element_index = i // 32  # Determine which element to modify
        bit_position = i % 32  # Position within the element
        if element_index < len(channel_en_CSR):
            channel_en_CSR[element_index] |= 1 << (bit_position)

    channel_en_CSR = [int(x) for x in channel_en_CSR][::-1]
    return channel_en_CSR


#this function pad zero for data that cannot fill a 64 bit port
def zero_padding(matrix_unpadded, pad_num):
    pass



def data_file_emit(**kwargs):
    data_str = []

    #######################################################
    ############      workload params        ##############
    #######################################################
    M = kwargs["M"]
    K = kwargs["K"]
    N = kwargs["N"]

    data_str += [format_scalar_definition("uint32_t", "M", M)]
    data_str += [format_scalar_definition("uint32_t", "K", K)]
    data_str += [format_scalar_definition("uint32_t", "N", N)]

    parfor_M = kwargs["parfor_M"]
    parfor_N = kwargs["parfor_N"]
    parfor_K = kwargs["parfor_K"]
    block_size = kwargs["block_size"]
    quantize_mode = kwargs["quantize_mode"]

    data_str += [format_scalar_definition("uint32_t", "PARFOR_M", parfor_M)]
    data_str += [format_scalar_definition("uint32_t", "PARFOR_N", parfor_N)]
    data_str += [format_scalar_definition("uint32_t", "PARFOR_K", parfor_K)]
    data_str += [format_scalar_definition("uint32_t", "BLOCK_SIZE", block_size)]#note that quantize mode is in it

    acc_cnt = np.ceil(K / parfor_K) #ceil if K cannot be divided by parfor_K
    out_cnt = np.ceil(M / parfor_M) * (N / parfor_N)
    data_str += [format_scalar_definition("uint32_t", "ACC_CNT", int(acc_cnt))]
    data_str += [format_scalar_definition("uint32_t", "OUT_CNT", int(out_cnt))]

    #######################################################
    ###################data generation#####################
    #######################################################
    # TODO: make a switch to whether use random gen data or real workload
    variance = 1
    A_fp32 = (np.random.randn(M, K) * variance ).astype(np.float32)
    B_fp32 = (np.random.randn(K, N) * variance ).astype(np.float32)
    m_padded = ceil(M / parfor_M) * parfor_M
    n_padded = ceil(N / parfor_N) * parfor_N
    # K 维度 padding 到 block_size 的整数倍，方便后续按 block_size 划分 k-blocks 和生成 shared exponent
    k_padded = ceil(K / parfor_K) * parfor_K
    K_padded = ceil(k_padded / block_size) * block_size# padding k to block_size的整数倍
    A_fp32_padded = np.zeros((m_padded, k_padded), dtype=np.float32)
    B_fp32_padded = np.zeros((k_padded, n_padded), dtype=np.float32)
    A_fp32_padded[:M, :K] = A_fp32
    B_fp32_padded[:K, :N] = B_fp32  
    
    A_quantized, exp_A, A_input = quantize.quantize_mx(A_fp32_padded, 'fp8_e5m2', block_size=block_size, axis=1)
    B_quantized, exp_B, B_input = quantize.quantize_mx(B_fp32_padded, 'fp8_e5m2', block_size=block_size, axis=0)

    #######################################################
    #####################streamer params###################
    #######################################################


    a_bitwidth = kwargs["A_bit_width"]
    b_bitwidth = kwargs["B_bit_width"]
    bankwidth  = 64
    shared_bitwidth = 8

    if quantize_mode == 0 :
        o_bitwidth = 32
    elif quantize_mode == 1:
        o_bitwidth = 16
    else:
        o_bitwidth = 8
    
    stationary = kwargs["stationary"]
    data_str += [format_scalar_definition("uint32_t", "stationary", stationary)]
    assert (
        stationary == 0 or stationary == 1 or stationary == 2
    ), "Invalid stationary setting"
    output_stationary = 0
    weight_stationary = 1
    input_stationary = 2

    #-------------derived loop counts----------------
    m_tiles         = ceil(m_padded / parfor_M)
    n_tiles         = ceil(n_padded / parfor_N)
    k_tiles         = ceil(k_padded / parfor_K)
    k_blocks        = ceil(k_padded / block_size)
    bits_per_byte   = 8

    #-------------Streamer A (element data)----------------
    data_str += [format_scalar_definition("int32_t", "Aslstride0", bankwidth / bits_per_byte)]

    A_tile_bytes = parfor_M * parfor_K * a_bitwidth / bits_per_byte

    #0 is the inner most loop
    if stationary == output_stationary:
        Atlbound0  = k_tiles;  # K
        Atlstride0 = A_tile_bytes
        Atlbound1  = n_tiles;  # N 
        Atlstride1 = 0
        Atlbound2  = m_tiles;  # M outest
        Atlstride2 = k_tiles * A_tile_bytes
    elif stationary == weight_stationary:
        Atlbound0  = m_tiles;  #first move in M dimension
        Atlstride0 = k_tiles * A_tile_bytes
        Atlbound1  = n_tiles;   
        Atlstride1 = 0
        Atlbound2  = k_tiles;   
        Atlstride2 = A_tile_bytes
    elif stationary == input_stationary:
        Atlbound0  = n_tiles;  #first move in N dimension 
        Atlstride0 = 0
        Atlbound1  = m_tiles;   
        Atlstride1 = k_tiles * A_tile_bytes
        Atlbound2  = k_tiles;   
        Atlstride2 = A_tile_bytes

    data_str += [format_scalar_definition("int32_t", "Atlbound0",  Atlbound0)]
    data_str += [format_scalar_definition("int32_t", "Atlstride0", Atlstride0)]
    data_str += [format_scalar_definition("int32_t", "Atlbound1",  Atlbound1)]
    data_str += [format_scalar_definition("int32_t", "Atlstride1", Atlstride1)]
    data_str += [format_scalar_definition("int32_t", "Atlbound2",  Atlbound2)]
    data_str += [format_scalar_definition("int32_t", "Atlstride2", Atlstride2)]

    #-------------Streamer B (element data)----------------
    data_str += [format_scalar_definition("int32_t", "Bslstride0", bankwidth / bits_per_byte)]

    B_tile_bytes = parfor_K * parfor_N * b_bitwidth / bits_per_byte

    if stationary == output_stationary:
        Btlbound0  = k_tiles;   
        Btlstride0 = B_tile_bytes
        Btlbound1  = n_tiles;   
        Btlstride1 = k_tiles * B_tile_bytes
        Btlbound2  = m_tiles;   
        Btlstride2 = 0
    elif stationary == weight_stationary:
        Btlbound0  = m_tiles;   
        Btlstride0 = 0
        Btlbound1  = n_tiles;   
        Btlstride1 = k_tiles * B_tile_bytes
        Btlbound2  = k_tiles;   
        Btlstride2 = B_tile_bytes
    elif stationary == input_stationary:
        Btlbound0  = n_tiles;   
        Btlstride0 = k_tiles * B_tile_bytes
        Btlbound1  = m_tiles;   
        Btlstride1 = 0
        Btlbound2  = k_tiles;   
        Btlstride2 = B_tile_bytes

    data_str += [format_scalar_definition("int32_t", "Btlbound0",  Btlbound0)]
    data_str += [format_scalar_definition("int32_t", "Btlstride0", Btlstride0)]
    data_str += [format_scalar_definition("int32_t", "Btlbound1",  Btlbound1)]
    data_str += [format_scalar_definition("int32_t", "Btlstride1", Btlstride1)]
    data_str += [format_scalar_definition("int32_t", "Btlbound2",  Btlbound2)]
    data_str += [format_scalar_definition("int32_t", "Btlstride2", Btlstride2)]

    #-------------Streamer Scale (combined A+B, bank-aligned)----------------
    steps_per_block = block_size // parfor_K
    num_shared_once = (parfor_M+parfor_N) #number of share exponent loaded for once
    shared_port_needed = num_shared_once//8
    shared_update_cnt = block_size//parfor_K #how many cycle to update shared exponent for once
    data_str += [format_scalar_definition("int32_t", "SHslstride0", bankwidth / bits_per_byte)]

    # (parfor_M + parfor_N) bytes per update
    combined_raw  = parfor_M + parfor_N
    # round up to bankwidth boundary
    combined_tile = int(ceil(combined_raw / (bankwidth / bits_per_byte)) * (bankwidth / bits_per_byte))
    pad           = combined_tile - combined_raw

    # output_stationary: scale loops mirror element A loop ordering
    if stationary == output_stationary:
        SHtlbound0  = steps_per_block;   SHtlstride0 = 0 #within a block
        SHtlbound1  = k_blocks;          SHtlstride1 = combined_tile #between blocks
        SHtlbound2  = n_tiles;           SHtlstride2 = k_blocks * combined_tile
        SHtlbound3  = m_tiles;           SHtlstride3 = n_tiles * k_blocks * combined_tile
    elif stationary == weight_stationary:
        SHtlbound0  = steps_per_block;   SHtlstride0 = 0
        SHtlbound1  = k_blocks;          SHtlstride1 = combined_tile
        SHtlbound2  = m_tiles;           SHtlstride2 = k_blocks * combined_tile
        SHtlbound3  = n_tiles;           SHtlstride3 = m_tiles * k_blocks * combined_tile
    elif stationary == input_stationary:
        SHtlbound0  = steps_per_block;   SHtlstride0 = 0
        SHtlbound1  = k_blocks;          SHtlstride1 = combined_tile
        SHtlbound2  = n_tiles;           SHtlstride2 = k_blocks * combined_tile
        SHtlbound3  = m_tiles;           SHtlstride3 = n_tiles * k_blocks * combined_tile

    data_str += [format_scalar_definition("int32_t", "SHtlbound0",  SHtlbound0)]
    data_str += [format_scalar_definition("int32_t", "SHtlstride0", SHtlstride0)]
    data_str += [format_scalar_definition("int32_t", "SHtlbound1",  SHtlbound1)]
    data_str += [format_scalar_definition("int32_t", "SHtlstride1", SHtlstride1)]
    data_str += [format_scalar_definition("int32_t", "SHtlbound2",  SHtlbound2)]
    data_str += [format_scalar_definition("int32_t", "SHtlstride2", SHtlstride2)]
    data_str += [format_scalar_definition("int32_t", "SHtlbound3",  SHtlbound3)]
    data_str += [format_scalar_definition("int32_t", "SHtlstride3", SHtlstride3)]

    #-------------Output element----------------
    # TODO: add an extra streamer for partial sum to support other type of stationarity
    data_str += [format_scalar_definition("int32_t", "Oslstride0", bankwidth / bits_per_byte)]

    O_tile_bytes = parfor_M * parfor_N * o_bitwidth / bits_per_byte

    if stationary == output_stationary:
        Otlbound0  = k_tiles;   
        Otlstride0 = 0
        Otlbound1  = n_tiles;   
        Otlstride1 = O_tile_bytes
        Otlbound2  = m_tiles;   
        Otlstride2 = n_tiles * O_tile_bytes
    elif stationary == weight_stationary:
        Otlbound0  = m_tiles;   
        Otlstride0 = 0
        Otlbound1  = n_tiles;   
        Otlstride1 = k_tiles * O_tile_bytes
        Otlbound2  = k_tiles;   
        Otlstride2 = O_tile_bytes
    elif stationary == input_stationary:
        Otlbound0  = n_tiles;   
        Otlstride0 = k_tiles * O_tile_bytes
        Otlbound1  = m_tiles;   
        Otlstride1 = 0
        Otlbound2  = k_tiles;   
        Otlstride2 = O_tile_bytes

    data_str += [format_scalar_definition("int32_t", "Otlbound0",  Otlbound0)]
    data_str += [format_scalar_definition("int32_t", "Otlstride0", Otlstride0)]
    data_str += [format_scalar_definition("int32_t", "Otlbound1",  Otlbound1)]
    data_str += [format_scalar_definition("int32_t", "Otlstride1", Otlstride1)]
    data_str += [format_scalar_definition("int32_t", "Otlbound2",  Otlbound2)]
    data_str += [format_scalar_definition("int32_t", "Otlstride2", Otlstride2)]


    #-------------Share scale Out ( bank-aligned)----------------
    #TODO


    #######################################################
    ##################### base  address ###################
    #######################################################
    def align_addr(addr, align):
        return int(ceil(addr / align) * align)

    bank_bytes = bankwidth // bits_per_byte   # = 8

    A_data_size     = m_padded * k_padded * a_bitwidth // bits_per_byte
    B_data_size     = k_padded * n_padded * b_bitwidth // bits_per_byte
    scale_data_size = m_tiles * n_tiles * k_blocks * combined_tile
    D_data_size     = m_padded * n_padded * o_bitwidth // bits_per_byte

    delta_local_a     = 0
    delta_local_b     = align_addr(delta_local_a + A_data_size, bank_bytes)
    delta_local_scale = align_addr(delta_local_b + B_data_size, bank_bytes)
    delta_local_d     = align_addr(delta_local_scale + scale_data_size, bank_bytes)

    data_str += [format_scalar_definition("int32_t", "delta_local_a",     delta_local_a)]
    data_str += [format_scalar_definition("int32_t", "delta_local_b",     delta_local_b)]
    data_str += [format_scalar_definition("int32_t", "delta_local_scale", delta_local_scale)]
    data_str += [format_scalar_definition("int32_t", "delta_local_d",     delta_local_d)]

    #######################################################
    ##################### data arrays #####################
    #######################################################

    # ---- A: tile reorder (m_tiles, k_tiles, parfor_M, parfor_K) ----
    A_tiled = (A_input
               .reshape(m_tiles, parfor_M, k_tiles, parfor_K) # goes to(0:m_tiles, 2:k_tiles, 1:parfor_M, 3:parfor_K)
               .transpose(0, 2, 1, 3)
               .flatten()
               .view(np.int8))
    data_str += [format_vector_definition("int8_t", "A", A_tiled)]

    # ---- B: tile reorder (k_tiles, n_tiles, parfor_K, parfor_N) ----
    B_tiled = (B_input
               .reshape(k_tiles, parfor_K, n_tiles, parfor_N)
               .transpose(0, 2, 1, 3)
               .flatten()
               .view(np.int8))
    data_str += [format_vector_definition("int8_t", "B", B_tiled)]

    # ---- Combined A+B scale (E8M0, biased by 127) ----
    # quantize_mx returns actual (unbiased) exponents; add 127 for E8M0 hardware format
    exp_A_u8 = np.clip(exp_A + 127, 0, 255).astype(np.uint8)  # (m_padded, k_blocks)
    exp_B_u8 = np.clip(exp_B + 127, 0, 255).astype(np.uint8)  # (k_blocks, n_padded)

    if stationary == output_stationary or stationary == input_stationary:
        loop = [(m, n, kb)
                for m in range(m_tiles)
                for n in range(n_tiles)
                for kb in range(k_blocks)]
    else:  # weight_stationary
        loop = [(m, n, kb)
                for n in range(n_tiles)
                for m in range(m_tiles)
                for kb in range(k_blocks)]

    chunks = []
    for (m, n, kb) in loop:
        a_sc = exp_A_u8[m * parfor_M:(m + 1) * parfor_M, kb]        # parfor_M bytes
        b_sc = exp_B_u8[kb, n * parfor_N:(n + 1) * parfor_N]         # parfor_N bytes
        chunks.append(np.concatenate([a_sc, b_sc, np.zeros(pad, dtype=np.uint8)]))
    combined_scale = np.concatenate(chunks).view(np.int8)
    data_str += [format_vector_definition("int8_t", "scale", combined_scale)]

    # ---- D golden: A_quantized @ B_quantized, tile reorder (m_tiles, n_tiles, parfor_M, parfor_N) ----
    D_golden = (A_quantized @ B_quantized).astype(np.float32)
    D_tiled = (D_golden
               .reshape(m_tiles, parfor_M, n_tiles, parfor_N)
               .transpose(0, 2, 1, 3)
               .flatten())
    data_str += [format_vector_definition("float", "D_golden", D_tiled)]
    data_str = "\n\n".join(data_str)
    return data_str

def main():
    parser = argparse.ArgumentParser(description="Generate data for kernels")
    parser.add_argument(
        "--swcfg",
        type=pathlib.Path,
        required=True,
        help="Select param config file kernel",
    )
    parser.add_argument(
        "--hwcfg",
        type=pathlib.Path,
        required=True,
        help="Select hardware config file kernel",
    )
    args = parser.parse_args()

    # Load param config file
    with args.swcfg.open() as f:
        param = hjson.loads(f.read())

    # Load hardware config file
    with args.hwcfg.open() as f:
        hw = hjson.loads(f.read())

    # Merge dictionaries (hw overrides param in case of conflicts)
    merged_config = {**param, **hw}

    # Emit header file
    print(data_file_emit(**merged_config))



if __name__ == '__main__':
    main()
