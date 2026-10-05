"use client";

import { ArrowRight } from "lucide-react";
import { Reveal } from "../primitives";

export function MessyMiddleSection() {
  return (
    <section id="reliability-in-practice" className="flowops-messy-middle">
      <Reveal className="reveal-fade messy-middle-intro">
        <div className="eyebrow center">
          For the workflows between tools and teams
        </div>
        <h2>Where automations get hard to trust.</h2>
        <p>
          Vantis sits between your automation platform and your production
          systems — watching every execution, detecting drift, and giving your
          team the context to fix what changed.
        </p>
      </Reveal>

      <Reveal delay={90} className="messy-middle-console">
        <div className="mm-console-head">
          <div className="mm-title-group">
            <span className="mm-dot" />
            <span>WORKFLOW RELIABILITY</span>
            <b>production</b>
          </div>
          <span className="mm-time">EXAMPLE · LAST 24 HOURS</span>
        </div>

        <div className="mm-console-body">
          <div className="mm-workflow">
            <div className="mm-workflow-head">
              <div>
                <span>WORKFLOW</span>
                <strong>order_to_fulfillment</strong>
              </div>
              <span className="mm-degraded">DEGRADED</span>
            </div>

            <div className="mm-flow">
              <div className="mm-node mm-ok">
                <span className="mm-node-icon">↗</span>
                <b>Order webhook</b>
                <small>42ms</small>
              </div>
              <span className="mm-connector" />
              <div className="mm-node mm-ok">
                <span className="mm-node-icon">✓</span>
                <b>Validate order</b>
                <small>118ms</small>
              </div>
              <span className="mm-connector" />
              <div className="mm-node mm-warn">
                <span className="mm-node-icon">!</span>
                <b>Inventory sync</b>
                <small>8.4s</small>
              </div>
              <span className="mm-connector" />
              <div className="mm-node mm-fail">
                <span className="mm-node-icon">×</span>
                <b>Fulfillment</b>
                <small>timed out</small>
              </div>
            </div>

            <div className="mm-insight">
              <span>Vantis SIGNAL</span>
              <p>
                Inventory sync latency is <b>14× above baseline</b>. The
                increase started 18 minutes after the latest warehouse
                deployment.
              </p>
              <a href="#get-started">
                Inspect execution <ArrowRight size={12} />
              </a>
            </div>
          </div>

          <div className="mm-stats">
            <div className="mm-stat">
              <span>RELIABILITY</span>
              <strong>96.4%</strong>
              <small>↓ 2.1% today</small>
            </div>
            <div className="mm-stat">
              <span>FAILED RUNS</span>
              <strong>37</strong>
              <small>of 1,284 executions</small>
            </div>
            <div className="mm-stat">
              <span>P95 LATENCY</span>
              <strong>2.81s</strong>
              <small>+34% vs baseline</small>
            </div>
          </div>
        </div>

        <div className="mm-console-foot">
          <span>
            Monitor <i /> Detect <i /> Diagnose <i /> Resolve
          </span>
          <span>18 workflows connected</span>
        </div>
      </Reveal>
    </section>
  );
}