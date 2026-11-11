---
name: python-runtime
description: 内置 Python 3.15.2 与 pip。当任务需要运行 Python 脚本、安装纯 Python 依赖、处理数据或用 sqlite/ssl/ctypes 时使用。
whenToUse: 需要执行 .py、python3 -c、创建 venv、用 pip 安装包、或排查 Python 在 Android 上的限制时。
---

# 内置 Python 3.15.2

`python3`、`pip` 已加入 PATH，直接调用即可；无需安装 Termux。

## 常用

```bash
python3 --version          # 3.15.2
python3 script.py
python3 -m venv "$HOME/.venv" && "$HOME/.venv/bin/pip" install requests
pip install --user requests
```

- 依赖请装进 venv 或 `pip install --user`，不要改动运行时自带的 `site-packages`，否则升级运行时会被覆盖。
- `SSL_CERT_FILE` 已指向内置 CA，`https` 请求可直接用。
- 已内置：`ssl`、`sqlite3`、`ctypes`（libffi）、`zlib`、`bz2`、`lzma`、`curses`、`readline`、`pyexpat`、`hashlib`（openssl）。

## 限制

- **不内置编译器 / 工具链**：运行时不含 `cc` / `gcc` / `clang` / `llvm` / `ar` / `make` / `readelf` / `objdump` 等。判断是否 ELF 一律用已内置的 `file`。需要编译 C 扩展的场景请在**开发机**完成，再把产物（`py3-none-any` wheel 或交叉编译出的 `.so`）推送到设备；不要在设备内编译——包体已约 695MB，再塞工具链会逼近 1GB，且发行用户不需要在设备里编译。
- 只装纯 Python 包或 `py3-none-any` wheel；manylinux/linux wheel 与 Android 不兼容（bionic 与 glibc 的符号/路径不同）。
- CPython 在 Android 上禁用了 `_multiprocessing` 与 `_posixshmem`（bionic 无 `sem_open`/`shm_open`）。用 `threading`、`asyncio` 或 `subprocess` 代替。
- 没有 `tkinter`（Android 无 Tk）；`idlelib`、`test` 与 `turtledemo` 已从运行时裁剪。
- 平台标签为 `android-27-arm64_v8a`，判断 wheel 兼容性时以它为准。
