# Infinity/NaN デバッグコード追加ガイド

## 追加する場所

以下のファイルにデバッグコードを追加してください：

1. `strategy/price_prediction_strategy.py` の `predict` メソッド
2. `models/price_prediction_model.py` の `predict` メソッド
3. 特徴量エンジニアリングのコード（`ConfigurableFeatureEngineer` など）

## 1. price_prediction_strategy.py の修正

`predict` メソッドの冒頭に以下を追加：

```python
import numpy as np
import pandas as pd

def predict(self, df):
    """予測実行前にデータの整合性をチェック"""
    
    # 特徴量を準備
    X = df[self.feature_columns].values
    
    # ========== デバッグコード開始 ==========
    # 1. 各特徴量でinfinity/NaNをチェック
    print("\n" + "="*80)
    print("DEBUG: Checking for infinity and NaN in features")
    print("="*80)
    
    feature_df = pd.DataFrame(X, columns=self.feature_columns)
    
    infinity_cols = []
    nan_cols = []
    large_value_cols = []
    
    for col in feature_df.columns:
        col_data = feature_df[col]
        
        # Infinityチェック
        if np.isinf(col_data).any():
            infinity_count = np.isinf(col_data).sum()
            infinity_indices = np.where(np.isinf(col_data))[0]
            infinity_values = col_data.iloc[infinity_indices[:5]]  # 最初の5件
            print(f"\n⚠️  INFINITY detected in '{col}':")
            print(f"   Count: {infinity_count}/{len(col_data)}")
            print(f"   First 5 indices: {infinity_indices[:5]}")
            print(f"   First 5 values: {infinity_values.values}")
            infinity_cols.append(col)
        
        # NaNチェック
        if col_data.isna().any():
            nan_count = col_data.isna().sum()
            nan_indices = np.where(col_data.isna())[0]
            print(f"\n⚠️  NaN detected in '{col}':")
            print(f"   Count: {nan_count}/{len(col_data)}")
            print(f"   First 5 indices: {nan_indices[:5]}")
            nan_cols.append(col)
        
        # 極端に大きな値チェック
        max_val = col_data.abs().max()
        if max_val > 1e10:
            large_indices = np.where(col_data.abs() > 1e10)[0]
            large_values = col_data.iloc[large_indices[:5]]
            print(f"\n⚠️  LARGE VALUE detected in '{col}':")
            print(f"   Max absolute value: {max_val}")
            print(f"   Count > 1e10: {len(large_indices)}")
            print(f"   First 5 indices: {large_indices[:5]}")
            print(f"   First 5 values: {large_values.values}")
            large_value_cols.append(col)
    
    # サマリー
    print("\n" + "-"*80)
    print("SUMMARY:")
    print(f"  Features with infinity: {len(infinity_cols)} - {infinity_cols}")
    print(f"  Features with NaN: {len(nan_cols)} - {nan_cols}")
    print(f"  Features with large values (>1e10): {len(large_value_cols)} - {large_value_cols}")
    print("="*80 + "\n")
    
    # 2. 問題のある特徴量の詳細情報
    if infinity_cols or nan_cols or large_value_cols:
        print("\n" + "="*80)
        print("DEBUG: Detailed statistics for problematic features")
        print("="*80)
        
        problem_cols = set(infinity_cols + nan_cols + large_value_cols)
        for col in problem_cols:
            col_data = feature_df[col]
            print(f"\nFeature: {col}")
            print(f"  Min: {col_data.min()}")
            print(f"  Max: {col_data.max()}")
            print(f"  Mean: {col_data.mean()}")
            print(f"  Std: {col_data.std()}")
            print(f"  Infinity count: {np.isinf(col_data).sum()}")
            print(f"  NaN count: {col_data.isna().sum()}")
            print(f"  Zero count: {(col_data == 0).sum()}")
            print(f"  Negative count: {(col_data < 0).sum()}")
    
    # 3. データクリーニング（infinity/NaNを修正）
    if infinity_cols or nan_cols:
        print("\n" + "="*80)
        print("DEBUG: Cleaning data (replacing infinity/NaN)")
        print("="*80)
        
        # InfinityをNaNに変換
        X = np.where(np.isinf(X), np.nan, X)
        
        # NaNを0で埋める（または前の値で埋める）
        X = pd.DataFrame(X, columns=self.feature_columns).fillna(0).values
        
        print("  ✓ Replaced infinity with NaN, then NaN with 0")
    
    # 4. 極端な値をクリッピング
    if large_value_cols:
        print("\n" + "="*80)
        print("DEBUG: Clipping extreme values")
        print("="*80)
        
        X = np.clip(X, -1e10, 1e10)
        print("  ✓ Clipped values to [-1e10, 1e10]")
    
    # 5. 最終チェック
    print("\n" + "="*80)
    print("DEBUG: Final check after cleaning")
    print("="*80)
    
    final_df = pd.DataFrame(X, columns=self.feature_columns)
    final_inf_count = np.isinf(final_df.values).sum()
    final_nan_count = final_df.isna().sum().sum()
    
    print(f"  Infinity count: {final_inf_count}")
    print(f"  NaN count: {final_nan_count}")
    
    if final_inf_count > 0 or final_nan_count > 0:
        print("  ⚠️  WARNING: Still have infinity/NaN after cleaning!")
    else:
        print("  ✓ Data is clean")
    
    print("="*80 + "\n")
    # ========== デバッグコード終了 ==========
    
    # 予測実行
    try:
        predictions = self.model.predict(X)
        return predictions
    except Exception as e:
        print(f"\n❌ ERROR during prediction:")
        print(f"   {type(e).__name__}: {e}")
        raise
```

## 2. price_prediction_model.py の修正

`predict` メソッドの `scaler.transform(X)` の前に以下を追加：

```python
def predict(self, X):
    """予測実行前にスケーラーへの入力データをチェック"""
    
    # ========== デバッグコード開始 ==========
    print("\n" + "="*80)
    print("DEBUG: price_prediction_model.predict - Input validation")
    print("="*80)
    
    # 入力データの形状と型を確認
    print(f"Input shape: {X.shape}")
    print(f"Input dtype: {X.dtype}")
    
    # Infinity/NaNチェック
    inf_mask = np.isinf(X)
    nan_mask = np.isnan(X)
    
    if inf_mask.any():
        inf_count = inf_mask.sum()
        inf_indices = np.where(inf_mask)
        print(f"\n⚠️  INFINITY detected: {inf_count} values")
        print(f"   Locations (first 10): {list(zip(inf_indices[0][:10], inf_indices[1][:10]))}")
        print(f"   Values (first 10): {X[inf_mask][:10]}")
    
    if nan_mask.any():
        nan_count = nan_mask.sum()
        nan_indices = np.where(nan_mask)
        print(f"\n⚠️  NaN detected: {nan_count} values")
        print(f"   Locations (first 10): {list(zip(nan_indices[0][:10], nan_indices[1][:10]))}")
    
    # 極端な値チェック
    large_mask = np.abs(X) > 1e10
    if large_mask.any():
        large_count = large_mask.sum()
        print(f"\n⚠️  LARGE VALUES detected: {large_count} values > 1e10")
        print(f"   Max absolute value: {np.abs(X).max()}")
        print(f"   Min value: {X.min()}")
        print(f"   Max value: {X.max()}")
    
    print("="*80 + "\n")
    # ========== デバッグコード終了 ==========
    
    # スケーラー適用
    try:
        X_scaled = self.scaler.transform(X)
        # ... 残りの予測処理
    except Exception as e:
        print(f"\n❌ ERROR in scaler.transform:")
        print(f"   {type(e).__name__}: {e}")
        print(f"   Input shape: {X.shape}")
        print(f"   Input stats: min={X.min()}, max={X.max()}, mean={X.mean()}")
        raise
```

## 3. 特徴量エンジニアリングのコード修正

特徴量計算後、すぐにチェックを追加：

```python
def transform(self, df):
    """特徴量計算後にinfinity/NaNをチェック"""
    
    # 特徴量計算
    feature_df = self._calculate_features(df)
    
    # ========== デバッグコード開始 ==========
    print("\n" + "="*80)
    print("DEBUG: Feature engineering - Checking for infinity/NaN")
    print("="*80)
    
    problem_features = []
    
    for col in feature_df.columns:
        col_data = feature_df[col]
        
        has_inf = np.isinf(col_data).any()
        has_nan = col_data.isna().any()
        has_large = (col_data.abs() > 1e10).any()
        
        if has_inf or has_nan or has_large:
            problem_features.append({
                'feature': col,
                'has_inf': has_inf,
                'has_nan': has_nan,
                'has_large': has_large,
                'inf_count': np.isinf(col_data).sum() if has_inf else 0,
                'nan_count': col_data.isna().sum() if has_nan else 0,
                'max_abs': col_data.abs().max(),
                'min': col_data.min(),
                'max': col_data.max(),
            })
    
    if problem_features:
        print(f"\n⚠️  Found {len(problem_features)} problematic features:")
        for pf in problem_features:
            print(f"\n  Feature: {pf['feature']}")
            if pf['has_inf']:
                print(f"    - Infinity: {pf['inf_count']} values")
            if pf['has_nan']:
                print(f"    - NaN: {pf['nan_count']} values")
            if pf['has_large']:
                print(f"    - Large values: max_abs={pf['max_abs']}")
            print(f"    - Range: [{pf['min']}, {pf['max']}]")
    else:
        print("✓ All features are clean")
    
    print("="*80 + "\n")
    # ========== デバッグコード終了 ==========
    
    # 問題のある特徴量を修正
    for col in feature_df.columns:
        col_data = feature_df[col]
        
        # InfinityをNaNに変換してから0で埋める
        if np.isinf(col_data).any():
            feature_df[col] = col_data.replace([np.inf, -np.inf], np.nan).fillna(0)
        
        # NaNを0で埋める
        if col_data.isna().any():
            feature_df[col] = col_data.fillna(0)
        
        # 極端な値をクリッピング
        if (col_data.abs() > 1e10).any():
            feature_df[col] = col_data.clip(-1e10, 1e10)
    
    return feature_df
```

## 実行方法

1. 上記のデバッグコードを該当ファイルに追加
2. 再度トレーニング/バックテストを実行
3. 出力されたデバッグ情報を確認して、どの特徴量で問題が発生しているか特定
4. 問題のある特徴量の計算ロジックを修正（ゼロ除算や対数計算のガードを追加）

## よくある原因と修正例

### ゼロ除算
```python
# 悪い例
feature = price / volume

# 良い例
feature = price / (volume + 1e-8)  # または
feature = np.where(volume > 0, price / volume, 0)
```

### 対数計算
```python
# 悪い例
feature = np.log(price)

# 良い例
feature = np.log(np.maximum(price, 1e-8))
```

### 比率計算
```python
# 悪い例
feature = (bid - ask) / mid_price

# 良い例
feature = np.where(mid_price > 0, (bid - ask) / mid_price, 0)
```
