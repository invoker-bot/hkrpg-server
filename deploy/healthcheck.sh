#!/usr/bin/env bash
# 基础 Java 镜像自带 bash；检查 HTTP 状态和 ready 正文，不依赖额外 curl 安装。
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/"${HKRPG_HEALTH_PORT:-18080}"
printf 'GET /health/ready HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == *' 200 '* ]]
while IFS= read -r header <&3; do
  [[ "$header" == $'\r' || -z "$header" ]] && break
done
body=$(cat <&3)
[[ "$body" =~ \"ready\"[[:space:]]*:[[:space:]]*true([[:space:]]*[,}]) ]]
