"use client";

import { ArrowRight } from "lucide-react";
import { Reveal } from "../primitives";

export function DetectSection() {
  return (
    <section className="wide-panel">
      <div className="wide-atmosphere" aria-hidden>
        <span className="orb orb-d" />
        <span className="orb orb-e" />
      </div>

      <Reveal className="reveal-left">
        <div className="eyebrow">Detect · Diagnose · Resolve</div>
        <h2>Know exactly where a workflow went wrong.</h2>
        <p>
          Vantis puts execution history, timing, dependencies, and failures on
          one timeline — so an incident is something you can investigate, not
          guess at.
        </p>
        <div className="pills">
          <span>Execution traces</span>
          <span>Dependency context</span>
          <span>Failure history</span>
        </div>
      </Reveal>

      <Reveal delay={100} className="reveal-right">
        <div className="flowops-trace-console">
          <div className="ftc-header">
            <div className="ftc-breadcrumb">
              <span>WORKFLOWS</span>
              <i>/</i>
              <b>sync_customer_to_warehouse</b>
            </div>
            <span className="ftc-status">
              <i /> Degraded
            </span>
          </div>

          <div className="ftc-summary">
            <div className="ftc-summary-main">
              <span>EXECUTION</span>
              <strong>#8f3c2a1</strong>
              <small>Started 12:41:08 · Duration 30.04s</small>
            </div>
            <div className="ftc-metric">
              <span>ERROR RATE</span>
              <b>8.2%</b>
            </div>
            <div className="ftc-metric">
              <span>P95 LATENCY</span>
              <b>2.81s</b>
            </div>
          </div>

          <div className="ftc-body">
            <div className="ftc-trace">
              <div className="ftc-trace-head">
                <span>EXECUTION TRACE</span>
                <span>30.04s</span>
              </div>

              <div className="trace-row trace-ok">
                <div className="trace-marker">
                  <i />
                </div>
                <div className="trace-info">
                  <b>Webhook received</b>
                  <small>POST /orders · 12:41:08.012</small>
                </div>
                <div className="trace-time">84ms</div>
              </div>

              <div className="trace-row trace-ok">
                <div className="trace-marker">
                  <i />
                </div>
                <div className="trace-info">
                  <b>Validate customer</b>
                  <small>customer-service · 12:41:08.096</small>
                </div>
                <div className="trace-time">162ms</div>
              </div>

              <div className="trace-row trace-warn selected">
                <div className="trace-marker">
                  <i />
                </div>
                <div className="trace-info">
                  <b>Sync to warehouse</b>
                  <small>warehouse-service · 12:41:08.258</small>
                </div>
                <div className="trace-time">29.7s</div>
              </div>

              <div className="trace-row trace-fail">
                <div className="trace-marker">
                  <i />
                </div>
                <div className="trace-info">
                  <b>Customer confirmation</b>
                  <small>skipped after timeout</small>
                </div>
                <div className="trace-time">—</div>
              </div>
            </div>

            <div className="ftc-detail">
              <div className="detail-head">
                <span>SELECTED STEP</span>
                <b>Sync to warehouse</b>
              </div>

              <div className="latency-chart">
                <div className="chart-grid" />
                <div className="chart-baseline">
                  <i />
                </div>
                <div className="chart-spike">
                  <i />
                </div>
                <span className="chart-tag">2.8s</span>
              </div>

              <div className="detail-row">
                <span>Expected</span>
                <b>600ms</b>
              </div>
              <div className="detail-row">
                <span>Actual</span>
                <b className="warn-text">29.7s</b>
              </div>
              <div className="detail-row">
                <span>Retries</span>
                <b>2 / 3</b>
              </div>

              <div className="detail-note">
                <span>Failure context</span>
                <p>Connection pool exhausted after warehouse deployment.</p>
              </div>
            </div>
          </div>

          <div className="ftc-footer">
            <span>
              <i /> 7 related signals
            </span>
            <span>
              Compare <b>last 20 executions</b>
            </span>
            <span>
              Open logs <ArrowRight size={11} />
            </span>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
