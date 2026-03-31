# Fork 同步上游详细指南（lovitus/SmsForwarder <- pppscn/SmsForwarder）

本指南用于把上游仓库 `pppscn/SmsForwarder` 的最新变更同步到 fork，并保留当前定制 frpc 方案。

## 1. 远程仓库约定
- `origin`：上游仓库（`https://github.com/pppscn/SmsForwarder.git`）
- `fork`：你的 fork（`https://github.com/lovitus/SmsForwarder.git`）

可用以下命令核对：

```bash
git remote -v
```

## 2. 推荐同步策略
- 推荐：**merge upstream/main 到你的长期维护分支**（例如 `codex/with-frpc-release-battery-opt`）。
- 不推荐在有多人协作时对共享分支直接 `rebase`（会重写历史）。

## 3. 标准同步流程（命令级）

### 3.1 拉取上游最新代码

```bash
git fetch origin
git fetch fork
```

### 3.2 切到你的维护分支

```bash
git checkout codex/with-frpc-release-battery-opt
git pull --ff-only fork codex/with-frpc-release-battery-opt
```

### 3.3 合并上游主干

```bash
git merge --no-ff origin/main
```

若无冲突，直接进入 3.5。若有冲突，执行 3.4。

### 3.4 解决冲突（重点文件）
上游同步最容易冲突的定制文件：
- `.github/workflows/Release.yml`
- `.github/workflows/Weekly_Build.yml`
- `scripts/prepare_custom_frpc_assets.sh`
- `scripts/cleanup_custom_frpc_assets.sh`
- `app/src/main/kotlin/cn/ppps/forwarder/utils/FrpcCompat.kt`
- `app/build.gradle`

冲突处理原则：
- 保留上游通用修复（安全补丁、依赖升级、业务逻辑修复）。
- 保留你的定制行为（with_frpc 产物矩阵、frpc 回落链路、成功判定、release 命名规则）。
- 不要盲目全选 ours/theirs，逐段比对。

处理完成后：

```bash
git add <冲突文件...>
git commit
```

### 3.5 推送合并结果

```bash
git push fork codex/with-frpc-release-battery-opt
```

## 4. 同步后验证（仅 GitHub Actions）
你要求不做本地大编译，建议只做下面动作：

### 4.1 触发一次 release 构建验证
- 打测试 tag（例如 `v3.5.0-mergecheck.1`）：

```bash
git tag -a v3.5.0-mergecheck.1 -m "merge check"
git push fork v3.5.0-mergecheck.1
```

### 4.2 在 Action 与 Release 页面核对
- `Release` workflow 全部成功。
- 产物矩阵符合预期：
  - 标准包 5 个
  - with_frpc 4 个（无 universal）
  - with_frpc_fallback 4 个（无 universal）
- with_frpc 抽样验包：
  - 有 `libfrpc.so`
  - 无 `assets/frpc/**`
- with_frpc_fallback 抽样验包：
  - 有 `libfrpc.so`
  - 有 `assets/frpc/*/frpc.bin`
  - 无 `libgojni.so`

## 5. 推荐分支模型
- `codex/with-frpc-release-battery-opt`：长期维护分支（承载定制能力）。
- `codex/sync-upstream-YYYYMMDD`：上游同步临时分支（可选）。

推荐流程：
1. 从长期维护分支切临时分支。
2. 在临时分支 merge `origin/main` 并解决冲突。
3. 通过 Actions 验证。
4. 再 merge 回长期维护分支。

## 6. 回滚方案
如果同步后线上验证失败：
- 回滚到上一个稳定 tag（例如 `v3.5.0-mix19.16`）。
- 或在维护分支上 `git revert <merge_commit_sha>` 后重新打 tag 发版。

## 7. 实操检查清单（建议复制到每次同步任务）
- 已 fetch `origin` / `fork`
- 已从 `fork` 更新维护分支
- 已 merge `origin/main`
- 冲突已逐段处理（关键 6 文件）
- 已 push 到 `fork` 维护分支
- 已通过 Actions 构建验证
- 已验包（`libfrpc.so` / `frpc.bin` / 无 `libgojni.so`）
- 已记录同步批次与对应 tag
