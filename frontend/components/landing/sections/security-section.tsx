"use client";

import { Reveal } from "../primitives";

export function SecuritySection() {
  return (
    <section id="security" className="center-section">
      <Reveal className="reveal-fade trust-intro">
        <div className="eyebrow center">Trust &amp; security</div>
        <h2>Built for production workflows.</h2>
        <p>
          Security and reliability are part of the foundation, not an add-on.
        </p>
      </Reveal>

      <Reveal delay={80} className="trust-grid">
        <div className="trust-item">
          <div className="trust-item-top">
            <span className="trust-number">01</span>
            <span className="trust-line" />
          </div>
          <h3>Secure by default</h3>
          <p>
            Least-privilege access, encrypted connections, and controlled
            credentials keep workflow data protected.
          </p>
        </div>

        <div className="trust-item">
          <div className="trust-item-top">
            <span className="trust-number">02</span>
            <span className="trust-line" />
          </div>
          <h3>Operational visibility</h3>
          <p>
            Every execution is traceable with the context teams need to
            understand failures and changes.
          </p>
        </div>

        <div className="trust-item">
          <div className="trust-item-top">
            <span className="trust-number">03</span>
            <span className="trust-line" />
          </div>
          <h3>Designed for control</h3>
          <p>
            Clear ownership, auditable activity, and predictable access patterns
            make reliability easier to manage.
          </p>
        </div>
      </Reveal>

      <Reveal delay={140} className="trust-footer">
        <span>Security controls</span>
        <i />
        <span>Execution audit trail</span>
        <i />
        <span>Role-based access</span>
      </Reveal>
    </section>
  );
}
