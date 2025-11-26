#!/usr/bin/env python3
"""
BigQueryからPostgreSQLへユーザーデータを移行するスクリプト
"""
import os
import sys
from google.cloud import bigquery
import psycopg2
from psycopg2.extras import execute_values

# BigQuery設定
PROJECT_ID = "tradingscreen"
DATASET_NAME = "repository"
TABLE_NAME = "users"

# PostgreSQL設定
PG_HOST = "localhost"
PG_PORT = 5432
PG_DATABASE = "exch_sim"
PG_USER = "postgres"
PG_PASSWORD = "postgres123"


def get_bigquery_users():
    """BigQueryからユーザーデータを取得"""
    print("🔍 Connecting to BigQuery...")
    
    # BigQueryクライアントを作成
    client = bigquery.Client(project=PROJECT_ID)
    
    # ユーザーデータを取得するクエリ
    query = f"""
    SELECT 
        username,
        password,
        roles
    FROM `{PROJECT_ID}.{DATASET_NAME}.{TABLE_NAME}`
    ORDER BY username
    """
    
    print(f"📊 Executing query: {query[:100]}...")
    query_job = client.query(query)
    results = query_job.result()
    
    users = []
    for row in results:
        # rolesは配列なので、リストに変換
        roles = row.roles if row.roles else []
        users.append({
            'username': row.username,
            'password': row.password,
            'roles': roles if isinstance(roles, list) else [roles] if roles else []
        })
    
    print(f"✅ Found {len(users)} users in BigQuery")
    return users


def insert_users_to_postgresql(users):
    """PostgreSQLにユーザーデータを挿入"""
    if not users:
        print("⚠️  No users to migrate")
        return
    
    print(f"🔌 Connecting to PostgreSQL...")
    conn = psycopg2.connect(
        host=PG_HOST,
        port=PG_PORT,
        database=PG_DATABASE,
        user=PG_USER,
        password=PG_PASSWORD
    )
    
    try:
        cur = conn.cursor()
        
        # 既存のユーザーを確認
        cur.execute("SELECT username FROM users")
        existing_users = {row[0] for row in cur.fetchall()}
        print(f"📋 Found {len(existing_users)} existing users in PostgreSQL")
        
        # ユーザーを挿入（ON CONFLICTでスキップ）
        inserted_count = 0
        skipped_count = 0
        
        for user in users:
            username = user['username']
            password = user['password']
            roles = user['roles']
            
            if username in existing_users:
                print(f"⏭️  Skipping existing user: {username}")
                skipped_count += 1
                continue
            
            # ユーザーを挿入（rolesカラムは存在しないので除外）
            cur.execute(
                """
                INSERT INTO users (username, password, created_at, updated_at)
                VALUES (%s, %s, NOW(), NOW())
                ON CONFLICT (username) DO NOTHING
                RETURNING username
                """,
                (username, password)
            )
            
            if cur.fetchone():
                inserted_count += 1
                print(f"✅ Inserted user: {username} with roles: {roles}")
                
                # user_rolesテーブルに挿入
                for role in roles:
                    # 既に存在するかチェック
                    cur.execute(
                        "SELECT 1 FROM user_roles WHERE username = %s AND role = %s",
                        (username, role)
                    )
                    if not cur.fetchone():
                        cur.execute(
                            "INSERT INTO user_roles (username, role) VALUES (%s, %s)",
                            (username, role)
                        )
        
        conn.commit()
        print(f"\n✅ Migration completed!")
        print(f"   - Inserted: {inserted_count} users")
        print(f"   - Skipped: {skipped_count} users")
        
    except Exception as e:
        conn.rollback()
        print(f"❌ Error inserting users: {e}")
        raise
    finally:
        cur.close()
        conn.close()


def main():
    """メイン処理"""
    print("🚀 Starting user migration from BigQuery to PostgreSQL\n")
    
    try:
        # BigQueryからユーザーを取得
        users = get_bigquery_users()
        
        if not users:
            print("⚠️  No users found in BigQuery")
            return
        
        # PostgreSQLに挿入
        insert_users_to_postgresql(users)
        
        print("\n✨ Migration completed successfully!")
        
    except Exception as e:
        print(f"\n❌ Migration failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()

