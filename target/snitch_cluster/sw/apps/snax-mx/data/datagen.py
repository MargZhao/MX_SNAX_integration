#!/usr/bin/env python3

# Copyright 2023 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# Zijun Zhao <zijun.zhao@student.kuleuven.be>

import sys
import argparse
import numpy as np
import os
from math import ceil
# import functions  # TODO: use functions.py for real MXFP8 decode

# Add data utility path
sys.path.append(os.path.join(os.path.dirname(__file__),
                "../../../../../../util/sim/"))
from data_utils import format_scalar_definition, \
                       format_vector_definition  # noqa: E402

# Hard parameters so modify
# at your own risk

# Value range
MIN = 0
MAX = 8

# Design-time spatial parallelism


def mock_mxfp8_to_fp32(x_uint8):
    """
    【注意】这是一个占位函数！
    你需要在这里实现真正的 MXFP8 解码逻辑（提取 sign, exp, mantissa）。
    如果你的系统有 Shared Scale，也应该在这个阶段乘上去。
    这里仅作演示：随机映射为 -1.0 到 1.0 的浮点数。
    """
    return (x_uint8.astype(np.float32) / 255.0) * 2.0 - 1.0

def golden_model(a_m_k, b_k_n, exp_A, exp_B, M, K, N, block_size):
    """
    黄金算术模型：MX 矩阵乘法 D = A_scaled @ B_scaled
    Shared exponent 沿 K 维度共享：
      exp_A[m][k // block_size]  — 每行 m 对应每个 K-block 一个 exponent
      exp_B[k // block_size][n]  — 每列 n 对应每个 K-block 一个 exponent
    TODO: 用 functions.py 里的真实 MXFP8 解码替换下面的 placeholder 缩放
    """
    # 1. 模拟硬件前端：用 shared exponent 做缩放（scale = 2^(exp - bias)）
    a_f32 = np.zeros((M, K), dtype=np.float32)
    b_f32 = np.zeros((K, N), dtype=np.float32)
    for m in range(M):
        for k in range(K):
            scale = 2.0 ** (int(exp_A[m][k // block_size]) - 127)
            a_f32[m][k] = float(a_m_k[m][k]) * scale
    for k in range(K):
        for n in range(N):
            scale = 2.0 ** (int(exp_B[k // block_size][n]) - 127)
            b_f32[k][n] = float(b_k_n[k][n]) * scale

    # 2. 矩阵乘法（FP32 累加）
    return (a_f32 @ b_f32).astype(np.float32)


def main():

    parser = argparse.ArgumentParser()
    #TODO: should add MNK or parfor_M parfor_N parfor_K as arguments
    parser.add_argument('--mode', type=int, default=0,  #TODO: replace with real settings
                        help='mode of accumulator.')
    # parser.add_argument('--acc_cnt', type=int, default=0, #should be K/parfor_K(vector size)
    #                     help='accumulation count for a output')
    # parser.add_argument('--out_cnt', type=int, default=100, #should be (M/parfor_M)*(N/parfor_N)
    #                     help='output conunt for a whole computation')
    parser.add_argument('--M', type=int, default=8,
                        help='M dimension of the matmul')
    parser.add_argument('--N', type=int, default=8,
                        help='N dimension of the matmul')
    parser.add_argument('--K', type=int, default=8,
                        help='K dimension of the matmul')
    parser.add_argument('--parfor_M', type=int, default=2,
                        help='spatial parallelism in M dimension')
    parser.add_argument('--parfor_N', type=int, default=2,
                        help='spatial parallelism in N dimension')
    parser.add_argument('--parfor_K', type=int, default=1,
                        help='spatial parallelism in K dimension')
    parser.add_argument('--block_size', type=int, default=32, choices=[16, 32, 64], 
                        help='Shared exponent block size (16, 32, or 64)')

    args = parser.parse_args()
    mode = args.mode
    M = args.M
    N = args.N
    K = args.K
    parfor_M = args.parfor_M
    parfor_N = args.parfor_N
    parfor_K = args.parfor_K
    block_size = args.block_size
    acc_cnt = np.ceil(K / parfor_K) #ceil if K cannot be divided by parfor_K
    out_cnt = np.ceil(M / parfor_M) * (N / parfor_N)

    a_length = M * K
    b_length = K * N

    # assert length % SPATIAL_PAR == 0, f"Error: Length must be a multiple of {SPATIAL_PAR}!"

    # Randomly generate inputs
    a_m_k = np.random.randint(0, 256, (M, K), dtype=np.uint8)
    b_k_n = np.random.randint(0, 256, (K, N), dtype=np.uint8)

    num_blocks_k = ceil(K / block_size)
    #先设置成1：（01111111）2 = bias=127，scale=2^0=1，后续再改成真正的随机数
    # exp_A: shape [M, num_blocks_k] — K-only sharing, one exponent per row per k-block
    exp_A = np.full((M, num_blocks_k), 127, dtype=np.uint8)
    # exp_B: shape [num_blocks_k, N] — K-only sharing, one exponent per k-block per col
    exp_B = np.full((num_blocks_k, N), 127, dtype=np.uint8)
    # # A 的 Exponent: 形状为 (M, num_blocks_k)
    # exp_A = np.random.randint(120, 135, (M, num_blocks_k), dtype=np.uint8)
    # # B 的 Exponent: 形状为 (num_blocks_k, N)
    # exp_B = np.random.randint(120, 135, (num_blocks_k, N), dtype=np.uint8)

    # ------------------------------------------------------------------
    # Build SHARED byte array
    # 每个 64-bit block (8 bytes) 对应一个 output tile (m_tile, n_tile) 的一个 k_block
    # 遍历顺序：m_tile (outer) → n_tile (middle) → k_b (inner)
    # Layout:
    #   byte 0: exp_A[m_tile*parfor_M + 0][k_b]
    #   byte 1: exp_A[m_tile*parfor_M + 1][k_b]
    #   byte 2: exp_B[k_b][n_tile*parfor_N + 0]
    #   byte 3: exp_B[k_b][n_tile*parfor_N + 1]
    #   bytes 4-7: 0x00 (padding, streamer granularity is 64-bit)
    # TODO: generalize byte packing for parfor_M != 2 or parfor_N != 2
    # ------------------------------------------------------------------
    shared_bytes = []
    for m_tile in range(M // parfor_M):
        for n_tile in range(N // parfor_N):
            for k_b in range(num_blocks_k):
                block = []
                for pm in range(parfor_M):
                    block.append(int(exp_A[m_tile * parfor_M + pm][k_b]))
                for pn in range(parfor_N):
                    block.append(int(exp_B[k_b][n_tile * parfor_N + pn]))
                # pad to 8 bytes (64-bit streamer granularity)
                block += [0] * (8 - len(block))
                shared_bytes.extend(block)
    shared_array = np.array(shared_bytes, dtype=np.uint8)

    # Number of 64-bit blocks in the shared exponent buffer
    SHARE_LEN = (M // parfor_M) * (N // parfor_N) * num_blocks_k
    share_vector_size = SHARE_LEN * 8  # bytes

    out_fp32 = golden_model(a_m_k, b_k_n, exp_A, exp_B, M, K, N, block_size)

    # Number of iterations for each streamer reader/writer
    # (number of 64-bit beats; TODO: update formulas when temporal_dim is raised to 3
    #  to properly model data reuse across M/N tiles)
    A_LOOP_ITER     = (M // parfor_M) * (N // parfor_N) * K * parfor_M // 8
    B_LOOP_ITER     = (M // parfor_M) * (N // parfor_N) * K * parfor_N // 8
    SHARE_LOOP_ITER = SHARE_LEN           # one 64-bit block per k_block per output tile
    OUT_LOOP_ITER   = (M // parfor_M) * (N // parfor_N)  # one beat per output tile

    out_uint32 = out_fp32.flatten().view(np.uint32)

    # Format header file
    # 写出控制参数，方便 C 代码配置 CSR
    ac_str  = format_scalar_definition('uint32_t', 'ACC_CNT',         int(acc_cnt))
    oc_str  = format_scalar_definition('uint32_t', 'OUT_CNT',         int(out_cnt))
    mode_str = format_scalar_definition('uint32_t', 'MODE',           mode)

    # DMA transfer sizes (bytes)
    a_len_str  = format_scalar_definition('uint32_t', 'a_data_length',    M * K)
    b_len_str  = format_scalar_definition('uint32_t', 'b_data_length',    K * N)
    out_len_str = format_scalar_definition('uint32_t', 'out_data_length', M * N * 4)
    sv_str     = format_scalar_definition('uint32_t', 'share_vector_size', int(share_vector_size))

    # Streamer temporal bounds (beats)
    al_str  = format_scalar_definition('uint32_t', 'A_LOOP_ITER',     int(A_LOOP_ITER))
    bl_str  = format_scalar_definition('uint32_t', 'B_LOOP_ITER',     int(B_LOOP_ITER))
    sl_str  = format_scalar_definition('uint32_t', 'SHARE_LOOP_ITER', int(SHARE_LOOP_ITER))
    ol_str  = format_scalar_definition('uint32_t', 'OUT_LOOP_ITER',   int(OUT_LOOP_ITER))
    slen_str = format_scalar_definition('uint32_t', 'SHARE_LEN',      int(SHARE_LEN))
    outlen_str = format_scalar_definition('uint32_t', 'OUT_LEN',      M * N)

    # 写出输入和输出向量
    a_str   = format_vector_definition('uint8_t',  'A',      a_m_k.flatten())
    b_str   = format_vector_definition('uint8_t',  'B',      b_k_n.flatten())
    sh_str  = format_vector_definition('uint8_t',  'SHARED', shared_array)
    out_str = format_vector_definition('uint32_t', 'OUT',    out_uint32)

    f_str = '\n\n'.join([
        ac_str, oc_str, mode_str,
        a_len_str, b_len_str, out_len_str, sv_str,
        al_str, bl_str, sl_str, ol_str, slen_str, outlen_str,
        a_str, b_str, sh_str, out_str,
    ])
    f_str += '\n'

    print(f_str)


if __name__ == '__main__':
    sys.exit(main())
