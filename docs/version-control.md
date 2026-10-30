# Git 版本管理与回退

2026-10-28 为项目建立本地 Git 仓库，主分支为 `main`，初始代码快照标记为 `baseline-2026-10-28`。此前的开发过程没有 Git 提交，无法恢复为逐次历史；初始提交记录的是建立仓库时的现状，不代表当天重新验收了全部功能。

## 日常版本

在项目根目录执行：

```powershell
# 查看尚未提交的修改
git status
git diff
# 查看最近版本
git log --oneline --decorate -10
# 查看某次提交；将 abc1234 替换成实际提交号
git show abc1234
```

每个可验收能力或修复单独提交，提交前检查差异并执行适用验证。提交仅包含该修改单元的相关文件；工作区已有的未提交修改需先识别归属，避免混入同一提交。未提交的文件修改不会自动成为 Git 历史。

## 回退与检查旧版本

回退某一提交时，先确认 `git status` 工作区干净，并检查提交内容，再用新提交撤销该次修改：

```powershell
git show abc1234
git revert abc1234
```

`git revert` 保留原历史。若目标提交之后还有依赖它的变更，可能需要处理冲突并重新验证；回到某个历史版本与撤销单个提交不是同一件事。撤销提交或恢复历史版本前，需检查后续变更的依赖关系和工作区未提交修改。

仅查看初始代码时，可以在工作区干净后临时切换：

```powershell
git switch --detach baseline-2026-10-28
# 查看结束后回到主分支
git switch main
```

需要基于初始版本开发时，创建单独分支：

```powershell
git switch -c recovery/from-baseline baseline-2026-10-28
```

不要把 `git reset --hard` 或 `git clean` 当作常规回退方式，它们可能丢弃尚未保存的工作。

## 管理范围与备份

源码、测试、数据库迁移、构建配置、前端依赖锁文件和项目文档进入仓库。本机 YAML、密码、MQ 凭据、日志、数据库文件、依赖目录和构建产物由 `.gitignore` 排除，原文件仍保留在本机；安装在 `.agents` 下的本机技能也不进入仓库，根目录 `AGENTS.md` 会被跟踪。

Git 回退不会恢复数据库内容、已执行的 Flyway 迁移、本机秘密配置、正在运行的进程或已部署的 JAR。代码回退前仍需确认数据库兼容性，并按需要重新构建和部署。数据库与秘密配置应独立安全备份。

本地 `.git` 目录保存提交历史，应随项目妥善保留；本地版本库不能替代异地备份。2026-10-28 已创建 GitHub 私有仓库 [kuangquanshui11/TaskFlow](https://github.com/kuangquanshui11/TaskFlow)，并关联为 `origin`。首次上传包括 `main` 分支和初始版本标签；后续本地提交与远程上传是两个步骤，提交不会自动上传。

检查关联与同步情况：

```powershell
git remote -v
git status -sb
# 获得上传授权后，将当前主分支提交同步到 GitHub
git push origin main
```

本机使用 GitHub CLI 的账号登录完成认证，不将访问令牌写入仓库、文档或远程 URL。换电脑时需单独登录 GitHub；私有仓库不会备份已被忽略的本机秘密配置和数据库。
