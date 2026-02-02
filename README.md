# MC-MMD-rust

在 Minecraft 1.20.1 中实现 MMD（MikuMikuDance）模型渲染和物理模拟的 Mod。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## 功能特性

- **PMX 模型加载**: 在 Minecraft 中加载和渲染 MMD 模型
- **VMD 动画播放**: 支持骨骼和表情变形的 MMD 动画播放
- **物理模拟**: 使用 Rapier3D 实现头发、衣物、配饰的实时物理效果
- **GPU 蒙皮**: 通过 Compute Shader 实现高性能顶点蒙皮
- **多层动画**: 支持多个动画同时混合播放

## 架构

本项目由两个主要部分组成：

1. **rust_engine**: 基于 Rust 的 MMD 物理和动画引擎
   - PMX/VMD 格式解析
   - 骨骼层次管理
   - 物理模拟（Rapier3D）
   - JNI 绑定用于 Java 交互

2. **Minecraft Mod**（Fabric/Forge）: 基于 Java 的渲染和集成
   - OpenGL 模型渲染
   - Compute Shader 蒙皮
   - Iris 光影兼容

## 构建

### 前置要求

- Rust 1.70+（用于 rust_engine）
- JDK 17+（用于 Minecraft mod）
- Gradle 8.x

### 构建 rust_engine

```bash
cd rust_engine
cargo build --release
```

### 构建 Minecraft Mod

```bash
./gradlew build
```

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

## 致谢

本项目基于众多开源项目和贡献者的工作。
完整致谢请参阅 [ACKNOWLEDGMENTS.md](ACKNOWLEDGMENTS.md)。

### 核心依赖

| 库 | 许可证 | 说明 |
|----|--------|------|
| [Rapier](https://rapier.rs) | Apache-2.0 | 3D 物理引擎 |
| [glam](https://github.com/bitshifter/glam-rs) | MIT/Apache-2.0 | 3D 数学库 |
| [mmd-rs](https://github.com/aankor/mmd-rs) | BSD-2-Clause | MMD 格式解析器 |

### 设计参考

| 项目 | 许可证 | 参考内容 |
|------|--------|----------|
| [KAIMyEntity](https://github.com/kjkjkAIStudio/KAIMyEntity) | MIT | 原始 Minecraft MMD 模组 |
| [KAIMyEntity-C](https://github.com/Gengorou-C/KAIMyEntity-C) | MIT | 本项目的直接前身（二次开发基础） |
| [Saba](https://github.com/benikabocha/saba) | MIT | 物理系统架构 |
| [nphysics](https://github.com/dimforge/nphysics) | Apache-2.0 | 骨骼层次设计 |
| [mdanceio](https://github.com/ReaNAiveD/mdanceio) | MIT | 动画系统 |

完整的第三方许可证信息请参阅 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

## 相关项目

- [MikuMikuDance](https://sites.google.com/view/vpvp/) - 樋口優开发的原版 MMD 软件
- [Saba](https://github.com/benikabocha/saba) - C++ MMD 库
- [Rapier](https://rapier.rs) - Rust 物理引擎