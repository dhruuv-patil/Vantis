"use client";

import { Reveal } from "../primitives";

export function OutcomesSection() {
  return (
    <section id="outcomes" className="flowops-outcomes-section">
      <Reveal className="reveal-fade outcomes-intro">
        <div className="eyebrow">What changes with Vantis</div>
        <h2>
          Less time hunting.
          <br />
          <em>More time fixing.</em>
        </h2>
        <p>
          Reliability becomes something your team can operate, not something
          they discover after the workflow has already failed.
        </p>
      </Reveal>

      <Reveal delay={90} className="outcomes-grid">
        <div className="outcome-card">
          <span>01</span>
          <strong>Every execution</strong>
          <p>Traceable from trigger to final action.</p>
        </div>
        <div className="outcome-card">
          <span>02</span>
          <strong>Every anomaly</strong>
          <p>Compared with what normal looks like.</p>
        </div>
        <div className="outcome-card">
          <span>03</span>
          <strong>Every failure</strong>
          <p>Connected to its dependencies and context.</p>
        </div>
        <div className="outcome-card">
          <span>04</span>
          <strong>Every incident</strong>
          <p>Ready for an engineering handoff.</p>
        </div>
      </Reveal>
    </section>
  );
}
