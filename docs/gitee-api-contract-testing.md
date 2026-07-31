# Gitee API 合约测试

`tools/gitee_api_contract_test.py` 对 Android App 使用的真实 Gitee v5 端点执行隔离分支合约测试。脚本覆盖：

- 私有仓库元数据、空仓库 `default_branch: null` 和 `permission` 权限对象；
- 空仓库首次 commit 初始化、非空仓库通过 `POST /branches` 创建测试分支、分支 HEAD 推进和分页提交历史；
- 单提交、文本/二进制内容、递归 tree 和 blob 读取；
- `path` action 的多文件提交和 Base64 二进制提交；
- 缺失文件、重复路径和无效令牌错误响应；
- 响应中的限流头（Gitee 返回时记录）。

测试只在指定分支下追加唯一的 `.codex-gitee-api-test/<uuid>/` 文件，不修改 `master`，也不 force-push 或重写历史。每次运行创建两个普通 commit，以验证分页和分支推进。

Gitee 对已存在分支中的缺失 content 可能返回 `HTTP 200` 与空数组，而不是 404；Android transport 会把 `200 + null/[]` 规范化为 404 语义，使未初始化仓库仍能进入安全初始化流程。

## 运行

令牌只能通过 `GITEE_TOKEN` 环境变量提供，脚本不接受令牌命令行参数，也不会输出认证 URL、请求体或令牌。

PowerShell：

```powershell
$env:GITEE_TOKEN = Read-Host "Gitee 临时 PAT" -MaskInput
python tools/gitee_api_contract_test.py `
  --owner raink3 `
  --repository ebbinghaus-notes `
  --branch codex/gitee-api-contract-20260731
Remove-Item Env:GITEE_TOKEN
```

Bash：

```bash
read -r -s GITEE_TOKEN
export GITEE_TOKEN
python3 tools/gitee_api_contract_test.py \
  --owner raink3 \
  --repository ebbinghaus-notes \
  --branch codex/gitee-api-contract-20260731
unset GITEE_TOKEN
```

测试分支是远端可见的审计记录。确认不再需要后，可在 Gitee 页面人工删除该测试分支；不要让自动测试删除分支，以免误删或掩盖失败现场。

## 2026-07-31 实测记录

针对私有仓库的真实 Gitee v5 响应确认：

- GET 使用查询参数 `access_token` 后仓库返回 200；无效令牌返回 401。
- 初始空仓库返回 `default_branch: null`，权限对象字段为 `permission`。
- 多文件 commit action 必须使用 `path`；`file_path` 返回 400。
- 空仓库允许首次 commit 创建分支；非空仓库的新分支必须先通过 `POST /branches` 创建。
- 已存在分支中的缺失 content 返回 `HTTP 200` 与空数组。
- 文本与 Base64 二进制内容、blob 字节、递归 tree、两页提交历史及分支 HEAD 推进均校验一致。
- 重复 `create` 路径返回 400；本次响应未提供 `X-RateLimit-*` 头。
- 自动化通过的二进制探针大小为 4096 bytes；尚未执行破坏性极限负载或限流耗尽测试，因此 OpenSpec 1.5 保持未完成。

本次测试创建的隔离分支：

- `codex/gitee-api-contract-20260731`
- `codex/gitee-api-contract-newbranch-20260731`
- `codex/gitee-api-contract-autocreate-20260731`

`master` 未创建、未修改。由于首个测试分支是在空仓库中创建的，Gitee 将其设为默认分支；不要直接把该测试分支作为正式档案分支。
