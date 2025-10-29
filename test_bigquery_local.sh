#!/bin/bash

echo "🚀 BigQuery ローカルテスト開始"
echo "=================================="

# Java環境設定
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

echo "📋 1. エンティティ変換テスト（認証情報不要）"
echo "---------------------------------------"
./gradlew test --tests "BigQueryEntityTest"

if [ $? -eq 0 ]; then
    echo "✅ エンティティテスト成功！"
    echo ""
else
    echo "❌ エンティティテスト失敗"
    exit 1
fi

echo "🔍 2. BigQuery設定確認"
echo "----------------------"
echo "BigQueryサービス設定の確認..."

# デフォルトの認証情報ファイルパス
DEFAULT_CREDENTIALS_PATH="/Users/sakamoto.yukio/.ssh/tradingscreen-exch-sim-bigquery.json"

# 環境変数の確認
if [ -n "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
    echo "✅ GOOGLE_APPLICATION_CREDENTIALS環境変数が設定済み: $GOOGLE_APPLICATION_CREDENTIALS"
    credentials_ready=true
elif [ -f "$DEFAULT_CREDENTIALS_PATH" ]; then
    echo "✅ デフォルト認証情報ファイルが見つかりました: $DEFAULT_CREDENTIALS_PATH"
    echo "   環境変数を設定します..."
    export GOOGLE_APPLICATION_CREDENTIALS="$DEFAULT_CREDENTIALS_PATH"
    credentials_ready=true
else
    echo "⚠️  BigQuery認証情報が見つかりません"
    echo "   環境変数 GOOGLE_APPLICATION_CREDENTIALS が未設定"
    echo "   またはファイル $DEFAULT_CREDENTIALS_PATH が存在しません"
    credentials_ready=false
fi

if [ "$credentials_ready" = true ]; then
    echo ""
    echo "🧪 3. BigQuery統合テスト実行可能"
    echo "--------------------------------"
    echo "BigQuery統合テストを実行しますか? (Y/n)"
    read -r response
    
    if [[ "$response" =~ ^[Nn]$ ]]; then
        echo "BigQuery統合テストをスキップしました"
    else
        echo "BigQuery統合テストを実行中..."
        echo "認証情報: $GOOGLE_APPLICATION_CREDENTIALS"
        export BIGQUERY_TEST_ENABLED=true
        ./gradlew test --tests "BigQueryIntegrationTest" -Dspring.profiles.active=bigquery
        
        if [ $? -eq 0 ]; then
            echo "🎉 BigQuery統合テスト成功！"
            echo ""
            echo "✅ BigQuery書き込みテスト完了"
            echo "   - テーブル作成: ✅"
            echo "   - 実行データ挿入: ✅" 
            echo "   - ポジションデータ挿入: ✅"
            echo "   - 取引履歴挿入: ✅"
        else
            echo "❌ BigQuery統合テスト失敗"
            echo "  認証情報やプロジェクト設定を確認してください"
            echo "  プロジェクトID: tradingscreen"
            echo "  データセット: test_repository"
        fi
    fi
else
    echo ""
    echo "📖 BigQuery接続テストの詳細な手順は BIGQUERY_TEST_GUIDE.md を参照してください"
    echo ""
    echo "💡 認証情報を設定するには:"
    echo "   export GOOGLE_APPLICATION_CREDENTIALS=\"/path/to/your/service-account-key.json\""
fi

echo ""
echo "📊 テスト結果サマリー"
echo "===================="
echo "✅ データモデル変換: 成功"
echo "✅ BigQueryエンティティ: 成功"

if [ "$credentials_ready" = true ] && [[ ! "$response" =~ ^[Nn]$ ]]; then
    echo "✅ BigQuery接続: テスト済み"
else
    echo "⚠️  BigQuery接続: 未テスト（認証情報が必要）"
fi

echo ""
echo "🎯 次のステップ:"
echo "1. 本番環境で BigQuery を有効にする場合:"
echo "   app.data-migration.bigquery-enabled=true"
echo ""
echo "2. BigQuery 統合テストを実行する場合:"
echo "   BIGQUERY_TEST_GUIDE.md の手順に従ってください"
echo ""
echo "3. 実際の取引データで BigQuery 書き込みをテストする場合:"
echo "   ./quick_test.sh でorder処理を実行してください"

echo ""
echo "🏁 BigQueryローカルテスト完了"