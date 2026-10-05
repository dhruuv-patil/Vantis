"use client";

import { Reveal } from "../primitives";

export function IncidentJourneySection() {
  return (
    <section id="incident-journey" className="flowops-journey-section">
      <Reveal className="reveal-fade journey-intro">
        <div className="eyebrow center">From signal to resolution</div>
        <h2>One incident. The whole story.</h2>
        <p>
          Vantis connects the scattered pieces of a workflow failure into one
          investigation path.
        </p>
      </Reveal>

      <Reveal delay={80} className="journey-timeline">
        <div className="journey-line" aria-hidden="true" />
        <div className="journey-step">
          <span className="journey-number">01</span>
          <div className="journey-node">
            <i />
          </div>
          <div className="journey-copy">
            <span>SIGNAL</span>
            <h3>Checkout reliability drops</h3>
            <p>
              Vantis detects a change in execution behavior instead of waiting
              for a user report.
            </p>
          </div>
        </div>

        <div className="journey-step">
          <span className="journey-number">02</span>
          <div className="journey-node">
            <i />
          </div>
          <div className="journey-copy">
            <span>DETECT</span>
            <h3>Inventory sync breaks baseline</h3>
            <p>
              Latency jumps from <b>590ms to 8.4s</b> across the affected
              workflow runs.
            </p>
          </div>
        </div>

        <div className="journey-step">
          <span className="journey-number">03</span>
          <div className="journey-node">
            <i />
          </div>
          <div className="journey-copy">
            <span>DIAGNOSE</span>
            <h3>Deployment change is correlated</h3>
            <p>
              The spike begins 18 minutes after a warehouse-service deployment.
            </p>
          </div>
        </div>

        <div className="journey-step">
          <span className="journey-number">04</span>
          <div className="journey-node">
            <i />
          </div>
          <div className="journey-copy">
            <span>RESOLVE</span>
            <h3>Evidence becomes an action</h3>
            <p>
              The incident handoff contains affected runs, dependency context,
              and next steps — ready for your team to act on.
            </p>
          </div>
        </div>
      </Reveal>
    </section>
  );
}