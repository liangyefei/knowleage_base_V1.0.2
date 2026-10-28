#!/usr/bin/env bash
set -euo pipefail

# MemPalace REST —— Linux 防火墙放行（面向纯 Linux 直部署，非 WSL2 形态）。
# WSL2 形态请改用 firewall.ps1（在 Windows 宿主机上以管理员身份运行）。
#
# 自动探测 ufw / firewalld；两者皆无则报错，提示手工放行。

PORT=8777

if command -v ufw >/dev/null 2>&1; then
  echo "检测到 ufw，放行 ${PORT}/tcp ..."
  sudo ufw allow ${PORT}/tcp
  sudo ufw status
elif command -v firewall-cmd >/dev/null 2>&1; then
  echo "检测到 firewalld，放行 ${PORT}/tcp ..."
  sudo firewall-cmd --add-port=${PORT}/tcp --permanent
  sudo firewall-cmd --reload
else
  echo "错误：未检测到 ufw 或 firewall-cmd，请手工放行 ${PORT}/tcp：" >&2
  echo "  iptables: sudo iptables -A INPUT -p tcp --dport ${PORT} -j ACCEPT" >&2
  exit 1
fi

echo
echo "[安全警告] 公网部署必须前置 Nginx + 鉴权（token），裸暴露 ${PORT} 会导致未授权访问。"
echo "  详见同目录 MemPalace部署文档.md 第 8 节（鉴权模式）。"
