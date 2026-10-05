"use client";

import { useEffect, useState } from "react";
import { ArrowRight, Check, Sparkles } from "lucide-react";
import { Reveal } from "../primitives";

export function CopilotSection() {
  const copilotPrompt =
    "Checkout workflows are timing out after the latest deployment. Find the failing dependency, compare it with recent executions, and prepare an incident summary for the team.";
  const [typedCopilotPrompt, setTypedCopilotPrompt] = useState("");

  useEffect(() => {
    let index = 0;
    const timer = window.setInterval(() => {
      index += 1;
      setTypedCopilotPrompt(copilotPrompt.slice(0, index));

      if (index >= copilotPrompt.length) {
        window.clearInterval(timer);
      }
    }, 28);

    return () => window.clearInterval(timer);
  }, []);

  return (
    <section id="builder" className="flowops-builder-section">
      <div className="builder-glow" aria-hidden="true" />

      <Reveal className="reveal-fade builder-intro">
        <div className="eyebrow center">Vantis AI</div>
        <h2>
          Build with context.
          <br />
          <em>Fix with intelligence.</em>
        </h2>
        <p>
          Use the visual builder for the workflow itself, then ask Vantis
          to investigate incidents, compare executions, and prepare the
          next operational steps.
        </p>
      </Reveal>

      <Reveal delay={100} className="builder-shell">
        <div className="builder-toolbar">
          <div className="builder-toolbar-left">
            <span className="builder-live">
              <i /> Live context
            </span>
            <span className="builder-divider" />
            <span className="builder-context">production / checkout</span>
          </div>
          <span className="builder-step">
            01 <b>/</b> 03
          </span>
        </div>

        <div className="builder-systems">
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/kubernetes/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>Kubernetes</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/datadog/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>Datadog</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/postgresql/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>Postgres</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/slack/36C5F0"
              alt=""
              aria-hidden="true"
            />
            <span>Slack</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/github/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>GitHub</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/amazonwebservices/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>AWS</span>
          </div>
          <div className="builder-system">
            <img
              src="https://cdn.simpleicons.org/temporal/ffffff"
              alt=""
              aria-hidden="true"
            />
            <span>Temporal</span>
          </div>
        </div>

        <div className="builder-composer">
          <div className="builder-composer-top">
            <span className="builder-label">INVESTIGATION REQUEST</span>
            <span className="builder-count">0 / 1000</span>
          </div>

          <p
            className="builder-prompt builder-typewriter"
            aria-label="Investigation request"
          >
            <span className="typewriter-text">{typedCopilotPrompt}</span>
            <span className="typewriter-cursor" aria-hidden="true" />
          </p>

          <div className="builder-composer-bottom">
            <div className="builder-scope">
              <span>⌁</span>
              <span>18 workflows</span>
              <span>·</span>
              <span>last 24h</span>
            </div>
            <button className="builder-generate" type="button">
              <Sparkles size={14} />
              Investigate
              <ArrowRight size={13} />
            </button>
          </div>
        </div>

        <div className="builder-preview">
          <div className="builder-preview-head">
            <span>WHAT VANTIS WILL DO</span>
            <span className="preview-status">
              <i /> Ready
            </span>
          </div>
          <div className="builder-actions">
            <div className="builder-action">
              <span className="action-index">01</span>
              <div>
                <b>Trace the execution path</b>
                <small>
                  Find the first abnormal step and its upstream dependencies.
                </small>
              </div>
            </div>
            <div className="builder-action">
              <span className="action-index">02</span>
              <div>
                <b>Compare against baseline</b>
                <small>
                  Check latency, error rate, and recent deployment changes.
                </small>
              </div>
            </div>
            <div className="builder-action">
              <span className="action-index">03</span>
              <div>
                <b>Prepare the incident handoff</b>
                <small>
                  Summarize evidence with links to traces and affected runs.
                </small>
              </div>
            </div>
          </div>
        </div>
      </Reveal>
    </section>
  );
}
