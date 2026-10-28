<#
.SYNOPSIS
    构造"全量 diff"扫描分支,用于触发 GitCode/Jenkins 全量代码扫描。

.DESCRIPTION
    原理:Jenkins 的扫描范围 = MR 的 diff 文件集合。
    diff = 源分支相对目标分支的差异,所以只要让 **目标分支为空**,diff 就等于源分支全部文件。

    本脚本构造一对分支:
      - 目标分支(base):空基线(0 文件),但基于 $BaseLineage 创建,保证与目标分支有共同祖先。
      - 源分支(src)   :仅包含扫描口径内的文件。

    扫描口径(本仓库约定):
      1. 只扫 src/main/java 下的 .java 文件;
      2. 只扫 settings.gradle.kts 中已声明的模块。

    重要约束:
      * base 的谱系($BaseLineage)必须与该 MR 最终要合入的分支一致,
        否则 Jenkins 执行 git merge 时会报 "refusing to merge unrelated histories"
        (错误码 CH.1010204.453)。本项目 main 与 dev2.0 无共同祖先,务必注意。
      * 本脚本只创建本地分支,**不推送**。推送与建 MR 由人工执行(见脚本结尾提示)。

.PARAMETER MainBranch
    代码来源分支(默认 dev2.0)。

.PARAMETER BaseLineage
    base 的谱系分支,必须与 MR 目标分支一致(默认 main)。

.PARAMETER SrcBranch / BaseBranch
    扫描分支名(默认 dev-scan/dev-scan-src 与 dev-scan/dev-scan-base)。

.PARAMETER RepoDir
    仓库工作目录(默认当前目录)。脚本会在其中执行 git 命令,
    但分支操作全部在**临时克隆**中进行,不影响你的工作区。

.EXAMPLE
    .\scripts\do-full-scan.ps1
    .\scripts\do-full-scan.ps1 -MainBranch dev2.0 -BaseLineage main
#>
[CmdletBinding()]
param(
    [string]$MainBranch  = 'dev2.0',
    [string]$BaseLineage = 'main',
    [string]$SrcBranch   = 'dev-scan/dev-scan-src',
    [string]$BaseBranch  = 'dev-scan/dev-scan-base',
    [string]$RepoDir     = '.',
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'

# 扫描口径:settings.gradle.kts 中声明的模块(嵌套 commons:* 已展开为路径形式)。
# ⚠️ 若 settings.gradle.kts 增删模块,请同步维护此处。
$ScanModules = @(
    'system'
    'knowledge'
    'llm'
    'hisilicon-knowledge-platform-service'
    'hisilicon-knowledge-core-service'
    'hisilicon-knowledge-iam-service'
    'hisilicon-knowledge-mcp-gateway'
    'commons/commons-i18n'
    'commons/commons-errorcode'
    'commons/commons-web'
    'commons/commons-abac'
    'commons/commons-dict'
    'commons/commons-sensitive'
    'commons/commons-ai'
    'commons/commons-oidc'
)

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "    $msg" -ForegroundColor Yellow }

function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    # 捕获 git 输出;非零退出码由调用方按需判断
    $out = & git @Args 2>&1
    return @{ Output = $out; Code = $LASTEXITCODE }
}

$RepoDir = (Resolve-Path -LiteralPath $RepoDir).Path
Push-Location $RepoDir
try {
    Write-Step "仓库: $RepoDir"
    $remoteUrl = (& git config --get remote.origin.url)
    Write-Host "    remote: $remoteUrl"

    Write-Step "拉取最新引用"
    # fetch 失败不致命(例如本机 SSH 凭据未配置):继续使用本地已有的 origin/* 引用,
    # 但必须显式告警,避免用过期引用构造扫描分支却浑然不知。
    & git fetch origin --quiet 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Warn2 "git fetch origin 失败(可能是鉴权问题),将使用本地已有的远程引用"
        Write-Warn2 "若引用过期,请先修复鉴权后重跑本脚本"
    }

    foreach ($b in @($MainBranch, $BaseLineage)) {
        & git rev-parse --verify --quiet "origin/$b" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "远程分支 origin/$b 不存在" }
    }

    # 在临时克隆中操作,避免污染你的工作区(工作区可能有未提交改动)
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("full-scan-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    Write-Step "创建临时克隆: $temp"
    & git clone --no-hardlinks --quiet $RepoDir $temp
    if ($LASTEXITCODE -ne 0) { throw "临时克隆失败" }

    Push-Location $temp
    try {
        # 兜底:克隆自本地仓库时可能带出本地分支,清理会造成 ref 命名空间冲突的分支
        foreach ($b in @('litianguo', 'dev2.0', 'main', 'dev')) {
            & git branch -D $b 2>$null | Out-Null
        }

        Write-Step "创建/重置空基线 $BaseBranch (谱系: $BaseLineage)"
        & git checkout --quiet -B $BaseBranch "origin/$BaseLineage"
        if ($LASTEXITCODE -ne 0) { throw "创建 $BaseBranch 失败" }
        # 仅从索引移除,不删工作区文件;该分支由此变为 0 文件,但历史仍连接 $BaseLineage
        & git rm -rf --cached . | Out-Null
        & git commit --quiet --allow-empty -m "empty baseline for dev scan"
        if ($LASTEXITCODE -ne 0) { throw "提交空基线失败" }

        Write-Step "创建源分支 $SrcBranch 并拉入 $MainBranch 全部文件"
        & git checkout --quiet -B $SrcBranch $BaseBranch
        if ($LASTEXITCODE -ne 0) { throw "创建 $SrcBranch 失败" }
        & git checkout "origin/$MainBranch" -- .
        if ($LASTEXITCODE -ne 0) { throw "拉取 $MainBranch 文件失败" }
        & git add -A | Out-Null

        Write-Step "裁剪扫描口径(仅保留 settings 模块的 src/main/java)"
        # 关键:必须用 ':(exclude,glob)<模块>/src/main/java/**' 逐模块列举。
        # 嵌套否定写法 ':!:(glob)**/src/main/java/**' 会误删全部文件(实测得 0 而非预期值)。
        $excludes = $ScanModules | ForEach-Object { ":(exclude,glob)$_/src/main/java/**" }
        & git rm -r --cached --quiet --ignore-unmatch '.' @excludes 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "裁剪扫描口径失败" }
        & git commit --quiet -m "dev full scan: settings modules src/main/java only"
        if ($LASTEXITCODE -ne 0) { throw "提交 src 分支失败(可能无变更)" }

        # ---------- 自校验 ----------
        Write-Step "自校验"
        $fail = @()

        $baseCount = (& git ls-tree -r --name-only $BaseBranch | Measure-Object -Line).Lines
        Write-Host "    base 文件数            : $baseCount (期望 0)"
        if ($baseCount -ne 0) { $fail += "base 非空($baseCount)" }

        $srcFiles   = @(& git ls-tree -r --name-only $SrcBranch)
        $srcCount   = $srcFiles.Count
        Write-Host "    src  文件数            : $srcCount"

        $nonJava = @($srcFiles | Where-Object { $_ -notmatch '\.java$' })
        Write-Host "    src  中非 .java 文件   : $($nonJava.Count) (期望 0)"
        if ($nonJava.Count -ne 0) { $fail += "src 含非 java 文件: $($nonJava -join ', ')" }

        $outside = @($srcFiles | Where-Object {
            $f = $_
            -not ($ScanModules | Where-Object { $f -like "$_/src/main/java/*" })
        })
        Write-Host "    src  中口径外文件      : $($outside.Count) (期望 0)"
        if ($outside.Count -ne 0) { $fail += "src 含口径外文件: $($outside -join ', ')" }

        $baseAncestor = (& git merge-base $BaseBranch "origin/$BaseLineage" 2>$null)
        Write-Host "    base <-> $BaseLineage 共同祖先 : $baseAncestor"
        if (-not $baseAncestor) { $fail += "base 与 $BaseLineage 无共同祖先" }

        $srcAncestor = (& git merge-base $SrcBranch $BaseBranch 2>$null)
        Write-Host "    src  <-> base 共同祖先 : $srcAncestor"
        if (-not $srcAncestor) { $fail += "src 与 base 无共同祖先" }

        # Jenkins 等价 diff:git diff <目标>...<源> --name-only
        $diffCount = (& git diff --name-only "$BaseBranch...$SrcBranch" | Measure-Object -Line).Lines
        Write-Host "    MR diff 文件数         : $diffCount (应等于 src 文件数)"
        if ($diffCount -ne $srcCount) { $fail += "diff($diffCount) != src($srcCount)" }

        # 模拟 Jenkins 的 git merge,确认不会 unrelated histories
        $mergeOut = & git merge --no-commit --no-ff $SrcBranch 2>&1
        $mergeCode = $LASTEXITCODE
        & git merge --abort 2>$null | Out-Null
        Write-Host "    merge 模拟             : exit=$mergeCode (期望 0)"
        if ($mergeCode -ne 0) { $fail += "merge 模拟失败: $($mergeOut -join ' ')" }

        Write-Host ""
        if ($fail.Count -gt 0) {
            Write-Host "自校验未通过:" -ForegroundColor Red
            $fail | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
            throw "自校验失败,未生成可用分支"
        }
        Write-Host "自校验通过。" -ForegroundColor Green

        # ---------- 导出分支到真实仓库(仅本地分支,不推送) ----------
        # 注意 1:此处**不能**依赖当前工作目录(仍在临时克隆内),必须用显式路径。
        # 注意 2:临时克隆里的提交对象在真实仓库中**并不存在**,直接 `git branch -f <sha>`
        #         会报 "not a valid branch point"。必须用 `git fetch <temp> <ref>` 把对象搬过来。
        Write-Step "导出分支到仓库(仅本地,不推送)"
        $exported = @{}
        foreach ($b in @($BaseBranch, $SrcBranch)) {
            # 用 refspec 把临时克隆的对象与其引用一并取回真实仓库。
            # 前置 '+' 表示强制更新,使脚本可重复执行(分支已存在时不会报
            # "would clobber existing tag"/拒绝更新)。这两个分支本就是每次重建的,
            # 强制覆盖是预期行为;且仅作用于本地分支,不影响远程。
            & git -C $RepoDir fetch --quiet --no-tags $temp "+refs/heads/${b}:refs/heads/${b}" 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "导出分支 $b 到仓库失败(fetch refspec)" }
            $sha = (& git -C $temp rev-parse $b)
            $exported[$b] = $sha
            Write-Ok "$b -> $sha"
        }

        # 导出后复核:确认分支确实存在于目标仓库,且指向预期提交
        foreach ($b in @($BaseBranch, $SrcBranch)) {
            $verify = (& git -C $RepoDir rev-parse --verify --quiet "refs/heads/$b")
            if ($verify -ne $exported[$b]) {
                throw "分支 $b 导出后校验失败(期望 $($exported[$b]),实际 $verify)"
            }
        }
        Write-Ok "导出校验通过(分支已存在于仓库 $RepoDir)"

        Write-Host ""
        Write-Host "完成。请手工执行以下步骤触发扫描:" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "  1) 推送分支(需先配好鉴权):" -ForegroundColor White
        Write-Host "       git push origin $SrcBranch --force-with-lease"
        Write-Host "       git push origin $BaseBranch            # 仅首次"
        Write-Host ""
        Write-Host "  2) 在 GitCode 创建 MR: $SrcBranch  ->  $BaseBranch" -ForegroundColor White
        Write-Host "       $($remoteUrl -replace '^git@([^:]+):', 'https://$1/' -replace '\.git$','')"
        Write-Host "       检查 Files changed 数量应约为 $srcCount;若远小于该值,说明 base 被污染,需重置。"
        Write-Host ""
        Write-Host "  3) 在 MR 评论区回复 build (仅一个单词) 触发 CI。" -ForegroundColor Yellow
        Write-Host "     仅创建 MR 不会触发扫描。"
        Write-Host ""
        Write-Host "  4) 扫描完成后查看 COO_CI 的「CI 结束」评论与报告链接," -ForegroundColor White
        Write-Host "     然后关闭 MR —— 不要点合并(base 必须保持为空基线)。"
        Write-Host ""
    }
    finally {
        if ((Get-Location).Path -eq $temp) { Pop-Location }
        if ($KeepTemp) { Write-Warn2 "临时克隆保留在: $temp" }
        else { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
    }
}
finally {
    Pop-Location
}