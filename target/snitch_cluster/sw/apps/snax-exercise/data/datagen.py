#!/usr/bin/env python3

# Copyright 2023 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# Ryan Antonio <ryan.antonio@esat.kuleuven.be>

import sys
import argparse
import numpy as np
import os

# Add data utility path
sys.path.append(os.path.join(os.path.dirname(__file__),
                "../../../../../../util/sim/"))
from data_utils import format_scalar_definition, \
                       format_vector_definition  # noqa: E402

# Hard parameters so modify
# at your own risk

# Value range
MIN = 0
MAX = 100

# Design-time spatial parallelism
SPATIAL_PAR = 8


def golden_model(a, b, upper_bias, lower_bias):
    """
    完全对齐架构图的黄金算术模型 (带 128-bit 拆分)
    """
    # 1. 拼接 bias: 将两个 32-bit 寄存器拼成一个 64-bit 数
    bias = (upper_bias << 32) | lower_bias

    # 2. 将一维长数组按 8 个一组进行分块 (Reshape)
    num_outputs = len(a) // SPATIAL_PAR
    a_reshaped = a.reshape((num_outputs, SPATIAL_PAR))
    b_reshaped = b.reshape((num_outputs, SPATIAL_PAR))

    # 3. 乘加树 (Multiplier + Adder Tree)
    # 注意：这里强制转换为 object，是为了防止 64位乘法在 numpy 中溢出，让 Python 原生大整数接管！
    dot_products = np.sum(a_reshaped.astype(object) * b_reshaped.astype(object), axis=1)

    # 4. 加上最终的 bias，此时 out_128 里面的元素是理论上的 128-bit 大整数
    out_128 = dot_products + bias

    # 5. 【核心新增】将 128-bit 结果拆分成两个 64-bit (Low 和 High)
    out_split = []
    for val in out_128:
        val_int = int(val)
        # 用位与操作 (&) 提取低 64 位
        low_64 = val_int & 0xFFFFFFFFFFFFFFFF
        # 右移 64 位后，再提取高 64 位
        high_64 = (val_int >> 64) & 0xFFFFFFFFFFFFFFFF
        
        # 按照小端序 (Little Endian)，先放低位，再放高位
        out_split.append(low_64)
        out_split.append(high_64)

    # 返回拆分好的数组，它的长度会变成原来的 2 倍！
    return np.array(out_split, dtype=np.uint64)


def main():

    parser = argparse.ArgumentParser()
    parser.add_argument('--length', type=int, default=64, 
                        help='Number of input elements. Must be a multiple of 8.')
    parser.add_argument('--upper_bias', type=int, default=0, 
                        help='Value for upper bias CSR')
    parser.add_argument('--lower_bias', type=int, default=100, 
                        help='Value for lower bias CSR')

    args = parser.parse_args()
    length = args.length
    upper_bias = args.upper_bias
    lower_bias = args.lower_bias

    assert length % SPATIAL_PAR == 0, f"Error: Length must be a multiple of {SPATIAL_PAR}!"

    # Randomly generate inputs
    a = np.random.randint(MIN, MAX, length)
    b = np.random.randint(MIN, MAX, length)

    out = golden_model(a, b, upper_bias, lower_bias)

    # Number of iterations for accelerator
    # depend on the spatial parallelism
    loop_iter = length//SPATIAL_PAR

    # Format header file
    dl_str = format_scalar_definition('uint32_t', 'DATA_LEN', length)
    ol_str = format_scalar_definition('uint32_t', 'OUT_LEN', loop_iter) # 输出长度
    li_str = format_scalar_definition('uint32_t', 'LOOP_ITER', loop_iter)
    ub_str = format_scalar_definition('uint32_t', 'UPPER_BIAS', upper_bias)
    lb_str = format_scalar_definition('uint32_t', 'LOWER_BIAS', lower_bias)

    a_str = format_vector_definition('uint64_t', 'A', a)
    b_str = format_vector_definition('uint64_t', 'B', b)

    out_str = format_vector_definition('uint64_t', 'OUT', out)
    f_str = '\n\n'.join([dl_str, ol_str, li_str, ub_str, lb_str, a_str, b_str, out_str])

    f_str += '\n'

    print(f_str)


if __name__ == '__main__':
    sys.exit(main())
