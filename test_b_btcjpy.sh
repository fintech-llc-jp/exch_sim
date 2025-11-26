#!/bin/bash

TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0MDYiLCJpYXQiOjE3NjIzNDQ1NDMsImV4cCI6MTc2MjQzMDk0M30.QZ4amdu0o_f-ztuDOi1a9kDX7CClMV2RIxPHvcfLfx8"

echo "=== G_BTCJPY Execution History (Page 0, Size 50) ==="
curl -s "http://localhost:3000/api/executions/all?page=0&size=50&symbol=B_BTCJPY" \
  -H "Authorization: Bearer $TOKEN" | jq .

echo ""
echo "=== G_BTCJPY Execution Count ==="
curl -s "http://localhost:3000/api/executions/all?page=0&size=50&symbol=B_BTCJPY" \
  -H "Authorization: Bearer $TOKEN" | jq '.totalElements'

