// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0

#include "snrt.h"

#include "data.h"
#include "snax-mx-AIagent-lib.h"

int main() {
    // Set err value for checking
    int err = 0;
    printf("Starting SNAX Exercise Accelerator...\n");

    // Allocates space in TCDM
    // A, B, SHARED are uint8 data; output is uint32 (float32 results)
    uint8_t  *local_a, *local_b, *local_shared;
    uint32_t *local_o;

    local_a      = (uint8_t  *)(snrt_l1_next() + delta_local_a);
    local_b      = (uint8_t  *)(snrt_l1_next() + delta_local_b);
    local_o      = (uint32_t *)(snrt_l1_next() + delta_local_o);
    local_shared = (uint8_t  *)(snrt_l1_next() + delta_local_shared);

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
        //TODO: make it compatible this with the low-bit precision data format
        snrt_dma_start_1d(local_a,      A,      a_data_length);
        //                addr_L1, addr_L2/3   , transfer size in bytes 
        snrt_dma_start_1d(local_b,      B,      b_data_length);
        snrt_dma_start_1d(local_shared, SHARED, share_vector_size);
        snrt_dma_wait_all();
        // Measure the end of the transfer process
        //uint32_t end_dma_load = snrt_mcycle();
    }

    // Synchronize cores by setting up a
    // fence barrier for the DMA and accelerator core
    snrt_cluster_hw_barrier();

    int32_t Aslstride[] = {Aslstride0};
    int32_t Atlbound[] = {Atlbound0, Atlbound1, Atlbound2};
    int32_t Atlstride[] = {Atlstride0, Atlstride1, Atlstride2};

    int32_t Bslstride[] = {Bslstride0};
    int32_t Btlbound[] = {Btlbound0, Btlbound1, Btlbound2};
    int32_t Btlstride[] = {Btlstride0, Btlstride1, Btlstride2};

    int32_t SHslstride[] = {SHslstride0};
    int32_t SHtlbound[]  = {SHtlbound0, SHtlbound1, SHtlbound2};
    int32_t SHtlstride[] = {SHtlstride0, SHtlstride1, SHtlstride2};

    int32_t Oslstride[]  = {Oslstride0};
    int32_t Otlbound[]   = {Otlbound0, Otlbound1,Otlbound2};
    int32_t Otlstride[]  = {Otlstride0, Otlstride1, Otlstride2};

    // This assigns the tasks inside the condition
    // to the core controlling the accelerator
    if (snrt_is_compute_core()) {// can also be snrt_global_core_idx() == 0
        // This marks the start of the
        // setting of CSRs for the accelerator
        uint32_t start_csr_setup = snrt_mcycle();

        // Configure streamer settings

        //set_mx_streamer_csr
        set_mx_streamer_csr(
            delta_local_a,      Aslstride,  Atlbound,  Atlstride,
            delta_local_b,      Bslstride,  Btlbound,  Btlstride,
            delta_local_shared, SHslstride, SHtlbound, SHtlstride,
            delta_local_o,      Oslstride,  Otlbound,  Otlstride);

        // // temporal_stride = 8: advance one 64-bit block (8 bytes) per temporal step
        // // temporal_bound: per-reader iteration counts from data.h
        // //TODO: when temporal_dim is raised to 3, add separate strides/bounds for each loop level
        // configure_streamer_a(
        //     (uint32_t)(uintptr_t)local_a, 0,
        //     /*spatial_stride=*/1, A_LOOP_ITER, /*temporal_stride=*/8);

        // configure_streamer_b(
        //     (uint32_t)(uintptr_t)local_b, 0,
        //     /*spatial_stride=*/1, B_LOOP_ITER, /*temporal_stride=*/8);

        // // Share reader: one 64-bit block per k_block per output tile
        // //   byte 0: exp_A[m0][k_b], byte 1: exp_A[m1][k_b]
        // //   byte 2: exp_B[k_b][n0], byte 3: exp_B[k_b][n1], bytes 4-7: 0
        // configure_streamer_share(
        //     (uint32_t)(uintptr_t)local_shared, 0,
        //     /*spatial_stride=*/1, SHARE_LOOP_ITER, /*temporal_stride=*/8);

        // // Output writer: one beat per output tile (2 channels × 64-bit = 4 × uint32)
        // configure_streamer_o(
        //     (uint32_t)(uintptr_t)local_o, 0,
        //     /*spatial_stride=*/1, OUT_LOOP_ITER, /*temporal_stride=*/8);
        // printf("Streamer setup done...\n");

        // Configure ALU settings
        set_mx_csr(MODE, ACC_CNT, OUT_CNT);
        printf("CSR setup done...\n");

        // Start streamer then start ALU
        set_mx_streamer_start();
        printf("Streamer started...\n");
        set_mx_start();
        printf("mx Exercise started...\n");

        // Mark the end of the CSR setup cycles
        uint32_t end_csr_setup = snrt_mcycle();

        wait_mx_and_streamer();

        printf("checkpoint 2\n");

        //TODO: change the checking method for mx
        // Compare results and check if the
        // accelerator returns correct answers
        // For every incorrect answer, increment err
        for (uint32_t i = 0; i < OUT_LEN; i++) {
            uint32_t expected = OUT[i];
            uint32_t actual   = local_o[i];

            if (expected != actual) {
                // 用 %08x 打印，不足 8 位补零
                printf("Mismatch at index %d: expected 0x%08x, actual 0x%08x\n",
                       i, expected, actual);
                err++;
            }
        }

        // Read performance counter
        printf("======================================\n");
        printf("SNAX Exercise Accelerator Finished!\n");
        printf("Hardware Cycles: %d \n", read_perf_mx());
        if (err == 0) {
            printf(">>> SUCCESS! Golden model matched! <<<\n");
        } else {
            printf(">>> FAILED! Errors found: %d <<<\n", err);
        }
        printf("======================================\n");
    };

    return err;
}
