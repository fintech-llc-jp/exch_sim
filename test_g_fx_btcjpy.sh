#!/bin/bash

TOKEN="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ5dWtpbzAwMSIsImlhdCI6MTc2MjM1MDA4MCwiZXhwIjoxNzYyNDM2NDgwfQ.0r6l-LyhJssq7EWywpz8tgFhrLhxfFy6SuRauH4DX7E"

echo "=== G_FX_BTCJPY Execution History (Page 0, Size 50) ==="
curl -s "http://localhost:8080/api/executions/all?page=0&size=50&symbol=G_FX_BTCJPY" \
  -H "Authorization: Bearer $TOKEN" | jq .

echo ""
echo "=== G_FX_BTCJPY Execution Count ==="
curl -s "http://localhost:8080/api/executions/all?page=0&size=50&symbol=G_FX_BTCJPY" \
  -H "Authorization: Bearer $TOKEN" | jq '.totalElements'

