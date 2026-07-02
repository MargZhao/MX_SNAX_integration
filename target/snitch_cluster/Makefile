# Copyright 2020 ETH Zurich and University of Bologna.
# Solderpad Hardware License, Version 0.51, see LICENSE for details.
# SPDX-License-Identifier: SHL-0.51
#
# Fabian Schuiki <fschuiki@iis.ee.ethz.ch>
# Florian Zaruba <zarubaf@iis.ee.ethz.ch>

#######################
# Makefile invocation #
#######################

DEBUG            ?= OFF  # ON to turn on debugging symbols
CFG_OVERRIDE     ?=      # Override default config file
SELECT_RUNTIME   ?=      # Select snRuntime implementation: "banshee", "rtl-generic(default)" or "rtl" 
SELECT_TOOLCHAIN ?=      # Select Toolchain: "llvm-generic" = llvm17 generic (default) or "llvm-snitch" llvm 12 snitch_specific
 					     # Note that the default toolchain only works with some applications 
						 # since it requires the rtl-generic runtime

.DEFAULT_GOAL := help
.PHONY: all clean
all: sw
clean: clean-sw clean-work clean-vsim clean-vlt clean-vcs clean-syn clean-logs clean-bender clean-generated

##########
# Common #
##########

MKFILE_PATH := $(abspath $(lastword $(MAKEFILE_LIST)))
MKFILE_DIR  := $(dir $(MKFILE_PATH))
ROOT        := ${MKFILE_DIR}../..
SNITCH_ROOT := $(ROOT)
SYN_ROOT    := $(ROOT)
SNAX_CFG_PATH := $(ROOT)/target/snitch_cluster/cfg/
SNAX_TPL_PATH := $(ROOT)/hw/templates/
SNAX_TEST_PATH ?= $(ROOT)/target/snitch_cluster/test/
SNAX_CHISEL_ACC_PATH := $(ROOT)/hw/chisel_acc
SNAX_CHISEL_PATH := $(ROOT)/hw/chisel/
CFG_FILE = $(shell realpath $(CFG_OVERRIDE))

include $(ROOT)/target/common/common.mk

############
# Programs #
############

REGGEN          ?= $(shell $(BENDER) path register_interface)/vendor/lowrisc_opentitan/util/regtool.py
CLUSTER_GEN     ?= $(ROOT)/util/clustergen.py
CLUSTER_GEN_SRC ?= $(wildcard $(ROOT)/util/clustergen/*.py)
WRAPPER_GEN     ?= $(ROOT)/util/snaxgen/snaxgen.py

#########################
# Files and directories #
#########################

SW_DIR             = sw
TARGET_C_HDRS_DIR ?= $(SW_DIR)/runtime/common
GENERATED_DIR     ?= $(MKFILE_DIR)generated

####################
#  Default Values  #
####################
BYPASS_ACCGEN ?= false
DISABLE_HEADER_GEN ?= false
SNAX_CHISEL_ACC_GEN ?= false
CSRMAN_PARAM_SCALA_PATH = $(ROOT)/hw/chisel/src/main/scala/snax/csr_manager/CsrManParamGen.scala
STREAM_PARAM_SCALA_PATH = $(ROOT)/hw/chisel/src/main/scala/snax/streamer/StreamParamGen.scala

####################
# SNAX Generations #
####################

# Adds targets from the generated bender target file
VSIM_BENDER := $(VSIM_BENDER) $(shell cat $(GENERATED_DIR)/bender_targets.tmp)
VLT_BENDER  := $(VLT_BENDER)  $(shell cat $(GENERATED_DIR)/bender_targets.tmp)
VCS_BENDER  := $(VCS_BENDER)  $(shell cat $(GENERATED_DIR)/bender_targets.tmp)
SYN_BENDER  := $(SYN_BENDER)  $(shell cat $(GENERATED_DIR)/bender_targets.tmp)

# List manual conditions for different configurations


ifeq (${CFG_OVERRIDE}, cfg/snax_mac_mult_cluster.hjson)

	BYPASS_ACCGEN = true

endif

ifeq (${CFG_OVERRIDE}, cfg/snitch_cluster.hjson)

	BYPASS_ACCGEN = true

endif

SNAX_MX_ORCHESTRATOR ?= $(MKFILE_DIR)sw/apps/snax-mx/orchestrator.py
SNAX_MX_GEN_CFG      := $(MKFILE_DIR)cfg/snax_mx_cluster.hjson

ifeq (${CFG_OVERRIDE}, cfg/snax_mx_cluster.hjson)

	CFG_FILE := $(SNAX_MX_GEN_CFG)

.PHONY: snax-mx-datagen
snax-mx-datagen:
	python3 $(SNAX_MX_ORCHESTRATOR) \
		--hwcfg $(MKFILE_DIR)cfg/snax_mx_cluster_template.hjson \
		--genhw $(SNAX_MX_GEN_CFG) \
		--output $(MKFILE_DIR)sw/apps/snax-mx/data/data.h

# Include auto-generated RTL defines from orchestrator (e.g. +define+SCALE_OUTPUT_EN).
# Leading dash: silently ignored if orchestrator has not been run yet.
-include $(MKFILE_DIR)cfg/snax_mx_defines.mk

# bender_targets.tmp depends on CFG_FILE (the generated hjson),
# so orchestrator must run first (order-only prerequisite)
$(GENERATED_DIR)/bender_targets.tmp: | snax-mx-datagen

endif

.PHONY: rtl-snax-gen rtl-gen

rtl-snax-gen: | $(GENERATED_DIR)
	@echo "-------------------------------------------------------------"
	@echo "Generating streamers, CSR managers, wrappers, and testharness"
	@echo "-------------------------------------------------------------"

	${WRAPPER_GEN} --cfg_path="$(CFG_FILE)" \
		--tpl_path="${SNAX_TPL_PATH}" \
		--test_path="${SNAX_TEST_PATH}" \
		--chisel_path="${SNAX_CHISEL_PATH}" \
		--bypass_accgen="${BYPASS_ACCGEN}" \
		--disable_header_gen="${DISABLE_HEADER_GEN}" \
		--gen_path="${GENERATED_DIR}/"


rtl-cluster-gen: | $(GENERATED_DIR)
	@echo "-------------------------------------------------------------"
	@echo "Generating Cluster Wrapper"
	@echo "-------------------------------------------------------------"

	$(CLUSTER_GEN) -c ${CFG_OVERRIDE} -o $(GENERATED_DIR) --wrapper

# $(GENERATED_DIR)/bender_targets.tmp: | $(GENERATED_DIR)
# 	${WRAPPER_GEN} --cfg_path="$(CFG_FILE)" --get_bender_targets > $(GENERATED_DIR)/bender_targets.tmp
$(GENERATED_DIR)/bender_targets.tmp: $(CFG_FILE) ${WRAPPER_GEN} | $(GENERATED_DIR)
	${WRAPPER_GEN} --cfg_path="$(CFG_FILE)" --get_bender_targets > $(GENERATED_DIR)/bender_targets.tmp
	
rtl-gen: $(GENERATED_DIR)/bender_targets.tmp rtl-snax-gen rtl-cluster-gen

VSIM_BENDER += -t QUESTA_SIM

# Add these targets for 
# SNAX cluster simulations only
VSIM_BENDER += -t snax_test_harness
VLT_BENDER  += -t snax_test_harness
VCS_BENDER  += -t snax_test_harness

# Verilated and compiled snitch cluster
VLT_AR = ${VLT_BUILDDIR}/Vtestharness__ALL.a

# If the configuration file is overriden on the command-line (through
# CFG_OVERRIDE) and this file differs from the least recently used
# (LRU) config, all targets depending on the configuration file have
# to be rebuilt. This file is used to express this condition as a
# prerequisite for other rules.
DEFAULT_CFG = cfg/default.hjson
CFG         = cfg/lru.hjson

#####################
# Simulator options #
#####################

QUESTA_64BIT = -64
VLOG_64BIT   = -64

VSIM_FLAGS += ${QUESTA_64BIT}
VSIM_FLAGS += -t 1ps
# Note: Only use this for post-synth sim purposes
# the goal is also to have functional verification
# with annotated delays
# SDF_ANNOTATE_PATH = should point to the SDF file
# SDF_TOP = should point to the top-level instance
#           to where it should the annotations be placed
ifdef SDF_ANNOTATE_PATH
  ifdef SDF_TOP
    VSIM_FLAGS += -sdfmax /tb_bin/i_dut/${SDF_TOP}=$(SDF_ANNOTATE_PATH)
    VSIM_FLAGS += -sdfreport=${MKFILE_DIR}/${VSIM_BUILDDIR}/sdf_report.txt
    VSIM_FLAGS += -sdfnoerror
    VSIM_FLAGS += +notimingchecks
  else
    $(error "Error! Missing SDF_TOP")
  endif
endif
VSIM_FLAGS += -do "log -r /*; run -a"

VLOG_FLAGS += -svinputport=compat
VLOG_FLAGS += -override_timescale 1ns/1ps
VLOG_FLAGS += -suppress 2583
VLOG_FLAGS += -suppress 13314
VLOG_FLAGS += ${VLOG_64BIT}

ifeq ($(MEM_TYPE), exclude_tcsram)
	VLOG_FLAGS += +define+TSMC_INITIALIZE_MEM_USING_DEFAULT_TASKS
	VLOG_FLAGS += +define+TSMC_MEM_LOAD_0
	VLOG_FLAGS += +define+TSMC_CM_UNIT_DELAY
endif 

VLT_FLAGS  += --trace

###############
# C testbench #
###############

TB_CC_SOURCES += \
	${TB_DIR}/bootrom.cc \
	${TB_DIR}/ipc.cc \
	${TB_DIR}/rtl_lib.cc \
	${TB_DIR}/common_lib.cc \
	$(GENERATED_DIR)/bootdata.cc

TB_CC_FLAGS += \
	-std=c++17 \
	-I${MKFILE_DIR}/test \
	-I${FESVR}/include \
	-I${TB_DIR}

# Required C sources for the verilator TB that are linked against the verilated model
VLT_COBJ += $(VLT_BUILDDIR)/tb/bootrom.o
VLT_COBJ += $(VLT_BUILDDIR)/tb/ipc.o
VLT_COBJ += $(VLT_BUILDDIR)/tb/common_lib.o
VLT_COBJ += $(VLT_BUILDDIR)/tb/verilator_lib.o
VLT_COBJ += $(VLT_BUILDDIR)/tb/tb_bin.o
# Sources from verilator root
VLT_COBJ += $(VLT_BUILDDIR)/vlt/verilated.o
VLT_COBJ += $(VLT_BUILDDIR)/vlt/verilated_dpi.o
VLT_COBJ += $(VLT_BUILDDIR)/vlt/verilated_vcd_c.o
ifeq ($(VERILATOR_VERSION), 5)
	VLT_COBJ += $(VLT_BUILDDIR)/vlt/verilated_timing.o
	VLT_COBJ += $(VLT_BUILDDIR)/vlt/verilated_threads.o
endif
# Bootdata
VLT_COBJ += $(VLT_BUILDDIR)/generated/bootdata.o

#################
# Prerequisites #
#################

CLUSTER_GEN_PREREQ = ${CLUSTER_GEN} ${CLUSTER_GEN_SRC}

# This target is always evaluated and creates a symlink to the least
# recently used config file. Because it is a symlink, targets to which it is a
# prerequisite will only be updated if the symlink target is newer than the
# depending targets, regardless of the symlink timestamp itself. The symlink
# timestamp can be taken into account by using the `make -L` flag on the
# command-line, however for simplicity we touch the symlink targets so it can
# be used without.
$(CFG): FORCE
	@# If the LRU config file doesn't exist, we use the default config.
	@if [ ! -e "$@" ] ; then \
		echo "Using default config file: $(DEFAULT_CFG) $@"; \
		ln -s --relative $(DEFAULT_CFG) $@; \
		touch $(DEFAULT_CFG); \
	fi
	@# If a config file is provided on the command-line and the LRU
	@# config file doesn't point to it already, then we make it point to it
	@if [ $(CFG_OVERRIDE) ] ; then \
		echo "Overriding config file with: $(CFG_OVERRIDE)"; \
		target=$$(readlink -f $@); \
		if [ "$$target" = "$(abspath $(CFG_OVERRIDE))" ] ; then \
			echo "LRU config file already points to $(CFG_OVERRIDE). Nothing to be done."; \
		else \
			rm -f $@; \
			ln -s --relative $(CFG_OVERRIDE) $@; \
			touch $(CFG_OVERRIDE); \
		fi \
	fi
FORCE:

test/bootrom.elf test/bootrom.dump test/bootrom.bin: test/bootrom.S test/bootrom.ld
	riscv64-unknown-elf-gcc -mabi=ilp32d -march=rv32imafd -static -nostartfiles -Ttest/bootrom.ld $< -o test/bootrom.elf
	riscv64-unknown-elf-objdump -d test/bootrom.elf > test/bootrom.dump
	riscv64-unknown-elf-objcopy -j .text -O binary test/bootrom.elf test/bootrom.bin


############
# Software #
############

CLUSTER_GEN_HEADERS = snitch_cluster_cfg.h \
					  snitch_cluster_addrmap.h

REGGEN_HEADERS = snitch_cluster_peripheral.h

TARGET_C_HDRS = $(addprefix $(TARGET_C_HDRS_DIR)/,$(CLUSTER_GEN_HEADERS) $(REGGEN_HEADERS))

.PHONY: sw clean-headers clean-sw

sw: $(TARGET_C_HDRS)
	$(MAKE) -C $(SW_DIR) CFG_OVERRIDE=$(CFG_OVERRIDE)

clean-headers:
	rm -f $(TARGET_C_HDRS)

clean-sw: clean-headers
	$(MAKE) -C $(SW_DIR) TARGET=clean

# CLUSTERGEN headers
$(addprefix $(TARGET_C_HDRS_DIR)/,$(CLUSTER_GEN_HEADERS)): %.h: $(CFG) $(CLUSTER_GEN_PREREQ) %.h.tpl
	@echo "[CLUSTERGEN] Generate $@"
	$(CLUSTER_GEN) -c $< --outdir $(TARGET_C_HDRS_DIR) --template $@.tpl

# REGGEN headers
$(TARGET_C_HDRS_DIR)/snitch_cluster_peripheral.h: $(ROOT)/hw/snitch_cluster/src/snitch_cluster_peripheral/snitch_cluster_peripheral_reg.hjson $(REGGEN)
	$(call reggen_generate_header,$@,$<)

#####################
# Boot and Memories #
#####################
$(GENERATED_DIR):
	mkdir -p $(GENERATED_DIR)

$(GENERATED_DIR)/link.ld: ${CFG} |  ${CLUSTER_GEN_PREREQ} $(GENERATED_DIR)
	$(CLUSTER_GEN) -c $< -o $(GENERATED_DIR) --linker

$(GENERATED_DIR)/memories.json: ${CFG} |  ${CLUSTER_GEN_PREREQ} $(GENERATED_DIR)
	$(CLUSTER_GEN) -c $< -o $(GENERATED_DIR) --memories

$(GENERATED_DIR)/bootdata.cc: ${CFG} |  ${CLUSTER_GEN_PREREQ} $(GENERATED_DIR)
	$(CLUSTER_GEN) -c $< -o $(GENERATED_DIR) --bootdata

#############
# Verilator #
#############

.PHONY: clean-vlt

# Clean all build directories and temporary files for Questasim simulation
clean-vlt: clean-work
	rm -rf bin $(VLT_BUILDDIR)

$(VLT_AR): ${VLT_SOURCES} ${TB_SRCS}
	+$(call VERILATE,testharness)
verilate: $(VLT_AR)

# Build targets for verilator TB
$(VLT_BUILDDIR)/tb/%.o: $(TB_DIR)/%.cc $(VLT_AR) ${VLT_BUILDDIR}/lib/libfesvr.a
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(VLT_CFLAGS) -c $< -o $@
$(VLT_BUILDDIR)/vlt/%.o: $(VLT_ROOT)/include/%.cpp
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(VLT_CFLAGS) -c $< -o $@
$(VLT_BUILDDIR)/generated/%.o: $(GENERATED_DIR)/%.cc ${VLT_BUILDDIR}/lib/libfesvr.a
	mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(VLT_CFLAGS) -c $< -o $@

# Build compilation script and compile all sources for Verilator simulation
# Link verilated archive with $(VLT_COBJ)
bin/snitch_cluster.vlt: $(VLT_AR) $(VLT_COBJ) ${VLT_BUILDDIR}/lib/libfesvr.a
	mkdir -p $(dir $@)
	$(CXX) $(LDFLAGS) $(VLT_CXXSTD_FLAGS) -L ${VLT_BUILDDIR}/lib -o $@ $(VLT_COBJ) $(VLT_AR) -lfesvr 

############
# Modelsim #
############

.PHONY: clean-vsim

# Clean all build directories and temporary files for Questasim simulation
clean-vsim: clean-work
	rm -rf bin/snitch_cluster.vsim bin/snitch_cluster.vsim.gui $(VSIM_BUILDDIR) vsim.wlf

${VSIM_BUILDDIR}/compile.vsim.tcl:
	mkdir $(dir $@)
	${BENDER} script vsim ${VSIM_BENDER} --vlog-arg="${VLOG_FLAGS} -work $(dir $@) " > $@
	echo '${VLOG} -work $(dir $@) ${TB_CC_SOURCES} ${TB_ASM_SOURCES} -vv -ccflags "$(TB_CC_FLAGS)"' >> $@
	echo 'return 0' >> $@

vsim_preparation: $(GENERATED_DIR)/bootdata.cc work/lib/libfesvr.a ${VSIM_BUILDDIR}/compile.vsim.tcl
	@echo "VSIM preparation done"

# Build compilation script and compile all sources for Questasim simulation
bin/snitch_cluster.vsim: ${VSIM_BUILDDIR}/compile.vsim.tcl $(VSIM_SOURCES) ${TB_SRCS} ${TB_CC_SOURCES} ${TB_ASM_SOURCES} work/lib/libfesvr.a
	$(VLIB) $(VSIM_BUILDDIR)
	$(call QUESTASIM,tb_bin)

#######
# VCS #
#######

.PHONY: clean-vcs

# Clean all build directories and temporary files for VCS simulation
clean-vcs: clean-work
	rm -rf bin/snitch_cluster.vcs $(VCS_BUILDDIR) vc_hdrs.h

# Build compilation script and compile all sources for VCS simulation
bin/snitch_cluster.vcs: ${VCS_SOURCES} ${TB_SRCS} $(TB_CC_SOURCES) $(TB_ASM_SOURCES) $(VCS_BUILDDIR)/compile.sh work/lib/libfesvr.a
	mkdir -p bin
	$(VCS) -Mlib=$(VCS_BUILDDIR) -Mdir=$(VCS_BUILDDIR) -o bin/snitch_cluster.vcs -cc $(CC) -cpp $(CXX) \
		-assert disable_cover -override_timescale=1ns/1ps -full64 tb_bin $(TB_CC_SOURCES) $(TB_ASM_SOURCES) \
		-CFLAGS "$(TB_CC_FLAGS)" -LDFLAGS "-L${FESVR}/lib" -lfesvr

###############
# DC Compiler #
###############

.PHONY: gen-syn clean-syn gen-mem-cfg package-syn

gen-syn:
	mkdir -p $(SYN_BUILDDIR)
	${BENDER} script synopsys ${SYN_BENDER} > $(SYN_BUILDDIR)/$(SYN_FLIST)
	sed -i '2c\set ROOT "$(SYN_ROOT)"' $(SYN_BUILDDIR)/$(SYN_FLIST)
	chmod +x $(SYN_BUILDDIR)/$(SYN_FLIST)

gen-mem-cfg:
	mkdir -p $(SYN_BUILDDIR)
	$(CLUSTER_GEN) -c ${CFG_OVERRIDE} -o $(SYN_BUILDDIR) --mem

# Self-contained synthesis source package for cross-environment transfer via git.
# The synthesis host needs neither Bender nor Docker: it just unpacks and runs DC.
#   work-syn/pickle/                       staged tree: rtl/ + pickle.f + run_area.tcl
#   syn_export/snax_mx_cluster_syn.tar.gz  committed bundle (tracked, git-transferable)
# Consume on the DC host:  tar xzf <tar> && cd pickle && dc_shell -f run_area.tcl
SYN_PKG_DIR    := $(SYN_BUILDDIR)/pickle
SYN_EXPORT_DIR := $(MKFILE_DIR)syn_export
SYN_PKG_TAR    := $(SYN_EXPORT_DIR)/snax_mx_cluster_syn.tar.gz
SYN_DC_SCRIPT  := $(MKFILE_DIR)syn/run_area.tcl

package-syn: PKG_ROOT := $(abspath $(ROOT))
package-syn: $(GENERATED_DIR)/bender_targets.tmp
	rm -rf $(SYN_PKG_DIR)
	mkdir -p $(SYN_PKG_DIR)/rtl $(SYN_EXPORT_DIR)
	${BENDER} script flist-plus ${SYN_BENDER} > $(SYN_PKG_DIR)/pickle.f
	@grep '^/' $(SYN_PKG_DIR)/pickle.f | while read f; do \
		rel=$${f#$(PKG_ROOT)/}; dst=$(SYN_PKG_DIR)/rtl/$$rel; \
		mkdir -p "$$(dirname "$$dst")"; cp "$$f" "$$dst"; \
	done
	@grep '^+incdir+' $(SYN_PKG_DIR)/pickle.f | sed 's/^+incdir+//' | sort -u | while read d; do \
		rel=$${d#$(PKG_ROOT)/}; dst=$(SYN_PKG_DIR)/rtl/$$rel; \
		mkdir -p "$$dst"; cp -r "$$d"/. "$$dst"/; \
	done
	sed -i 's|^+incdir+$(PKG_ROOT)/|+incdir+rtl/|; s|^$(PKG_ROOT)/|rtl/|' $(SYN_PKG_DIR)/pickle.f
	cp $(SYN_DC_SCRIPT) $(SYN_PKG_DIR)/run_area.tcl
	tar czf $(SYN_PKG_TAR) -C $(SYN_BUILDDIR) pickle
	@echo "---"
	@du -sh $(SYN_PKG_DIR) $(SYN_PKG_TAR)
	@echo "Committed bundle: $(SYN_PKG_TAR)"

# ---------------------------------------------------------------------------
# Per-run bundle: the full system RTL (synthesis/DUT view — same scope as
# package-syn and the KULeuven Precision-Scalable_MX hw/ tree, just automated
# via `bender script flist-plus`) plus THIS run's sim VCD and sw config, in one
# self-contained folder for power/energy analysis. One folder per run_thru.
#   run_bundles/<label>_<stamp>/rtl/       flattened, self-contained system RTL
#   run_bundles/<label>_<stamp>/pickle.f   relative-path filelist (compile order)
#   run_bundles/<label>_<stamp>/sim.vcd    switching activity from this run
#   run_bundles/<label>_<stamp>/{data.h,params.hjson,snax_mx_cluster.hjson,manifest.txt}
# VCD path overridable: `make package-run RUN_VCD=/path/to/sim.vcd`.
RUN_BUNDLE_ROOT := $(MKFILE_DIR)run_bundles
# Local ext4 VCD (NOT git-transferred; converted to SAIF for the bundle).
RUN_VCD         ?= /tmp/snax_run.vcd
RUN_PARAMS      := $(MKFILE_DIR)sw/apps/snax-mx/data/params.hjson
RUN_DATA_H      := $(MKFILE_DIR)sw/apps/snax-mx/data/data.h
RUN_GEN_CFG     := $(MKFILE_DIR)cfg/snax_mx_cluster.hjson
RUN_LABEL_PY    := $(MKFILE_DIR)sw/apps/snax-mx/run_label.py
# Tracked dir for git-transferable bundle tarballs.
RUN_EXPORT_DIR  := $(MKFILE_DIR)run_export
# NOTE: the bundle keeps the FULL-cluster VCD (gzipped) so a downstream per-block
# power breakdown (SPM/MX/streamer/DMA/CPU/ICache) sees every module. Windowing
# happens at SIM time via SNAX_VCD_START_CYCLE/STOP_CYCLE (see run_thru.sh), NOT
# here — package-run only gzips whatever VCD it is given.
# For provenance, pass the window that produced RUN_VCD so it's recorded in the
# manifest:  make package-run RUN_WIN_START=6000 RUN_WIN_STOP=18257
RUN_WIN_START   ?=
RUN_WIN_STOP    ?=

.PHONY: package-run
package-run: PKG_ROOT := $(abspath $(ROOT))
package-run: $(GENERATED_DIR)/bender_targets.tmp
	@label=`python3 $(RUN_LABEL_PY) $(RUN_PARAMS)`; \
	stamp=`date +%Y%m%d_%H%M%S`; dst=$(RUN_BUNDLE_ROOT)/$${label}_$${stamp}; \
	echo "[package-run] bundling → $$dst"; \
	rm -rf "$$dst"; mkdir -p "$$dst/rtl"; \
	$(BENDER) script flist-plus $(SYN_BENDER) > "$$dst/pickle.f"; \
	grep '^/' "$$dst/pickle.f" | while read f; do \
	  rel=$${f#$(PKG_ROOT)/}; o="$$dst/rtl/$$rel"; mkdir -p "`dirname "$$o"`"; cp "$$f" "$$o"; \
	done; \
	grep '^+incdir+' "$$dst/pickle.f" | sed 's/^+incdir+//' | sort -u | while read i; do \
	  rel=$${i#$(PKG_ROOT)/}; o="$$dst/rtl/$$rel"; mkdir -p "$$o"; cp -r "$$i"/. "$$o"/ 2>/dev/null || true; \
	done; \
	sed -i 's|^+incdir+$(PKG_ROOT)/|+incdir+rtl/|; s|^$(PKG_ROOT)/|rtl/|' "$$dst/pickle.f"; \
	cp $(RUN_PARAMS) "$$dst/params.hjson"; \
	[ -f $(MKFILE_DIR)sw/apps/snax-mx/SYNTHESIS_AGENT_GUIDE.md ] && \
	  cp $(MKFILE_DIR)sw/apps/snax-mx/SYNTHESIS_AGENT_GUIDE.md "$$dst/SYNTHESIS_AGENT_GUIDE.md" || true; \
	[ -f $(RUN_DATA_H) ]  && cp $(RUN_DATA_H)  "$$dst/data.h" || true; \
	[ -f $(RUN_GEN_CFG) ] && cp $(RUN_GEN_CFG) "$$dst/snax_mx_cluster.hjson" || true; \
	if [ -f "$(RUN_VCD)" ]; then \
	    echo "[package-run] gzip VCD (`du -h "$(RUN_VCD)" | cut -f1`) → sim.vcd.gz ..."; \
	    gzip -c "$(RUN_VCD)" > "$$dst/sim.vcd.gz"; \
	    gz=`du -m "$$dst/sim.vcd.gz" | cut -f1`; \
	    echo "[package-run] sim.vcd.gz = $${gz} MB"; \
	    if [ "$$gz" -gt 100 ]; then \
	      echo "[package-run][WARN] $${gz} MB > GitHub 100 MB/file limit — window the VCD"; \
	      echo "[package-run][WARN]   (SNAX_VCD_STOP_CYCLE/START_CYCLE) or use git-LFS / a self-hosted remote."; \
	    fi; \
	  else echo "[package-run][warn] VCD not found: $(RUN_VCD) — skipped (run sim with --vcd first)"; fi; \
	{ echo "label     : $$label"; echo "stamp     : $$stamp"; \
	  echo "git       : `git -C $(ROOT) rev-parse --short HEAD 2>/dev/null`"; \
	  echo "cfg       : $(CFG_OVERRIDE)"; \
	  echo "rtl_files  : `grep -c '^rtl/' "$$dst/pickle.f"`"; \
	  echo "vcd_gz_MB  : `du -m "$$dst/sim.vcd.gz" 2>/dev/null | cut -f1 || echo NA`"; \
	  echo "vcd_window : cycles [$(if $(RUN_WIN_START),$(RUN_WIN_START),0), $(if $(RUN_WIN_STOP),$(RUN_WIN_STOP),END)] (SIM-time; printf verification excluded)"; \
	  echo "--- params.hjson ---"; cat $(RUN_PARAMS); } > "$$dst/manifest.txt"; \
	{ echo "# Power bundle: full system RTL + gzipped sim VCD (sim.vcd.gz)."; \
	  echo "# On the DC/PT-PX host:"; \
	  echo "#   gunzip sim.vcd.gz"; \
	  echo "#   vcd2saif -input sim.vcd -output activity.saif   # vendor tool"; \
	  echo "#   read_saif activity.saif -instance <path>        # (or PT-PX read_vcd sim.vcd)"; \
	  echo "# NOTE: git rejects files >100 MB (GitHub). If sim.vcd.gz is over,"; \
	  echo "#   window the sim (SNAX_VCD_STOP_CYCLE) or use git-LFS."; \
	  echo "# RTL filelist: pickle.f (paths relative to rtl/).";  } > "$$dst/README.txt"; \
	mkdir -p $(RUN_EXPORT_DIR); \
	tar czf "$(RUN_EXPORT_DIR)/$${label}_$${stamp}.tar.gz" -C $(RUN_BUNDLE_ROOT) "$${label}_$${stamp}"; \
	echo "---"; du -sh "$$dst" "$(RUN_EXPORT_DIR)/$${label}_$${stamp}.tar.gz"; \
	echo "[package-run] folder : $$dst"; \
	echo "[package-run] git tar: $(RUN_EXPORT_DIR)/$${label}_$${stamp}.tar.gz  (commit this to transfer)"

clean-syn: clean-work
	rm -rf $(SYN_BUILDDIR)

########
# Util #
########

.PHONY: clean-work clean-bender clean-logs help

clean-work:
	rm -rf work

clean-bender:
	rm -rf $(ROOT)/Bender.lock $(ROOT)/.bender/

clean-logs:
	rm -rf $(LOGS_DIR)

clean-generated:
	rm -rf $(GENERATED_DIR) $(SNAX_TEST_PATH)testharness.sv $(CSRMAN_PARAM_SCALA_PATH) $(STREAM_PARAM_SCALA_PATH) $(CHISEL_GENERATED_DIR)

# Help command
Blue=\033[1;34m
Black=\033[0m
help:
	@echo -e "${Blue}Makefile Targets${Black} for the ${Blue}Snitch Cluster${Black}"
	@echo -e "Use 'make <target>' where <target> is one of:"
	@echo -e ""
	@echo -e "${Blue}help           ${Black}Show an overview of all Makefile targets."
	@echo -e ""
	@echo -e "${Blue}bin/snitch_cluster.vcs  ${Black}Build compilation script and compile all sources for VCS simulation."
	@echo -e "${Blue}bin/snitch_cluster.vlt  ${Black}Build compilation script and compile all sources for Verilator simulation."
	@echo -e "${Blue}bin/snitch_cluster.vsim ${Black}Build compilation script and compile all sources for Questasim simulation."
	@echo -e ""
	@echo -e "${Blue}sw               ${Black}Build all software."
	@echo -e ""
	@echo -e "${Blue}clean            ${Black}Clean everything except traces in logs directory."
	@echo -e "${Blue}clean-bender     ${Black}Clean Bender dependencies."
	@echo -e "${Blue}clean-sw         ${Black}Clean all software."
	@echo -e "${Blue}clean-generated  ${Black}Delete all generated files in the generated directory."
	@echo -e "${Blue}clean-logs       ${Black}Delete all traces in logs directory."
	@echo -e "${Blue}clean-vcs        ${Black}Clean all build directories and temporary files for VCS simulation."
	@echo -e "${Blue}clean-vlt        ${Black}Clean all build directories and temporary files for Verilator simulation."
	@echo -e "${Blue}clean-vsim       ${Black}Clean all build directories and temporary files for Questasim simulation."
	@echo -e ""
	@echo -e "Additional useful targets from the included Makefrag:"
	@echo -e "${Blue}traces           ${Black}Generate the better readable traces in .logs/trace_hart_<hart_id>.txt with spike-dasm."
	@echo -e "${Blue}annotate         ${Black}Annotate the better readable traces in .logs/trace_hart_<hart_id>.s with the source code related with the retired instructions."
