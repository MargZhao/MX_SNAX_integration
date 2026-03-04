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
import functions.py

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

def golden_model(a_uint8, b_uint8, acc_cnt, out_cnt):
    """
    黄金算术模型：精确模拟 PE 内部的局部累加行为
    """
    # 1. 模拟硬件前端：MXFP8 转换为内部的高精度/FP32 格式
    a_fp32 = mock_mxfp8_to_fp32(a_uint8)
    b_fp32 = mock_mxfp8_to_fp32(b_uint8)

    # 2. 时空展开 (Reshape)
    # 维度: [输出总次数, 每次输出需要的累加周期数, 空间并行度(PE数量)]
    a_reshaped = a_fp32.reshape((out_cnt, acc_cnt, SPATIAL_PAR))
    b_reshaped = b_fp32.reshape((out_cnt, acc_cnt, SPATIAL_PAR))

    # 3. 乘加树 (Multiplier + Accumulator)
    # a * b 对应单周期的乘积
    # sum(axis=1) 对应在 acc_cnt 维度上进行 Partial Sum 累加
    out_fp32 = np.sum(a_reshaped * b_reshaped, axis=1, dtype=np.float32)

    # 展平为一维数组 (长度应为 out_cnt * SPATIAL_PAR)
    return out_fp32.flatten()


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
    #先设置成1：（01111111）2，后续再改成真正的随机数
    exp_A = np.array(127, (M, num_blocks_k), dtype=np.uint8)
    exp_B = np.array(127, (num_blocks_k, N), dtype=np.uint8)
    # # A 的 Exponent: 形状为 (M, num_blocks_k)
    # exp_A = np.random.randint(120, 135, (args.M, num_blocks_k), dtype=np.uint8)
    # # B 的 Exponent: 形状为 (num_blocks_k, N)
    # exp_B = np.random.randint(120, 135, (num_blocks_k, args.N), dtype=np.uint8)


    #搞清楚这个
    out_fp32 = golden_model(a_uint8, b_uint8, acc_cnt, out_cnt)

    # Number of iterations for accelerator
    # depend on the spatial parallelism
    loop_iter = length//SPATIAL_PAR

    out_uint32 = out_fp32.view(np.uint32)

    # Format header file
    # Format header file
    # 写出控制参数，方便 C 代码配置 CSR
    ac_str = format_scalar_definition('uint32_t', 'ACC_CNT', acc_cnt)
    oc_str = format_scalar_definition('uint32_t', 'OUT_CNT', out_cnt)
    te_str = format_scalar_definition('uint32_t', 'TOTAL_ELEMENTS', total_elements)

    # 写出输入和输出向量
    a_str = format_vector_definition('uint8_t', 'A', a_uint8)
    b_str = format_vector_definition('uint8_t', 'B', b_uint8)
    out_str = format_vector_definition('uint32_t', 'OUT', out_uint32)

    f_str = '\n\n'.join([ac_str, oc_str, te_str, a_str, b_str, out_str])
    f_str += '\n'

    print(f_str)


if __name__ == '__main__':
    sys.exit(main())
