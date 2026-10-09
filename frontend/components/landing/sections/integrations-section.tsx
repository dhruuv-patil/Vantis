"use client";

import { integrationCards } from "../data";
import { Reveal } from "../primitives";
import { BrandIcon } from "@/components/brand/brand-icon";

export function IntegrationsSection() {
  return (
    <section id="integrations" className="center-section integrations-section">
      <Reveal className="reveal-fade integrations-intro">
        <div className="eyebrow center">
          Connect without changing your stack
        </div>
        <h2>Fits into the stack you already run.</h2>
        <p>
          Connect your existing workflows and operational systems without
          rebuilding your stack. Start with the integrations Vantis supports
          today, or connect anything through webhooks and HTTP APIs.
        </p>
      </Reveal>

      <div className="integration-grid integration-grid-reference">
        {integrationCards.map(({ title, items }, i) => (
          <Reveal
            key={title}
            delay={i * 45}
            className="integration-reference-card"
          >
            <div className="integration-card-title">{title}</div>
            <div className="integration-card-items">
              {items.map(([name, slug, color]) => (
                <span key={name} className="integration-item">
                  <span
                    className="integration-logo-wrap"
                    style={
                      { "--integration-color": color } as React.CSSProperties
                    }
                  >
                    <BrandIcon
                      slug={slug}
                      name={name}
                      label={name}
                      className="integration-logo integration-logo-color size-4 object-contain"
                      theme="auto"
                    />
                  </span>
                  <b>{name}</b>
                </span>
              ))}
            </div>
          </Reveal>
        ))}
      </div>

      <Reveal delay={180} className="integration-custom">
        <span className="integration-link-icon">↗</span>
        <span>+ custom webhook triggers and HTTP API action blocks</span>
      </Reveal>
    </section>
  );
}
