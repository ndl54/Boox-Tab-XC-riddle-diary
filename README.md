# Riddle Diary for BOOX

*(Chinese below / 中文在下方)*

---

## English

An enchanted diary for BOOX e-ink devices (developed for the **Note X2**),
recreating the **Tom Riddle's Diary** effect from Harry Potter — inspired by the
reMarkable [riddle](https://github.com/MaximeRivest/riddle) project:

1. **Write on the blank page with your pen** — ink appears as you write.
2. **Rest the pen for ~2.8s** and your handwriting is absorbed into the page,
   fading away in the order it was written.
3. A moment later the **diary writes back**, its reply rising out of the page
   word by word in a handwriting-style font (Ma Shan Zheng for Chinese, Dancing
   Script for Western text).
4. The reply lingers, then fades back into the page, and the diary waits for you
   to write again.

A vision model reads your handwritten page directly and plays the "diary
spirit", replying briefly in whatever language you wrote in. Two backends are
selectable in settings:

- **Anthropic (Claude)** — official Java SDK, default model `claude-opus-4-8`.
- **OpenAI / compatible** — Chat Completions, default model `gpt-4o-mini`. The
  base URL is configurable, so it also works with any OpenAI-compatible service
  (self-hosted vLLM/Ollama gateways, third-party proxies).

### Install

Enable USB debugging on the device (Settings → About → tap the build number,
then turn on USB debugging), then:

```bash
adb install -r app-debug.apk
# BOOX freezes newly installed apps; unfreeze before first launch:
adb shell pm enable com.billtt.riddle
```

Or open the project in Android Studio and Run.

### Use

1. On first launch a settings dialog appears. Pick a backend, paste its **API
   key** (model / base URL have defaults), and tap Save.
2. Write with the pen, then **rest the pen for ~2.8s** to trigger absorption.
3. The reply reveals, lingers, and fades. **Tap the screen** during the linger
   to make it fade immediately.
4. **Long-press the screen with a finger** to open settings any time.
5. The pen's eraser end erases whole strokes.

For build and development details, see [CLAUDE.md](CLAUDE.md).

---

## 中文

在 BOOX 电子墨水设备（针对 **Note X2** 开发）上复刻《哈利·波特》中 **Tom Riddle
日记** 的交互，灵感来自 reMarkable 平台的
[riddle](https://github.com/MaximeRivest/riddle) 项目：

1. **用笔在空白页上书写** —— 笔迹边写边现。
2. **停笔约 2.8 秒**，字迹按书写顺序逐渐淡出、被纸“吸收”。
3. 片刻之后，**日记开始回信**，回信以手写风格字体逐字从纸中“渗出”（中文用
   「马善政楷书」，西文用 Dancing Script）。
4. 回信停留片刻后同样淡去，纸面恢复空白，等待你继续书写。

背后由视觉大模型直接阅读你的手写页面截图，扮演“日记之灵”，以你书写所用的语言
简短回信。可在设置中切换两种后端：

- **Anthropic (Claude)** —— 官方 Java SDK，默认模型 `claude-opus-4-8`。
- **OpenAI / 兼容接口** —— Chat Completions，默认模型 `gpt-4o-mini`；Base URL
  可配置，因此也适用于任何 OpenAI 兼容服务（自建 vLLM/Ollama 网关、第三方中转）。

### 安装

先在设备上开启 USB 调试（设置 → 关于 → 连点版本号，再打开 USB 调试），然后：

```bash
adb install -r app-debug.apk
# BOOX 会冻结新装应用，首次启动前先解冻：
adb shell pm enable com.billtt.riddle
```

或用 Android Studio 打开项目直接运行。

### 使用

1. 首次启动会弹出设置：选择后端，粘贴对应的 **API Key**（模型 / Base URL 已有
   默认值），点保存。
2. 用笔书写，然后**停笔约 2.8 秒**触发吸收。
3. 回信浮现、停留、淡去。停留期间**用手指点一下屏幕**可让它立即淡去。
4. **用手指长按屏幕**可随时打开设置。
5. 笔的橡皮端可整笔擦除。

构建与开发细节见 [CLAUDE.md](CLAUDE.md)。

---

## Credits & license

- Fonts: [Dancing Script](https://fonts.google.com/specimen/Dancing+Script) and
  [Ma Shan Zheng](https://fonts.google.com/specimen/Ma+Shan+Zheng) — SIL Open
  Font License 1.1 (see `FONT_LICENSE_OFL.txt`).
- App code: MIT License (see `LICENSE`).
- Concept and interaction inspired by
  [MaximeRivest/riddle](https://github.com/MaximeRivest/riddle) (MIT).

## Codex / ChatGPT subscription login

The fork supports **Codex (ChatGPT login)** directly on BOOX, with device-code and browser sign-in, an account model picker, encrypted session storage, and automatic token refresh. See [setup and implementation notes](docs/codex-login.md).
