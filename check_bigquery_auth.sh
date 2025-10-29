#!/bin/bash

echo "🔍 BigQuery認証情報の確認"
echo "=========================="

# 認証情報ファイルのパス
CREDENTIALS_PATH="/Users/sakamoto.yukio/.ssh/tradingscreen-exch-sim-bigquery.json"

if [ ! -f "$CREDENTIALS_PATH" ]; then
    echo "❌ 認証情報ファイルが見つかりません: $CREDENTIALS_PATH"
    exit 1
fi

echo "✅ 認証情報ファイル確認: $CREDENTIALS_PATH"

# 環境変数を設定
export GOOGLE_APPLICATION_CREDENTIALS="$CREDENTIALS_PATH"

# 1. サービスアカウント情報を確認
echo ""
echo "📋 1. サービスアカウント情報"
echo "----------------------------"
if command -v gcloud &> /dev/null; then
    echo "サービスアカウント情報を取得中..."
    
    # 認証情報を一時的に有効化
    gcloud auth activate-service-account --key-file="$CREDENTIALS_PATH" 2>/dev/null
    
    # サービスアカウント詳細を表示
    SERVICE_ACCOUNT=$(gcloud config get-value account 2>/dev/null)
    if [ -n "$SERVICE_ACCOUNT" ]; then
        echo "✅ サービスアカウント: $SERVICE_ACCOUNT"
        
        # サービスアカウントが属するプロジェクトを抽出
        PROJECT_FROM_SA=$(echo "$SERVICE_ACCOUNT" | sed 's/.*@\([^.]*\)\.iam\.gserviceaccount\.com/\1/')
        echo "📦 所属プロジェクト: $PROJECT_FROM_SA"
    else
        echo "⚠️  サービスアカウント情報を取得できませんでした"
    fi
else
    echo "⚠️  gcloud CLIがインストールされていません"
    echo "   JSONファイルから情報を抽出します..."
    
    # JSONファイルから直接情報を抽出
    if command -v jq &> /dev/null; then
        PROJECT_ID=$(jq -r '.project_id' "$CREDENTIALS_PATH")
        CLIENT_EMAIL=$(jq -r '.client_email' "$CREDENTIALS_PATH")
        echo "📦 プロジェクトID: $PROJECT_ID"
        echo "📧 サービスアカウント: $CLIENT_EMAIL"
    else
        echo "⚠️  jqコマンドがありません。JSONファイルを手動で確認してください"
        echo "   確認項目: project_id, client_email"
    fi
fi

# 2. tradingscreenプロジェクトのBigQueryアクセス権限を確認
echo ""
echo "🎯 2. tradingscreenプロジェクトへのアクセステスト"
echo "----------------------------------------------"

TARGET_PROJECT="tradingscreen"
TARGET_DATASET="test_repository"

if command -v bq &> /dev/null; then
    echo "BigQueryアクセステストを実行中..."
    
    # データセット一覧を取得してみる
    echo "📊 データセット一覧を取得中..."
    bq ls --project_id="$TARGET_PROJECT" 2>/dev/null
    BQ_ACCESS_RESULT=$?
    
    if [ $BQ_ACCESS_RESULT -eq 0 ]; then
        echo "✅ tradingscreenプロジェクトのBigQueryへのアクセス成功"
        
        # 特定のデータセット存在確認
        echo "📂 test_repositoryデータセットの確認..."
        bq show --project_id="$TARGET_PROJECT" "$TARGET_DATASET" 2>/dev/null
        DATASET_RESULT=$?
        
        if [ $DATASET_RESULT -eq 0 ]; then
            echo "✅ test_repositoryデータセットが存在します"
        else
            echo "⚠️  test_repositoryデータセットが存在しません"
            echo "   作成コマンド: bq mk --location=US $TARGET_PROJECT:$TARGET_DATASET"
        fi
    else
        echo "❌ tradingscreenプロジェクトのBigQueryアクセスに失敗"
        echo "   権限不足の可能性があります"
    fi
else
    echo "⚠️  bq CLIがインストールされていません"
    echo "   BigQueryアクセステストをスキップします"
fi

# 3. 権限確認のまとめ
echo ""
echo "📊 権限確認結果"
echo "================"

if [ -f "$CREDENTIALS_PATH" ]; then
    echo "✅ 認証情報ファイル: 存在"
else
    echo "❌ 認証情報ファイル: 不存在"
fi

if command -v bq &> /dev/null && [ $BQ_ACCESS_RESULT -eq 0 ]; then
    echo "✅ BigQueryアクセス権限: あり"
    echo "🎉 現在の認証情報でtradingscreenプロジェクトにアクセス可能です"
elif command -v bq &> /dev/null; then
    echo "❌ BigQueryアクセス権限: なし"
    echo "⚠️  以下の可能性があります："
    echo "   1. サービスアカウントにBigQuery権限が付与されていない"
    echo "   2. tradingscreenプロジェクトへのアクセス権限がない"
    echo "   3. プロジェクトが存在しない、またはBigQuery APIが無効"
else
    echo "⚠️  BigQueryアクセス権限: 確認不可（bq CLIなし）"
fi

echo ""
echo "🎯 次のステップ:"
echo "1. 権限がある場合: ./test_bigquery_local.sh を実行"
echo "2. 権限がない場合: Google Cloud Consoleで権限を確認・設定"
echo "3. プロジェクトが異なる場合: 正しいプロジェクトIDに修正"

echo ""
echo "💡 権限設定が必要な場合:"
echo "   サービスアカウントに以下の権限を付与："
echo "   - BigQuery Data Editor"
echo "   - BigQuery Job User"
echo "   - 対象プロジェクト: tradingscreen"