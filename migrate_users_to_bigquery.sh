#!/bin/bash

echo "👥 ユーザーデータをBigQueryに移行"
echo "================================="

# 設定
USERS_JSON_FILE="./users.json"
PROJECT_ID="tradingscreen"
DATASET_NAME="repository"
TABLE_NAME="users"
TEMP_JSON_FILE="/tmp/users_for_bigquery.json"

# 認証情報の確認
if [ -z "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
    DEFAULT_CREDENTIALS_PATH="/Users/sakamoto.yukio/.ssh/tradingscreen-exch-sim-bigquery.json"
    if [ -f "$DEFAULT_CREDENTIALS_PATH" ]; then
        echo "🔐 デフォルト認証情報を使用: $DEFAULT_CREDENTIALS_PATH"
        export GOOGLE_APPLICATION_CREDENTIALS="$DEFAULT_CREDENTIALS_PATH"
    else
        echo "❌ 認証情報が見つかりません"
        echo "   環境変数 GOOGLE_APPLICATION_CREDENTIALS を設定するか"
        echo "   ファイル $DEFAULT_CREDENTIALS_PATH を配置してください"
        exit 1
    fi
else
    echo "🔐 認証情報を使用: $GOOGLE_APPLICATION_CREDENTIALS"
fi

# users.jsonファイルの存在確認
if [ ! -f "$USERS_JSON_FILE" ]; then
    echo "❌ users.jsonファイルが見つかりません: $USERS_JSON_FILE"
    exit 1
fi

echo "✅ users.jsonファイル確認: $USERS_JSON_FILE"

# 1. JSONデータをBigQuery形式に変換
echo ""
echo "🔄 1. JSONデータの変換"
echo "---------------------"

# jqを使ってBigQuery用のJSON形式に変換
if command -v jq &> /dev/null; then
    echo "jqを使用してデータを変換中..."
    
    # 現在のタイムスタンプを取得
    CURRENT_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
    
    # BigQuery用にデータを変換（各ユーザーを1行ずつ出力）
    jq --arg timestamp "$CURRENT_TIMESTAMP" -c '
    .users[] | {
        username: .username,
        password: .password,
        roles: .roles,
        created_at: $timestamp,
        updated_at: $timestamp
    }' "$USERS_JSON_FILE" > "$TEMP_JSON_FILE"
    
    if [ $? -eq 0 ]; then
        echo "✅ データ変換完了"
        
        # 変換されたデータの確認
        USER_COUNT=$(wc -l < "$TEMP_JSON_FILE")
        echo "📊 変換されたユーザー数: $USER_COUNT"
        
        # サンプルデータを表示
        echo ""
        echo "📋 変換後データサンプル:"
        head -n 1 "$TEMP_JSON_FILE" | jq .
    else
        echo "❌ データ変換に失敗しました"
        exit 1
    fi
else
    echo "❌ jqコマンドが見つかりません"
    echo "   Homebrewでインストール: brew install jq"
    exit 1
fi

# 2. BigQueryテーブルの確認
echo ""
echo "🔍 2. BigQueryテーブルの確認"
echo "---------------------------"

if command -v bq &> /dev/null; then
    # テーブルの存在確認
    bq show "$PROJECT_ID:$DATASET_NAME.$TABLE_NAME" > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo "✅ テーブル確認: $PROJECT_ID:$DATASET_NAME.$TABLE_NAME"
        
        # 既存データの確認
        EXISTING_ROWS=$(bq query --use_legacy_sql=false --format=csv "SELECT COUNT(*) FROM \`$PROJECT_ID.$DATASET_NAME.$TABLE_NAME\`" | tail -n 1)
        echo "📊 既存レコード数: $EXISTING_ROWS"
        
        if [ "$EXISTING_ROWS" -gt 0 ]; then
            echo ""
            echo "⚠️  既存データが存在します"
            echo "   続行すると既存データは保持され、新しいデータが追加されます"
            echo "   既存データを削除して置き換えますか? (y/N)"
            read -r response
            
            if [[ "$response" =~ ^[Yy]$ ]]; then
                echo "🗑️  既存データを削除中..."
                bq query --use_legacy_sql=false "DELETE FROM \`$PROJECT_ID.$DATASET_NAME.$TABLE_NAME\` WHERE TRUE"
                if [ $? -eq 0 ]; then
                    echo "✅ 既存データ削除完了"
                else
                    echo "❌ 既存データ削除に失敗しました"
                    exit 1
                fi
            else
                echo "📝 既存データを保持してデータを追加します"
            fi
        fi
    else
        echo "❌ テーブルが見つかりません: $PROJECT_ID:$DATASET_NAME.$TABLE_NAME"
        echo "   テーブルを作成してから再実行してください"
        exit 1
    fi
else
    echo "❌ bq CLIがインストールされていません"
    exit 1
fi

# 3. データの挿入
echo ""
echo "📥 3. BigQueryへのデータ挿入"
echo "---------------------------"

echo "BigQueryにデータを挿入中..."

# bq loadコマンドでJSONデータを挿入（既存テーブルのスキーマを使用）
bq load \
    --source_format=NEWLINE_DELIMITED_JSON \
    "$PROJECT_ID:$DATASET_NAME.$TABLE_NAME" \
    "$TEMP_JSON_FILE"

if [ $? -eq 0 ]; then
    echo "✅ BigQueryへのデータ挿入完了"
    
    # 挿入後のレコード数確認
    FINAL_ROWS=$(bq query --use_legacy_sql=false --format=csv "SELECT COUNT(*) FROM \`$PROJECT_ID.$DATASET_NAME.$TABLE_NAME\`" | tail -n 1)
    echo "📊 挿入後レコード数: $FINAL_ROWS"
    
    # サンプルデータの表示
    echo ""
    echo "📋 挿入されたデータサンプル:"
    bq query --use_legacy_sql=false --format=prettyjson --max_rows=3 "
        SELECT username, roles, created_at, updated_at 
        FROM \`$PROJECT_ID.$DATASET_NAME.$TABLE_NAME\` 
        ORDER BY created_at DESC 
        LIMIT 3
    "
    
else
    echo "❌ BigQueryへのデータ挿入に失敗しました"
    exit 1
fi

# 4. 一時ファイルのクリーンアップ
echo ""
echo "🧹 4. 一時ファイルのクリーンアップ"
echo "--------------------------------"
if [ -f "$TEMP_JSON_FILE" ]; then
    rm "$TEMP_JSON_FILE"
    echo "✅ 一時ファイルを削除: $TEMP_JSON_FILE"
fi

# 5. 移行結果の確認
echo ""
echo "🎉 ユーザーデータ移行完了"
echo "========================"
echo "✅ ソース: $USERS_JSON_FILE"
echo "✅ 宛先: $PROJECT_ID:$DATASET_NAME.$TABLE_NAME"
echo "✅ 最終レコード数: $FINAL_ROWS"

echo ""
echo "🔍 データ確認コマンド:"
echo "bq query --use_legacy_sql=false \"SELECT * FROM \\\`$PROJECT_ID.$DATASET_NAME.$TABLE_NAME\\\` LIMIT 10\""

echo ""
echo "💡 次のステップ:"
echo "1. アプリケーションでBigQueryベースの認証を有効化"
echo "2. CustomUserDetailsServiceをBigQuery対応に修正"
echo "3. ローカルのusers.jsonをバックアップとして保持"