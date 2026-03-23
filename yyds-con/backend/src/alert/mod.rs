use std::sync::OnceLock;

use reqwest::Client;

use crate::api::scheduler::{AlertConfig, TaskRun};

/// Lazily initialised reqwest client (reused across all webhook calls).
fn http_client() -> &'static Client {
    static CLIENT: OnceLock<Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .expect("Failed to build reqwest client")
    })
}

/// Check alert conditions and fire webhook if triggered.
pub async fn check_and_alert(cfg: &AlertConfig, run: &TaskRun) {
    let summary = &run.summary;
    if summary.total == 0 {
        return;
    }

    let fail_count = summary.failed + summary.timeout;
    let offline_count = summary.offline;
    let fail_rate = (fail_count + offline_count) as f64 / summary.total as f64;

    let should_alert = (cfg.on_any_fail && fail_count > 0)
        || (cfg.on_device_offline && offline_count > 0)
        || cfg
            .fail_rate_threshold
            .map(|t| fail_rate >= t)
            .unwrap_or(false);

    if !should_alert {
        return;
    }

    if let Some(ref url) = cfg.webhook_url {
        if !url.is_empty() {
            fire_webhook(url, run).await;
        }
    }
}

/// POST a JSON alert payload to the webhook URL.
/// Supports DingTalk, WeCom, Feishu, and generic webhooks (HTTP + HTTPS).
async fn fire_webhook(url: &str, run: &TaskRun) {
    let summary = &run.summary;
    let status_emoji = match run.status.as_str() {
        "done"         => "✅",
        "partial_fail" => "⚠️",
        "all_fail"     => "❌",
        _              => "ℹ️",
    };

    let title = format!("{} Schedule Alert: {}", status_emoji, run.schedule_name);

    let detail_lines: Vec<String> = run
        .device_results
        .iter()
        .filter(|r| r.status != "success")
        .take(10)
        .map(|r| {
            let truncated_output = if r.output.len() > 80 {
                format!("{}…", &r.output[..80])
            } else {
                r.output.clone()
            };
            format!(
                "  • {} ({}): {} — {}",
                r.model,
                &r.imei[..r.imei.len().min(8)],
                r.status,
                truncated_output,
            )
        })
        .collect();

    let content = format!(
        "**{}**\nTrigger: {}\nResult: success {} / failed {} / offline {} / timeout {}\nDuration: {}ms\n\n{}",
        title,
        run.trigger_type,
        summary.success,
        summary.failed,
        summary.offline,
        summary.timeout,
        run.duration_ms,
        detail_lines.join("\n"),
    );

    // Auto-detect webhook format by URL pattern
    let body = if url.contains("dingtalk.com") || url.contains("oapi.dingtalk") {
        serde_json::json!({
            "msgtype": "markdown",
            "markdown": { "title": title, "text": content }
        })
    } else if url.contains("qyapi.weixin") || url.contains("wechat") {
        serde_json::json!({
            "msgtype": "markdown",
            "markdown": { "content": content }
        })
    } else if url.contains("feishu.cn") || url.contains("larksuite.com") {
        serde_json::json!({
            "msg_type": "interactive",
            "card": {
                "header": {
                    "title": { "tag": "plain_text", "content": title },
                    "template": "red"
                },
                "elements": [{ "tag": "markdown", "content": content }]
            }
        })
    } else {
        // Generic webhook — send full structured payload
        serde_json::json!({
            "schedule_id":   run.schedule_id,
            "schedule_name": run.schedule_name,
            "run_id":        run.id,
            "trigger_type":  run.trigger_type,
            "status":        run.status,
            "triggered_at":  run.triggered_at,
            "duration_ms":   run.duration_ms,
            "summary":       run.summary,
            "failed_devices": run.device_results.iter()
                .filter(|r| r.status != "success")
                .collect::<Vec<_>>(),
            "title":   title,
            "content": content,
        })
    };

    let url_owned = url.to_string();
    match http_client().post(&url_owned).json(&body).send().await {
        Ok(resp) => {
            let status = resp.status();
            if status.is_success() {
                tracing::info!(url = %url_owned, "Alert webhook delivered");
            } else {
                let body_text = resp.text().await.unwrap_or_default();
                tracing::warn!(
                    url = %url_owned,
                    status = %status,
                    body = %body_text,
                    "Alert webhook returned non-2xx"
                );
            }
        }
        Err(e) => {
            tracing::warn!(url = %url_owned, error = %e, "Alert webhook request failed");
        }
    }
}
