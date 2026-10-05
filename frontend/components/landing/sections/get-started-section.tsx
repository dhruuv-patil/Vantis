"use client";

import { ArrowRight } from "lucide-react";
import { Reveal } from "../primitives";

export function GetStartedSection() {
  return (
    <section id="get-started" className="flowops-get-started">
      <div className="get-started-image" aria-hidden="true" />
      <div className="get-started-overlay" aria-hidden="true" />

      <Reveal className="get-started-content">
        <div className="eyebrow">Get started with Vantis</div>
        <h2>
          Build every workflow.
          <br />
          <em>Trust every run.</em>
        </h2>
        <p>
          Build from scratch or connect what you already have. Vantis gives
          every workflow the execution visibility and reliability context it
          needs to run in production.
        </p>

        <div className="get-started-actions">
          <a href="/register" className="btn btn-primary">
            Start building <ArrowRight size={15} />
          </a>
          <a href="#platform" className="btn btn-secondary">
            Explore the platform
          </a>
        </div>

        <div className="get-started-note">
          <span>Connect your stack</span>
          <i />
          <span>Trace every execution</span>
          <i />
          <span>Resolve with context</span>
        </div>
      </Reveal>
    </section>
  );
}