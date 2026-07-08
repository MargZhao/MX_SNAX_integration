#!/usr/bin/env python3
# Copyright 2024 KU Leuven.
# Licensed under the Apache License, Version 2.0, see LICENSE for details.
# SPDX-License-Identifier: Apache-2.0
#
# vcd2saif.py — minimal VCD → backward-SAIF converter for PrimeTime PX / DC.
#
# This container has no Synopsys `vcd2saif`, and a raw VCD is far too large to
# move via git (GitHub caps files at 100 MB). This emits a compact,
# git-transferable SAIF: per-net T0/T1/TX/TC aggregated over the VCD's time span
# (the Verilator TB already windows the dump). PrimeTime PX / DC read it via
#   read_saif activity.saif -instance <path> -scope <su>
#
# Notes / scope:
#   * Vectors are bit-blasted (foo[3]..foo[0]) so names map onto a synthesized,
#     bit-blasted gate netlist.
#   * --scope keeps only nets under a hierarchy (e.g. the accelerator subtree),
#     matching your DC synthesis scope — cheap "scoping" without Verilator
#     pragmas, and it keeps the SAIF small.
#   * z is folded into X (SAIF has no high-Z state). Real ($var real) nets are
#     skipped (not power-relevant).
#   * This is a best-effort converter; cross-check against the real vendor
#     vcd2saif on the DC host if in doubt.
#
# Usage:
#   vcd2saif.py in.vcd out.saif [--scope testharness/i_dut/.../i_snax] \
#               [--top-instance TOP]

import sys
import re
import argparse


def parse_args():
    ap = argparse.ArgumentParser(description="VCD → backward SAIF")
    ap.add_argument("vcd")
    ap.add_argument("saif")
    ap.add_argument("--scope", default="",
                    help="keep only nets under this '/'- or '.'-separated "
                         "hierarchy prefix (default: whole design)")
    ap.add_argument("--top-instance", default="",
                    help="name of the SAIF top INSTANCE (default: VCD top scope)")
    return ap.parse_args()


def norm(v):
    """Normalise a VCD level char to {'0','1','x'} (z→x, upper→lower)."""
    v = v.lower()
    return v if v in ("0", "1") else "x"


def scope_matches(sc, filt):
    """True if `filt` occurs as a contiguous run anywhere in scope tuple `sc`
    (i.e. the net is at/below that module) — lets --scope take just the
    accelerator module name instead of the full path from the root."""
    if not filt:
        return True
    n = len(filt)
    return any(sc[i:i + n] == filt for i in range(len(sc) - n + 1))


def main():
    args = parse_args()
    scope_filter = tuple(t for t in args.scope.replace(".", "/").split("/") if t)

    # ── Pass 1: header (scopes + vars) ───────────────────────────────────────
    # id -> {"w":width, "cur":[...], "last":[...], "t0/t1/tx/tc":[...]}
    state = {}
    # id -> list of (scope_tuple, base_name, msb, lsb)  (aliases share an id)
    names = {}
    scope_stack = []
    timescale = "1ns"
    top_scope = None

    f = open(args.vcd, "r", errors="replace")
    line = f.readline()
    while line:
        s = line.strip()
        if s.startswith("$timescale"):
            # value may be on the same or next line, up to $end
            body = s[len("$timescale"):].strip()
            while "$end" not in body:
                body += " " + f.readline().strip()
            timescale = body.replace("$end", "").strip() or "1ns"
        elif s.startswith("$scope"):
            parts = s.split()
            # $scope module <name> $end
            nm = parts[2] if len(parts) >= 3 else "?"
            scope_stack.append(nm)
            if top_scope is None:
                top_scope = nm
        elif s.startswith("$upscope"):
            if scope_stack:
                scope_stack.pop()
        elif s.startswith("$var"):
            # $var <type> <width> <id> <name> [range] $end
            p = s.split()
            vtype, width, vid, name = p[1], int(p[2]), p[3], p[4]
            if vtype == "real":
                line = f.readline()
                continue
            msb, lsb = width - 1, 0
            if len(p) >= 6 and p[5].startswith("["):
                rng = p[5].strip("[]")
                if ":" in rng:
                    a, b = rng.split(":")
                    msb, lsb = int(a), int(b)
                else:  # single-bit slice like name[3]
                    msb = lsb = int(rng)
            sc = tuple(scope_stack)
            in_scope = scope_matches(sc, scope_filter)
            if not in_scope:
                line = f.readline()
                continue
            names.setdefault(vid, []).append((sc, name, msb, lsb))
            if vid not in state:
                state[vid] = {
                    "w": width,
                    "cur": ["x"] * width,
                    "last": [0] * width,
                    "t0": [0] * width, "t1": [0] * width,
                    "tx": [0] * width, "tc": [0] * width,
                }
        elif s.startswith("$enddefinitions"):
            break
        line = f.readline()

    if not state:
        sys.exit(f"[vcd2saif] no nets matched scope '{args.scope}'. "
                 f"Check the hierarchy (grep '$scope' in the VCD).")

    # ── Pass 2: body (value changes) ─────────────────────────────────────────
    def upd(st, k, nv, T):
        d = T - st["last"][k]
        c = st["cur"][k]
        if c == "0":
            st["t0"][k] += d
        elif c == "1":
            st["t1"][k] += d
        else:
            st["tx"][k] += d
        if (c == "0" and nv == "1") or (c == "1" and nv == "0"):
            st["tc"][k] += 1
        st["cur"][k] = nv
        st["last"][k] = T

    T = 0
    first_T = None
    line = f.readline()
    while line:
        c0 = line[0]
        if c0 == "#":
            T = int(line[1:])
            if first_T is None:
                first_T = T
        elif c0 in "01xzXZ":
            vid = line[1:].strip()
            st = state.get(vid)
            if st is not None:
                upd(st, 0, norm(c0), T)  # scalar → bit 0
        elif c0 in "bB":
            sp = line.split()
            bits, vid = sp[0][1:], sp[1]
            st = state.get(vid)
            if st is not None:
                w = st["w"]
                # left-extend per VCD rule (pad with '0', or MSB if x/z)
                pad = bits[0] if bits and bits[0] in "xzXZ" else "0"
                bits = bits.rjust(w, pad)[-w:]
                for k in range(w):
                    upd(st, k, norm(bits[k]), T)
        # ignore 'r' reals, '$dumpvars'/'$end', comments, etc.
        line = f.readline()
    end_T = T
    f.close()

    # flush remaining time-in-state to end
    for st in state.values():
        for k in range(st["w"]):
            d = end_T - st["last"][k]
            c = st["cur"][k]
            if c == "0":
                st["t0"][k] += d
            elif c == "1":
                st["t1"][k] += d
            else:
                st["tx"][k] += d

    duration = end_T - (first_T or 0)

    # ── Build scope tree for nested INSTANCE emission ────────────────────────
    # node = {"children": {name: node}, "nets": [(display_name, id, bit)]}
    def new_node():
        return {"children": {}, "nets": []}

    root = new_node()
    for vid, aliases in names.items():
        st = state[vid]
        for (sc, base, msb, lsb) in aliases:
            node = root
            for part in sc:
                node = node["children"].setdefault(part, new_node())
            if st["w"] == 1:
                node["nets"].append((base, vid, 0))
            else:
                step = -1 if msb >= lsb else 1
                bit = msb
                for k in range(st["w"]):  # k=0 is MSB
                    node["nets"].append((f"{base}[{bit}]", vid, k))
                    bit += step

    # ── Emit SAIF ────────────────────────────────────────────────────────────
    out = open(args.saif, "w")
    w = out.write
    w("(SAIFILE\n")
    w('(SAIFVERSION "2.0")\n')
    w('(DIRECTION "backward")\n')
    w('(DESIGN)\n')
    w('(DATE "generated by vcd2saif.py")\n')
    w('(VENDOR "snax_cluster")\n')
    w('(PROGRAM_NAME "vcd2saif.py")\n')
    w('(VERSION "1.0")\n')
    w("(DIVIDER / )\n")
    m = re.match(r"\s*(\d+)\s*([a-zA-Z]+)", timescale)
    ts_num, ts_unit = (m.group(1), m.group(2)) if m else ("1", "ns")
    w(f"(TIMESCALE {ts_num} {ts_unit})\n")
    w(f"(DURATION {duration})\n")

    def emit(node, name, ind):
        pad = "  " * ind
        w(f"{pad}(INSTANCE {name}\n")
        if node["nets"]:
            w(f"{pad}  (NET\n")
            for (disp, vid, k) in node["nets"]:
                st = state[vid]
                w(f"{pad}    ({disp} (T0 {st['t0'][k]}) (T1 {st['t1'][k]}) "
                  f"(TX {st['tx'][k]}) (TC {st['tc'][k]}) (IG 0))\n")
            w(f"{pad}  )\n")
        for cname, child in node["children"].items():
            emit(child, cname, ind + 1)
        w(f"{pad})\n")

    # top: if a scope filter was given, root has one child chain; emit from the
    # filtered top so read_saif -instance maps cleanly.
    top_name = args.top_instance or top_scope or "top"
    if len(root["children"]) == 1 and not root["nets"]:
        only = next(iter(root["children"]))
        emit(root["children"][only], args.top_instance or only, 1)
    else:
        emit(root, top_name, 1)
    w(")\n")
    out.close()

    n_nets = sum(len(a) * (state[v]["w"]) for v, a in names.items())
    print(f"[vcd2saif] {args.vcd} → {args.saif}  "
          f"(duration={duration} {ts_unit}, "
          f"~{n_nets} net-bits, scope='{args.scope or 'ALL'}')")


if __name__ == "__main__":
    main()
