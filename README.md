# Gboard Emoji Input Enhancer

一个为 Gboard 简体中文拼音输入法优化 Emoji 候选策略的 **LSPosed** 插件。

## 测试环境

- 已测试于 Gboard `18.4.1`、`17.2.2` 版本。

## 安装

1. 安装 Release 中的提供的软件。
2. 在 LSPosed 中启用 **Gboard Emoji Input Enhancer**，作用域只勾选 `com.google.android.inputmethod.latin`（Gboard）。
3. 强行停止 Gboard，再打开输入框。自动识别在后台执行，第一次启动时可先收起再打开键盘。

1. 输入 `xiaoku` （ `xcku`自然码双拼），确认第一候选为“笑哭”、第二候选为 😂。点击 😂 后应只上屏一个 😂，随后输入应开始新一轮拼写。
2. 另测空格上屏“笑哭”、退格清空输入、候选展开/收起、切换语言、无映射词、已有 emoji 去重。模块应用内提供了测试输入框。

## 构建

需要 JDK 17 和 Android SDK 36。

```sh
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。APK 位于 `app/build/outputs/apk/debug/`。

## 感谢

- 中文词表到 Emoji 的映射取自 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)，一个基于 rime 的优秀的拼音生态，[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。

- Gradle Wrapper，[Gradle](https://github.com/gradle/gradle)，[Apache License 2.0](LICENSES/Gradle-Apache-2.0.txt)。
- Xposed API，[rovo89/XposedBridge](https://github.com/rovo89/XposedBridge)。
- JUnit 4，[junit-team/junit4](https://github.com/junit-team/junit4)，EPL 1.0。

