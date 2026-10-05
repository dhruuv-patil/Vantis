"use client";

import { useEffect, useState } from "react";
import { ArrowRight, Play } from "lucide-react";
import { CountUp } from "./primitives";

export function BuildPanel() {
  const nodes = [
    ["Trigger", "Stripe webhook", "trigger", "order.created"],
    ["Condition", "Order value", "condition", "amount > $100"],
    ["Action", "Create fulfillment", "action", "POST /fulfillment"],
    ["Notify", "Post to Slack", "notify", "#operations"],
  ];
  return (
    <div className="hero-window build-panel-production">
      <div className="build-production-header">
        <div className="build-production-title">
          <div className="build-production-crumb">
            <span>WORKFLOWS</span>
            <i>/</i>
            <span>ORDER TO FULFILLMENT</span>
          </div>
          <h2>Order fulfillment</h2>
        </div>
        <div className="build-production-actions">
          <span className="build-production-saved">
            <b /> Saved
          </span>
          <button type="button" className="build-production-test">
            <Play size={10} /> Test run <ArrowRight size={11} />
          </button>
        </div>
      </div>

      <div className="build-production-body">
        <aside className="build-production-sidebar">
          <div className="build-production-sidebar-head">
            <span>STEPS</span>
            <button type="button">+</button>
          </div>
          {nodes.map(([label, name, type], i) => (
            <div
              key={label}
              className={`build-production-step ${i === 0 ? "is-active" : ""}`}
            >
              <span className={`build-production-step-icon ${type}`}>
                {type === "trigger"
                  ? "↗"
                  : type === "condition"
                    ? "◇"
                    : type === "action"
                      ? "→"
                      : "◈"}
              </span>
              <div>
                <strong>{label}</strong>
                <small>{name}</small>
              </div>
              <em>{String(i + 1).padStart(2, "0")}</em>
            </div>
          ))}
          <div className="build-production-sidebar-bottom">
            <span>
              <i /> All connections healthy
            </span>
            <small>Stripe · Postgres · Slack</small>
          </div>
        </aside>

        <div className="build-production-canvas">
          <div className="build-production-grid" aria-hidden="true" />
          <div className="build-production-canvas-head">
            <span>CANVAS</span>
            <div>
              <span>−</span>
              <b>100%</b>
              <span>+</span>
              <i />
              <span>Fit</span>
            </div>
          </div>

          <div className="build-production-flow">
            {nodes.map(([label, name, type, detail], i) => (
              <div className="build-production-flow-item" key={label}>
                <div
                  className={`build-production-node ${type} ${i === 0 ? "is-selected" : ""}`}
                >
                  <div className={`build-production-node-icon ${type}`}>
                    {type === "trigger"
                      ? "↗"
                      : type === "condition"
                        ? "◇"
                        : type === "action"
                          ? "→"
                          : "◈"}
                  </div>
                  <div className="build-production-node-copy">
                    <span>{label.toUpperCase()}</span>
                    <strong>{name}</strong>
                    <small>{detail}</small>
                  </div>
                  <button type="button" aria-label="More options">
                    •••
                  </button>
                </div>
                {i < nodes.length - 1 && (
                  <div
                    className="build-production-connector"
                    aria-hidden="true"
                  >
                    <i />
                  </div>
                )}
              </div>
            ))}
          </div>

          <div className="build-production-canvas-foot">
            <span>
              <i /> Workflow valid
            </span>
            <span>
              4 steps <b>·</b> 3 connections
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}

export function MonitorPanel() {
  const traceRows = [
    "Webhook received",
    "Validate customer",
    "Sync to warehouse",
    "Notify team",
  ];
  return (
    <div className="hero-window">
      <div className="window-heading">
        <div>
          <p className="tiny">Monitor / Overview</p>
          <h2>Revenue sync</h2>
        </div>
        <span className="status">
          <b /> All systems nominal
        </span>
      </div>
      <div className="dashboard">
        <div className="metric-grid">
          <div>
            <p>Success rate</p>
            <strong>
              <CountUp to={99.82} decimals={2} suffix="%" />
            </strong>
          </div>
          <div>
            <p>Executions</p>
            <strong>
              <CountUp to={12.4} decimals={1} suffix="k" />
            </strong>
          </div>
          <div>
            <p>p95 latency</p>
            <strong>
              <CountUp to={1.8} decimals={1} suffix="s" />
            </strong>
          </div>
        </div>
        <div className="trace">
          <p className="tiny">Live execution trace</p>
          {traceRows.map((x, i) => (
            <div
              className="trace-line"
              key={x}
              style={{ transitionDelay: `${i * 70}ms` }}
            >
              <b className={i === 3 ? "warn pulse" : "pulse"} />
              <span>{x}</span>
              <em>{i === 3 ? "Waiting" : "Complete"}</em>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export function DetectPanel() {
  const bars = [22, 34, 28, 40, 31, 52, 46, 70, 54, 38, 30, 26];
  const [grown, setGrown] = useState(false);
  useEffect(() => {
    const t = requestAnimationFrame(() => setTimeout(() => setGrown(true), 50));
    return () => cancelAnimationFrame(t);
  }, []);
  return (
    <div className="hero-window">
      <div className="window-heading">
        <div>
          <p className="tiny">Detect / Anomalies</p>
          <h2>Warehouse sync latency</h2>
        </div>
        <span className="status amber">
          <b /> 1 anomaly found
        </span>
      </div>
      <div className="dashboard">
        <div className="anomaly-chart">
          {bars.map((h, i) => (
            <div className="bar-col" key={i}>
              <div
                className={`bar ${i === 7 ? "bar-alert" : ""}`}
                style={{
                  height: grown ? `${h * 2}px` : "0px",
                  transitionDelay: `${i * 35}ms`,
                }}
              />
            </div>
          ))}
          <div className="bar-baseline" />
        </div>
        <div className="alert-row">
          <span className="alert-dot" />
          <div>
            <p className="alert-title">
              Latency spike on <code>sync_customer_to_warehouse</code>
            </p>
            <p className="alert-sub">
              3.4x above 7-day baseline · started 4 minutes ago
            </p>
          </div>
          <span className="pill-tag">Investigate</span>
        </div>
      </div>
    </div>
  );
}

export function DiagnosePanel() {
  const signals = [
    ["01", "Checkout latency", "4.6× baseline", "warning"],
    ["02", "DB connection pool", "92% saturated", "danger"],
    ["03", "Customer sync", "184 retries", "danger"],
    ["04", "Webhook ingress", "Normal", "ok"],
  ];

  return (
    <div className="hero-window">
      <div className="reliability-control-plane">
        <div className="rcp-top">
          <div>
            <p className="tiny">VANTIS / INCIDENT INVESTIGATION · EXAMPLE</p>
            <h2>Your workflows have a nervous system.</h2>
          </div>
          <div className="rcp-live">
            <i /> LIVE SIGNALS
          </div>
        </div>

        <div className="rcp-core">
          <div className="rcp-radar">
            <span className="radar-ring ring-one" />
            <span className="radar-ring ring-two" />
            <span className="radar-ring ring-three" />
            <span className="radar-sweep" />
            <span className="radar-core">
              <b>87</b>
              <small>HEALTH</small>
            </span>
            <span className="radar-ping ping-one" />
            <span className="radar-ping ping-two" />
            <span className="radar-ping ping-three" />
          </div>

          <div className="rcp-core-copy">
            <span className="rcp-label">SYSTEM STATE</span>
            <strong>1 incident · 3 signals</strong>
            <p>
              Vantis connected the available execution signals so your team
              can investigate the failure faster.
            </p>
            <div className="rcp-progress">
              <i />
            </div>
            <div className="rcp-mini-stats">
              <span>
                <b>18</b> workflows
              </span>
              <span>
                <b>12.8k</b> runs / 24h
              </span>
              <span>
                <b>—</b> MTTR
              </span>
            </div>
          </div>
        </div>

        <div className="rcp-section-head">
          <span>WHAT VANTIS SEES</span>
          <small>correlated automatically</small>
        </div>

        <div className="rcp-signals">
          {signals.map(([id, name, value, state], i) => (
            <div
              className={`rcp-signal ${state}`}
              key={id}
              style={{ animationDelay: `${i * 100}ms` }}
            >
              <span className="signal-id">{id}</span>
              <div className="signal-copy">
                <b>{name}</b>
                <small>{value}</small>
              </div>
              <span className="signal-state">
                <i /> {state === "ok" ? "stable" : "anomaly"}
              </span>
            </div>
          ))}
        </div>

        <div className="rcp-action">
          <div className="action-orbit">
            <span>✦</span>
          </div>
          <div className="action-copy">
            <span>VANTIS ACTION</span>
            <strong>Trace → detect → explain → act</strong>
            <p>Likely root cause: warehouse connection saturation.</p>
          </div>
          <div className="action-arrow">
            <ArrowRight size={15} />
          </div>
        </div>

        <div className="rcp-bottom">
          <span>
            <i /> Detecting continuously
          </span>
          <span>
            Example incident <b>2m ago</b>
          </span>
          <span>
            Signals analyzed <b>7</b>
          </span>
        </div>
      </div>
    </div>
  );
}

export const panels = [BuildPanel, MonitorPanel, DetectPanel, DiagnosePanel];
