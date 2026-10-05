"use client";

import { Play } from "lucide-react";
import { Reveal, Window } from "../primitives";

export function MonitorSection() {
  return (
    <section id="platform" className="section">
      <Reveal as="div" className="copy reveal-left">
        <div className="eyebrow">Monitor</div>
        <h2>One source of truth for workflow health.</h2>
        <p>
          See what is running, what is slowing down, and where reliability is
          trending across your entire automation stack.
        </p>
        <div className="pills">
          <span>Real-time health</span>
          <span>Execution history</span>
          <span>Team context</span>
        </div>
        <a className="learn" href="#">
          <Play size={13} /> Watch the 2-min overview
        </a>
      </Reveal>
      <Reveal delay={120} className="reveal-right">
        <Window image="https://images.unsplash.com/photo-1500534623283-312aade485b7?auto=format&fit=crop&w=1800&q=85">
          <div className="flowops-monitor-panel">
            <div className="monitor-top">
              <div>
                <p className="tiny">Live reliability monitor</p>
                <h3>Workflow operations</h3>
              </div>
              <span className="live-status">
                <i /> Live
              </span>
            </div>

            <div className="monitor-score-row">
              <div className="reliability-score">
                <span>Reliability</span>
                <strong>98.7%</strong>
                <small>+1.8% this week</small>
              </div>
              <div className="run-summary">
                <span>24h executions</span>
                <strong>12,842</strong>
                <small>Across 18 workflows</small>
              </div>
            </div>

            <div className="workflow-list">
              <div className="workflow-row">
                <div className="workflow-name">
                  <span className="workflow-icon">↗</span>
                  <div>
                    <strong>Stripe → HubSpot</strong>
                    <small>Customer sync</small>
                  </div>
                </div>
                <div className="workflow-meta">
                  <span>2,481 runs</span>
                  <b>Healthy</b>
                </div>
              </div>

              <div className="workflow-row">
                <div className="workflow-name">
                  <span className="workflow-icon">◈</span>
                  <div>
                    <strong>Lead enrichment</strong>
                    <small>AI enrichment pipeline</small>
                  </div>
                </div>
                <div className="workflow-meta">
                  <span>842 runs</span>
                  <b className="degraded">Degraded</b>
                </div>
              </div>

              <div className="workflow-row">
                <div className="workflow-name">
                  <span className="workflow-icon">⌁</span>
                  <div>
                    <strong>Slack incident alerts</strong>
                    <small>Production notifications</small>
                  </div>
                </div>
                <div className="workflow-meta">
                  <span>3,104 runs</span>
                  <b>Healthy</b>
                </div>
              </div>
            </div>

            <div className="execution-strip">
              <div className="strip-label">
                <span>Execution activity</span>
                <small>Last 60 minutes</small>
              </div>
              <div className="activity-bars">
                {Array.from({ length: 28 }).map((_, i) => (
                  <i key={i} style={{ height: `${22 + ((i * 13) % 42)}%` }} />
                ))}
              </div>
            </div>
          </div>
        </Window>
      </Reveal>
    </section>
  );
}
