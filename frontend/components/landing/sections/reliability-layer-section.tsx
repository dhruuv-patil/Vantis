"use client";

import { Reveal } from "../primitives";

export function ReliabilityLayerSection() {
  return (
    <section id="reliability-layer" className="flowops-layer-section">
      <Reveal className="reveal-fade layer-intro">
        <div className="eyebrow">How it fits your stack</div>
        <h2>
          One reliability layer
          <br />
          <em>for every workflow.</em>
        </h2>
        <p>
          Build workflows in Vantis or connect the systems you already use.
          Either way, every execution flows into the same reliability layer —
          with one place to monitor, detect, investigate, and resolve what
          changes.
        </p>
      </Reveal>

      <Reveal delay={90} className="layer-diagram">
        <div className="layer-row layer-sources">
          <span className="layer-label">WORKFLOW SOURCES</span>
          <div className="layer-chip">
            <span>n8n</span>
          </div>
          <div className="layer-chip">
            <span>Zapier</span>
          </div>
          <div className="layer-chip">
            <span>Temporal</span>
          </div>
          <div className="layer-chip">
            <span>Custom jobs</span>
          </div>
        </div>

        <div className="layer-connector">
          <i />
          <i />
          <i />
        </div>

        <div className="layer-flowops">
          <div className="layer-flowops-brand">
            <span>Vantis</span>
            <b>RELIABILITY LAYER</b>
          </div>
          <div className="layer-capabilities">
            <span>Build</span>
            <span>Run</span>
            <span>Monitor</span>
            <span>Resolve</span>
          </div>
        </div>

        <div className="layer-connector layer-connector-bottom">
          <i />
          <i />
          <i />
        </div>

        <div className="layer-row layer-targets">
          <span className="layer-label">PRODUCTION SYSTEMS</span>
          <div className="layer-chip">
            <span>AWS</span>
          </div>
          <div className="layer-chip">
            <span>Postgres</span>
          </div>
          <div className="layer-chip">
            <span>APIs</span>
          </div>
          <div className="layer-chip">
            <span>Services</span>
          </div>
        </div>
      </Reveal>
    </section>
  );
}