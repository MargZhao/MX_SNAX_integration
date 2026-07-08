# End-to-end: gen RTL+data -> build -> sim (windowed VCD) -> package bundle.
#
# ── VCD windowing (applied at SIM time; package-run only gzips what's dumped) ──
# The post-compute printf verification is ~98.7% of the sim — a testbench
# artifact, NOT real workload. VCD_STOP cuts that tail; VCD_START optionally
# drops one-time boot. Cycle numbers are CONFIG-SPECIFIC (M/K/N/dtype shift them
# — re-derive from a first unwindowed run's trace / the phase table).
#
#   Full-system energy breakdown (SPM/MX/streamer/DMA/CPU/ICache, like Fig.8):
#     keep FULL cluster (package-run gzips the whole VCD, no scope) AND include
#     the DMA-load + setup phases → set VCD_START=0 (or the DMA-load start to
#     drop one-time boot) and VCD_STOP just past streamer-drain.
#   Accelerator-only (peak compute power): VCD_START=<MX start> VCD_STOP=<MX done>.
#
# This config (fp8 32x64x32): boot 0-6k, DMA 6k-6.8k, CSR 6.8k-9.9k,
#   compute 11.7k-15.6k, drain ->18.3k, printf 18.3k -> 1.44M.
#: "${VCD_START:=0}"        # start cycle (0 = power-on; set ~6000 to drop boot)
#: "${VCD_STOP:=50000}"     # stop cycle — margin over compute+drain, cuts printf tail
#: "${VLT_JOBS:=2}"         # verilator C++ build parallelism (2 avoids OOM on 16GB hosts)

make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson rtl-gen
make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson bin/snitch_cluster.vlt VLT_JOBS=$VLT_JOBS -j$(nproc)
make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson sw -j$(nproc)
# VCD -> native ext4 (/tmp), NOT /workspaces v9fs which corrupts multi-GB files.
#SNAX_VCD_PATH=/tmp/snax_run.vcd SNAX_VCD_START_CYCLE=$VCD_START SNAX_VCD_STOP_CYCLE=$VCD_STOP \
./target/snitch_cluster/bin/snitch_cluster.vlt ./target/snitch_cluster/sw/apps/snax-mx/build/snax-mx.elf --vcd
#make -C target/snitch_cluster CFG_OVERRIDE=cfg/snax_mx_cluster.hjson package-run \
#  RUN_VCD=/tmp/snax_run.vcd RUN_WIN_START=$VCD_START RUN_WIN_STOP=$VCD_STOP
  