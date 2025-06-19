#!/bin/bash
  # signup_users.sh

  API_URL="http://localhost:8080/api/auth/signup"

  # ユーザーリスト
  users=(
    "trader001:password123"
    "trader002:password456"
    "trader003:password789"
  )

  for user in "${users[@]}"; do
    username=$(echo $user | cut -d: -f1)
    password=$(echo $user | cut -d: -f2)

    echo "Registering user: $username"
    curl -X POST $API_URL \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"$username\",\"password\":\"$password\"}" \
      -w "\nStatus: %{http_code}\n\n"
  done
