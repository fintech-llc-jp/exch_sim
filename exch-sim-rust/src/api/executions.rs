use crate::api::AppState;
use crate::middleware::AuthState;
use crate::models::Execution;
use axum::{
    extract::{Extension, Query},
    http::StatusCode,
    response::Json,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionHistoryResponse {
    pub username: String,
    pub page: i32,
    pub size: i32,
    pub total_pages: i32,
    pub total_elements: i64,
    pub executions: Vec<ExecutionHistoryDto>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionHistoryDto {
    #[serde(rename = "execID")]
    pub exec_id: String,
    #[serde(rename = "clOrdID")]
    pub cl_ord_id: String,
    pub symbol: String,
    pub exec_status: String,
    pub last_px: f64,
    pub last_qty: f64,
    pub counter_party_username: Option<String>,
    pub side: String,
    pub created_at: chrono::DateTime<chrono::Utc>,
}

#[derive(Debug, Serialize)]
pub struct ErrorResponse {
    pub error: String,
}

#[derive(Debug, Deserialize)]
pub struct ExecutionHistoryQuery {
    #[serde(default = "default_page")]
    page: i32,
    #[serde(default = "default_size")]
    size: i32,
    symbol: Option<String>,
}

fn default_page() -> i32 {
    0
}

fn default_size() -> i32 {
    20
}

pub async fn get_execution_history(
    Extension(state): Extension<AppState>,
    Extension(auth_state): Extension<AuthState>,
    Query(params): Query<ExecutionHistoryQuery>,
) -> Result<Json<ExecutionHistoryResponse>, (StatusCode, Json<ErrorResponse>)> {
    let username = &auth_state.username;

    // Query executions from database with pagination
    let executions = state
        .database
        .query_executions_paginated(username, params.page, params.size, params.symbol.as_deref())
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get execution history: {}", e),
                }),
            )
        })?;

    // Get total count for pagination
    let total_count = state
        .database
        .count_executions(username, params.symbol.as_deref())
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to count executions: {}", e),
                }),
            )
        })?;

    let total_pages = ((total_count as f64) / (params.size as f64)).ceil() as i32;

    // Convert executions to DTOs
    let execution_dtos: Vec<ExecutionHistoryDto> = executions
        .into_iter()
        .filter(|exec| {
            // Only include FILLED and PARTIAL_FILL (exclude NEW)
            matches!(exec.exec_status, crate::models::ExecStatus::Filled | crate::models::ExecStatus::PartiallyFilled)
        })
        .map(|exec| {
            // Get instrument config for price/qty multipliers
            let instrument = state.config.get_instrument(&exec.symbol);
            let price_multiplier = instrument.map(|i| i.price_multiplier as f64).unwrap_or(1_000_000.0);
            let qty_multiplier = instrument.map(|i| i.qty_multiplier as f64).unwrap_or(1_000_000.0);

            ExecutionHistoryDto {
                exec_id: exec.exec_id,
                cl_ord_id: exec.cl_ord_id,
                symbol: exec.symbol,
                exec_status: match exec.exec_status {
                    crate::models::ExecStatus::New => "NEW".to_string(),
                    crate::models::ExecStatus::PartiallyFilled => "PARTIAL_FILL".to_string(),
                    crate::models::ExecStatus::Filled => "FILLED".to_string(),
                    crate::models::ExecStatus::Canceled => "CANCELED".to_string(),
                    crate::models::ExecStatus::Rejected => "REJECTED".to_string(),
                },
                last_px: exec.last_px as f64 / price_multiplier,
                last_qty: exec.last_qty as f64 / qty_multiplier,
                counter_party_username: Some(exec.counter_party_username.clone()),
                side: exec.side,
                created_at: exec.created_at,
            }
        })
        .collect();

    // Apply symbol filter if provided (already filtered in query, but double-check)
    let filtered_dtos: Vec<ExecutionHistoryDto> = if let Some(symbol_filter) = &params.symbol {
        execution_dtos
            .into_iter()
            .filter(|dto| dto.symbol.eq_ignore_ascii_case(symbol_filter))
            .collect()
    } else {
        execution_dtos
    };

    // Recalculate pagination for filtered results
    let filtered_total = filtered_dtos.len() as i64;
    let filtered_pages = ((filtered_total as f64) / (params.size as f64)).ceil() as i32;

    Ok(Json(ExecutionHistoryResponse {
        username: username.clone(),
        page: params.page,
        size: params.size,
        total_pages: filtered_pages,
        total_elements: filtered_total,
        executions: filtered_dtos,
    }))
}

#[derive(Debug, Deserialize)]
pub struct AllExecutionHistoryQuery {
    #[serde(default = "default_page")]
    page: i32,
    #[serde(default = "default_size")]
    size: i32,
    symbol: String, // Required for /all endpoint
}

pub async fn get_all_execution_history(
    Extension(state): Extension<AppState>,
    Extension(_auth_state): Extension<AuthState>,
    Query(params): Query<AllExecutionHistoryQuery>,
) -> Result<Json<ExecutionHistoryResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Query executions from database for all users with symbol filter
    let executions = state
        .database
        .query_executions_by_symbol_all_users(&params.symbol, params.page, params.size)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to get all execution history: {}", e),
                }),
            )
        })?;

    // Get total count for pagination
    let total_count = state
        .database
        .count_executions_by_symbol_all_users(&params.symbol)
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(ErrorResponse {
                    error: format!("Failed to count executions: {}", e),
                }),
            )
        })?;

    let total_pages = ((total_count as f64) / (params.size as f64)).ceil() as i32;

    // Convert executions to DTOs
    let execution_dtos: Vec<ExecutionHistoryDto> = executions
        .into_iter()
        .filter(|exec| {
            // Only include FILLED and PARTIAL_FILL (exclude NEW)
            matches!(exec.exec_status, crate::models::ExecStatus::Filled | crate::models::ExecStatus::PartiallyFilled)
        })
        .map(|exec| {
            // Get instrument config for price/qty multipliers
            let instrument = state.config.get_instrument(&exec.symbol);
            let price_multiplier = instrument.map(|i| i.price_multiplier as f64).unwrap_or(1_000_000.0);
            let qty_multiplier = instrument.map(|i| i.qty_multiplier as f64).unwrap_or(1_000_000.0);

            ExecutionHistoryDto {
                exec_id: exec.exec_id,
                cl_ord_id: exec.cl_ord_id,
                symbol: exec.symbol,
                exec_status: match exec.exec_status {
                    crate::models::ExecStatus::New => "NEW".to_string(),
                    crate::models::ExecStatus::PartiallyFilled => "PARTIAL_FILL".to_string(),
                    crate::models::ExecStatus::Filled => "FILLED".to_string(),
                    crate::models::ExecStatus::Canceled => "CANCELED".to_string(),
                    crate::models::ExecStatus::Rejected => "REJECTED".to_string(),
                },
                last_px: exec.last_px as f64 / price_multiplier,
                last_qty: exec.last_qty as f64 / qty_multiplier,
                counter_party_username: Some(exec.counter_party_username.clone()),
                side: exec.side,
                created_at: exec.created_at,
            }
        })
        .collect();

    Ok(Json(ExecutionHistoryResponse {
        username: params.symbol.clone(), // Use symbol as username for /all endpoint
        page: params.page,
        size: params.size,
        total_pages,
        total_elements: total_count,
        executions: execution_dtos,
    }))
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VolumeCalculationResponse {
    pub symbol: String,
    pub from_time: chrono::DateTime<chrono::Utc>,
    pub to_time: chrono::DateTime<chrono::Utc>,
    pub total_volume: f64,
    pub execution_count: i64,
    pub time_range_description: String,
}

#[derive(Debug, Deserialize)]
pub struct VolumeCalculationQuery {
    symbol: String,
    #[serde(rename = "fromTime")]
    from_time: String,
    #[serde(rename = "toTime")]
    to_time: String,
}

pub async fn get_volume_calculation(
    Extension(state): Extension<AppState>,
    Extension(_auth_state): Extension<AuthState>,
    Query(params): Query<VolumeCalculationQuery>,
) -> Result<Json<VolumeCalculationResponse>, (StatusCode, Json<ErrorResponse>)> {
    // Parse time strings
    let from_time = chrono::DateTime::parse_from_rfc3339(&params.from_time)
        .map(|dt| dt.with_timezone(&chrono::Utc))
        .or_else(|_| {
            chrono::NaiveDateTime::parse_from_str(&params.from_time, "%Y-%m-%dT%H:%M:%S")
                .map(|dt| dt.and_utc())
        })
        .map_err(|_| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Invalid fromTime format: {}", params.from_time),
                }),
            )
        })?;

    let to_time = chrono::DateTime::parse_from_rfc3339(&params.to_time)
        .map(|dt| dt.with_timezone(&chrono::Utc))
        .or_else(|_| {
            chrono::NaiveDateTime::parse_from_str(&params.to_time, "%Y-%m-%dT%H:%M:%S")
                .map(|dt| dt.and_utc())
        })
        .map_err(|_| {
            (
                StatusCode::BAD_REQUEST,
                Json(ErrorResponse {
                    error: format!("Invalid toTime format: {}", params.to_time),
                }),
            )
        })?;

    let symbol_upper = params.symbol.to_uppercase();
    let is_all_symbols = symbol_upper == "ALL";

    // Calculate volume
    let (total_volume, execution_count) = if is_all_symbols {
        state
            .database
            .calculate_total_volume(from_time, to_time)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to calculate total volume: {}", e),
                    }),
                )
            })?
    } else {
        state
            .database
            .calculate_volume_by_symbol(&symbol_upper, from_time, to_time)
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(ErrorResponse {
                        error: format!("Failed to calculate volume: {}", e),
                    }),
                )
            })?
    };

    // Get instrument config for qty multiplier
    let instrument = state.config.get_instrument(&symbol_upper);
    let qty_multiplier = instrument.map(|i| i.qty_multiplier as f64).unwrap_or(1_000_000.0);
    let volume_in_units = total_volume as f64 / qty_multiplier;

    let time_range_description = format!(
        "From {} to {}",
        from_time.format("%Y-%m-%d %H:%M:%S"),
        to_time.format("%Y-%m-%d %H:%M:%S")
    );

    Ok(Json(VolumeCalculationResponse {
        symbol: symbol_upper,
        from_time,
        to_time,
        total_volume: volume_in_units,
        execution_count,
        time_range_description,
    }))
}

