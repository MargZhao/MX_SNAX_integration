#==============================================================================
# SNAX MX cluster - area breakdown synthesis script (Synopsys Design Compiler)
#==============================================================================
# This script is shipped inside the self-contained package produced by
#   make CFG_OVERRIDE=cfg/snax_mx_cluster.hjson package-syn
#
# Usage on the synthesis host (no Bender / Docker needed there):
#   tar xzf snax_mx_cluster_syn.tar.gz
#   cd pickle
#   # 1) point DC at your PDK standard-cell .db (edit the library section below)
#   # 2) choose SRAM handling via BLACKBOX_SRAM
#   dc_shell -f run_area.tcl
#
# All paths in pickle.f are relative to this directory, so run DC from here.
#==============================================================================

#---------------------------- user knobs --------------------------------------
set DESIGN_TOP    snax_mx_cluster_wrapper
set FLIST         pickle.f
set RPT_DIR       reports

# SRAM / SPM handling -- see the table in the package notes:
#   1 = treat tc_sram (SPM banks) as BLACK BOX. DC reports accurate *logic*
#       area; add the SRAM macro area separately from your memory-compiler
#       datasheet. This is the realistic ASIC number.
#   0 = synthesize tc_sram to flip-flops. Self-contained, but SPM area is
#       ~10x inflated and dominates the report (only logic ratios are useful).
set BLACKBOX_SRAM 1

#---------------------------- technology libraries ----------------------------
# >>> EDIT for your PDK before running <<<
#   set target_library  "your_stdcell_typ.db"
#   set link_library    [list * your_stdcell_typ.db]
if {![info exists target_library] || $target_library eq {}} {
  puts "WARNING: target_library is empty -- set your PDK .db before compile_ultra."
}

#---------------------------- parse the flist ---------------------------------
set incdirs {}
set defs    {}
set files   {}
set fh [open $FLIST r]
while {[gets $fh line] >= 0} {
  set line [string trim $line]
  if {$line eq ""} { continue }
  if {[string match {+incdir+*} $line]} {
    lappend incdirs [string range $line 8 end]
  } elseif {[string match {+define+*} $line]} {
    lappend defs [string range $line 8 end]
  } else {
    if {$BLACKBOX_SRAM && [string match {*tc_sram*} $line]} { continue }
    lappend files $line
  }
}
close $fh
puts "INFO: [llength $files] sources, [llength $incdirs] incdirs, BLACKBOX_SRAM=$BLACKBOX_SRAM"

#---------------------------- analyze + elaborate -----------------------------
set search_path [concat $search_path $incdirs]
if {$BLACKBOX_SRAM} {
  # leave unresolved tc_sram references as empty black boxes instead of erroring
  set_app_var hdlin_unresolved_modules black_box
}
analyze -format sverilog -define $defs $files
elaborate $DESIGN_TOP
current_design $DESIGN_TOP
link

#---------------------------- compile -----------------------------------------
# Quick area estimate. Replace with your real constraints/flow as needed.
compile_ultra -no_autoungroup

#---------------------------- area breakdown ----------------------------------
file mkdir $RPT_DIR

# Full per-instance hierarchy (the authoritative breakdown).
report_area -hierarchy -nosplit > $RPT_DIR/area_hierarchy.rpt

# Per-block totals: sum area over every instance whose module matches a pattern,
# so all replicated cores/banks are aggregated into one number.
proc area_by_ref {label pat} {
  set cells [get_cells -quiet -hier -filter "ref_name =~ $pat"]
  set n [sizeof_collection $cells]
  set tot 0
  if {$n > 0} {
    foreach_in_collection c $cells { set tot [expr {$tot + [get_attribute $c area]}] }
  }
  return [format "%-14s %-32s n=%-5d area=%.1f" $label $pat $n $tot]
}

set bf [open $RPT_DIR/area_blocks.rpt w]
puts $bf "# block        ref_name pattern                 count  total area"
puts $bf [area_by_ref CPU          {snitch_cc*}]
puts $bf [area_by_ref CPU-int      {snitch}]
puts $bf [area_by_ref CPU-fpu      {snitch_fp_ss*}]
puts $bf [area_by_ref ICache       {snitch_icache*}]
puts $bf [area_by_ref DMA          {*dma*}]
puts $bf [area_by_ref SPM-bank     {tc_sram*}]
puts $bf [area_by_ref TCDM-xbar    {*tcdm_interconnect*}]
puts $bf [area_by_ref MXcore       {*snax_mx_alu*}]
puts $bf [area_by_ref DataStreamer {*Streamer*}]
puts $bf [area_by_ref CSRman       {*csr_manager*}]
close $bf

puts "DONE. See $RPT_DIR/area_hierarchy.rpt and $RPT_DIR/area_blocks.rpt"
