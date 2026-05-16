# Integration 章节思路

## 核心叙事线索

```
params.hjson
    │
    ├─→ orchestrator.py ──→ Chisel sbt ──→ RTL (PE_Array.sv, PE_Array_wrapper.sv)
    │         └──→ Streamer/Shell cfg (JSON) ──→ 自动生成 snax_mx_alu_wrapper.sv
    │
    └─→ datagen.py ──→ quantize.py ──→ data.h (golden + 输入数组 + CSR 参数)
                                              │
                                    snax-mx.c + library
                                              │
                                        RTL 仿真 / 板级运行
```

参数文件是唯一的配置入口，所有组件都从它出发，这是整个 integration 的主线。

---

## 建议章节划分

### 4.X Integration Overview（~0.5 页）

**要写什么**：一张系统框图 + 一段描述。

- 说明整体分为三层：参数配置层、代码生成层、运行时层
- 强调单一入口（params.hjson）驱动硬件生成和软件测试数据生成，保证两者一致性
- 列出各组件角色（下面各节展开）

---

### 4.X.1 Parameter Configuration（~0.5 页）

**要写什么**：params.hjson 里每个字段的含义和它影响哪个下游组件。

关键字段分类：

| 字段 | 影响 |
|---|---|
| `A_dtype`, `B_dtype` | 硬件 MAC 类型 → orchestrator RTL 生成；datagen 量化格式 |
| `quantize_mode` | 输出 requant 类型 → orchestrator 选 RequantFP8/INT8；datagen 选输出量化路径 |
| `shared_format` | Scale format (UE8M0..UE2M6) → 硬件 scale finder；datagen 量化函数选择（v2/v6） |
| `parfor_M/N/K` | 硬件 tile 尺寸 → TileRows/TileCols/VectorSize；Streamer tiling 参数 |
| `block_size` | MX block 大小 → RequantConfig；Streamer N 维 padding |
| `stationary` | Streamer loop 顺序 |

---

### 4.X.2 Hardware Generation（~1.5 页）

**主角**：`orchestrator.py`

**要写什么**：orchestrator 做了两件事，要分开讲。

**（a）RTL 生成（`run_pe_array_gen`）**

- 读 params → 调用 `sbt runMain mx.GeneratePEArray` → 产出 `PE_Array.sv`（Chisel 生成）
- 参数映射关系（表格或列举）：`A_dtype→type-a`，`parfor_N→tile-cols`，`quantize_mode→requant-mode` 等
- `emit_pe_array_wrapper_sv`：生成静态 SV adaptor，把 Chisel 扁平化 IO 重新打包成 shell_wrapper 期望的 packed multi-dim 接口
- 强调：wrapper 的 TileRows/TileCols/BlockSize 参数全部从 params 导出，保证与 Chisel 模块严格对齐

**（b）外围硬件配置（`compute_hw_cfg`）**

- Streamer tile size 计算（A/B/Scale/Output/OutputScale 各 streamer 的 slstride、tlbound、tlstride）
- 重点说明 mode 分支：mode 0/1（per-element FP32/BF16）vs mode 2+（block MX output），输出 streamer 宽度不同
- 产出 JSON 配置 → 送入 SNAX cluster 自动生成工具链（snax_mx_alu_wrapper.sv + streamer 配置）

---

### 4.X.3 Software Test Infrastructure（~1.5 页）

**主角**：`datagen.py` + `quantize.py`

**要写什么**：以"一次 datagen 调用"为线索，按数据流向讲。

**（a）输入量化（quantize.py）**

- 介绍 `quantize_mx_v2` 和 `quantize_mx_v6` 的差异（UE8M0 用 v2/floor，非 UE8M0 用 v6/ceil）
- Scale 约定：UE8M0 用 `floor(log2(max))+127`，与硬件 `MaxScaleFinder` 完全对齐
- 输出：量化后的 raw element codes + 8-bit scale array（格式与硬件 TCDM layout 一致）

**（b）Tiling 和内存布局（datagen.py）**

- A/B tile reorder：`(m_tiles, k_tiles, parfor_M, parfor_K)` reshape → 与 Streamer AGU 读取顺序对齐
- Combined scale packing：A scale + B scale 按 Streamer 期望的 loop 顺序拼接
- 输出 golden：O_quant_golden / O_scale_golden 的字节顺序与硬件 `Cat(...)` 的小端 layout 对齐（行列均需翻转）

**（c）CSR 参数**

- acc_cnt、out_cnt、tiling loop bounds 全部从 params 派生后写入 `data.h`
- 静态 C 程序只读参数，不做计算，保证软硬件使用完全相同的控制流

---

### 4.X.4 Runtime and Verification（~0.5 页）

**主角**：`snax-mx.c` + library

**要写什么**：

- 静态程序结构：初始化 → 配置 CSR → 启动 Streamer → 等待完成 → 比较结果
- 与 golden 的比较粒度：O_quant_golden（element 级）+ O_scale_golden（block scale 级）
- 运行环境：RTL 仿真 (Verilator/QuestaSim) 或 FPGA，数据通过 `data.h` 静态链接

---

## 写作建议

1. **用参数文件串联各节**：每节开头都可以说"当 `quantize_mode=2` 时，这一组件做了……"，让读者看清楚参数如何流动
2. **框图优先**：integration 章节最适合用一张清晰的数据流框图替代大段文字
3. **不重复硬件细节**：硬件内部（PE_Array、RequantFP8 算法）应在前面的章节讲，这里只讲接口和配置映射
4. **强调自动化和一致性**：这个设计的亮点是参数唯一、各组件联动，避免人工同步错误——这值得在 integration 里专门说一下
