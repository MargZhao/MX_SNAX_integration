// Copyright 2024 KU Leuven.
// Licensed under the Apache License, Version 2.0, see LICENSE for details.
// SPDX-License-Identifier: Apache-2.0

#pragma once

#include "streamer_csr_addr_map.h"
#include <stdint.h>

// MX Accelerator CSRs (start at STREAMER_PERFORMANCE_COUNTER_CSR + 1 )
#define MX_CSR_BASE       (STREAMER_PERFORMANCE_COUNTER_CSR + 1)
#define MX_CSR_MODE       (MX_CSR_BASE + 0)   // rw: accumulator mode
#define MX_CSR_ACC_COUNT  (MX_CSR_BASE + 1)   // rw: k_blocks per output
#define MX_CSR_OUT_COUNT  (MX_CSR_BASE + 2)   // rw: total output tiles
#define MX_CSR_START      (MX_CSR_BASE + 3)   // rw: write 1 to start
#define MX_CSR_BUSY       (MX_CSR_BASE + 4)   // ro: 1 while running
#define MX_CSR_PERF_COUNT (MX_CSR_BASE + 5)   // ro: cycle counter

// Streamer control
void configure_streamer_a(uint32_t base_ptr_low, uint32_t base_ptr_high,
                           uint32_t spatial_stride, uint32_t temporal_bound,
                           uint32_t temporal_stride);
void configure_streamer_b(uint32_t base_ptr_low, uint32_t base_ptr_high,
                           uint32_t spatial_stride, uint32_t temporal_bound,
                           uint32_t temporal_stride);
void configure_streamer_share(uint32_t base_ptr_low, uint32_t base_ptr_high,
                               uint32_t spatial_stride, uint32_t temporal_bound,
                               uint32_t temporal_stride);
void configure_streamer_o(uint32_t base_ptr_low, uint32_t base_ptr_high,
                           uint32_t spatial_stride, uint32_t temporal_bound,
                           uint32_t temporal_stride);
void start_streamer(void);
uint32_t read_busy_streamer(void);

// MX accelerator control
void configure_mx(uint32_t mode, uint32_t acc_count, uint32_t out_count);
void start_mx(void);
uint32_t read_busy_mx(void);
uint32_t read_perf_mx(void);
