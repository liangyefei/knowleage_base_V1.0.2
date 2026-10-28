@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul

REM ===========================================================================
REM firewall.bat - haisi 全部服务 WSL2 端口转发 + 防火墙放行（统一脚本）
REM
REM 适用环境：105 服务器 = Windows + WSL2（Ubuntu）
REM   Docker 容器跑在 WSL2 内，WSL2 内部端口默认只对 Windows 本机 localhost
REM   可达；局域网其他机器访问 192.168.80.250:<port> 会 Connection refused。
REM   本脚本为每个服务配置：
REM     1) netsh interface portproxy  (Windows 0.0.0.0:port -> WSL2 IP:port)
REM     2) Windows 防火墙入站放行规则  (haisi <name> <port>)
REM
REM 覆盖服务与端口（来自各服务 docker-compose.yml）：
REM     codegraph              9748    CodeGraph REST API
REM     cbm                    9749    Codebase-Memory-MCP REST API
REM     mempalace              8777    MemPalace REST API
REM     lac                    5955    LAC 分词服务
REM     embedding              16604   Qwen3-Embedding
REM     reranker               16603   Coreb-Reranker
REM     redis                  16379   Redis (宿主机映射)
REM     postgresql             16602   PostgreSQL (宿主机映射)
REM     memmachine-postgres    5432    MemMachine PostgreSQL (pgvector)
REM     memmachine-neo4j-http  7474    Neo4j Browser (HTTP)
REM     memmachine-neo4j-https 7473   Neo4j HTTPS
REM     memmachine-neo4j-bolt  7687    Neo4j Bolt 协议
REM     memmachine-app         8080    MemMachine REST API v2 + MCP
REM
REM 用法（必须以管理员身份运行 cmd）：
REM     firewall.bat                    自动探测 WSL2 IP，放行全部服务
REM     firewall.bat 172.24.104.177     指定 WSL2 IP，放行全部服务
REM
REM 幂等：可重复执行（先删后建，不会因规则已存在而失败）。
REM
REM 注意：WSL2 重启后内部 IP 可能变化（如 172.24.x.x），需重跑本脚本。
REM ===========================================================================

set "WSLIP=%~1"

REM 1. 管理员权限检查（net session 需要管理员，失败则不是管理员）
net session >nul 2>&1
if !errorlevel! neq 0 (
    echo [error] 请以管理员身份运行命令提示符。
    echo         右键 cmd -^> "以管理员身份运行"，再执行本脚本。
    exit /b 1
)

REM 2. 探测 WSL2 IP（未指定时自动跑 wsl ip -4 addr show eth0 取 inet 行）
if "%WSLIP%"=="" (
    echo [firewall] 未指定 WSL IP，自动探测 WSL2 IP...
    set "FOUND="
    REM ip -4 addr show eth0 的 inet 行示例：
    REM   inet 172.24.104.177/20 brd 172.24.111.255 scope global eth0
    REM tokens=2 取 "172.24.104.177/20"，再 delims=/ 取 IP
    for /f "tokens=2" %%a in ('wsl ip -4 addr show eth0 2^>nul ^| findstr "inet "') do (
        if not defined FOUND (
            for /f "tokens=1 delims=/" %%b in ("%%a") do (
                set "WSLIP=%%b"
                set "FOUND=1"
            )
        )
    )
    if "!FOUND!"=="" (
        echo [error] 未能自动探测 WSL2 IP。
        echo         请用 "wsl ip addr show eth0" 查看 inet 行，再运行：
        echo         firewall.bat ^<你的WSL_IP^>
        exit /b 1
    )
)
echo [firewall] WSL2 IP = !WSLIP!
echo.

REM 3. 为每个服务配置 portproxy + 防火墙规则（幂等：先删后建）
echo [firewall] 开始配置端口转发 + 防火墙规则...
call :openPort "codegraph"               9748
call :openPort "cbm"                     9749
call :openPort "mempalace"               8777
call :openPort "lac"                     5955
call :openPort "embedding"               16604
call :openPort "reranker"                16603
call :openPort "redis"                   16379
call :openPort "postgresql"              16602
call :openPort "memmachine-postgres"     5432
call :openPort "memmachine-neo4j-http"   7474
call :openPort "memmachine-neo4j-https" 7473
call :openPort "memmachine-neo4j-bolt"   7687
call :openPort "memmachine-app"          8080
echo.

REM 4. 打印验证
echo [firewall] 当前端口转发规则：
netsh interface portproxy show all
echo.
echo [firewall] 当前防火墙规则（haisi*）：
powershell -NoProfile -Command "Get-NetFirewallRule -DisplayName 'haisi*' -ErrorAction SilentlyContinue | Format-Table DisplayName, Enabled, Direction, Action -AutoSize"
echo.

REM 5. 局域网访问地址清单
echo [firewall] 完成
echo.
echo [info] 局域网访问地址（宿主机 192.168.80.250）：
echo   CodeGraph:          http://192.168.80.250:9748/health
echo   CBM:                http://192.168.80.250:9749/api/v1/projects
echo   MemPalace:          http://192.168.80.250:8777/healthz
echo   LAC:                http://192.168.80.250:5955
echo   Embedding:          http://192.168.80.250:16604
echo   Reranker:           http://192.168.80.250:16603
echo   Redis:              192.168.80.250:16379
echo   PostgreSQL:         192.168.80.250:16602  (db=faraidb user=farai)
echo   MemMachine-PG:      192.168.80.250:5432   (db=memmachine user=memmachine)
echo   MemMachine-Neo4j:   http://192.168.80.250:7474  bolt: 192.168.80.250:7687
echo   MemMachine-App:     http://192.168.80.250:8080/api/v2/health
echo.
echo [warn] WSL2 重启后 IP 可能变化（如从 !WSLIP! 变为其它 172.24.x.x）。
echo       若外部访问不通，先 "wsl ip addr show eth0" 取新 IP，再运行：
echo         firewall.bat ^<新IP^>
exit /b 0

REM ===========================================================================
REM 子过程：openPort
REM   %~1 = 服务名称（用于防火墙规则 DisplayName）
REM   %~2 = 端口号
REM   依赖：!WSLIP!（主流程已设置）
REM   动作：先删后建 portproxy + 防火墙规则（幂等，重复运行不报错）
REM ===========================================================================
:openPort
set "NAME=%~1"
set "PORT=%~2"
netsh interface portproxy delete v4tov4 listenport=!PORT! listenaddress=0.0.0.0 >nul 2>&1
netsh interface portproxy add v4tov4 listenport=!PORT! listenaddress=0.0.0.0 connectport=!PORT! connectaddress=!WSLIP!
if !errorlevel! neq 0 (
    echo [warn] portproxy add 失败: !NAME! !PORT!
) else (
    echo [ok]   portproxy  !NAME! !PORT!  to !WSLIP!:!PORT!
)
netsh advfirewall firewall delete rule name="haisi !NAME! !PORT!" >nul 2>&1
netsh advfirewall firewall add rule name="haisi !NAME! !PORT!" dir=in action=allow protocol=TCP localport=!PORT!
if !errorlevel! neq 0 (
    echo [warn] firewall rule 失败: !NAME! !PORT!
) else (
    echo [ok]   firewall  !NAME! !PORT!
)
goto :eof
