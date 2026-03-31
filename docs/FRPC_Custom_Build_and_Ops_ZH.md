# SmsForwarder 定制 frpc 方案总结与运维手册（2026-03）

## 1. 目标
- 默认分发的 `with_frpc` 包只保留低风险的 `.so` 运行路径。
- 高风险的“落地文件 + exec”能力从默认分发面剥离，只在兼容兜底包中保留。
- 标准包继续保留原有 `JNI libgojni.so` 路线。
- 所有大构建继续走 GitHub Actions，不依赖本地大编译。

## 2. 当前产物矩阵
- 标准包：
  - `universal + armeabi-v7a + arm64-v8a + x86 + x86_64`
  - 运行时后端：JNI `libgojni.so`
- `with_frpc`：
  - 仅 4 个 ABI 包，无 universal
  - 运行时后端：`lib/<abi>/libfrpc.so`
  - 不携带 `assets/frpc/**`
  - 不允许 runtime exec fallback
- `with_frpc_fallback`：
  - 仅 4 个 ABI 包，无 universal
  - 运行时后端：优先 `lib/<abi>/libfrpc.so`
  - 携带 `assets/frpc/<abi>/frpc.bin`
  - 允许解压到应用私有目录后 `exec`

## 3. 运行时逻辑

### 3.1 标准包
- 仅走 JNI：
  - `Frpclib.runContent`
  - `Frpclib.runFile`

### 3.2 with_frpc
- `BuildConfig.WITH_FRPC_PACKAGE = true`
- `BuildConfig.WITH_FRPC_EXEC_FALLBACK = false`
- `FrpcCompat` 只允许：
  - `nativeLibraryDir/libfrpc.so`
- 若该路径不可用，直接失败，不会：
  - 安装 `assets/frpc`
  - 落地 `filesDir/libs/frpc`
  - 调用 exec fallback

### 3.3 with_frpc_fallback
- `BuildConfig.WITH_FRPC_PACKAGE = true`
- `BuildConfig.WITH_FRPC_EXEC_FALLBACK = true`
- `FrpcCompat` 候选顺序：
  1. `nativeLibraryDir/libfrpc.so`
  2. `filesDir/libs/frpc`
  3. `codeCacheDir/libs/frpc`
  4. `noBackupFilesDir/libs/frpc`
  5. `cacheDir/libs/frpc`

## 4. 构建期开关
- Gradle 参数：
  - `-PwithFrpcPackage=true|false`
  - `-PwithFrpcExecFallback=true|false`
- BuildConfig：
  - `WITH_FRPC_PACKAGE`
  - `WITH_FRPC_EXEC_FALLBACK`

## 5. 构建脚本约束
- `scripts/prepare_custom_frpc_assets.sh`
  - 总是构建 `jniLibs_custom/<abi>/libfrpc.so`
  - 仅当 `WITH_FRPC_EXEC_FALLBACK=true` 时才生成 `assets/frpc/<abi>/frpc.bin`
- `scripts/cleanup_custom_frpc_assets.sh`
  - 清理 `assets/frpc`
  - 清理 `jniLibs_custom`
  - 清理 `build/custom_frpc_meta`

## 6. Release 策略
- Release 名称继续使用：`YYYYMMDDHHMM SmsForwarder vX.Y.Z`
- 推荐安装顺序：
  1. 先装 `with_frpc`
  2. 若日志出现以下兼容性问题，再切 `with_frpc_fallback`
     - `permission denied`
     - `cannot run program`
     - `not executable`
     - `custom frpc backend unavailable`
  3. 若需要旧 JNI 路线，再使用标准包

## 7. 验包标准

### 7.1 with_frpc
- 必须有 `lib/<abi>/libfrpc.so`
- 不应有 `assets/frpc/**`
- 不应有 `libgojni.so`

### 7.2 with_frpc_fallback
- 必须有 `lib/<abi>/libfrpc.so`
- 必须有 `assets/frpc/<abi>/frpc.bin`
- 不应有 `libgojni.so`

## 8. 日志与观察点
- 当 fallback 包实际命中 exec 路径时，`FrpcCompat` 会输出明确日志：
  - `using exec fallback backend: ...`
- 当默认 `with_frpc` 包被硬门禁拦住时，会输出：
  - `exec fallback disabled; skip writable custom binary install`

## 9. 关键文件
- `.github/workflows/Release.yml`
- `.github/workflows/Weekly_Build.yml`
- `scripts/prepare_custom_frpc_assets.sh`
- `scripts/cleanup_custom_frpc_assets.sh`
- `app/build.gradle`
- `app/src/main/kotlin/cn/ppps/forwarder/utils/FrpcCompat.kt`

## 10. 维护建议
- 不要再把 exec fallback 混回默认 `with_frpc` 包。
- Play Protect 风险验证优先看默认 `with_frpc`，不要拿 fallback 包做主分发结论。
- 以后如果 fallback 命中率长期很低，可以继续考虑进一步收缩兼容包分发面。
