import hjson
import sys

def update_streamer_config(input_format, file_path):
    # 1. 定义你的硬件生成逻辑
    if input_format == "INT8":
        spatial = 8
        channel = 8
    elif input_format == "FP16":
        spatial = 2
        channel = 2
    else:
        spatial = 1
        channel = 1

    print(f"-> 正在读取配置文件: {file_path}")
    
    # 2. 读取原始的 HJSON 文件
    with open(file_path, 'r', encoding='utf-8') as f:
        # hjson.load 能够完美解析带有注释的格式，将其转化为 Python 字典
        config = hjson.load(f)

    # 3. 精确导航到需要修改的特定分支 (其他分支如 'cluster', 'dram' 完全不动)
    streamer_cfg = config["snax_alu_streamer_template"]

    # --- 修改 Data Reader 参数 ---
    streamer_cfg["data_reader_params"]["spatial_bounds"] = [[spatial], [spatial]]
    streamer_cfg["data_reader_params"]["num_channel"] = [channel, channel]
    
    # 如果你也想联动修改 Writer，可以取消下面的注释：
    streamer_cfg["data_writer_params"]["spatial_bounds"] = [[spatial]]
    streamer_cfg["data_writer_params"]["num_channel"] = [channel]

    # 4. 将整个修改后的配置写回新文件 (避免直接覆盖原文件导致手滑丢失)
    output_path = "generated_alu_cluster.hjson"
    with open(output_path, 'w', encoding='utf-8') as f:
        # hjson.dump 会把它重新转换回带有友好的松散格式的 hjson 文本
        hjson.dump(config, f)

    print(f"-> 成功生成新配置文件: {output_path}")
    print(f"-> 当前设置: 输入格式={input_format}, 空间并行度={spatial}, 通道数={channel}")

if __name__ == "__main__":
    # 假设你的原始配置文件叫这个名字
    original_config_file = "snax_mx_alu_cluster.hjson" 
    
    # 从终端读取参数，默认为 INT8
    format_arg = sys.argv[1] if len(sys.argv) > 1 else "INT8"
    
    update_streamer_config(format_arg, original_config_file)