# Copyright 2026 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0

# 1. 改成你的 App 名字
APP     = snax-exercise

# 2. 告诉编译器去哪里找 data.h
INCDIRS = data

# 3. 告诉编译器去哪里找你的驱动头文件
INCDIRS += ../../snax/snax-exercise/include

# 4. 把你刚刚编译好的驱动 .o 文件链接进来
RISCV_LDFLAGS += ../../snax/snax-exercise/build/snax-exercise-lib.o

# 5. 指向你的主程序
SRCS    = src/snax-exercise.c

# 引入官方编译规则和你的数据生成 Makefile
include ../common.mk
include ./data/Makefile

# 强制先生成 data.h 再编译 C 代码
$(DEP): $(DATA_H)