# Gboard 万象增强（LSPosed 模块）

为 Android Gboard 中文输入法集成两项原生体验增强，支持跨版本字节码自动适配（已实机验证 **Gboard 18.6.9**）：

1. **独立的「双拼 - 自然码辅助码」方案**
   - 直接出现在 Gboard 设置 **语言 → 简体中文 → 拼音 → 拼音方案** 列表中，与原有的「双拼 - 自然码」完全独立（切回原自然码即恢复纯原生行为）。
   - 底层复用 Gboard 自然码键位提示、系统词库、用户词库与滑行输入，上层接入 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) 全量 `zi.pro` 字表（约 4.3 万汉字、5.9 万条编码，按词频排序）。
   - **直接辅助 + 词库整句混输**：
     - **3 码单字**：如 `vfj` → `镇`（空格或点击直接上屏并消费辅码）；
     - **4 码词优先 + `/` 单字聚拢**：有双字成词时双字词优先排第 1（如 `xcku` → `笑哭`），末尾按 `/`（如 `nire/`）强制 4 码单字置顶第 1；
     - **词组与整句混输**：任意音节可自由附加 1~2 位自然码形码（如 `rjhuk` → `rjh uk`、`vgjuf` → `证据`、`womfvfj` → `我们镇`）；
     - **拼音区暂存与退格撤销**：在 `rjh uk` 中先点单字 `燃` 会暂存在拼音区显示 `燃 uk`，选完 `烧` 后整词 `燃烧` 上屏；若误选 `燃` 直接按退格即可撤销回 `rjh uk`。

2. **Emoji 第 2 候选增强**
   - 开关直接集成在 Gboard **语言 → 简体中文 → 拼音** 设置页内（「Emoji 第 2 候选增强」），切换即刻生效。
   - 当第 1 候选命中万象 Emoji 词表时（如 `xcku` → `笑哭`），在第 2 位插入对应 Emoji（`😂`），不影响空格默认上屏原词。

## 使用方法

1. 安装本模块 APK，在 **LSPosed** 中启用模块并勾选 **Gboard**，强行停止并重新打开 Gboard。
2. 打开 **Gboard 设置 → 语言 → 简体中文 → 拼音**：
   - 在 **拼音方案** 中选择 **「双拼 - 自然码辅助码」**；
   - 按需开关下方的 **「Emoji 第 2 候选增强」**。

## 构建与测试

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## 更新词表与适配新版本 Gboard

- 从本机 `~/Library/Rime/` 重新导入万象 Emoji 与自然码辅码字表：
  ```bash
  python3 tools/import_rime.py
  ```
- 模块内置字节码结构解析器（`DexProfileResolver`），Gboard 升级后会自动适配；也可连接手机通过 `adb` 提取 APK 生成静态配置：
  ```bash
  python3 tools/inspect_gboard.py --adb --write-asset
  ```

## 致谢

- Emoji 映射与自然码辅助码字表来自 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)（CC BY 4.0）。
