# SmsForwarder 定制 frpc 方案总结与运维手册（2026-03）

## 1. 本次改造目标与结果
- 目标：在不关闭保活能力前提下，提供稳定可用的 `with_frpc` 版本，并避免旧 JNI 版本漂移导致的不可用问题。
- 目标：所有大构建走 GitHub Actions，不依赖本地大编译。
- 目标：提升异常可观测性，避免“实际失败却显示成功”。

当前结果（截至 `v3.5.0-mix19.16`）：
- `with_frpc` 仅产出 4 个非 universal APK（`armeabi-v7a/arm64-v8a/x86/x86_64`）。
- `with_frpc` 包中已排除 `libgojni.so`，优先使用自定义 `libfrpc.so`。
- 自定义 frpc 资产采用 `assets/frpc/<abi>/frpc.bin`（gzip payload）分发。
- frpc 启动成功判定已改为“强判定”：必须在启动窗口内识别到成功信号日志才返回成功。
- release 名称已改为 `YYYYMMDDHHMM SmsForwarder vX.Y.Z...`，避免排序混乱。

## 2. 当前技术方案（关键点）

### 2.1 打包与产物策略
- 标准包（`withFrpcPackage=false`）：
  - 产出 `universal + 4 ABI`。
  - `excludeFrpclib=true`，不携带 JNI `libgojni.so`。
- 定制包（`withFrpcPackage=true`）：
  - 仅上传 4 ABI（不上传 universal）。
  - `excludeFrpclib=true`。
  - 额外携带：
    - `lib/<abi>/libfrpc.so`（优先执行）
    - `assets/frpc/<abi>/frpc.bin`（gzip payload，作为可提取 fallback）

### 2.2 运行时后端选择与回落
`FrpcCompat` 当前候选执行路径按顺序尝试：
1. `nativeLibraryDir/libfrpc.so`
2. `filesDir/libs/frpc`
3. `codeCacheDir/libs/frpc`
4. `noBackupFilesDir/libs/frpc`
5. `cacheDir/libs/frpc`

补充规则：
- 若候选路径不可执行，自动尝试下一候选。
- 若启动失败（权限、格式、文件缺失等），自动尝试下一候选。
- with_frpc 模式不再“一次失败后永久 unavailable”，允许继续重试与诊断。

### 2.3 成功判定（关键）
- 旧逻辑：进程短时间不退出即认为成功。
- 新逻辑：只有在启动窗口（`STARTUP_READY_TIMEOUT_MS`）内检测到成功信号才返回成功。
- 成功信号（当前）：
  - `login to server success`
  - `start proxy success`
- 若仅进程存在但无成功信号，则判定为失败并触发下一 fallback。

## 3. 关键文件清单
- Workflow:
  - `.github/workflows/Release.yml`
  - `.github/workflows/Weekly_Build.yml`
- 打包脚本:
  - `scripts/prepare_custom_frpc_assets.sh`
  - `scripts/cleanup_custom_frpc_assets.sh`
- 运行时兼容层:
  - `app/src/main/kotlin/cn/ppps/forwarder/utils/FrpcCompat.kt`
- 构建配置:
  - `app/build.gradle`

## 4. 发布与验收标准（每次发版必做）
- 触发方式：push tag（例如 `v3.5.0-mix19.16`）触发 `Release` workflow。
- 通过条件：
  - GitHub Action `Release` 全绿。
  - Release 页面存在：
    - 标准包 5 个 APK
    - with_frpc 4 个 APK
    - `BUILD_INFO.txt` / `SHA256SUMS.txt`
- 抽样验包（至少 arm64 with_frpc）：
  - 必须有 `lib/arm64-v8a/libfrpc.so`
  - 必须有 `assets/frpc/*/frpc.bin`
  - 不应有 `libgojni.so`

## 5. 常见问题排查

### 5.1 “frpc failed to run / permission denied”
- 先确认 APK 内含 `libfrpc.so`（优先路径）。
- 检查弹窗错误是否包含具体候选路径与失败原因。
- 若为单一路径失败，应自动尝试后续路径；若全部失败，查看汇总错误。

### 5.2 “看起来成功但 FRPS 没注册”
- 现在已启用成功信号强判定；若仍“无注册”，应返回失败而非成功。
- 请贴完整失败弹窗（包含 backend 信息 + tail log）。

### 5.3 Play Protect 风险
- 已做降低风险处理（资产 gzip payload、排除旧 JNI）。
- Play Protect 规则会动态变化，不能承诺 100% 不触发；建议每次小步发布并验证。

## 6. 后续维护建议
- 每次只做一个主题变更并发版，便于回归定位。
- 统一通过 GitHub Actions 构建，不在本地做大构建。
- 保留 `mix19.x` 递增标签，便于回滚定位。
- 与上游同步时，优先保护下列定制点不被覆盖：
  - `Release.yml` 的产物矩阵与 release 命名
  - `prepare_custom_frpc_assets.sh` 与 `cleanup_custom_frpc_assets.sh`
  - `FrpcCompat.kt` 的回落链路与成功判定
  - `app/build.gradle` 的 `withFrpcPackage` 行为

