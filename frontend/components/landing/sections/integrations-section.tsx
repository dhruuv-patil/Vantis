"use client";

import { integrationCards } from "../data";
import { SlackLogo, TeamsLogo } from "../logos";
import { Reveal } from "../primitives";

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
                    {name === "Slack" ? (
                      <SlackLogo />
                    ) : name === "Microsoft Teams" ? (
                      <TeamsLogo />
                    ) : slug ? (
                      <img
                        className="integration-logo integration-logo-color"
                        src={`https://thesvg.org/icons/${slug}/default.svg`}
                        alt=""
                        aria-hidden="true"
                        loading="lazy"
                      />
                    ) : (
                      <span
                        className="integration-generic-icon"
                        aria-hidden="true"
                      >
                        {name === "HTTP APIs"
                          ? "↗"
                          : name === "Webhooks"
                            ? "⌁"
                            : "✉"}
                      </span>
                    )}
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
