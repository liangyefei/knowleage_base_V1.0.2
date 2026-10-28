# MemPalace REST —— Windows 宿主机 WSL2 端口转发 + 防火墙放行。
#
# 面向 105 服务器 Windows + WSL2 部署形态：Docker 跑在 WSL2 内，
# 虚拟 IP 默认 172.24.104.177（WSL 重启后可能变化），宿主机局域网 IP 192.168.80.250。
# WSL2 内部监听的端口默认只对 Windows 本机 localhost 可达，局域网其他机器访问
# 192.168.80.250:8777 会 Connection refused，必须配置端口转发 + 防火墙放行。
#
# 必须以管理员身份运行。在普通 PowerShell 里可 elevate：
#   Start-Process pwsh -Verb RunAs -ArgumentList '.\firewall.ps1'
# 或直接以管理员 PowerShell 执行：
#   .\firewall.ps1 -Action add           # 添加端口转发 + 防火墙规则（默认）
#   .\firewall.ps1 -Action show          # 查看当前规则
#   .\firewall.ps1 -Action remove        # 删除端口转发 + 防火墙规则
#   .\firewall.ps1 -Action add -WslIP 172.24.104.177   # 显式指定 WSL IP
#
# WSL 重启后 IP 可能变化，需重跑 -Action add（或先 -Action remove 再 add）。

#Requires -RunAsAdministrator

param(
  [string]$Action = "add",
  [int]$Port = 8777,
  [string]$WslIP = ""
)

# 从 `wsl ip addr show eth0` 输出中解析 172.x.x.x 的 inet 地址（去掉 CIDR 后缀）。
function Resolve-WslIP {
  $raw = wsl ip addr show eth0 2>$null
  if (-not $raw) {
    throw "无法从 'wsl ip addr show eth0' 获取输出。请确认 WSL 已启动。"
  }
  $ip = $null
  foreach ($line in ($raw -split "`n")) {
    if ($line -match '^\s*inet\s+(172\.\d+\.\d+\.\d+)/') {
      $ip = $Matches[1]
      break
    }
  }
  if (-not $ip) {
    throw "未在 wsl 输出中找到 172.x.x.x 的 inet 地址。原始输出:`n$raw"
  }
  return $ip
}

switch ($Action) {
  "add" {
    if ([string]::IsNullOrWhiteSpace($WslIP)) {
      $WslIP = Resolve-WslIP
    }
    Write-Host "WSL IP: $WslIP"

    netsh interface portproxy add v4tov4 listenport=$Port listenaddress=0.0.0.0 connectport=$Port connectaddress=$WslIP

    try {
      New-NetFirewallRule -DisplayName "MemPalace $Port" -Direction Inbound -LocalPort $Port -Protocol TCP -Action Allow -ErrorAction Stop | Out-Null
      Write-Host "防火墙规则已创建: MemPalace $Port"
    } catch {
      Write-Host "防火墙规则已存在，忽略: $_"
    }

    Write-Host
    Write-Host "=== portproxy ==="
    netsh interface portproxy show all
    Write-Host
    Write-Host "=== firewall rules ==="
    Get-NetFirewallRule -DisplayName "MemPalace*" | Format-Table DisplayName, Enabled, Direction, Action -AutoSize
    Write-Host
    Write-Host "局域网访问: http://192.168.80.250:$Port/healthz"
    Write-Host "WSL 重启后 IP 可能变化，需重跑 .\firewall.ps1 -Action add 或先 -Action remove 再 add。"
  }
  "show" {
    Write-Host "=== portproxy ==="
    netsh interface portproxy show all
    Write-Host
    Write-Host "=== firewall rules ==="
    Get-NetFirewallRule -DisplayName "MemPalace*" | Format-Table DisplayName, Enabled, Direction, Action -AutoSize
  }
  "remove" {
    netsh interface portproxy delete v4tov4 listenport=$Port listenaddress=0.0.0.0
    try {
      Remove-NetFirewallRule -DisplayName "MemPalace $Port" -ErrorAction Stop
      Write-Host "已删除防火墙规则: MemPalace $Port"
    } catch {
      Write-Host "防火墙规则不存在，忽略: $_"
    }
  }
  default {
    Write-Error "未知 Action: $Action（应为 add / show / remove）"
    exit 2
  }
}
