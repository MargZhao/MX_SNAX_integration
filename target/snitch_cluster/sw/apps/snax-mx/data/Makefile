# Copyright 2024 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# Zijun Zhao <zijun.zhao@student.kuleuven.be>

# Usage of absolute paths is required to externally include this Makefile
MK_DIR   := $(dir $(realpath $(lastword $(MAKEFILE_LIST))))
DATA_DIR := $(realpath $(MK_DIR))

# Set these as default values
MODE ?= 0 #TODO: replace with real settings
M ?= 4
N ?= 4
K ?= 4
PARFOR_M ?= 2
PARFOR_N ?= 2
PARFOR_K ?= 1
GROUP_SIZE ?= 32


# File paths
DATAGEN_PY = $(DATA_DIR)/datagen.py
DATA_H = $(DATA_DIR)/data.h

.PHONY: all
all: $(DATA_H)

.PHONY: clean clean-data

clean-data:
	rm -f $(DATA_H)

clean: clean-data

$(DATA_H): $(DATAGEN_PY)
	python3 $(DATAGEN_PY) \
		--mode=$(MODE) \
		--M=$(M) \
		--N=$(N) \
		--K=$(K) \
		--parfor_M=$(PARFOR_M) \
		--parfor_N=$(PARFOR_N) \
		--parfor_K=$(PARFOR_K) \
		--block_size=$(BLOCK_SIZE) > $@

