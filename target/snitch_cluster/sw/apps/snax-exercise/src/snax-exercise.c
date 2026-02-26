// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0

#include "snrt.h"

#include "data.h"
#include "snax-exercise-lib.h"
#include "streamer_csr_addr_map.h"

int main() {
    // Set err value for checking
    int err = 0;
    printf("Starting SNAX Exercise Accelerator...\n");

    // Allocates space in TCDM
    uint64_t *local_a, *local_b, *local_o;

    local_a = (uint64_t *)snrt_l1_next();
    local_b = local_a + DATA_LEN;
    local_o = local_b + DATA_LEN;

    // Start of pre-loading data from L2 memory
    // towards the L1 TCDM memory
    // Use the Snitch core with a DMA
    // to move the data from L2 to L1
    if (snrt_is_dm_core()) {
        // This measures the start of cycle count
        // for preloading the data to the L1 memory
        uint32_t start_dma_load = snrt_mcycle();

        // The DATA_LEN is found in data.h
        printf("Start DMA loading...\n");
        size_t vector_size = DATA_LEN * sizeof(uint64_t);
        snrt_dma_start_1d(local_a, A, vector_size);
        snrt_dma_start_1d(local_b, B, vector_size);

        // Measure the end of the transfer process
        uint32_t end_dma_load = snrt_mcycle();
    }

    // Synchronize cores by setting up a
    // fence barrier for the DMA and accelerator core
    snrt_cluster_hw_barrier();

    // This assigns the tasks inside the condition
    // to the core controlling the accelerator
    if (snrt_is_compute_core()) {
        // This marks the start of the
        // setting of CSRs for the accelerator
        uint32_t start_csr_setup = snrt_mcycle();

        // Configure streamer settings
        // 参数: 低位地址, 高位地址, 空间跨度(8字节), 循环次数, 时间跨度(64字节)
        //TODO: make it configurable in datagen.py
        uint32_t spatial_stride = 8;
        uint32_t temporal_stride = 8 * 8; // 每次吸入 8 个元素，步进 8*8 字节
        uint32_t out_temporal_stride = 2 * 8;
        //可以说 temporal_stride才是决定parfor数量的
        configure_streamer_a((uint64_t)local_a, 0, spatial_stride, LOOP_ITER, temporal_stride);
        
        configure_streamer_b((uint64_t)local_b, 0, spatial_stride, LOOP_ITER, temporal_stride);

        configure_streamer_o((uint64_t)local_o, 0, spatial_stride, LOOP_ITER, out_temporal_stride);
        printf("Streamer setup done...\n");

        // Configure ALU settings
        configure_exercise(UPPER_BIAS, LOWER_BIAS, LOOP_ITER);
        printf("CSR setup done...\n");

        // Start streamer then start ALU
        start_streamer();
        printf("Streamer started...\n");
        start_exercise();
        printf("Exercise started...\n");

        // Mark the end of the CSR setup cycles
        uint32_t end_csr_setup = snrt_mcycle();

        // Do this to poll the accelerator
        while (read_busy_exercise()) {
        };

        printf("checkpoint 1\n");


        // Do this to poll the streamer state
        while (read_busy_streamer()) {
        };

        printf("checkpoint 2\n");

        // Compare results and check if the
        // accelerator returns correct answers
        // For every incorrect answer, increment err
        for (uint32_t i = 0; i < OUT_LEN*2; i++) {
            uint64_t expected = OUT[i];
            uint64_t actual = *(local_o + i);
            
            if (expected != actual) {
                // 判断当前错的是低 64 位还是高 64 位
                char* part = (i % 2 == 0) ? "Low" : "High";
                
                // 把 64 位的 expected 劈成高 32 位和低 32 位
                uint32_t exp_hi = (uint32_t)(expected >> 32);
                uint32_t exp_lo = (uint32_t)(expected & 0xFFFFFFFF);
                
                // 把 64 位的 actual 劈成高 32 位和低 32 位
                uint32_t act_hi = (uint32_t)(actual >> 32);
                uint32_t act_lo = (uint32_t)(actual & 0xFFFFFFFF);
                
                // 用 %08x 打印，不足 8 位补零，中间加个下划线方便看
                printf("Mismatch at index %d [%s]: expected: 0x%08x_%08x, actual: 0x%08x_%08x\n", 
                       i, part, 
                       exp_hi, exp_lo,
                       act_hi, act_lo);
                err++;
            }
        }

        // Read performance counter
        printf("======================================\n");
        printf("SNAX Exercise Accelerator Finished!\n");
        printf("Hardware Cycles: %d \n", read_perf_exercise());
        if (err == 0) {
            printf(">>> SUCCESS! Golden model matched! <<<\n");
        } else {
            printf(">>> FAILED! Errors found: %d <<<\n", err);
        }
        printf("======================================\n");
    };

    return err;
}
