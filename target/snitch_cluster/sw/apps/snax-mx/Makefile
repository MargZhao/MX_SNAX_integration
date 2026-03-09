# Copyright 2025 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# Zijun Zhao <zijun.zhao@student.kuleuven.be>

APP = snax-mx

INCDIRS = data \
          ../../snax/snax-mx-gemm/include

# Include this binary in the final build
RISCV_LDFLAGS += ../../snax/snax-mx-gemm/build/snax-mx-gemm-lib.o

SRCS = src/snax-mx.c

include ../common.mk
include ./data/Makefile

$(DEP): $(DATA_H)
